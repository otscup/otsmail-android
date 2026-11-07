package com.otscup.otsmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 定时发送的时间计算与调度去重。
 * 覆盖：跨天、时区偏移、夏令时跳变/重叠、换时区重锚、选到过去、去重与排序。
 */
class ScheduledSendTimeTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val newYork = ZoneId.of("America/New_York")

    private fun atLocal(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun mail(
        id: String = "a",
        key: String = "k",
        at: Long = 1_000L,
        status: String = ScheduledMail.STATUS_PENDING,
    ) = ScheduledMail(
        id = id,
        key = key,
        accountId = 1,
        to = listOf("x@y.com"),
        subject = "主题",
        text = "正文",
        html = "<p>正文</p>",
        atMillis = at,
        atLocal = "2026-11-08T08:00",
        status = status,
    )

    @Test
    fun atLocalRespectsZoneOffset() {
        val date = LocalDate.of(2026, 11, 8)
        val sh = ScheduledSendTime.atLocal(shanghai, date, 8, 0)
        val tk = ScheduledSendTime.atLocal(tokyo, date, 8, 0)
        // 上海 08:00 = 00:00Z；东京早一小时 = 前一天 23:00Z
        assertEquals(Instant.parse("2026-11-08T00:00:00Z").toEpochMilli(), sh)
        assertEquals(60 * 60 * 1000L, sh - tk)
    }

    @Test
    fun nextAtUsesTodayWhenTimeNotYetPassed() {
        val now = atLocal(shanghai, 2026, 11, 7, 7, 0)
        assertEquals(atLocal(shanghai, 2026, 11, 7, 8, 0), ScheduledSendTime.nextAt(now, shanghai, 8, 0))
    }

    @Test
    fun nextAtCrossesDayWhenTimeAlreadyPassed() {
        val now = atLocal(shanghai, 2026, 11, 7, 20, 0)
        assertEquals(atLocal(shanghai, 2026, 11, 8, 8, 0), ScheduledSendTime.nextAt(now, shanghai, 8, 0))
        // 跨月/跨年由 java.time 处理
        val newYear = atLocal(shanghai, 2026, 12, 31, 23, 59)
        assertEquals(atLocal(shanghai, 2027, 1, 1, 0, 30), ScheduledSendTime.nextAt(newYear, shanghai, 0, 30))
    }

    @Test
    fun nextMorningAndEveningBoundaries() {
        val beforeEight = atLocal(shanghai, 2026, 11, 7, 7, 59)
        val afterEight = atLocal(shanghai, 2026, 11, 7, 8, 1)
        assertEquals(atLocal(shanghai, 2026, 11, 7, 8, 0), ScheduledSendTime.nextMorning(beforeEight, shanghai))
        assertEquals(atLocal(shanghai, 2026, 11, 8, 8, 0), ScheduledSendTime.nextMorning(afterEight, shanghai))

        val beforeEvening = atLocal(shanghai, 2026, 11, 7, 17, 0)
        val afterEvening = atLocal(shanghai, 2026, 11, 7, 19, 0)
        assertEquals(atLocal(shanghai, 2026, 11, 7, 18, 30), ScheduledSendTime.nextEvening(beforeEvening, shanghai))
        assertEquals(atLocal(shanghai, 2026, 11, 8, 18, 30), ScheduledSendTime.nextEvening(afterEvening, shanghai))
    }

    @Test
    fun dstGapShiftsForward() {
        // 2024-03-10 02:00→03:00 纽约跳过一小时，02:30 不存在，按 atZone 规则顺延到 03:30
        val gap = atLocal(newYork, 2024, 3, 10, 2, 30)
        val zoned = Instant.ofEpochMilli(gap).atZone(newYork)
        assertEquals(LocalTime.of(3, 30), zoned.toLocalTime())
        assertEquals(ZoneOffset.ofHours(-4), zoned.offset)
    }

    @Test
    fun dstOverlapUsesEarlierOccurrence() {
        // 2024-11-03 01:30 出现两次，java.time 取较早的一次（EDT, -04:00）
        val overlap = atLocal(newYork, 2024, 11, 3, 1, 30)
        val zoned = Instant.ofEpochMilli(overlap).atZone(newYork)
        assertEquals(LocalTime.of(1, 30), zoned.toLocalTime())
        assertEquals(ZoneOffset.ofHours(-4), zoned.offset)
    }

    @Test
    fun reanchorKeepsWallClockAcrossZones() {
        val now = atLocal(shanghai, 2026, 11, 7, 12, 0)
        val wall = "2026-11-08T08:00"
        val sh = ScheduledSendTime.reanchor(wall, shanghai, now)!!
        val tk = ScheduledSendTime.reanchor(wall, tokyo, now)!!
        // 同样是当地 8 点，东京比上海早一小时（UTC 更早）
        assertEquals(60 * 60 * 1000L, sh - tk)
        assertEquals(LocalTime.of(8, 0), Instant.ofEpochMilli(tk).atZone(tokyo).toLocalTime())
        assertEquals(wall, ScheduledSendTime.localStamp(tk, tokyo))
    }

    @Test
    fun reanchorNeverSchedulesInPast() {
        val now = atLocal(shanghai, 2026, 11, 7, 23, 30)
        val past = ScheduledSendTime.reanchor("2026-11-07T08:00", shanghai, now)!!
        assertEquals(now + 60_000L, past)
        assertTrue(past > now)
    }

    @Test
    fun atLeastOneMinuteAheadNeverPast() {
        val now = 1_000_000L
        assertEquals(now + 60_000L, ScheduledSendTime.atLeastOneMinuteAhead(now - 5_000L, now))
        assertEquals(now + 120_000L, ScheduledSendTime.atLeastOneMinuteAhead(now + 120_000L, now))
    }

    @Test
    fun dedupeKeyIgnoresAddressOrderAndCase() {
        val a = ScheduledSendTime.dedupeKey(1, listOf("A@x.com", "b@x.com"), " Hi ", "<p>x</p>")
        val b = ScheduledSendTime.dedupeKey(1, listOf("b@X.COM ", "a@x.com"), "Hi", "<p>x</p>")
        assertEquals(a, b)
        val otherAccount = ScheduledSendTime.dedupeKey(2, listOf("A@x.com", "b@x.com"), "Hi", "<p>x</p>")
        assertTrue(a != otherAccount)
    }

    @Test
    fun upsertReplacesPendingSameKeyAndKeepsId() {
        val first = mail(id = "old", at = 3_000L)
        val second = first.copy(id = "new", atMillis = 5_000L)
        val merged = ScheduledSendTime.upsert(listOf(first), second)
        assertEquals(1, merged.size)
        // 复用旧 id：闹钟 PendingIntent 不变，不会残留两个任务
        assertEquals("old", merged[0].id)
        assertEquals(5_000L, merged[0].atMillis)
        assertEquals(ScheduledMail.STATUS_PENDING, merged[0].status)
    }

    @Test
    fun upsertDoesNotCountFailedAsDuplicate() {
        val failed = mail(id = "f", status = ScheduledMail.STATUS_FAILED)
        val second = mail(id = "new", at = 2_000L)
        val merged = ScheduledSendTime.upsert(listOf(failed), second)
        assertEquals(2, merged.size)
    }

    @Test
    fun upsertKeepsListSortedByTime() {
        val later = mail(id = "late", key = "k2", at = 9_000L)
        val earlier = mail(id = "early", key = "k3", at = 1_000L)
        val merged = ScheduledSendTime.upsert(listOf(later), earlier)
        assertEquals(listOf(1_000L, 9_000L), merged.map { it.atMillis })
    }
}
