package com.scraft.glassreport.capture.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 読み上げる言語。声の割り当ては実装（[GoogleTtsSpeaker]）が持つ。
 * 設定値・翻訳先・TTS のロケールを同じカタログで管理する。
 */
enum class SpeechLanguage(
    val code: String,
    val label: String,
    val ttsLocale: String,
    val translationName: String,
) {
    JA("ja", "日本語", "ja-JP", "Japanese"),
    EN("en", "英語", "en-US", "English"),
    ZH_CN("zh-CN", "中国語（簡体字）", "cmn-CN", "Simplified Chinese"),
    ZH_TW("zh-TW", "中国語（繁体字）", "cmn-TW", "Traditional Chinese"),
    KO("ko", "韓国語", "ko-KR", "Korean"),
    ES("es", "スペイン語", "es-ES", "Spanish"),
    FR("fr", "フランス語", "fr-FR", "French"),
    DE("de", "ドイツ語", "de-DE", "German"),
    IT("it", "イタリア語", "it-IT", "Italian"),
    PT("pt", "ポルトガル語", "pt-BR", "Brazilian Portuguese"),
    RU("ru", "ロシア語", "ru-RU", "Russian"),
    AR("ar", "アラビア語", "ar-XA", "Arabic"),
    HI("hi", "ヒンディー語", "hi-IN", "Hindi"),
    NL("nl", "オランダ語", "nl-NL", "Dutch"),
    ID("id", "インドネシア語", "id-ID", "Indonesian"),
    VI("vi", "ベトナム語", "vi-VN", "Vietnamese"),
    TH("th", "タイ語", "th-TH", "Thai"),
    TR("tr", "トルコ語", "tr-TR", "Turkish"),
    PL("pl", "ポーランド語", "pl-PL", "Polish"),
    UK("uk", "ウクライナ語", "uk-UA", "Ukrainian"),
    ;

    companion object {
        /** Product settings expose four languages; retain other enum values for received legacy data. */
        val selectable: List<SpeechLanguage> = listOf(JA, ZH_CN, EN, KO)

        /** Retired or unknown saved settings migrate to Japanese, never silently switch Chinese scripts. */
        fun forSettings(code: String): SpeechLanguage = fromCodeOrNull(code)?.takeIf { it in selectable } ?: JA

        fun fromCode(code: String): SpeechLanguage = fromCodeOrNull(code) ?: JA

        fun fromCodeOrNull(code: String): SpeechLanguage? {
            val parts =
                code
                    .trim()
                    .lowercase()
                    .replace('_', '-')
                    .split('-')
            if (parts.first() in setOf("zh", "cmn")) {
                return when {
                    "hant" in parts -> ZH_TW
                    "hans" in parts -> ZH_CN
                    parts.any { it in setOf("tw", "hk", "mo") } -> ZH_TW
                    else -> ZH_CN
                }
            }
            return entries.firstOrNull { it.code == parts.first() }
        }
    }
}

/** 読み上げの進み具合。 */
enum class SpeakPhase { IDLE, SYNTHESIZING, PLAYING, FAILED }

/** 読み上げの状態。HUD はこれを文字にするだけでよい。 */
data class SpeakStatus(
    val phase: SpeakPhase = SpeakPhase.IDLE,
    val language: SpeechLanguage = SpeechLanguage.JA,
    /** 失敗理由（HUD に出せる短い日本語）。失敗以外は空。 */
    val message: String = "",
) {
    /** 合成中または再生中。 */
    val speaking: Boolean get() = phase == SpeakPhase.SYNTHESIZING || phase == SpeakPhase.PLAYING
}

/**
 * 読み上げ（TTS）の抽象。実装を隔離し、差し替え・テストを可能にする。
 *
 * 実機（中国国内版 Rokid Glasses）には `TextToSpeech` を実装するサービスが 1 つも無いため、
 * 実装は Google Cloud TTS を叩く [GoogleTtsSpeaker]。呼び出しは投げっぱなしで、
 * 進み具合と失敗は [status] を購読して受け取る（例外は投げない）。
 */
interface Speaker {
    val status: StateFlow<SpeakStatus>

    /** [text] を [language] で読み上げる。再生中なら止めて差し替える。 */
    fun speak(
        text: String,
        language: SpeechLanguage = SpeechLanguage.JA,
    )

    /** 合成・再生を打ち切って待機に戻す。 */
    fun stop()

    fun close()
}

/** 何もしない実装（テストや TTS 無効時用）。 */
object NoopSpeaker : Speaker {
    override val status: StateFlow<SpeakStatus> = MutableStateFlow(SpeakStatus()).asStateFlow()

    override fun speak(
        text: String,
        language: SpeechLanguage,
    ) {}

    override fun stop() {}

    override fun close() {}
}
