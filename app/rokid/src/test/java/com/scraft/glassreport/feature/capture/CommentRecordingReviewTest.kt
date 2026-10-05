package com.scraft.glassreport.feature.capture

import androidx.lifecycle.ViewModelStore
import com.scraft.glassreport.capture.asr.RecordedVoice
import com.scraft.glassreport.capture.asr.SpeechRecordingSession
import com.scraft.glassreport.capture.asr.SpeechResult
import com.scraft.glassreport.capture.asr.SpeechSource
import com.scraft.glassreport.capture.asr.VoiceRecorder
import com.scraft.glassreport.capture.camera.CapturedPhoto
import com.scraft.glassreport.capture.camera.PhotoSource
import com.scraft.glassreport.net.NoMediaSink
import com.scraft.glassreport.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentRecordingReviewTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun singleTapStopsAndTranscribesOnce() =
        runTest {
            val speech = SplitSpeech()
            val vm = recording(speech)
            vm.onTap(null, null, true)
            vm.onTap(null, null, true)
            runCurrent()
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertEquals(1, speech.transcribed.size)
            assertEquals(1000L, speech.transcribed.single().durationMs)
            assertEquals(ConfirmChoice.NO, vm.ui.value.confirm)
            assertEquals(1, speech.sessions.single().releases)
        }

    @Test fun doubleTapHoldsAudioUntilConfirmed() =
        runTest {
            val speech = SplitSpeech()
            val vm = recording(speech)
            vm.reviewCommentRecording()
            runCurrent()
            assertEquals(CapturePhase.COMMENT_REVIEW, vm.ui.value.phase)
            assertEquals(CommentReviewChoice.CONFIRM, vm.ui.value.commentReviewChoice)
            assertTrue(speech.transcribed.isEmpty())
            vm.decideCommentReview(CommentReviewChoice.CONFIRM)
            vm.decideCommentReview(CommentReviewChoice.CONFIRM)
            runCurrent()
            assertEquals(1, speech.transcribed.size)
            assertEquals(
                "文字起こし",
                vm.ui.value.draft
                    ?.comment,
            )
        }

    @Test fun cancelResumesRemainingTimeAndMultiplePausesJoinOnlyPcm() =
        runTest {
            val speech = SplitSpeech()
            val vm = recording(speech)
            repeat(2) {
                vm.reviewCommentRecording()
                runCurrent()
                vm.decideCommentReview(CommentReviewChoice.CANCEL)
                runCurrent()
            }
            assertEquals(listOf(5000L, 4000L, 3000L), speech.requestedDurations)
            speech.sessions.last().complete(3000L)
            runCurrent()
            val voice = speech.transcribed.single()
            assertEquals(5000L, voice.durationMs)
            assertEquals(44 + 160000, voice.wav.size)
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
        }

    @Test fun retakeDiscardsOldAudioAndStartsFreshFiveSeconds() =
        runTest {
            val speech = SplitSpeech()
            val vm = recording(speech)
            vm.reviewCommentRecording()
            runCurrent()
            vm.decideCommentReview(CommentReviewChoice.RETAKE)
            runCurrent()
            speech.sessions.last().complete(5000L)
            runCurrent()
            assertEquals(listOf(5000L, 5000L), speech.requestedDurations)
            assertEquals(5000L, speech.transcribed.single().durationMs)
        }

    @Test fun backFromReviewResumesAndClearReleasesCurrentRecording() =
        runTest {
            val speech = SplitSpeech()
            val vm = recording(speech)
            vm.reviewCommentRecording()
            runCurrent()
            assertTrue(vm.onBackPressed())
            runCurrent()
            assertEquals(CapturePhase.LISTENING, vm.ui.value.phase)
            val store = ViewModelStore()
            store.put("capture", vm)
            store.clear()
            runCurrent()
            assertEquals(1, speech.sessions.last().releases)
            assertTrue(speech.closed)
            assertTrue(speech.transcribed.isEmpty())
        }

    @Test fun autoFinishThenLateDoubleTapDoesNotStartReviewOrTranscribeTwice() =
        runTest {
            val speech = SplitSpeech()
            val vm = recording(speech)
            speech.sessions.single().complete(5000L)
            runCurrent()
            vm.reviewCommentRecording()
            runCurrent()
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertEquals(1, speech.transcribed.size)
        }

    @Test fun backDuringTranscribingCancelsWithoutRestoringOldComment() =
        runTest {
            val speech = SplitSpeech().apply { transcriptGate = CompletableDeferred() }
            val vm = recording(speech)
            vm.onTap(null, null, true)
            runCurrent()
            assertEquals(CapturePhase.TRANSCRIBING, vm.ui.value.phase)
            assertTrue(vm.onBackPressed())
            speech.transcriptGate!!.complete(Unit)
            runCurrent()
            assertEquals(CapturePhase.CONFIRM, vm.ui.value.phase)
            assertEquals(
                "",
                vm.ui.value.draft
                    ?.comment,
            )
        }

    private fun TestScope.recording(speech: SplitSpeech): CaptureViewModel {
        val vm = CaptureViewModel(listOf(NoMediaSink), speech, askCommentAfterCapture = true)
        vm.onTap(PhotoSource { CapturedPhoto(byteArrayOf(1), 4, 3, null) }, null, true)
        runCurrent()
        vm.decideComment(ConfirmChoice.YES)
        runCurrent()
        return vm
    }

    private class SplitSpeech : SpeechSource {
        override val available = true
        val requestedDurations = mutableListOf<Long>()
        val sessions = mutableListOf<Session>()
        val transcribed = mutableListOf<RecordedVoice>()
        var closed = false
        var transcriptGate: CompletableDeferred<Unit>? = null

        override suspend fun listenOnce(): Result<SpeechResult> = error("Legacy method must not be used")

        override fun beginRecording(durationMs: Long?): SpeechRecordingSession {
            requestedDurations += durationMs!!
            return Session().also { sessions += it }
        }

        override suspend fun transcribe(voice: RecordedVoice): Result<SpeechResult> {
            transcribed += voice
            transcriptGate?.await()
            return Result.success(SpeechResult("文字起こし", voice.durationMs, 20L))
        }

        override fun close() {
            closed = true
        }
    }

    private class Session : SpeechRecordingSession {
        val result = CompletableDeferred<RecordedVoice>()
        var releases = 0

        override fun stop() {
            complete(1000L)
        }

        fun complete(durationMs: Long) {
            result.complete(RecordedVoice(VoiceRecorder.toWav(ByteArray((durationMs * 32).toInt()) { 1 }), durationMs, 1000))
        }

        override suspend fun await(): Result<RecordedVoice> =
            try {
                Result.success(result.await())
            } finally {
                releases++
            }
    }
}
