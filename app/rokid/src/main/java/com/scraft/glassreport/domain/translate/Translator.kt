package com.scraft.glassreport.domain.translate

import com.scraft.glassreport.capture.tts.SpeechLanguage

/**
 * 翻訳の抽象。実装は `net.translate.CloudTranslationClient`。
 *
 * 画面（ViewModel）はこのインターフェースにだけ依存させ、テストではフェイクを注入する。
 * 失敗は例外ではなく [Result] に畳んで返す（HUD に理由を出すだけで済むように）。
 */
interface Translator {
    /** Detect the source language automatically, returning only the target text. */
    suspend fun translate(
        text: String,
        targetLanguage: SpeechLanguage,
    ): Result<String> =
        if (targetLanguage == SpeechLanguage.EN) toEnglish(text) else Result.failure(UnsupportedOperationException("翻訳言語が未対応"))

    /** 日本語 [text] を英語にする。訳文のみが返る。 */
    suspend fun toEnglish(text: String): Result<String>
}
