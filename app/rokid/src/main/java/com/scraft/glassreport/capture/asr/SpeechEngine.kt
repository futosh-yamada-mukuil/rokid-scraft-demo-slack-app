package com.scraft.glassreport.capture.asr

enum class SpeechEngine(
    val code: String,
    val label: String,
) {
    GOOGLE("google", "Google STT"),
    OFFLINE("offline", "Offline STT"),
    ;

    companion object {
        fun fromCode(
            code: String,
            default: SpeechEngine = GOOGLE,
        ): SpeechEngine = entries.firstOrNull { it.code == code } ?: default
    }
}
