package com.scraft.glassreport.capture.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OfflinePcmWavTest {
    @Test
    fun normalizesSignedLittleEndianPcm() {
        assertArrayEquals(floatArrayOf(-1f, 0f, 32767f / 32768), OfflinePcmWav.samples(wav(-32768, 0, 32767)), 0f)
    }

    @Test
    fun acceptsEmptyRecording() {
        assertArrayEquals(floatArrayOf(), OfflinePcmWav.samples(wav()), 0f)
    }

    @Test
    fun rejectsTruncation() {
        val input = wav(10)
        assertThrows(IllegalArgumentException::class.java) { OfflinePcmWav.samples(input.copyOf(input.size - 1)) }
    }

    @Test
    fun rejectsUnsupportedRate() {
        val input = wav(10)
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 48000)
        assertThrows(IllegalArgumentException::class.java) { OfflinePcmWav.samples(input) }
    }

    @Test
    fun rejectsExcessivelyLongAudioBeforeModelInitialization() {
        assertThrows(IllegalArgumentException::class.java) { OfflinePcmWav.samples(wav(*IntArray(96001))) }
    }

    @Test
    fun skipsUnknownChunksAndPadding() {
        val original = wav(123)
        val input = ByteArray(original.size + 10)
        original.copyInto(input, 0, 0, 36)
        "JUNK".toByteArray().copyInto(input, 36)
        ByteBuffer
            .wrap(input)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(40, 1)
            .putInt(4, input.size - 8)
        original.copyInto(input, 46, 36)
        assertArrayEquals(floatArrayOf(123f / 32768), OfflinePcmWav.samples(input), 0f)
    }

    private fun wav(vararg values: Int): ByteArray =
        ByteBuffer
            .allocate(44 + values.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(capacity() - 8)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(16000)
                putInt(32000)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(values.size * 2)
                values.forEach { putShort(it.toShort()) }
            }.array()
}
