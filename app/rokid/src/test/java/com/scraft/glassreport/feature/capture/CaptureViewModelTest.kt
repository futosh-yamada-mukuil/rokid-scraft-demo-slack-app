package com.scraft.glassreport.feature.capture

import com.scraft.glassreport.capture.asr.RecordedVoice
import com.scraft.glassreport.capture.asr.SpeechRecordingSession
import com.scraft.glassreport.capture.asr.SpeechResult
import com.scraft.glassreport.capture.asr.SpeechSource
import com.scraft.glassreport.capture.camera.CapturedPhoto
import com.scraft.glassreport.capture.camera.PhotoSource
import com.scraft.glassreport.capture.camera.RecordedVideo
import com.scraft.glassreport.capture.camera.VIDEO_MAX_DURATION_MS
import com.scraft.glassreport.capture.camera.VideoSession
import com.scraft.glassreport.capture.camera.VideoSource
import com.scraft.glassreport.data.prefs.InMemoryTokenStore
import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.MIME_MP4
import com.scraft.glassreport.net.MediaSink
import com.scraft.glassreport.net.SendOutcome
import com.scraft.glassreport.net.SignInRequiredException
import com.scraft.glassreport.net.devmonitor.UploadClient
import com.scraft.glassreport.net.teams.TeamsAuth
import com.scraft.glassreport.net.teams.TeamsClient
import com.scraft.glassreport.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer

    private val photo = CapturedPhoto(jpeg = ByteArray(103 * 1_024), width = 960, height = 1280, thumbnail = null)
    private val video =
        RecordedVideo(
            bytes = ByteArray(2 * 1_024 * 1_024),
            width = 1280,
            height = 720,
            durationMs = 5_400L,
            hasAudioTrack = true,
            thumbnail = null,
        )

    /** 実機カメラの代わり。ImageCapture / VideoCapture は JVM 上で生成もモックもできない。 */
    private val photoSource = PhotoSource { photo }

    private val monitor = FakeSink("モニタ", "192.168.0.27:8787", setOf(MIME_JPEG, MIME_MP4))
    private val teams = FakeSink("Teams", "Teams チャネル", setOf(MIME_JPEG))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── 撮影モード × 送信先の巡回 ───────────────────────────────────────────

    @Test
    fun initialState_containsUploadHost() {
        val vm = monitorViewModel(endpoint = "http://192.168.0.27:8787/upload")

        assertEquals(CaptureMode.PHOTO, vm.ui.value.mode)
        assertEquals(CapturePhase.IDLE, vm.ui.value.phase)
        assertEquals("192.168.0.27:8787", vm.ui.value.host)
        assertEquals("写真 → モニタ", vm.ui.value.target)
        assertEquals(ConfirmChoice.NO, vm.ui.value.confirm)
    }

    @Test
    fun initialState_reportsMissingEndpoint() {
        assertEquals("宛先未設定", monitorViewModel(endpoint = "").ui.value.host)
    }

    @Test
    fun initialState_isPhotoToTheFirstSink() {
        val vm = viewModel(monitor, teams)

        assertEquals(CaptureMode.PHOTO, vm.ui.value.mode)
        assertEquals("写真 → モニタ", vm.ui.value.target)
        assertEquals("192.168.0.27:8787", vm.ui.value.host)
        assertEquals(CapturePhase.IDLE, vm.ui.value.phase)
    }

    @Test
    fun swipe_cyclesPhotoTargetsThenVideoTargets() {
        val vm = viewModel(monitor, teams)

        // 写真 → モニタ / 写真 → Teams / 動画 → モニタ の 3 通りだけを巡回する。
        vm.nextTarget()
        assertEquals("写真 → Teams", vm.ui.value.target)
        assertEquals("Teams チャネル", vm.ui.value.host)
        assertEquals(CaptureMode.PHOTO, vm.ui.value.mode)

        vm.nextTarget()
        assertEquals("動画 → モニタ", vm.ui.value.target)
        assertEquals(CaptureMode.VIDEO, vm.ui.value.mode)

        vm.nextTarget()
        assertEquals("写真 → モニタ", vm.ui.value.target)

        vm.previousTarget()
        assertEquals("動画 → モニタ", vm.ui.value.target)
    }

    @Test
    fun swipe_cyclesBetweenPhotoAndVideo_whenTheMonitorIsTheOnlySink() {
        val vm = monitorViewModel()

        vm.nextTarget()
        assertEquals(CaptureMode.VIDEO, vm.ui.value.mode)

        vm.nextTarget()
        assertEquals(CaptureMode.PHOTO, vm.ui.value.mode)
    }

    @Test
    fun videoIsNeverPairedWithTeams() {
        val vm = viewModel(monitor, teams)

        val seen = allTargets(vm)

        assertFalse(seen.contains("動画 → Teams"))
        assertTrue(seen.contains("写真 → Teams"))
    }

    @Test
    fun teamsOnly_offersPhotoOnly() {
        val vm = viewModel(teams)

        assertEquals("写真 → Teams", vm.ui.value.target)

        // 候補が 1 つしか無いのでスワイプしても変わらない（動画は選べない）。
        vm.nextTarget()
        assertEquals("写真 → Teams", vm.ui.value.target)
        assertEquals(CaptureMode.PHOTO, vm.ui.value.mode)
    }

    @Test
    fun realClients_produceTheExpectedTargetSet() {
        // 実装（UploadClient / TeamsClient）の accepts が意図どおり組み合わせを絞ることの確認。
        val uploader = UploadClient("http://192.168.0.27:8787/upload")
        val teamsClient =
            TeamsClient(
                auth = TeamsAuth(InMemoryTokenStore(), tenantId = "t", clientId = "c"),
                teamId = "team",
                channelId = "19:abc@thread.tacv2",
            )
        val vm = CaptureViewModel(listOf(uploader, teamsClient), FakeSpeech())

        assertEquals(listOf("写真 → モニタ", "写真 → Teams", "動画 → モニタ"), allTargets(vm))
    }

    @Test
    fun noSinks_stillShowsAScreenAndReportsMissingDestination() {
        val vm = CaptureViewModel(emptyList(), FakeSpeech())

        assertEquals("写真 → 未設定", vm.ui.value.target)
        assertEquals("送信先が未設定", vm.ui.value.host)
    }

    @Test
    fun missingTeamsWithFixedPhotoModeDoesNotFallBackToHttp() {
        val vm = CaptureViewModel(emptyList(), FakeSpeech(), defaultSink = "Teams", fixedMode = CaptureMode.PHOTO)
        assertEquals("写真 → 未設定", vm.ui.value.target)
        assertEquals("送信先が未設定", vm.ui.value.host)
        vm.nextTarget()
        assertEquals("写真 → 未設定", vm.ui.value.target)
    }

    // ── 設定画面の「既定の送信先」 ──────────────────────────────────────────

    @Test
    fun defaultSink_choosesTheInitialTarget() {
        val vm = viewModel(monitor, teams, defaultSink = "Teams")

        assertEquals("写真 → Teams", vm.ui.value.target)
        assertEquals("Teams チャネル", vm.ui.value.host)
        // 既定は初期値でしかない。その場でスワイプすればいつでも動かせる。
        vm.nextTarget()
        assertEquals("動画 → モニタ", vm.ui.value.target)
    }

    @Test
    fun defaultSink_neverPairsTeamsWithVideo() {
        // 写真の組が先に並ぶので、既定が Teams でも選ばれるのは「写真 → Teams」。
        val vm = viewModel(monitor, teams, defaultSink = "Teams")

        assertEquals(CaptureMode.PHOTO, vm.ui.value.mode)
        assertFalse(allTargets(vm).contains("動画 → Teams"))
    }

    @Test
    fun defaultSink_fallsBackToTheFirstTargetWhenUnavailable() {
        // `.env` から Teams が消えた後など、保存済みの既定が候補に無いとき。
        val vm = viewModel(monitor, defaultSink = "Teams")

        assertEquals("写真 → モニタ", vm.ui.value.target)
    }

    @Test
    fun defaultSink_blankKeepsTheFirstTarget() {
        assertEquals("写真 → モニタ", viewModel(monitor, teams).ui.value.target)
    }

    // ── 待機中の操作 ────────────────────────────────────────────────────────

    @Test
    fun videoMaxDuration_hasNoFixedLimit() {
        assertEquals(Long.MAX_VALUE, VIDEO_MAX_DURATION_MS)
    }

    @Test
    fun missingPhotoCapture_showsFailureThenReturnsToIdle() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = monitorViewModel()

            vm.onTap(null, null, audioPermissionGranted = false)

            assertEquals(CapturePhase.FAILED, vm.ui.value.phase)
            assertEquals("撮影失敗: カメラ未準備", vm.ui.value.status)

            advanceTimeBy(3_000L)
            runCurrent()
            assertEquals(CapturePhase.IDLE, vm.ui.value.phase)
            assertEquals("待機中", vm.ui.value.status)
        }

    @Test
    fun missingVideoCapture_showsFailure() {
        val vm = monitorViewModel()
        vm.nextTarget() // 写真 → モニタ の次は 動画 → モニタ（宛先が1つのため）

        vm.onTap(null, null, audioPermissionGranted = true)

        assertEquals(CaptureMode.VIDEO, vm.ui.value.mode)
        assertEquals(CapturePhase.FAILED, vm.ui.value.phase)
        assertEquals("撮影失敗: カメラ未準備", vm.ui.value.status)
    }

    @Test
    fun cameraError_truncatesDetailAndCanBeDismissedBySwipe() {
        val vm = monitorViewModel()

        vm.onCameraError(IllegalStateException("x".repeat(60)))
        assertEquals(CapturePhase.FAILED, vm.ui.value.phase)
        assertEquals(40, vm.ui.value.detail.length)

        vm.nextTarget()
        assertEquals(CapturePhase.IDLE, vm.ui.value.phase)
        assertEquals("", vm.ui.value.detail)
    }

    @Test
    fun busy_isTrueOnlyForActiveWorkPhases() {
        assertFalse(CaptureUiState(phase = CapturePhase.IDLE).busy)
        assertTrue(CaptureUiState(phase = CapturePhase.CAPTURING).busy)
        assertTrue(CaptureUiState(phase = CapturePhase.RECORDING).busy)
        assertTrue(CaptureUiState(phase = CapturePhase.LISTENING).busy)
        assertTrue(CaptureUiState(phase = CapturePhase.TRANSCRIBING).busy)
        assertTrue(CaptureUiState(phase = CapturePhase.UPLOADING).busy)
        assertFalse(CaptureUiState(phase = CapturePhase.CONFIRM).busy)
        assertFalse(CaptureUiState(phase = CapturePhase.DONE).busy)
        assertFalse(CaptureUiState(phase = CapturePhase.FAILED).busy)
    }

    // ── 写真 → 音声コメント → 確認 ──────────────────────────────────────────

    @Test
    fun photo_recordsCommentAndStopsAtTheConfirmation() =
        runTest(mainDispatcherRule.dispatcher) {
            val speech = FakeSpeech()
            val vm = monitorViewModel(speech = speech)

            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()

            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertTrue(vm.ui.value.confirming)
            assertEquals(1, speech.calls)
            // 既定は「いいえ」。確認するまで一切送らない。
            assertEquals(ConfirmChoice.NO, vm.ui.value.confirm)
            assertEquals(0, server.requestCount)

            val draft = vm.ui.value.draft!!
            assertEquals(CaptureMode.PHOTO, draft.mode)
            assertEquals("配管から水漏れ", draft.comment)
            assertEquals("960x1280 / 103KB", draft.measure)
        }

    @Test
    fun fixedPhoto_asksForCommentThenSendsOnlyAfterFinalConfirmation() =
        runTest(mainDispatcherRule.dispatcher) {
            val speech = FakeSpeech()
            val vm =
                CaptureViewModel(
                    sinks = listOf(monitor, teams),
                    speech = speech,
                    defaultSink = "Teams",
                    fixedMode = CaptureMode.PHOTO,
                    askCommentAfterCapture = true,
                )

            vm.nextTarget()
            assertEquals("写真 → Teams", vm.ui.value.target)
            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()
            assertEquals(CapturePhase.COMMENT_CHOICE, vm.ui.value.phase)
            assertEquals(0, speech.calls)
            assertTrue(teams.sent.isEmpty())

            vm.decideComment(ConfirmChoice.YES)
            runCurrent()
            assertEquals(1, speech.calls)
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertEquals(
                "配管から水漏れ",
                vm.ui.value.draft
                    ?.comment,
            )
            assertTrue(teams.sent.isEmpty())

            vm.decide(ConfirmChoice.YES)
            runCurrent()
            assertEquals(listOf("配管から水漏れ"), teams.sent.map { it.comment })
        }

    @Test
    fun fixedVideo_canSkipCommentAndStillRequiresSendConfirmation() =
        runTest(mainDispatcherRule.dispatcher) {
            val speech = FakeSpeech()
            val session = FakeVideoSession(video.copy(hasAudioTrack = false))
            val vm =
                CaptureViewModel(
                    sinks = listOf(monitor),
                    speech = speech,
                    defaultSink = "モニタ",
                    fixedMode = CaptureMode.VIDEO,
                    askCommentAfterCapture = true,
                )

            vm.onTap(null, VideoSource { session }, audioPermissionGranted = true)
            vm.onTap(null, VideoSource { session }, audioPermissionGranted = true)
            runCurrent()
            assertEquals(CapturePhase.COMMENT_CHOICE, vm.ui.value.phase)
            assertEquals(CaptureMode.VIDEO, vm.ui.value.mode)
            vm.decideComment(ConfirmChoice.NO)
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertEquals(
                "",
                vm.ui.value.draft
                    ?.comment,
            )
            assertEquals(0, speech.calls)
            assertTrue(monitor.sent.isEmpty())
        }

    @Test
    fun optionalComment_sttFailureKeepsPhotoUntilUserContinuesWithoutComment() =
        runTest(mainDispatcherRule.dispatcher) {
            val speech = FakeSpeech(result = Result.failure(IOException("認識エラー")))
            val vm =
                CaptureViewModel(
                    sinks = listOf(monitor),
                    speech = speech,
                    defaultSink = "モニタ",
                    fixedMode = CaptureMode.PHOTO,
                    askCommentAfterCapture = true,
                )

            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()
            vm.decideComment(ConfirmChoice.YES)
            runCurrent()
            assertEquals(CapturePhase.COMMENT_FAILED, vm.ui.value.phase)
            assertArrayEquals(
                photo.jpeg,
                vm.ui.value.draft
                    ?.bytes,
            )
            assertTrue(
                vm.ui.value.detail
                    .contains("認識エラー"),
            )
            assertEquals(ConfirmChoice.NO, vm.ui.value.confirm)
            assertEquals(1, speech.calls)
            assertTrue(monitor.sent.isEmpty())

            // コメント選択はもう表示されず、同じ操作を呼んでも再録音しない。
            vm.decideComment(ConfirmChoice.YES)
            runCurrent()
            assertEquals(1, speech.calls)
            assertEquals(
                "",
                vm.ui.value.draft
                    ?.comment,
            )
            vm.nextTarget()
            assertEquals(CommentFailureChoice.CONTINUE, vm.ui.value.commentFailureChoice)
            vm.onTap(null, null, audioPermissionGranted = true)
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertArrayEquals(
                photo.jpeg,
                vm.ui.value.draft
                    ?.bytes,
            )
            vm.decide(ConfirmChoice.NO)
            runCurrent()
            assertTrue(monitor.sent.isEmpty())
        }

    @Test
    fun optionalComment_silenceEmptyTranscriptAndNetworkFailureAllPreservePhoto() =
        runTest(mainDispatcherRule.dispatcher) {
            val outcomes =
                listOf(
                    Result.success(SpeechResult("", 5_000L, 100L, silent = true, wav = byteArrayOf(1))),
                    Result.success(SpeechResult("", 5_000L, 100L, wav = byteArrayOf(1))),
                    Result.success(SpeechResult("", 5_000L, 100L)),
                    Result.failure(IOException("通信エラー")),
                )
            for (outcome in outcomes) {
                val speech = FakeSpeech(result = outcome)
                val vm =
                    CaptureViewModel(
                        sinks = listOf(monitor),
                        speech = speech,
                        defaultSink = "モニタ",
                        fixedMode = CaptureMode.PHOTO,
                        askCommentAfterCapture = true,
                    )
                vm.onTap(photoSource, null, audioPermissionGranted = true)
                runCurrent()
                vm.decideComment(ConfirmChoice.YES)
                runCurrent()

                assertEquals(CapturePhase.COMMENT_FAILED, vm.ui.value.phase)
                assertArrayEquals(
                    photo.jpeg,
                    vm.ui.value.draft
                        ?.bytes,
                )
                assertEquals(ConfirmChoice.NO, vm.ui.value.confirm)
                assertTrue(
                    vm.ui.value.detail
                        .isNotBlank(),
                )
                assertEquals(
                    "",
                    vm.ui.value.draft
                        ?.comment,
                )
                assertNull(
                    vm.ui.value.draft
                        ?.voice,
                )
                assertEquals(1, speech.calls)
                assertTrue(monitor.sent.isEmpty())
            }
        }

    @Test
    fun optionalComment_withoutMicPermissionPreservesPhotoAndAllowsContinue() =
        runTest(mainDispatcherRule.dispatcher) {
            val speech = FakeSpeech()
            val vm =
                CaptureViewModel(
                    sinks = listOf(monitor),
                    speech = speech,
                    defaultSink = "モニタ",
                    fixedMode = CaptureMode.PHOTO,
                    askCommentAfterCapture = true,
                )
            vm.onTap(photoSource, null, audioPermissionGranted = false)
            runCurrent()
            vm.decideComment(ConfirmChoice.YES)

            assertEquals(CapturePhase.COMMENT_FAILED, vm.ui.value.phase)
            assertArrayEquals(
                photo.jpeg,
                vm.ui.value.draft
                    ?.bytes,
            )
            assertTrue(
                vm.ui.value.detail
                    .contains("マイク権限"),
            )
            assertEquals(ConfirmChoice.NO, vm.ui.value.confirm)
            assertEquals(0, speech.calls)
            assertTrue(monitor.sent.isEmpty())
            vm.decideCommentFailure(CommentFailureChoice.CONTINUE)
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertArrayEquals(
                photo.jpeg,
                vm.ui.value.draft
                    ?.bytes,
            )
        }

    @Test
    fun photo_withoutMicPermission_skipsRecordingAndSendsNoComment() =
        runTest(mainDispatcherRule.dispatcher) {
            val speech = FakeSpeech()
            val vm = monitorViewModel(speech = speech)

            vm.onTap(photoSource, null, audioPermissionGranted = false)
            runCurrent()

            assertEquals(0, speech.calls)
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertEquals(
                "",
                vm.ui.value.draft
                    ?.comment,
            )
            assertEquals("マイク権限なし（コメントなし）", vm.ui.value.detail)
        }

    @Test
    fun photo_legacySpeechFailurePreservesPhotoForRecovery() =
        runTest(mainDispatcherRule.dispatcher) {
            val speech = FakeSpeech(result = Result.failure(IOException("認証エラー")))
            val vm = monitorViewModel(speech = speech)

            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()

            assertEquals(CapturePhase.COMMENT_FAILED, vm.ui.value.phase)
            assertArrayEquals(
                photo.jpeg,
                vm.ui.value.draft
                    ?.bytes,
            )
            assertEquals(
                "",
                vm.ui.value.draft
                    ?.comment,
            )
            assertEquals("文字起こし失敗: 認証エラー", vm.ui.value.detail)
        }

    @Test
    fun photo_silentRecording_isReportedSeparatelyFromUnrecognised() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = monitorViewModel(speech = FakeSpeech(result = Result.success(SpeechResult("", 3_000L, 700L, silent = true))))

            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()

            assertEquals("マイクが音を拾えませんでした", vm.ui.value.detail)
        }

    @Test
    fun photo_unavailableSpeechEngine_skipsRecording() =
        runTest(mainDispatcherRule.dispatcher) {
            val speech = FakeSpeech(available = false)
            val vm = monitorViewModel(speech = speech)

            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()

            assertEquals(0, speech.calls)
            assertEquals("音声認識を利用できません（コメントなし）", vm.ui.value.detail)
        }

    // ── 送信確認 ────────────────────────────────────────────────────────────

    @Test
    fun recordingAndTranscriptionFailuresKeepPhotoAndCanRetryWithoutRecapturing() =
        runTest(mainDispatcherRule.dispatcher) {
            for (failure in listOf(
                "begin",
                "await",
                "await-throw",
                "timeout",
                "timeout-stop-throw",
                "stt-result",
                "stt-throw",
                "stt-timeout",
            )) {
                val speech = RecoverableSpeech(failure)
                val vm =
                    CaptureViewModel(
                        sinks = listOf(monitor),
                        speech = speech,
                        fixedMode = CaptureMode.PHOTO,
                        askCommentAfterCapture = true,
                    )
                vm.onTap(photoSource, null, audioPermissionGranted = true)
                runCurrent()
                val draft = vm.ui.value.draft
                vm.decideComment(ConfirmChoice.YES)
                runCurrent()
                if (failure in listOf("timeout", "timeout-stop-throw", "stt-timeout")) {
                    advanceTimeBy(20_001)
                    runCurrent()
                    if (failure != "stt-timeout") assertTrue(speech.stopped)
                }
                assertEquals(failure, CapturePhase.COMMENT_FAILED, vm.ui.value.phase)
                assertSame(draft, vm.ui.value.draft)
                assertArrayEquals(
                    photo.jpeg,
                    vm.ui.value.draft
                        ?.bytes,
                )
                assertEquals(
                    "",
                    vm.ui.value.draft
                        ?.comment,
                )
                vm.onCameraError(IOException("late camera error"))
                assertSame(draft, vm.ui.value.draft)
                assertTrue(monitor.sent.isEmpty())
                speech.failure = ""
                vm.onTap(null, null, audioPermissionGranted = true)
                runCurrent()
                assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
                assertArrayEquals(
                    photo.jpeg,
                    vm.ui.value.draft
                        ?.bytes,
                )
                assertEquals(
                    "再録音成功",
                    vm.ui.value.draft
                        ?.comment,
                )
                assertTrue(monitor.sent.isEmpty())
            }
        }

    @Test
    fun backFromVoiceFailureKeepsPhotoAndDoesNotSendOrDelete() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = monitorViewModel(speech = FakeSpeech(result = Result.failure(IOException("認識失敗"))))
            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()
            val draft = vm.ui.value.draft
            assertEquals(CapturePhase.COMMENT_FAILED, vm.ui.value.phase)
            assertTrue(vm.onBackPressed())
            assertEquals(CapturePhase.DRAFT_REVIEW, vm.ui.value.phase)
            assertSame(draft, vm.ui.value.draft)
            assertTrue(vm.onBackPressed())
            assertSame(draft, vm.ui.value.draft)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun legacyBlankTranscriptWithAudibleWavKeepsPcAutoRecognitionPath() =
        runTest(mainDispatcherRule.dispatcher) {
            val wav = byteArrayOf(1, 2, 3)
            val vm = monitorViewModel(speech = FakeSpeech(result = Result.success(SpeechResult("", 3_000L, 100L, wav = wav))))
            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertSame(
                wav,
                vm.ui.value.draft
                    ?.voice,
            )
            assertArrayEquals(
                photo.jpeg,
                vm.ui.value.draft
                    ?.bytes,
            )
            assertEquals(0, server.requestCount)
        }

    @Test
    fun legacyRecognitionCancelledByBackCannotOverwriteConfirmationOrRetainedPhoto() =
        runTest(mainDispatcherRule.dispatcher) {
            val result = CompletableDeferred<SpeechResult>()
            val speech =
                object : SpeechSource {
                    override val available = true

                    override suspend fun listenOnce(): Result<SpeechResult> = withContext(NonCancellable) { Result.success(result.await()) }

                    override fun close() {}
                }
            val vm = monitorViewModel(speech = speech)
            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()
            advanceTimeBy(3_001)
            runCurrent()
            assertEquals(CapturePhase.TRANSCRIBING, vm.ui.value.phase)
            assertTrue(vm.onBackPressed())
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            vm.decide(ConfirmChoice.NO)
            val retained = vm.ui.value
            result.complete(SpeechResult("遅い結果", 3_000L, 20_000L))
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(retained, vm.ui.value)
            assertArrayEquals(
                photo.jpeg,
                vm.ui.value.draft
                    ?.bytes,
            )
            assertEquals(
                "",
                vm.ui.value.draft
                    ?.comment,
            )
            assertEquals(0, server.requestCount)
        }

    @Test
    fun confirmNo_preservesDraftWithoutSending() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = confirmingPhoto()
            val draft = vm.ui.value.draft

            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()

            assertEquals(0, server.requestCount)
            assertEquals(CapturePhase.DRAFT_REVIEW, vm.ui.value.phase)
            assertEquals(draft, vm.ui.value.draft)
            assertArrayEquals(
                photo.jpeg,
                vm.ui.value.draft
                    ?.bytes,
            )
            assertEquals(
                "配管から水漏れ",
                vm.ui.value.draft
                    ?.comment,
            )
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(draft, vm.ui.value.draft)
        }

    @Test
    fun backWhileConfirming_preservesDraftAndIsConsumedRepeatedly() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = confirmingPhoto()
            val draft = vm.ui.value.draft

            assertTrue(vm.onBackPressed())
            assertEquals(0, server.requestCount)
            assertEquals(draft, vm.ui.value.draft)
            assertTrue(vm.onBackPressed())
            assertEquals(draft, vm.ui.value.draft)
        }

    @Test
    fun retainedDraft_canReturnToConfirmationWithoutSendingOrExplicitlyDiscard() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = confirmingPhoto()
            val draft = vm.ui.value.draft
            vm.decide(ConfirmChoice.NO)
            assertEquals(DraftReviewChoice.SEND, vm.ui.value.draftReviewChoice)
            vm.nextTarget()
            assertEquals(DraftReviewChoice.DISCARD, vm.ui.value.draftReviewChoice)
            vm.previousTarget()
            vm.onTap(null, null, audioPermissionGranted = true)
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertEquals(ConfirmChoice.NO, vm.ui.value.confirm)
            assertEquals(draft, vm.ui.value.draft)
            assertEquals(0, server.requestCount)
            vm.decide(ConfirmChoice.NO)
            vm.onCameraError(IllegalStateException("late camera callback"))
            assertEquals(draft, vm.ui.value.draft)
            vm.decideDraftReview(DraftReviewChoice.DISCARD)
            assertNull(vm.ui.value.draft)
            assertEquals(CapturePhase.DONE, vm.ui.value.phase)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun swipeWhileConfirming_movesBetweenYesAndNoInsteadOfChangingMode() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = confirmingPhoto()

            vm.nextTarget()
            assertEquals(ConfirmChoice.YES, vm.ui.value.confirm)
            assertEquals(CaptureMode.PHOTO, vm.ui.value.mode)

            vm.previousTarget()
            assertEquals(ConfirmChoice.NO, vm.ui.value.confirm)
            assertEquals(CaptureMode.PHOTO, vm.ui.value.mode)
        }

    @Test
    fun confirmYes_uploadsPhotoWithTranscriptAsComment() =
        runTest(mainDispatcherRule.dispatcher) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"id":"p-1","kind":"photo"}"""))
            val vm = confirmingPhoto()

            vm.nextTarget() // いいえ → はい
            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent() // 送信コルーチンを起こす（POST 自体は Dispatchers.IO で走る）

            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("image/jpeg", request.getHeader("Content-Type"))
            assertEquals(URLEncoder.encode("配管から水漏れ", "UTF-8"), request.getHeader("X-Comment"))
            assertEquals(photo.jpeg.size.toLong(), request.bodySize)

            pumpUntil { vm.ui.value.phase == CapturePhase.DONE }
            assertEquals("送信しました", vm.ui.value.status)
            assertNull(vm.ui.value.draft)
        }

    @Test
    fun uploadFailure_keepsTheDraftForRetry() =
        runTest(mainDispatcherRule.dispatcher) {
            server.enqueue(MockResponse().setResponseCode(415).setBody("unsupported"))
            val vm = confirmingPhoto()

            vm.decide(ConfirmChoice.YES)

            pumpUntil {
                vm.ui.value.alert
                    .isNotBlank()
            }
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertEquals("送信に失敗しました: HTTP 415", vm.ui.value.alert)
            // 撮ったものは残し、選択は安全側（いいえ）へ戻す。
            assertEquals(
                "配管から水漏れ",
                vm.ui.value.draft
                    ?.comment,
            )
            assertEquals(ConfirmChoice.NO, vm.ui.value.confirm)
        }

    // ── 選択中の送信先へ送る ────────────────────────────────────────────────

    @Test
    fun confirmYes_sendsToTheSelectedSinkOnly() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(monitor, teams)
            vm.nextTarget() // 写真 → Teams

            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()
            vm.decide(ConfirmChoice.YES)
            runCurrent()

            assertEquals(0, monitor.sent.size)
            assertEquals(FakeSink.Sent(photo.jpeg.size, MIME_JPEG, "配管から水漏れ"), teams.sent.single())
            assertEquals(CapturePhase.DONE, vm.ui.value.phase)
            assertEquals("送信しました", vm.ui.value.status)
        }

    @Test
    fun video_isSentToTheMonitorAsMp4() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(monitor, teams)
            val session = FakeVideoSession(video)
            val videoSource = VideoSource { session }
            vm.nextTarget()
            vm.nextTarget() // 動画 → モニタ（Teams は動画を受けないので現れない）

            vm.onTap(null, videoSource, audioPermissionGranted = true)
            runCurrent()
            vm.onTap(null, videoSource, audioPermissionGranted = true)
            runCurrent()
            vm.decide(ConfirmChoice.YES)
            runCurrent()

            assertEquals(0, teams.sent.size)
            assertEquals(FakeSink.Sent(video.bytes.size, MIME_MP4, ""), monitor.sent.single())
        }

    @Test
    fun signInRequiredFailure_keepsTheDraftAndAsksForSignIn() =
        runTest(mainDispatcherRule.dispatcher) {
            val expired =
                FakeSink(
                    "Teams",
                    "Teams チャネル",
                    setOf(MIME_JPEG),
                    outcome = Result.failure(SignInRequiredException("Teams 未サインイン")),
                )
            val vm = viewModel(expired)

            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()
            vm.decide(ConfirmChoice.YES)
            runCurrent()

            assertTrue(vm.ui.value.signInRequired)
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertEquals("送信に失敗しました: Teams 未サインイン", vm.ui.value.alert)
            assertEquals(
                "配管から水漏れ",
                vm.ui.value.draft
                    ?.comment,
            )

            // 画面が認証へ送ったらフラグは落ちる（戻ってきたときに二重で遷移しない）。
            vm.onSignInHandled()
            assertFalse(vm.ui.value.signInRequired)
        }

    @Test
    fun expiredThenSignedIn_resendsTheSameDraftFromTheSameViewModel() =
        runTest(mainDispatcherRule.dispatcher) {
            val expired =
                FakeSink(
                    "Teams",
                    "Teams チャネル",
                    setOf(MIME_JPEG),
                    outcome = Result.failure(SignInRequiredException("Teams 未サインイン")),
                )
            val vm = viewModel(expired)

            // ① 期限切れ: 撮って送ろうとして弾かれる。下書きは確認ポップアップに残る。
            vm.onTap(photoSource, null, audioPermissionGranted = true)
            runCurrent()
            val draft = vm.ui.value.draft!!
            vm.decide(ConfirmChoice.YES)
            runCurrent()

            assertTrue(vm.ui.value.signInRequired)
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)

            // ② 認証画面へ行って戻ってくる。`popBackStack` なら NavBackStackEntry ごと生き残るので
            //    **この ViewModel インスタンスがそのまま**再開する（navigate で積み直すと別物になる）。
            vm.onSignInHandled()
            expired.outcome = Result.success(SendOutcome(elapsedMs = 420L))
            assertSame(draft, vm.ui.value.draft)
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)

            // ③ 撮り直さずに、抱えたままの下書きをそのまま再送できる。
            vm.decide(ConfirmChoice.YES)
            runCurrent()

            assertEquals(2, expired.sent.size)
            assertEquals(expired.sent[0], expired.sent[1])
            assertEquals(FakeSink.Sent(photo.jpeg.size, MIME_JPEG, "配管から水漏れ"), expired.sent[1])
            assertEquals(CapturePhase.DONE, vm.ui.value.phase)
            assertEquals("送信しました", vm.ui.value.status)
            assertNull(vm.ui.value.draft)
        }

    // ── 録画（手動停止） ────────────────────────────────────────────────────

    @Test
    fun video_startsOnFirstTapAndStopsOnSecond() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeVideoSession(video)
            val vm = monitorViewModel()
            val videoSource = VideoSource { session }
            vm.nextTarget() // 録画モードへ

            vm.onTap(null, videoSource, audioPermissionGranted = true)
            assertEquals(CapturePhase.RECORDING, vm.ui.value.phase)
            assertEquals("録画中 0秒", vm.ui.value.status)
            assertEquals("タップで停止（電池10%・メモリ不足で自動停止）", vm.ui.value.detail)

            runCurrent()
            advanceTimeBy(2_100L)
            runCurrent()
            assertEquals("録画中 2秒", vm.ui.value.status)
            assertFalse(session.stopped)

            vm.onTap(null, videoSource, audioPermissionGranted = true)
            runCurrent()

            assertTrue(session.stopped)
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertEquals(
                "5秒 / 1280x720 / 2.0MB",
                vm.ui.value.draft
                    ?.measure,
            )
            assertEquals("音声付き動画", vm.ui.value.detail)
            // 動画自体に音声が入るので音声コメントは録らない。
            assertEquals(
                "",
                vm.ui.value.draft
                    ?.comment,
            )
        }

    @Test
    fun video_swipeIsIgnoredWhileRecording() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeVideoSession(video)
            val vm = monitorViewModel()
            val videoSource = VideoSource { session }
            vm.nextTarget()

            vm.onTap(null, videoSource, audioPermissionGranted = true)
            runCurrent()
            vm.nextTarget()

            assertEquals(CaptureMode.VIDEO, vm.ui.value.mode)
            assertEquals(CapturePhase.RECORDING, vm.ui.value.phase)
        }

    @Test
    fun video_longRecordingIsShownAsMinutesAndSeconds() =
        runTest(mainDispatcherRule.dispatcher) {
            val session = FakeVideoSession(video.copy(durationMs = 65_200L, hasAudioTrack = false))
            val vm = monitorViewModel()
            val videoSource = VideoSource { session }
            vm.nextTarget()

            vm.onTap(null, videoSource, audioPermissionGranted = true)
            runCurrent()
            vm.onTap(null, videoSource, audioPermissionGranted = true)
            runCurrent()

            assertEquals(
                "1分5秒 / 1280x720 / 2.0MB",
                vm.ui.value.draft
                    ?.measure,
            )
            assertEquals("動画を撮影しました", vm.ui.value.detail)
        }

    // ── ヘルパー ────────────────────────────────────────────────────────────

    /** 写真を撮って確認ポップアップを出したところまで進める（宛先は MockWebServer の受信モニタ）。 */
    private fun TestScope.confirmingPhoto(speech: SpeechSource = FakeSpeech()): CaptureViewModel {
        val vm = monitorViewModel(speech = speech)
        vm.onTap(photoSource, null, audioPermissionGranted = true)
        runCurrent()
        check(vm.ui.value.phase == CapturePhase.CONFIRM) { "確認まで進んでいない: ${vm.ui.value}" }
        return vm
    }

    /**
     * `UploadClient` は `Dispatchers.IO` に出るので、仮想時間だけでは戻りを待てない。
     * 実時間で待ちつつ `runCurrent()` で戻ってきた継続を回す（時間は進めないので
     * 結果表示の自動リセットは発火しない）。
     */
    private fun TestScope.pumpUntil(
        timeoutMs: Long = 5_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("条件が満たされませんでした")
    }

    /** 宛先が受信モニタ（MockWebServer）だけの ViewModel。HTTP の中身まで見たいとき用。 */
    private fun monitorViewModel(
        endpoint: String = server.url("/upload").toString(),
        speech: SpeechSource = FakeSpeech(),
    ) = CaptureViewModel(listOf(UploadClient(endpoint = endpoint)), speech)

    private fun viewModel(
        vararg sinks: MediaSink,
        speech: SpeechSource = FakeSpeech(),
        defaultSink: String = "",
    ) = CaptureViewModel(sinks.toList(), speech, defaultSink)

    /** 巡回して現れる送信先ラベルを 1 周ぶん集める。 */
    private fun allTargets(vm: CaptureViewModel) =
        buildList {
            repeat(3) {
                add(vm.ui.value.target)
                vm.nextTarget()
            }
        }

    /** 受け入れる MIME と結果だけを変えたテスト用の宛先。送ったものを控える。 */
    private class FakeSink(
        override val label: String,
        override val detail: String,
        private val accepted: Set<String>,
        /** サインインし直した後だけ成功する、のような「途中で変わる宛先」を作れるように var。 */
        var outcome: Result<SendOutcome> = Result.success(SendOutcome(elapsedMs = 100L)),
    ) : MediaSink {
        val sent = mutableListOf<Sent>()

        override fun accepts(mime: String): Boolean = mime in accepted

        override suspend fun send(
            bytes: ByteArray,
            mime: String,
            comment: String,
            voice: ByteArray?,
        ): Result<SendOutcome> {
            sent += Sent(bytes.size, mime, comment)
            return outcome
        }

        /** 送信の記録。ByteArray は equals が参照比較なのでサイズだけ控える。 */
        data class Sent(
            val bytes: Int,
            val mime: String,
            val comment: String,
        )
    }

    private class FakeSpeech(
        override val available: Boolean = true,
        private val result: Result<SpeechResult> = Result.success(SpeechResult("配管から水漏れ", 3_000L, 800L)),
    ) : SpeechSource {
        var calls = 0
        var closed = false

        override suspend fun listenOnce(): Result<SpeechResult> {
            calls++
            return result
        }

        override fun close() {
            closed = true
        }
    }

    private class RecoverableSpeech(
        var failure: String,
    ) : SpeechSource {
        override val available = true
        var stopped = false

        override suspend fun listenOnce(): Result<SpeechResult> = error("split recording should be used")

        override fun beginRecording(durationMs: Long?): SpeechRecordingSession {
            if (failure == "begin") throw IOException("録音開始失敗")
            return object : SpeechRecordingSession {
                override suspend fun await(): Result<RecordedVoice> {
                    if (failure in listOf("timeout", "timeout-stop-throw")) awaitCancellation()
                    if (failure == "await-throw") throw IOException("録音例外")
                    if (failure == "await") return Result.failure(IOException("録音失敗"))
                    return Result.success(RecordedVoice(byteArrayOf(1, 2), 5_000L, 1_000))
                }

                override fun stop() {
                    stopped = true
                    if (failure == "timeout-stop-throw") throw IOException("停止例外")
                }
            }
        }

        override suspend fun transcribe(voice: RecordedVoice): Result<SpeechResult> {
            if (failure == "stt-timeout") awaitCancellation()
            if (failure == "stt-throw") throw IOException("認識例外")
            if (failure == "stt-result") return Result.failure(IOException("認識失敗"))
            return Result.success(SpeechResult("再録音成功", 5_000L, 100L))
        }

        override fun close() {}
    }

    private class FakeVideoSession(
        private val video: RecordedVideo,
    ) : VideoSession {
        private val finalized = CompletableDeferred<Unit>()
        var stopped = false

        override fun stop() {
            stopped = true
            finalized.complete(Unit)
        }

        override suspend fun await(): RecordedVideo {
            finalized.await()
            return video
        }
    }
}
