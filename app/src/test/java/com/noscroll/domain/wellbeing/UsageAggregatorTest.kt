package com.noscroll.domain.wellbeing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Usage-event streams are messy in specific, well-known ways, and each of those ways silently
 * corrupts a daily total if you trust the stream. Every case below is one of them.
 */
class UsageAggregatorTest {

    private val zone = ZoneId.of("Europe/London")
    private val aggregator = UsageAggregator(zone)
    private val day = LocalDate.of(2026, 8, 11)

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun fg(pkg: String, millis: Long) = UsageEventRecord(pkg, UsageEventType.FOREGROUND, millis)
    private fun bg(pkg: String, millis: Long) = UsageEventRecord(pkg, UsageEventType.BACKGROUND, millis)

    private val windowStart = at(0, 0, day.minusDays(2))
    private val windowEnd = at(23, 59)

    @Test
    fun `a simple foreground and background pair is measured`() {
        val result = aggregator.aggregate(
            listOf(fg("com.example.app", at(10)), bg("com.example.app", at(10, 30))),
            windowStart,
            windowEnd,
        )
        assertEquals(30 * 60_000L, result.packageTotal(day, "com.example.app"))
    }

    @Test
    fun `a missing background event is closed by the next foreground`() {
        // The commonest dropped pair. Without this the first app runs forever.
        val result = aggregator.aggregate(
            listOf(fg("app.a", at(10)), fg("app.b", at(10, 20)), bg("app.b", at(10, 50))),
            windowStart,
            windowEnd,
        )
        assertEquals(20 * 60_000L, result.packageTotal(day, "app.a"))
        assertEquals(30 * 60_000L, result.packageTotal(day, "app.b"))
    }

    @Test
    fun `multiple activities of the same package are not double counted`() {
        val result = aggregator.aggregate(
            listOf(
                fg("app.a", at(10)),
                fg("app.a", at(10, 5)),  // second activity of the same app
                fg("app.a", at(10, 10)),
                bg("app.a", at(10, 30)),
            ),
            windowStart,
            windowEnd,
        )
        assertEquals("counted once, not three times", 30 * 60_000L, result.packageTotal(day, "app.a"))
    }

    @Test
    fun `screen off stops the clock`() {
        val result = aggregator.aggregate(
            listOf(
                fg("app.a", at(10)),
                UsageEventRecord("", UsageEventType.SCREEN_NON_INTERACTIVE, at(10, 15)),
                UsageEventRecord("", UsageEventType.SCREEN_INTERACTIVE, at(11)),
                fg("app.a", at(11)),
                bg("app.a", at(11, 10)),
            ),
            windowStart,
            windowEnd,
        )
        assertEquals("the 45 locked minutes are not counted", 25 * 60_000L, result.packageTotal(day, "app.a"))
    }

    @Test
    fun `shutdown closes an open interval`() {
        val result = aggregator.aggregate(
            listOf(
                fg("app.a", at(10)),
                UsageEventRecord("", UsageEventType.SHUTDOWN, at(10, 20)),
            ),
            windowStart,
            windowEnd,
        )
        assertEquals(20 * 60_000L, result.packageTotal(day, "app.a"))
    }

    @Test
    fun `an interval spanning midnight is split across both days`() {
        val result = aggregator.aggregate(
            listOf(
                fg("app.a", at(23, 30, day.minusDays(1))),
                bg("app.a", at(0, 30, day)),
            ),
            windowStart,
            windowEnd,
        )
        assertEquals(30 * 60_000L, result.packageTotal(day.minusDays(1), "app.a"))
        assertEquals(30 * 60_000L, result.packageTotal(day, "app.a"))
    }

    @Test
    fun `an unclosed interval is counted only up to the end of the window`() {
        val end = at(12)
        val result = aggregator.aggregate(listOf(fg("app.a", at(10))), windowStart, end)
        assertEquals("no time is invented past 'now'", 2 * 60 * 60_000L, result.packageTotal(day, "app.a"))
    }

    @Test
    fun `a background event with no matching foreground is ignored`() {
        // We have no defensible start time, so attributing anything would be fabricating data.
        val result = aggregator.aggregate(listOf(bg("app.ghost", at(10))), windowStart, windowEnd)
        assertEquals(0L, result.packageTotal(day, "app.ghost"))
    }

    @Test
    fun `events outside the window are ignored`() {
        val result = aggregator.aggregate(
            listOf(fg("app.a", at(10, 0, day.minusDays(30))), bg("app.a", at(11, 0, day.minusDays(30)))),
            windowStart,
            windowEnd,
        )
        assertTrue(result.perDayPerPackage.isEmpty())
    }

    @Test
    fun `out of order events are sorted before aggregation`() {
        val result = aggregator.aggregate(
            listOf(bg("app.a", at(10, 30)), fg("app.a", at(10))),
            windowStart,
            windowEnd,
        )
        assertEquals(30 * 60_000L, result.packageTotal(day, "app.a"))
    }

    @Test
    fun `hourly buckets are filled correctly`() {
        val result = aggregator.aggregate(
            listOf(fg("app.a", at(10, 30)), bg("app.a", at(12, 15))),
            windowStart,
            windowEnd,
        )
        val hourly = result.perDayHourly.getValue(day)
        assertEquals(30 * 60_000L, hourly[10])
        assertEquals(60 * 60_000L, hourly[11])
        assertEquals(15 * 60_000L, hourly[12])
    }

    @Test
    fun `totalUpTo only counts hours before the cutoff`() {
        val result = aggregator.aggregate(
            listOf(fg("app.a", at(8)), bg("app.a", at(20))),
            windowStart,
            windowEnd,
        )
        // Twelve hours of usage, but only up to 10:00 counts here.
        assertEquals(2 * 60 * 60_000L, result.totalUpTo(day, cutoffHour = 10, cutoffMinute = 0))
    }

    @Test
    fun `a dst transition does not corrupt the daily split`() {
        // In Europe/London, 2026-03-29 loses an hour at 01:00. An interval across it must still be
        // attributed to the right local day.
        val dstDay = LocalDate.of(2026, 3, 29)
        val start = LocalDateTime.of(dstDay.minusDays(1), java.time.LocalTime.of(23, 30))
            .atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(dstDay, java.time.LocalTime.of(2, 30))
            .atZone(zone).toInstant().toEpochMilli()

        val result = aggregator.aggregate(
            listOf(fg("app.a", start), bg("app.a", end)),
            start - 1000,
            end + 1000,
        )
        assertTrue(result.perDayPerPackage.containsKey(dstDay.minusDays(1)))
        assertTrue(result.perDayPerPackage.containsKey(dstDay))
        val total = result.totalFor(dstDay.minusDays(1)) + result.totalFor(dstDay)
        assertEquals("no time is created or lost across the transition", end - start, total)
    }

    @Test
    fun `top apps are ranked by duration`() {
        val result = aggregator.aggregate(
            listOf(
                fg("app.small", at(9)), bg("app.small", at(9, 10)),
                fg("app.big", at(10)), bg("app.big", at(11)),
            ),
            windowStart,
            windowEnd,
        )
        val top = result.topApps(day)
        assertEquals("app.big", top.first().first)
        assertEquals(2, top.size)
    }
}
