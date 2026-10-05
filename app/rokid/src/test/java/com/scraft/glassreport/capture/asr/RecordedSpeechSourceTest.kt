package com.scraft.glassreport.capture.asr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedSpeechSourceTest {
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
