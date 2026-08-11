package com.noscroll.domain.wellbeing

import java.time.LocalDate

/**
 * The baseline a saving is measured against. Naming it is not decoration: an unlabelled "you saved
 * 15 minutes" is meaningless, and a claim the user cannot check is a claim they will stop believing.
 */
enum class SavingBaseline {
    YESTERDAY,
    SEVEN_DAY_AVERAGE,
}

sealed interface TimeSavedResult {
    /** Genuinely less usage than the baseline, measured over the same window. */
    data class Saved(val minutes: Long, val baseline: SavingBaseline) : TimeSavedResult

    /** More usage than the baseline. Reported factually, never as a failure. */
    data class Higher(val minutes: Long, val baseline: SavingBaseline) : TimeSavedResult

    /** Same to the minute. */
    data class Level(val baseline: SavingBaseline) : TimeSavedResult

    /** Not enough history to make an honest comparison. Nothing is shown. */
    data object InsufficientData : TimeSavedResult
}

/**
 * Computes "time saved" the only way it can be stated honestly: same clock window, same measurement,
 * both sides real.
 *
 * Two things this deliberately will not do:
 *  - It will not compare today-so-far against a *full* yesterday, which is the usual way this number
 *    gets inflated.
 *  - It will not estimate minutes from blocked attempts. We have no idea how long a scroll that
 *    never happened would have lasted, so we count blocked openings separately and leave it at that.
 */
class TimeSavedCalculator {

    /**
     * @param cutoffHour   the current local hour; both sides are measured up to this point
     * @param cutoffMinute the current local minute
     */
    fun compareWithYesterday(
        aggregate: UsageAggregate,
        today: LocalDate,
        cutoffHour: Int,
        cutoffMinute: Int,
    ): TimeSavedResult {
        val yesterday = today.minusDays(1)
        if (!aggregate.perDayHourly.containsKey(yesterday)) return TimeSavedResult.InsufficientData

        val todayMillis = aggregate.totalUpTo(today, cutoffHour, cutoffMinute)
        val baselineMillis = aggregate.totalUpTo(yesterday, cutoffHour, cutoffMinute)
        return classify(todayMillis, baselineMillis, SavingBaseline.YESTERDAY)
    }

    /**
     * Compares against the average of the previous [days] days at the same cutoff. Requires at least
     * [minimumDays] of real history; on a fresh install it returns [TimeSavedResult.InsufficientData]
     * rather than inventing a first-day saving.
     */
    fun compareWithAverage(
        aggregate: UsageAggregate,
        today: LocalDate,
        cutoffHour: Int,
        cutoffMinute: Int,
        days: Int = 7,
        minimumDays: Int = 3,
    ): TimeSavedResult {
        val history = (1..days)
            .map { today.minusDays(it.toLong()) }
            .filter { aggregate.perDayHourly.containsKey(it) }
        if (history.size < minimumDays) return TimeSavedResult.InsufficientData

        val baselineMillis = history.sumOf { aggregate.totalUpTo(it, cutoffHour, cutoffMinute) } / history.size
        val todayMillis = aggregate.totalUpTo(today, cutoffHour, cutoffMinute)
        return classify(todayMillis, baselineMillis, SavingBaseline.SEVEN_DAY_AVERAGE)
    }

    private fun classify(todayMillis: Long, baselineMillis: Long, baseline: SavingBaseline): TimeSavedResult {
        val deltaMinutes = (baselineMillis - todayMillis) / 60_000L
        return when {
            deltaMinutes > 0 -> TimeSavedResult.Saved(deltaMinutes, baseline)
            deltaMinutes < 0 -> TimeSavedResult.Higher(-deltaMinutes, baseline)
            else -> TimeSavedResult.Level(baseline)
        }
    }
}
