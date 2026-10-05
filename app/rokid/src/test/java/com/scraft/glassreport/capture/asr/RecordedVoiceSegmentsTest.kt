package com.scraft.glassreport.capture.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedVoiceSegmentsTest {
    @Test fun segmentsKeepOrderAndPeakWithoutDuplicatingHeaders() {
        val prefix = segment(1000L, 1, 700)
        val next = segment(2000L, 2, 900)
        val joined = joinRecordedVoice(prefix, next, 5000L)
        assertEquals(3000L, joined.durationMs)
        assertEquals(44 + 96000, joined.wav.size)
        assertEquals(900, joined.peak)
        assertTrue(joined.wav.copyOfRange(44, 32044).all { it == 1.toByte() })
        assertTrue(joined.wav.copyOfRange(32044, joined.wav.size).all { it == 2.toByte() })
    }

    @Test fun repeatedResumeNeverExceedsFiveSeconds() {
        var joined: RecordedVoice? = null
        repeat(8) { joined = joinRecordedVoice(joined, segment(1000L, 1, 900), 5000L) }
        assertEquals(5000L, joined!!.durationMs)
        assertEquals(160044, joined!!.wav.size)
    }

    private fun segment(
        ms: Long,
        value: Int,
        peak: Int,
    ) = RecordedVoice(
        VoiceRecorder.toWav(
            ByteArray((ms * 32).toInt()) {
                value.toByte()
            },
        ),
        ms,
        peak,
    )
}
