package com.scraft.glassreport.capture.asr

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRecorderLifecycleTest {
    @Test fun earlyStopReturnsPartialWavAndReleasesOnce() =
        runTest {
            val stop = VoiceStopSignal()
            val input =
                Input { bytes, _ ->
                    bytes.fill(1)
                    stop.stop()
                    3200
                }
            val voice = VoiceRecorder.recordFrom(input, 5000L, stop) { testScheduler.currentTime }
            assertEquals(100L, voice.durationMs)
            assertEquals(3244, voice.wav.size)
            assertEquals(1, input.stops)
            assertEquals(1, input.releases)
        }

    @Test fun automaticLimitReturnsExactlyFiveSeconds() =
        runTest {
            val input =
                Input { bytes, size ->
                    bytes.fill(1)
                    size
                }
            val voice = VoiceRecorder.recordFrom(input, 5000L, VoiceStopSignal()) { testScheduler.currentTime }
            assertEquals(5000L, voice.durationMs)
            assertEquals(160044, voice.wav.size)
            assertEquals(1, input.releases)
        }

    @Test fun pcmAmplitudeDistinguishesRealSilenceFromRecordedAudio() =
        runTest {
            for ((sample, silent) in listOf(0 to true, 1200 to false)) {
                val input =
                    Input { bytes, size ->
                        for (index in 0 until size step 2) {
                            bytes[index] = sample.toByte()
                            bytes[index + 1] = (sample shr 8).toByte()
                        }
                        size
                    }
                val voice = VoiceRecorder.recordFrom(input, 5000L, VoiceStopSignal()) { testScheduler.currentTime }
                assertEquals(sample, voice.peak)
                assertEquals(silent, voice.silent)
                assertEquals(5000L, voice.durationMs)
                assertEquals(1, input.releases)
                if (sample > 0) assertFalse(voice.silent)
            }
        }

    @Test fun immediateStopReturnsEmptySilentWav() =
        runTest {
            val input = Input { _, _ -> error("No read expected") }
            val voice = VoiceRecorder.recordFrom(input, 5000L, VoiceStopSignal().apply { stop() }) { testScheduler.currentTime }
            assertEquals(44, voice.wav.size)
            assertTrue(voice.silent)
            assertEquals(1, input.releases)
        }

    @Test fun cancellationDuringEmptyReadsStopsAndReleases() =
        runTest {
            val input = Input { _, _ -> 0 }
            val job = launch { VoiceRecorder.recordFrom(input, 5000L, VoiceStopSignal()) { testScheduler.currentTime } }
            runCurrent()
            assertTrue(input.reads > 0)
            job.cancelAndJoin()
            assertEquals(1, input.stops)
            assertEquals(1, input.releases)
        }

    @Test fun readFailureStillReleasesEvenIfStopThrows() =
        runTest {
            val input = Input { _, _ -> -1 }.apply { failStop = true }
            val failure = runCatching { VoiceRecorder.recordFrom(input, 5000L, VoiceStopSignal()) { testScheduler.currentTime } }
            assertTrue(failure.exceptionOrNull() is IOException)
            assertEquals(1, input.stops)
            assertEquals(1, input.releases)
        }

    private class Input(
        val answer: (ByteArray, Int) -> Int,
    ) : VoicePcmInput {
        var reads = 0
        var stops = 0
        var releases = 0
        var failStop = false

        override fun start() {}

        override fun read(
            buffer: ByteArray,
            size: Int,
        ): Int {
            reads++
            return answer(buffer, size)
        }

        override fun stop() {
            stops++
            if (failStop) error("stop failed")
        }

        override fun release() {
            releases++
        }
    }
}
