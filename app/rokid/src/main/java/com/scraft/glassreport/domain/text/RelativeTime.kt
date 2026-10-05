package com.scraft.glassreport.domain.text

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * `createdDateTime`（ISO 8601 / UTC）から「たった今 / 3分前 / 2時間前」を作る**純粋関数**。
 *
 * Feed が出す時刻表示はこれだけ（お客様仕様：表示項目は投稿者・メッセージ・経過時間のみ）。
 * 端末の時計がずれて未来の時刻が来ることがあるので、その場合は「たった今」に丸める。
 */
object RelativeTime {
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    /** これより古い投稿は経過時間ではなく日付（`8月14日`）で出す。 */
    private const val DATE_THRESHOLD_MS = 7 * DAY_MS

    /**
     * `2026-08-14T09:10:11.123Z` のような文字列を epoch ミリ秒にする。
     * 解釈できなければ null（時刻表示だけを諦め、投稿自体は出す）。
     */
    fun parseIso8601(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            // Graph は `Z` を付けて返すが、オフセット表記（+09:00）でも読めるようにしておく。
            OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                Instant.parse(text.trim()).toEpochMilli()
            } catch (ignored: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * [atMs] が [nowMs] からどれだけ前かを短い日本語にする。
     *
     * 1分未満=「たった今」/ 1時間未満=「N分前」/ 1日未満=「N時間前」/ 7日未満=「N日前」/
     * それ以上=「M月D日」（[zone] は表示に使う時間帯。既定は端末のもの）。
     */
    fun format(
        atMs: Long,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val elapsed = nowMs - atMs
        return when {
            // 端末の時計ずれで未来になっている場合も含めて「たった今」に寄せる。
            elapsed < MINUTE_MS -> "たった今"
            elapsed < HOUR_MS -> "${elapsed / MINUTE_MS}分前"
            elapsed < DAY_MS -> "${elapsed / HOUR_MS}時間前"
            elapsed < DATE_THRESHOLD_MS -> "${elapsed / DAY_MS}日前"
            else -> {
                val date = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
                "${date.monthValue}月${date.dayOfMonth}日"
            }
        }
    }
}
