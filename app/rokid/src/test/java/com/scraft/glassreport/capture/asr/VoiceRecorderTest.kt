package com.scraft.glassreport.capture.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV ヘッダの組み立ては純粋関数なので JVM 上で固める。
 * **AmiVoice は 16kHz / mono / 16bit PCM 以外だと空文字を返す**ため、ここが崩れると無言で壊れる。
 */
class VoiceRecorderTest {
    private val pcm = ByteArray(320) { (it % 128).toByte() }

    private fun wav(pcm: ByteArray = this.pcm) = VoiceRecorder.toWav(pcm)

    private fun ByteArray.ascii(
        offset: Int,
        length: Int,
    ) = String(this, offset, length, Charsets.US_ASCII)

    private fun ByteArray.int(offset: Int) = ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun ByteArray.short(offset: Int) =
        ByteBuffer
            .wrap(this, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt()

    @Test
    fun header_isRiffWaveWithPcmFormatChunk() {
        val bytes = wav()

        assertEquals("RIFF", bytes.ascii(0, 4))
        assertEquals("WAVE", bytes.ascii(8, 4))
        assertEquals("fmt ", bytes.ascii(12, 4))
        assertEquals("data", bytes.ascii(36, 4))
        assertEquals(16, bytes.int(16)) // fmt チャンク長
        assertEquals(1, bytes.short(20)) // 1 = リニア PCM
    }

    @Test
    fun header_declaresSixteenKilohertzMonoPcm16() {
        val bytes = wav()

        assertEquals(VOICE_CHANNELS, bytes.short(22))
        assertEquals(VOICE_SAMPLE_RATE, bytes.int(24))
        assertEquals(VOICE_SAMPLE_RATE * 2, bytes.int(28)) // バイト毎秒 = 16000 * 1ch * 2byte
        assertEquals(2, bytes.short(32)) // ブロック整列
        assertEquals(VOICE_BITS_PER_SAMPLE, bytes.short(34))
    }

    @Test
    fun sizes_countTheSamplePayload() {
        val bytes = wav()

        assertEquals(WAV_HEADER_BYTES + pcm.size, bytes.size)
        assertEquals(WAV_HEADER_BYTES - 8 + pcm.size, bytes.int(4)) // RIFF チャンクサイズ
        assertEquals(pcm.size, bytes.int(40)) // data チャンクサイズ
    }

    @Test
    fun payload_isAppendedUnchanged() {
        val bytes = wav()

        assertTrue(pcm.contentEquals(bytes.copyOfRange(WAV_HEADER_BYTES, bytes.size)))
    }

    @Test
    fun emptyPcm_stillProducesValidHeader() {
        val bytes = wav(ByteArray(0))

        assertEquals(WAV_HEADER_BYTES, bytes.size)
        assertEquals(0, bytes.int(40))
    }

    @Test
    fun silence_isJudgedByPeakAmplitude() {
        assertTrue(RecordedVoice(ByteArray(0), 3_000L, peak = 12).silent)
        assertFalse(RecordedVoice(ByteArray(0), 3_000L, peak = 9_000).silent)
    }

    @Test
    fun commentDuration_isThreeSeconds() {
        assertEquals(3_000L, VOICE_DURATION_MS)
    }
}
