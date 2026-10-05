package com.scraft.glassreport.domain.text

/**
 * Microsoft Graph が返す投稿本文（`body.contentType = "html"`）を、
 * グラスに出せるプレーンテキストへ落とす**純粋関数**。
 *
 * 実データは `<div>これはテストです</div><div><img src="…/hostedContents/…/$value"></div>` のような形で、
 * 1 投稿が複数の `<div>` に分かれる。タグを単純に消すと行が繋がって読めなくなるため、
 * **ブロック要素は改行に置き換えてから**残りのタグを消す。
 *
 * 実体参照（`&amp;` `&lt;` `&#39;` `&#x27;` `&nbsp;`）は元の文字に戻す。
 * 送付された `read_Teams_Message.py` の `_strip_html`（`re.sub` + `html.unescape`）が元。
 */
object HtmlText {
    /** 改行として扱うブロック要素（開始・終了タグとも）。`<br>` は単独タグ。 */
    private val BLOCK_TAG = Regex("</?\\s*(div|p|br|li|tr|h[1-6]|blockquote)\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** 残りのタグ（`<img …>` `<span>` `<attachment …>` など）。 */
    private val ANY_TAG = Regex("<[^>]*>")

    /** 画像の数え上げ用。本文には展開せず件数だけ出す（単眼・低解像度では画像が読めないため）。 */
    private val IMG_TAG = Regex("<\\s*img\\b", RegexOption.IGNORE_CASE)

    /** 実体参照。名前つき（`&amp;`）と数値（`&#39;` / `&#x27;`）の両方。 */
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

    /** 名前つき実体参照のうち Teams の本文に実際に出るもの。未知の名前はそのまま残す。 */
    private val NAMED_ENTITIES =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "hellip" to "…",
            "mdash" to "—",
            "ndash" to "–",
            "yen" to "¥",
        )

    /** 行内で潰す空白（半角・タブ・全角・NBSP・ゼロ幅）。 */
    private val INLINE_SPACES = Regex("[ \\t\\u00A0\\u3000\\u200B]+")

    /**
     * HTML 本文をプレーンテキストにする。
     *
     * - ブロック要素 → 改行、それ以外のタグ → 削除
     * - 実体参照を復元（**タグを消した後**に行う。本文中の `&lt;b&gt;` をタグと誤認しないため）
     * - 行ごとに前後の空白を落とし、空行は捨てる
     */
    fun toPlainText(html: String?): String {
        if (html.isNullOrEmpty()) return ""
        val stripped =
            html
                .replace(BLOCK_TAG, "\n")
                .replace(ANY_TAG, "")
        return unescapeEntities(stripped)
            .lineSequence()
            .map { it.replace(INLINE_SPACES, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** 本文に埋まっている画像の件数（`hostedContents` への `<img>` 参照）。 */
    fun imageCount(html: String?): Int = if (html.isNullOrEmpty()) 0 else IMG_TAG.findAll(html).count()

    /** 実体参照を元の文字へ。解釈できないものは書かれたまま残す（消して読めなくするより良い）。 */
    fun unescapeEntities(text: String): String {
        if (!text.contains('&')) return text
        return ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.drop(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value
                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.let(::codePointToString) ?: match.value
                else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
            }
        }
    }

    /** Unicode コードポイントを文字列へ。範囲外（壊れた入力）は空にする。 */
    private fun codePointToString(codePoint: Int): String? = if (codePoint in 1..0x10FFFF) String(Character.toChars(codePoint)) else null
}
