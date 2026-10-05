package com.scraft.glassreport.capture.asr

/** Join our fixed-format PCM WAV segments, dropping the pause and capping total recorded audio. */
internal fun joinRecordedVoice(
    prefix: RecordedVoice?,
    next: RecordedVoice,
    maxDurationMs: Long,
): RecordedVoice {
    val previousPcm = prefix?.wav?.drop(WAV_HEADER_BYTES)?.toByteArray() ?: ByteArray(0)
    val nextPcm = next.wav.drop(WAV_HEADER_BYTES).toByteArray()
    val limit = (VOICE_SAMPLE_RATE * 2L * maxDurationMs / 1000L).toInt()
    val combined = (previousPcm + nextPcm).take(limit).toByteArray()
    val aligned = combined.copyOf(combined.size - combined.size % 2)
    return RecordedVoice(
        wav = VoiceRecorder.toWav(aligned),
        durationMs = aligned.size * 1000L / (VOICE_SAMPLE_RATE * 2),
        peak = maxOf(prefix?.peak ?: 0, next.peak),
    )
}
