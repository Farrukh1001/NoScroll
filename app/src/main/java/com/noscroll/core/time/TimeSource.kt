package com.noscroll.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every part of NoScroll that reasons about time goes through this interface so the protection
 * timers, the Instagram budget and the daily rollover can be driven deterministically from tests.
 *
 * Two clocks are exposed on purpose:
 *  - [wallMillis] is the user-visible clock. It can jump backwards (manual change, NITZ, timezone).
 *  - [monotonicMillis] never jumps backwards while the device is awake and is what we accumulate
 *    active-usage durations with, so changing the system clock cannot mint free Instagram minutes.
 */
interface TimeSource {
    /** Wall-clock milliseconds since the epoch. May jump in either direction. */
    fun wallMillis(): Long

    /** Monotonic milliseconds since boot. Never decreases while the process is alive. */
    fun monotonicMillis(): Long

    /** The zone used to decide what "today" means. Re-read every call so DST/timezone moves apply. */
    fun zone(): ZoneId

    fun today(): LocalDate = Instant.ofEpochMilli(wallMillis()).atZone(zone()).toLocalDate()

    /** Epoch millis of the next local midnight strictly after [wallMillis]. */
    fun nextMidnightMillis(): Long =
        today().plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
}

class SystemTimeSource(
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) : TimeSource {
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun monotonicMillis(): Long = android.os.SystemClock.elapsedRealtime()
    override fun zone(): ZoneId = zoneProvider()
}
