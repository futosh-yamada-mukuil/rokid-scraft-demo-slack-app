package com.scraft.glassreport.capture.asr

/** Recognizes a local 16 kHz mono PCM16 WAV; recording ownership stays outside the engine. */
interface WavTranscriber {
    val available: Boolean
    val unavailableReason: String get() = "音声認識が利用できません"

    suspend fun transcribe(wav: ByteArray): Result<String>

    fun close()
}
