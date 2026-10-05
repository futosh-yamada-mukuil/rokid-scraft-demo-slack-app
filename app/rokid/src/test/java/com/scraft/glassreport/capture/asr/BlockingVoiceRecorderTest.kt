package com.scraft.glassreport.capture.asr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BlockingVoiceRecorderTest {
    @Test fun stopUnblocksReadAndReleasesExactlyOnce() =
        runBlocking {
            val input = BlockedInput()
            val stop = VoiceStopSignal()
            val task = async(Dispatchers.IO) { VoiceRecorder.recordWithStop(input, 5000L, stop) }
            withTimeout(2000L) { input.entered.await() }
            stop.stop()
            val result = withTimeout(2000L) { task.await() }
            assertEquals(44, result.wav.size)
            assertEquals(1, input.stops)
            assertEquals(1, input.releases)
        }

    @Test fun cancellationUnblocksReadAndReleasesExactlyOnce() =
        runBlocking {
            val input = BlockedInput()
            val task = async(Dispatchers.IO) { VoiceRecorder.recordWithStop(input, 5000L, VoiceStopSignal()) }
            withTimeout(2000L) { input.entered.await() }
            withTimeout(2000L) { task.cancelAndJoin() }
            assertEquals(1, input.stops)
            assertEquals(1, input.releases)
        }

    @Test fun deadlineUnblocksRead() =
        runBlocking {
            val input = BlockedInput()
            val task = async(Dispatchers.IO) { VoiceRecorder.recordWithStop(input, 5000L, VoiceStopSignal(), stopTimeoutMs = 50L) }
            withTimeout(2000L) { task.await() }
            assertEquals(1, input.stops)
            assertEquals(1, input.releases)
        }

    @Test fun stopBeforeWorkerStartsCannotStartMicrophoneAfterward() =
        runBlocking {
            val input = BlockedInput()
            val stop = VoiceStopSignal().apply { stop() }
            VoiceRecorder.recordWithStop(input, 5000L, stop)
            assertEquals(0, input.starts)
            assertEquals(1, input.stops)
            assertEquals(1, input.releases)
        }

    @Test fun shortSamplesStayPcm16LittleEndianNotFloat32() {
        val bytes = ByteArray(8)
        VoiceRecorder.pcm16ToLittleEndian(shortArrayOf(0, 32767, -32768, -1), 4, bytes)
        assertArrayEquals(byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1), bytes)
    }

    private class BlockedInput : VoicePcmInput {
        val entered = CompletableDeferred<Unit>()
        private val unblocked = CountDownLatch(1)
        var starts = 0
        var stops = 0
        var releases = 0

        override fun start() {
            starts++
        }

        override fun read(
            buffer: ByteArray,
            size: Int,
        ): Int {
            entered.complete(Unit)
            assertTrue("Native read must be unblocked by stop", unblocked.await(3, TimeUnit.SECONDS))
            return -3
        }

        override fun stop() {
            stops++
            unblocked.countDown()
        }

        override fun release() {
            releases++
        }
    }
}
