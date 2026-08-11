package com.noscroll.domain.wellbeing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class UsageEventType {
    /** An activity of this package came to the foreground. */
    FOREGROUND,

    /** An activity of this package left the foreground. */
    BACKGROUND,

    /** The screen went off or the device locked; whatever was open stops counting. */
    SCREEN_NON_INTERACTIVE,

    /** The screen came back on. Present for completeness; a FOREGROUND event follows it. */
    SCREEN_INTERACTIVE,

    /** Device shutdown or a usage-stats reboot marker; closes any open interval. */
    SHUTDOWN,
}

data class UsageEventRecord(
    val packageName: String,
    val type: UsageEventType,
    val timestampMillis: Long,
)

/** Foreground milliseconds per package, per local day, plus a 24-slot hourly profile per day. */
data class UsageAggregate(
    val perDayPerPackage: Map<LocalDate, Map<String, Long>> = emptyMap(),
    val perDayHourly: Map<LocalDate, List<Long>> = emptyMap(),
) {
    fun totalFor(date: LocalDate): Long = perDayPerPackage[date]?.values?.sum() ?: 0L

    fun packageTotal(date: LocalDate, packageName: String): Long =
        perDayPerPackage[date]?.get(packageName) ?: 0L

    fun topApps(date: LocalDate, limit: Int = 5): List<Pair<String, Long>> =
        perDayPerPackage[date].orEmpty().entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }

    /**
     * Foreground milliseconds on [date] up to [cutoffMillis], summed across apps. This is what makes
     * an honest "compared with yesterday by this time" possible instead of comparing a partial day
     * against a full one.
     */
    fun totalUpTo(date: LocalDate, cutoffHour: Int, cutoffMinute: Int): Long {
        val hourly = perDayHourly[date] ?: return 0L
        var total = 0L
        for (hour in 0 until cutoffHour.coerceIn(0, 24)) {
            total += hourly.getOrElse(hour) { 0L }
        }
        if (cutoffHour in 0..23 && cutoffMinute > 0) {
            // Prorate the partial hour. It is an approximation inside one hour only, and it is
            // applied identically to both sides of every comparison.
            total += hourly.getOrElse(cutoffHour) { 0L } * cutoffMinute / 60
        }
        return total
    }
}

/**
 * Turns a raw usage-event stream into daily totals.
 *
 * `UsageStatsManager` streams are messy in ways that quietly corrupt totals if you trust them: an
 * app can go foreground twice with no matching background, a reboot can drop the closing event
 * entirely, several activities of the same package interleave, and an interval routinely straddles
 * midnight. Each of those is handled explicitly below, and each has a test.
 *
 * The rule for an unclosed interval is to close it at the end of the requested window rather than to
 * discard it, so "today so far" is complete without inventing time that has not happened yet.
 */
class UsageAggregator(private val zone: ZoneId) {

    fun aggregate(
        events: List<UsageEventRecord>,
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): UsageAggregate {
        val perDayPerPackage = mutableMapOf<LocalDate, MutableMap<String, Long>>()
        val perDayHourly = mutableMapOf<LocalDate, MutableList<Long>>()

        fun record(packageName: String, fromMillis: Long, toMillis: Long) {
            if (toMillis <= fromMillis) return
            splitByHour(fromMillis, toMillis).forEach { slice ->
                val day = perDayPerPackage.getOrPut(slice.date) { mutableMapOf() }
                day[packageName] = (day[packageName] ?: 0L) + slice.durationMillis
                val hourly = perDayHourly.getOrPut(slice.date) { MutableList(24) { 0L } }
                hourly[slice.hour] = hourly[slice.hour] + slice.durationMillis
            }
        }

        var openPackage: String? = null
        var openSince = 0L

        val ordered = events
            .filter { it.timestampMillis in windowStartMillis..windowEndMillis }
            .sortedBy { it.timestampMillis }

        for (event in ordered) {
            when (event.type) {
                UsageEventType.FOREGROUND -> {
                    if (openPackage == event.packageName) continue // another activity of the same app
                    if (openPackage != null) record(openPackage, openSince, event.timestampMillis)
                    openPackage = event.packageName
                    openSince = event.timestampMillis
                }

                UsageEventType.BACKGROUND -> {
                    if (openPackage == event.packageName) {
                        record(openPackage, openSince, event.timestampMillis)
                        openPackage = null
                    }
                    // A background event for an app we never saw open is a dropped pair; ignoring it
                    // is correct, because we have no defensible start time to attribute.
                }

                UsageEventType.SCREEN_NON_INTERACTIVE, UsageEventType.SHUTDOWN -> {
                    if (openPackage != null) {
                        record(openPackage, openSince, event.timestampMillis)
                        openPackage = null
                    }
                }

                UsageEventType.SCREEN_INTERACTIVE -> Unit
            }
        }

        // Still open at the end of the window: count up to the window end, not beyond.
        if (openPackage != null) record(openPackage, openSince, windowEndMillis)

        return UsageAggregate(
            perDayPerPackage = perDayPerPackage.mapValues { it.value.toMap() },
            perDayHourly = perDayHourly.mapValues { it.value.toList() },
        )
    }

    private data class HourSlice(val date: LocalDate, val hour: Int, val durationMillis: Long)

    /**
     * Chops an interval at every local hour boundary, which also chops it at midnight. Doing it in
     * local time is what makes DST transitions and timezone changes come out right.
     */
    private fun splitByHour(fromMillis: Long, toMillis: Long): List<HourSlice> {
        val slices = mutableListOf<HourSlice>()
        var cursor = fromMillis
        var guard = 0
        while (cursor < toMillis && guard < MAX_SLICES) {
            guard++
            val zoned = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = zoned.plusHours(1).withMinute(0).withSecond(0).withNano(0)
            val boundary = nextHour.toInstant().toEpochMilli()
            val sliceEnd = minOf(boundary, toMillis)
            slices += HourSlice(zoned.toLocalDate(), zoned.hour, sliceEnd - cursor)
            cursor = sliceEnd
        }
        return slices
    }

    private companion object {
        /** An interval longer than this is pathological; stop slicing rather than spin. */
        const val MAX_SLICES = 24 * 40
    }
}
