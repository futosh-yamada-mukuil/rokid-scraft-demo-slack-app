package com.scraft.glassreport.domain.model

import com.scraft.glassreport.capture.tts.SpeechLanguage

/**
 * Feed に 1 件ずつ出す投稿。**お客様仕様の表示項目（投稿者・メッセージ・経過時間）だけ**を持つ。
 *
 * 画像は本文に展開しない（単眼・低解像度のディスプレイでは読めないため）。
 * 代わりに件数だけを [attachmentCount] に持ち、「画像 1 件」のように添える。
 *
 * @param id Graph の `id`（新着判定に使う。同じ投稿を二度鳴らさないための鍵）。
 * @param author `from.user.displayName`。取れないときは「(不明な送信者)」。
 * @param text HTML を剥がしたプレーンテキスト（`domain.text.HtmlText`）。
 * @param createdAtMs `createdDateTime` を epoch ミリ秒にしたもの。解釈できなければ null。
 * @param attachmentCount 本文に埋まった画像＋添付ファイルの件数。
 */
data class FeedPost(
    val id: String,
    val author: String,
    val text: String,
    val createdAtMs: Long?,
    val attachmentCount: Int = 0,
    val language: SpeechLanguage = SpeechLanguage.JA,
    /** 本文内および添付に含まれる画像の件数。ロビーでは画像本体を表示しない。 */
    val imageCount: Int = 0,
    /** Graph の投稿者 ID とサインイン中の ID を照合できたときだけ確定する。 */
    val isOwn: Boolean? = null,
    val translation: TranslationState = TranslationState.NONE,
    /** False for raw Teams text: its source language is unknown until translation succeeds. */
    val canSpeak: Boolean = true,
) {
    val displayText: String get() =
        when (translation) {
            TranslationState.PENDING -> "[翻訳中]\n$text"
            TranslationState.FAILED -> "[翻訳できません・原文]\n$text"
            else -> text
        }
}

enum class TranslationState { NONE, PENDING, TRANSLATED, FAILED }
