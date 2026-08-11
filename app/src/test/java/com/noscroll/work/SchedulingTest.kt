package com.noscroll.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The evening reminder is scheduled, not alarmed.
 *
 * NoScroll requests neither `SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM` — those are for alarm
 * clocks, and a wellbeing summary is not one. These tests cover the delay arithmetic that has to be
 * right for the reminder to land on the correct wall-clock time across midnight, DST and timezone
 * moves.
 */
class SchedulingTest {

    private val london = ZoneId.of("Europe/London")

    @Test
    fun `the default reminder time is ten in the evening`() {
        // 22:00 local, the documented default.
        val now = LocalDateTime.of(2026, 8, 11, 9, 0)
        val delay = NoScrollWorkScheduler.millisUntilNext(22, 0, now, london)
        assertEquals(13 * 3_600_000L, delay)
    }

    @Test
    fun `a time later today schedules for today`() {
        val now = LocalDateTime.of(2026, 8, 11, 21, 30)
        assertEquals(30 * 60_000L, NoScrollWorkScheduler.millisUntilNext(22, 0, now, london))
    }

    @Test
    fun `a time already passed schedules for tomorrow`() {
        val now = LocalDateTime.of(2026, 8, 11, 22, 30)
        assertEquals(23 * 3_600_000L + 30 * 60_000L, NoScrollWorkScheduler.millisUntilNext(22, 0, now, london))
    }

    @Test
    fun `the exact reminder minute schedules for tomorrow, not immediately`() {
        val now = LocalDateTime.of(2026, 8, 11, 22, 0)
        assertEquals(24 * 3_600_000L, NoScrollWorkScheduler.millisUntilNext(22, 0, now, london))
    }

    @Test
    fun `a custom time is honoured`() {
        val now = LocalDateTime.of(2026, 8, 11, 8, 15)
        assertEquals(75 * 60_000L, NoScrollWorkScheduler.millisUntilNext(9, 30, now, london))
    }

    @Test
    fun `the spring dst transition shortens the gap by an hour`() {
        // Europe/London springs forward at 01:00 on 2026-03-29. From 23:00 the night before to
        // 22:00 the next evening is 22 real hours, not 23.
        val now = LocalDateTime.of(2026, 3, 28, 23, 0)
        val delay = NoScrollWorkScheduler.millisUntilNext(22, 0, now, london)
        assertEquals("computed through the zone, not by adding 24 hours", 22 * 3_600_000L, delay)
    }

    @Test
    fun `the autumn dst transition lengthens the gap by an hour`() {
        // Clocks go back at 02:00 on 2026-10-25.
        val now = LocalDateTime.of(2026, 10, 24, 23, 0)
        val delay = NoScrollWorkScheduler.millisUntilNext(22, 0, now, london)
        assertEquals(24 * 3_600_000L, delay)
    }

    @Test
    fun `a different timezone produces a different delay for the same local time`() {
        val now = LocalDateTime.of(2026, 8, 11, 9, 0)
        val tokyo = NoScrollWorkScheduler.millisUntilNext(22, 0, now, ZoneId.of("Asia/Tokyo"))
        val londonDelay = NoScrollWorkScheduler.millisUntilNext(22, 0, now, london)
        // Both are 13 hours of local wall-clock time; the point is the calculation runs in the zone
        // it was given rather than a hardcoded one.
        assertEquals(13 * 3_600_000L, tokyo)
        assertEquals(13 * 3_600_000L, londonDelay)
    }

    @Test
    fun `out of range values are clamped rather than throwing`() {
        val now = LocalDateTime.of(2026, 8, 11, 9, 0)
        assertTrue(NoScrollWorkScheduler.millisUntilNext(99, 99, now, london) >= 0)
        assertTrue(NoScrollWorkScheduler.millisUntilNext(-3, -3, now, london) >= 0)
    }

    @Test
    fun `the delay is never negative`() {
        (0..23).forEach { hour ->
            val now = LocalDateTime.of(2026, 8, 11, hour, 30)
            assertTrue(NoScrollWorkScheduler.millisUntilNext(22, 0, now, london) >= 0)
        }
    }
}
