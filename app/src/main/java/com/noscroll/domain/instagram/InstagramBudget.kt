package com.noscroll.domain.instagram

import java.time.LocalDate

/**
 * The shared Instagram scrolling budget.
 *
 * One budget per device per local day. It is deliberately not keyed by account, username or login
 * session — signing out and back in, or switching to a second account, continues the same day's
 * budget. NoScroll never reads or stores who is signed in.
 */
data class InstagramBudgetState(
    val date: LocalDate,
    val baseLimitMinutes: Int,
    val consumedSeconds: Long = 0L,
    val extraMinutesGranted: Int = 0,
    val emergencyUsedToday: Boolean = false,
    /** A base-limit increase that was approved today but must not take effect until tomorrow. */
    val pendingNextDayBaseLimitMinutes: Int? = null,
) {
    val totalAllowanceSeconds: Long
        get() = (baseLimitMinutes.toLong() + extraMinutesGranted.toLong()) * 60L

    val remainingSeconds: Long
        get() = (totalAllowanceSeconds - consumedSeconds).coerceAtLeast(0L)

    val isExhausted: Boolean get() = remainingSeconds <= 0L

    val baseRemainingSeconds: Long
        get() = (baseLimitMinutes.toLong() * 60L - consumedSeconds).coerceAtLeast(0L)

    /** The emergency extension only unlocks once the base budget itself is gone. */
    val baseExhausted: Boolean get() = baseRemainingSeconds <= 0L
}

/**
 * Accumulates active time against [InstagramBudgetState].
 *
 * Time is measured with a monotonic clock and only while every condition in [canConsume] holds, so
 * the budget cannot be inflated by changing the system clock and cannot be drained by Instagram
 * sitting in the background.
 */
class InstagramBudgetEngine {

    /**
     * The conditions under which the clock runs. All four must hold.
     */
    fun canConsume(
        instagramForeground: Boolean,
        screenOnAndUnlocked: Boolean,
        budgetedSurfaceDetected: Boolean,
        blockingOverlayShown: Boolean,
    ): Boolean = instagramForeground && screenOnAndUnlocked && budgetedSurfaceDetected && !blockingOverlayShown

    /**
     * Adds the elapsed slice between two monotonic readings.
     *
     * A backwards or absurdly large jump (process restart, a reading from a previous boot) is
     * discarded rather than trusted — losing a slice is always safer than granting free time.
     */
    fun accumulate(state: InstagramBudgetState, lastMonotonic: Long, nowMonotonic: Long): InstagramBudgetState {
        val delta = nowMonotonic - lastMonotonic
        if (delta <= 0L || delta > MAX_PLAUSIBLE_SLICE_MILLIS) return state
        val seconds = delta / 1000.0
        val newConsumed = state.consumedSeconds + seconds.toLong()
        // Never let consumption run away past the allowance; the blocker takes over at zero anyway.
        val capped = newConsumed.coerceAtMost(state.totalAllowanceSeconds + 60L)
        return state.copy(consumedSeconds = capped)
    }

    /**
     * Rolls the budget over to [today] if the stored day is older.
     *
     * Rollover is idempotent — calling it repeatedly on the same day changes nothing — and it is the
     * only place a base-limit increase that was approved yesterday becomes live.
     */
    fun rollover(state: InstagramBudgetState, today: LocalDate): InstagramBudgetState {
        if (!today.isAfter(state.date)) return state
        return InstagramBudgetState(
            date = today,
            baseLimitMinutes = state.pendingNextDayBaseLimitMinutes ?: state.baseLimitMinutes,
            consumedSeconds = 0L,
            extraMinutesGranted = 0,
            emergencyUsedToday = false,
            pendingNextDayBaseLimitMinutes = null,
        )
    }

    /**
     * Applies a new base limit.
     *
     * Lowering it is a strengthening change and bites immediately, including for the rest of today.
     * Raising it has already served its waiting period by the time it gets here, but it still only
     * takes effect tomorrow so that the wait cannot be used to rescue the current day.
     */
    fun applyBaseLimit(state: InstagramBudgetState, newLimitMinutes: Int): InstagramBudgetState =
        if (newLimitMinutes <= state.baseLimitMinutes) {
            state.copy(baseLimitMinutes = newLimitMinutes, pendingNextDayBaseLimitMinutes = null)
        } else {
            state.copy(pendingNextDayBaseLimitMinutes = newLimitMinutes)
        }

    companion object {
        /** Longer than this between two readings means something restarted; don't trust the slice. */
        const val MAX_PLAUSIBLE_SLICE_MILLIS = 5 * 60 * 1000L
    }
}
