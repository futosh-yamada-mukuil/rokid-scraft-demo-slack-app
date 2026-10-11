package com.scraft.glassreport.domain.text

/**
 * 音声認識の結果を、Teams に投稿できる 1 つの文へ整える**純粋関数**。
 *
 * 認識エンジンは日本語・中国語でも単語や発話の区切りに半角空白を入れて返すことがある
 * （`エラー 発生` / `確認しました。 送ります`）。分かち書きしない言語では誤りなので取り除く。
 * 英語や韓国語の語間の空白は意味を持つため、そのまま残す。
 */
object TranscriptText {
    /**
     * 空白を挟まずに続けて書く文字。漢字・かなに加えて、Unicode のスクリプトが `Common` 扱いで
     * `\p{IsKatakana}` に含まれない長音記号 `ー` や和文の句読点・括弧も数える
     * （抜けていると `エラー 発生` や `しました。 次` の空白が残る）。
     */
    private const val UNSPACED = "\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー、。，．！？「」『』（）・"

    private val UNSPACED_GAP = Regex("(?<=[$UNSPACED])\\s+(?=[$UNSPACED])")

    /**
     * 同梱の認識モデルが現場の語を別の字に取り違える**既知の例**。足すのは実際の録音で確かめた誤りだけ
     * （推測で増やすと正しい語を壊す）。
     *
     * - `書真` → `写真` … 「こちらが現場の写真」が「現場の書真」になる（2026-10-04 の AVD 検証と Slack 投稿で確認）。
     *   `文書真偽` `清書真っ最中` のように別の語をまたぐ並びは、前後の字を見て対象から外す。
     */
    private val KNOWN_MISRECOGNITIONS = listOf(Regex("(?<![\\p{IsHan}])書真(?![\\p{IsHan}っ似ん])") to "写真")

    private val KANA = Regex("[\\p{IsHiragana}\\p{IsKatakana}]")

    /**
     * [KNOWN_MISRECOGNITIONS] を直す。かなを含む文（＝日本語）だけが対象で、
     * 同じ漢字の並びが意味を持ちうる中国語の文には手を付けない。
     */
    fun correct(text: String): String =
        if (!KANA.containsMatchIn(text)) {
            text
        } else {
            KNOWN_MISRECOGNITIONS.fold(text) { fixed, (pattern, replacement) -> pattern.replace(fixed, replacement) }
        }

    /** 同梱のオフライン認識が返した生の文字列を、投稿する文にする（空白の整形 → 既知の取り違えの修正）。 */
    fun fromOfflineRecognizer(raw: String): String = correct(normalize(raw))

    /** 前後の空白を落とし、分かち書きしない文字どうしの間の空白だけを消す。 */
    fun normalize(text: String): String = text.trim().replace(UNSPACED_GAP, "")

    /** 発話ごとに分かれて届いた認識結果を順に繋ぐ。英語・韓国語は空白で区切り、日本語・中国語は詰める。 */
    fun join(segments: List<String>): String = normalize(segments.map(String::trim).filter(String::isNotEmpty).joinToString(" "))
}
