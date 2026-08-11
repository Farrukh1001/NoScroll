package com.noscroll.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A fake clock the rest of the test suite uses. Wall and monotonic time move independently, which is
 * exactly the property the protection timers are built on.
 */
class FakeTimeSource(
    var wall: Long = 0L,
    var monotonic: Long = 0L,
    var zone: ZoneId = ZoneId.of("UTC"),
) : TimeSource {
    override fun wallMillis(): Long = wall
    override fun monotonicMillis(): Long = monotonic
    override fun zone(): ZoneId = zone

    /** Time passing normally: both clocks advance together. */
    fun advance(millis: Long) {
        wall += millis
        monotonic += millis
    }

    /** The user changing the system clock. The monotonic clock does not care. */
    fun setWallClock(millis: Long) {
        wall = millis
    }

    /** A reboot: the monotonic clock restarts while the wall clock carries on. */
    fun reboot(offMillis: Long) {
        wall += offMillis
        monotonic = 0L
    }
}

class TimeSourceTest {

    private fun epochOf(date: LocalDate, hour: Int, zone: ZoneId): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun todayIsReadInTheConfiguredZone() {
        val london = ZoneId.of("Europe/London")
        val time = FakeTimeSource(zone = london)
        // 23:30 UTC on 10 August is already 11 August in London during BST.
        time.wall = LocalDateTime.of(2026, 8, 10, 23, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 8, 11), time.today())
    }

    @Test
    fun nextMidnightIsTheStartOfTomorrowLocally() {
        val zone = ZoneId.of("UTC")
        val time = FakeTimeSource(zone = zone)
        time.wall = epochOf(LocalDate.of(2026, 8, 11), 22, zone)

        val expected = LocalDate.of(2026, 8, 12).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, time.nextMidnightMillis())
    }

    @Test
    fun aTimezoneChangeMovesWhatTodayMeans() {
        val time = FakeTimeSource(zone = ZoneId.of("UTC"))
        time.wall = LocalDateTime.of(2026, 8, 11, 22, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 8, 11), time.today())

        // Flying east: the same instant is already tomorrow.
        time.zone = ZoneId.of("Asia/Tokyo")
        assertEquals(LocalDate.of(2026, 8, 12), time.today())
    }

    @Test
    fun changingTheWallClockDoesNotMoveTheMonotonicClock() {
        val time = FakeTimeSource(wall = 1_000_000, monotonic = 500)
        time.setWallClock(1_000_000 + 7 * 24 * 3_600_000L)

        assertEquals("the monotonic clock is unaffected", 500, time.monotonicMillis())
    }

    @Test
    fun aRebootResetsTheMonotonicClockOnly() {
        val time = FakeTimeSource(wall = 1_000_000, monotonic = 900_000)
        time.reboot(offMillis = 3 * 3_600_000L)

        assertEquals(0L, time.monotonicMillis())
        assertTrue("wall time carried on while the device was off", time.wallMillis() > 1_000_000)
    }

    @Test
    fun normalPassageMovesBothClocksTogether() {
        val time = FakeTimeSource(wall = 1_000, monotonic = 1_000)
        time.advance(60_000)
        assertEquals(61_000, time.wallMillis())
        assertEquals(61_000, time.monotonicMillis())
    }
}
