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

    /** 数字。和文の字と隣り合うときだけ詰める（`50 円` → `50円`）。英語の `50 pieces` や韓国語の `50 원` は変えない。 */
    private const val DIGITS = "0-9０-９"

    private val UNSPACED_GAP = Regex("(?<=[$UNSPACED])\\s+(?=[$UNSPACED$DIGITS])|(?<=[$DIGITS])\\s+(?=[$UNSPACED])")

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

    /** 句読点・記号・空白。これらを除いて残るものが文の中身。 */
    private val NOT_CONTENT = Regex("[\\p{P}\\p{S}\\s]+")

    /**
     * それだけでは何も伝えない出力。雑音だけの録音に実モデルが返したもの（`I` `我` `The`）と、
     * 同じ種類の冠詞・一人称・言いよどみ。`好` `是` `네` `OK` のような短い返事は入れない。
     */
    private val CONTENT_FREE =
        setOf(
            "i",
            "a",
            "an",
            "the",
            "uh",
            "um",
            "oh",
            "ah",
            "eh",
            "hm",
            "hmm",
            "mm",
            "我",
            "嗯",
            "呃",
            "啊",
            "哦",
            "あ",
            "え",
            "ん",
            "えー",
            "あー",
            "어",
            "음",
            "아",
        )

    /**
     * 文字起こしに伝える中身があるか。
     *
     * 同梱モデルは、声の入っていない録音（雑音だけ）にも `.` `I.` `我.` `The.` のような短い文字列を返す
     * （2026-10-11、AVD上の実モデルで雑音のみ10件すべてと、スピーカー越しの小さい音で確認）。
     * Rokid は環境音だけで無音判定を超えることがあり、そのままだと意味のないコメントが投稿される。
     * 句読点だけの出力と [CONTENT_FREE] だけの出力は「聞き取れなかった」として扱う。
     */
    fun meaningful(text: String): Boolean {
        val content = text.replace(NOT_CONTENT, "")
        return content.isNotEmpty() && content.lowercase() !in CONTENT_FREE
    }

    /**
     * 同梱のオフライン認識が返した生の文字列を、投稿する文にする
     * （空白の整形 → 既知の取り違えの修正）。中身が無ければ空文字（＝聞き取れなかった）。
     */
    fun fromOfflineRecognizer(raw: String): String = correct(normalize(raw)).takeIf(::meaningful).orEmpty()

    /** 前後の空白を落とし、分かち書きしない文字どうしの間の空白だけを消す。 */
    fun normalize(text: String): String = text.trim().replace(UNSPACED_GAP, "")

    /** 発話ごとに分かれて届いた認識結果を順に繋ぐ。英語・韓国語は空白で区切り、日本語・中国語は詰める。 */
    fun join(segments: List<String>): String = normalize(segments.map(String::trim).filter(String::isNotEmpty).joinToString(" "))
}
