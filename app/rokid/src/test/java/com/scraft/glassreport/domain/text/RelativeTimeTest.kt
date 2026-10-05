package com.scraft.glassreport.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class RelativeTimeTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val now = RelativeTime.parseIso8601("2026-08-14T09:00:00Z")!!

    @Test
    fun parsesGraphTimestampWithMillisecondsAndZ() {
        assertEquals(
            RelativeTime.parseIso8601("2026-08-14T09:00:00Z"),
            RelativeTime.parseIso8601("2026-08-14T09:00:00.000Z"),
        )
    }

    @Test
    fun parsesOffsetNotationToo() {
        assertEquals(
            RelativeTime.parseIso8601("2026-08-14T09:00:00Z"),
            RelativeTime.parseIso8601("2026-08-14T18:00:00+09:00"),
        )
    }

    @Test
    fun brokenTimestampIsNullInsteadOfThrowing() {
        assertNull(RelativeTime.parseIso8601("2026/08/14 09:00"))
        assertNull(RelativeTime.parseIso8601(""))
        assertNull(RelativeTime.parseIso8601(null))
    }

    @Test
    fun underOneMinuteIsJustNow() {
        assertEquals("たった今", RelativeTime.format(now, now))
        assertEquals("たった今", RelativeTime.format(now - 59_000L, now))
    }

    @Test
    fun futureTimestampFromClockSkewIsAlsoJustNow() {
        assertEquals("たった今", RelativeTime.format(now + 120_000L, now))
    }

    @Test
    fun minutesHoursAndDays() {
        assertEquals("1分前", RelativeTime.format(now - 60_000L, now))
        assertEquals("3分前", RelativeTime.format(now - 3 * 60_000L, now))
        assertEquals("59分前", RelativeTime.format(now - 59 * 60_000L, now))
        assertEquals("1時間前", RelativeTime.format(now - 60 * 60_000L, now))
        assertEquals("2時間前", RelativeTime.format(now - 2 * 3_600_000L, now))
        assertEquals("23時間前", RelativeTime.format(now - 23 * 3_600_000L, now))
        assertEquals("1日前", RelativeTime.format(now - 24 * 3_600_000L, now))
        assertEquals("6日前", RelativeTime.format(now - 6 * 24 * 3_600_000L, now))
    }

    @Test
    fun olderThanAWeekFallsBackToTheDate() {
        val at = RelativeTime.parseIso8601("2026-08-01T23:30:00Z")!!

        // UTC 8/1 23:30 は東京では 8/2 08:30。表示は端末の時間帯に合わせる。
        assertEquals("8月2日", RelativeTime.format(at, now, tokyo))
        assertEquals("8月1日", RelativeTime.format(at, now, ZoneId.of("UTC")))
    }
}
