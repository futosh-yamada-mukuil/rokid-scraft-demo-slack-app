package com.scraft.glassreport.net

/**
 * 応答 JSON から必要なスカラー値だけを取り出す最小ユーティリティ。
 *
 * 叩く先（`login.microsoftonline.com` / Microsoft Graph / AmiVoice / Gemini）はどれも応答の形が
 * 決まっていて、こちらが使うのは数個のキーだけ。JSON ライブラリを1つ増やすより読める量が少ないので
 * 正規表現で済ませる。`org.json` はローカル JVM テストでスタブ（`returnDefaultValues`）に化けるため使わない。
 *
 * 制約: **同名キーは最初の1つ**を返す（Graph の応答はトップレベルのキーが先に来る）。
 * ネストした値を取りたい場合は呼び出し側で切り出してから渡すこと。
 */
internal object JsonLite {
    /** `"key": "値"` の値を取り出し、エスケープを戻して返す。 */
    fun string(
        json: String,
        key: String,
    ): String? {
        // 値の中の \" で終端を誤認しないよう、エスケープ列を1文字として読む。
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(json) ?: return null
        return unescape(match.groupValues[1])
    }

    /** `"key": 12` の値を取り出す。小数・指数は使わない（`expires_in` / `interval` のみが対象）。 */
    fun number(
        json: String,
        key: String,
    ): Long? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()

    /** JSON 文字列リテラルの中身として安全な形に変換する（本文 HTML を手組みするため必要）。 */
    fun escape(value: String): String =
        buildString(value.length + 16) {
            for (c in value) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
        }

    private fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i == raw.lastIndex) {
                out.append(c)
                i++
                continue
            }
            when (val esc = raw[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                    hex.toIntOrNull(16)?.let { out.append(it.toChar()) } ?: out.append(hex)
                    i += 4
                }
                else -> out.append(esc) // \" \\ \/ はその文字自身
            }
            i += 2
        }
        return out.toString()
    }
}
