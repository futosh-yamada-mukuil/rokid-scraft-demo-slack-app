package com.scraft.glassreport.capture.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RecordedSpeechSourceTest {
    /** 先読みの合図だけを数える。認識結果は固定。 */
    private class CountingTranscriber(
        override val available: Boolean = true,
    ) : WavTranscriber {
        var prepared = 0
        var cancelled = 0
        var transcribed = 0

        override suspend fun transcribe(wav: ByteArray): Result<String> {
            transcribed++
            return Result.success("確認しました")
        }

        override fun prepare() {
            prepared++
        }

        override fun cancelPrepared() {
            cancelled++
        }

        override fun close() {}
    }

    private fun recorder(result: () -> RecordedVoice) =
        object : VoiceCapture {
            override suspend fun record(durationMs: Long): RecordedVoice = result()
        }

    private val spoken = RecordedVoice(VoiceRecorder.toWav(byteArrayOf(1, 0, 2, 0)), 100, 1_000)

    /**
     * モデルの読み込みに 4 秒、認識に 0.3 秒かかる認識器（同梱モデルを AVD で動かしたときの配分に近い）。
     * 本物と同じ [PreparedResource] で先読みする。[warmUp] が false なら従来どおり認識の直前に読み込む。
     */
    private class SlowLoadingTranscriber(
        scope: CoroutineScope,
        private val warmUp: Boolean,
    ) : WavTranscriber {
        private val lock = Mutex()
        var loads = 0
        private val warm = PreparedResource(scope, lock, holdMs = 10_000, create = { load() }, release = {})
        override val available = true

        private suspend fun load(): Any {
            delay(4_000)
            loads++
            return Any()
        }

        override fun prepare() {
            if (warmUp) warm.prepare()
        }

        override fun cancelPrepared() = warm.discard()

        override suspend fun transcribe(wav: ByteArray): Result<String> =
            lock.withLock {
                warm.takeLocked() ?: load()
                delay(300)
                Result.success("現場の写真です")
            }

        override fun close() = warm.discard()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadingTheModelWhileRecordingLeavesOnlyTheRecognitionToWaitFor() =
        runTest {
            suspend fun waitAfterRecording(warmUp: Boolean): Pair<Long, String> {
                val transcriber = SlowLoadingTranscriber(backgroundScope, warmUp)
                val fiveSeconds =
                    recorder {
                        spoken
                    }.let { capture ->
                        VoiceCapture { duration ->
                            delay(5_000)
                            capture.record(duration)
                        }
                    }
                val source = RecordedSpeechSource(transcriber, fiveSeconds)
                val voice = source.beginRecording().await().getOrThrow()
                val recorded = currentTime
                val text = source.transcribe(voice).getOrThrow().text
                assertEquals(1, transcriber.loads)
                return (currentTime - recorded) to text
            }

            val (before, textBefore) = waitAfterRecording(warmUp = false)
            val (after, textAfter) = waitAfterRecording(warmUp = true)

            assertEquals(4_300, before)
            assertEquals(300, after)
            // 速くなるのは待ちだけ。文字起こしは同じ。
            assertEquals(textBefore, textAfter)
        }

    @Test
    fun recognitionIsPreparedWhenRecordingStartsAndKeptForTheTranscription() =
        runTest {
            val transcriber = CountingTranscriber()
            val source = RecordedSpeechSource(transcriber, recorder { spoken })

            val session = source.beginRecording()
            assertEquals(1, transcriber.prepared)
            val text = source.transcribe(session.await().getOrThrow()).getOrThrow().text

            assertEquals("確認しました", text)
            assertEquals(0, transcriber.cancelled)
        }

    @Test
    fun preparedRecognitionIsDroppedWhenNothingWillBeTranscribed() =
        runTest {
            // 無音。認識へ進まないので、読み込んだモデルを抱えたままにしない。
            val silent = CountingTranscriber()
            val quiet = RecordedVoice(VoiceRecorder.toWav(byteArrayOf(1, 0)), 100, 499)
            val silentSource = RecordedSpeechSource(silent, recorder { quiet })
            silentSource.transcribe(silentSource.beginRecording().await().getOrThrow()).getOrThrow()
            assertEquals(1, silent.cancelled)
            assertEquals(0, silent.transcribed)

            // 録音の失敗。
            val failing = CountingTranscriber()
            val failed = RecordedSpeechSource(failing, recorder { throw IOException("マイクを開けません") }).beginRecording().await()
            assertTrue(failed.isFailure)
            assertEquals(1, failing.cancelled)

            // 認識エンジンが使えない構成では準備自体をしない。
            val missing = CountingTranscriber(available = false)
            RecordedSpeechSource(missing, recorder { spoken }).beginRecording()
            assertEquals(0, missing.prepared)
        }

    @Test
    fun silentRecordingNeverCallsEitherRecognitionEngine() =
        runTest {
            val source =
                RecordedSpeechSource(
                    object : WavTranscriber {
                        override val available = true

                        override suspend fun transcribe(wav: ByteArray): Result<String> = error("silent audio must remain local")

                        override fun close() {}
                    },
                )
            val voice = RecordedVoice(VoiceRecorder.toWav(byteArrayOf(1, 0)), 100, 499)
            val result = source.transcribe(voice).getOrThrow()
            assertEquals("", result.text)
            assertTrue(result.silent)
            assertSame(voice.wav, result.wav)
        }

    @Test fun sharedSourcePreservesRecordingStopDurationAndWav() =
        runTest {
            val wav = VoiceRecorder.toWav(byteArrayOf(1, 0, 2, 0))
            var requested = 0L
            var stopped = false
            var closed = false
            val transcriber =
                object : WavTranscriber {
                    override val available = true

                    override suspend fun transcribe(wav: ByteArray) = Result.success(" recognized ")

                    override fun close() {
                        closed = true
                    }
                }
            val recorder =
                object : VoiceCapture {
                    override suspend fun record(durationMs: Long): RecordedVoice = error("stop-aware overload expected")

                    override suspend fun record(
                        durationMs: Long,
                        stop: VoiceStopSignal,
                    ): RecordedVoice {
                        requested = durationMs
                        stopped = stop.isRequested
                        return RecordedVoice(wav, 100, 1000)
                    }
                }
            val source = RecordedSpeechSource(transcriber, recorder, 5000)
            val session = source.beginRecording(3000)
            session.stop()
            val result = source.transcribe(session.await().getOrThrow()).getOrThrow()
            assertEquals(3000, requested)
            assertTrue(stopped)
            assertEquals("recognized", result.text)
            assertSame(wav, result.wav)
            source.close()
            assertTrue(closed)
        }

    @Test fun engineSettingsDefaultToGoogle() {
        assertEquals(SpeechEngine.GOOGLE, SpeechEngine.fromCode(""))
        assertEquals(SpeechEngine.GOOGLE, SpeechEngine.fromCode("amiVoice"))
        assertEquals(SpeechEngine.OFFLINE, SpeechEngine.fromCode("offline"))
    }
}
