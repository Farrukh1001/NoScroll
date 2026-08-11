package com.noscroll.domain.instagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InstagramBudgetTest {

    private val engine = InstagramBudgetEngine()
    private val today = LocalDate.of(2026, 8, 11)

    private fun state(
        consumed: Long = 0,
        limit: Int = 30,
        extra: Int = 0,
        emergencyUsed: Boolean = false,
        date: LocalDate = today,
    ) = InstagramBudgetState(
        date = date,
        baseLimitMinutes = limit,
        consumedSeconds = consumed,
        extraMinutesGranted = extra,
        emergencyUsedToday = emergencyUsed,
    )

    @Test
    fun `default budget is thirty minutes`() {
        assertEquals(30 * 60L, state().totalAllowanceSeconds)
        assertEquals(30 * 60L, state().remainingSeconds)
    }

    @Test
    fun `the clock only runs when every condition holds`() {
        assertTrue(engine.canConsume(true, true, true, false))
        assertFalse("instagram in background", engine.canConsume(false, true, true, false))
        assertFalse("screen locked", engine.canConsume(true, false, true, false))
        assertFalse("not a budgeted surface", engine.canConsume(true, true, false, false))
        assertFalse("already blocked", engine.canConsume(true, true, true, true))
    }

    @Test
    fun `accumulate adds elapsed monotonic time`() {
        val result = engine.accumulate(state(), lastMonotonic = 1_000, nowMonotonic = 61_000)
        assertEquals(60, result.consumedSeconds)
    }

    @Test
    fun `a backwards clock reading is discarded`() {
        val result = engine.accumulate(state(consumed = 100), lastMonotonic = 61_000, nowMonotonic = 1_000)
        assertEquals("no free time from a backwards jump", 100, result.consumedSeconds)
    }

    @Test
    fun `an implausibly long slice is discarded`() {
        // A gap this long means the process restarted, not that somebody scrolled for an hour
        // without a single event.
        val result = engine.accumulate(state(), 0, InstagramBudgetEngine.MAX_PLAUSIBLE_SLICE_MILLIS + 1)
        assertEquals(0, result.consumedSeconds)
    }

    @Test
    fun `exhaustion is reported at exactly zero`() {
        val exhausted = state(consumed = 30 * 60L)
        assertTrue(exhausted.isExhausted)
        assertEquals(0, exhausted.remainingSeconds)

        val almost = state(consumed = 30 * 60L - 1)
        assertFalse(almost.isExhausted)
    }

    @Test
    fun `rollover resets consumption and the emergency flag`() {
        val yesterday = state(consumed = 1800, extra = 15, emergencyUsed = true, date = today.minusDays(1))
        val rolled = engine.rollover(yesterday, today)
        assertEquals(today, rolled.date)
        assertEquals(0, rolled.consumedSeconds)
        assertEquals(0, rolled.extraMinutesGranted)
        assertFalse(rolled.emergencyUsedToday)
    }

    @Test
    fun `rollover is idempotent within a day`() {
        val current = state(consumed = 600)
        val once = engine.rollover(current, today)
        val twice = engine.rollover(once, today)
        assertEquals(600, once.consumedSeconds)
        assertEquals("calling it again must not hand back time", 600, twice.consumedSeconds)
    }

    @Test
    fun `a pending base limit increase takes effect at rollover`() {
        val yesterday = state(date = today.minusDays(1)).copy(pendingNextDayBaseLimitMinutes = 45)
        val rolled = engine.rollover(yesterday, today)
        assertEquals(45, rolled.baseLimitMinutes)
        assertNull(rolled.pendingNextDayBaseLimitMinutes)
    }

    @Test
    fun `lowering the limit bites today but raising it waits for tomorrow`() {
        val lowered = engine.applyBaseLimit(state(consumed = 600), 15)
        assertEquals("a lower limit applies at once", 15, lowered.baseLimitMinutes)
        assertNull(lowered.pendingNextDayBaseLimitMinutes)

        val raised = engine.applyBaseLimit(state(consumed = 600), 60)
        assertEquals("today's limit is unchanged", 30, raised.baseLimitMinutes)
        assertEquals(60, raised.pendingNextDayBaseLimitMinutes)
    }

    @Test
    fun `the base budget is what unlocks the extension, not the granted extra`() {
        val withExtra = state(consumed = 30 * 60L, extra = 10)
        assertTrue("base is gone", withExtra.baseExhausted)
        assertFalse("but there is granted time left", withExtra.isExhausted)
        assertEquals(10 * 60L, withExtra.remainingSeconds)
    }

    /**
     * The acceptance case from the specification: the budget is shared across accounts because it is
     * never keyed by one. Switching account is simply not an event this model has.
     */
    @Test
    fun `the budget is shared across account switches`() {
        // 20 minutes used on the first account.
        var budget = state(consumed = 20 * 60L)
        assertEquals(10 * 60L, budget.remainingSeconds)

        // The user switches to a second account. Nothing about the state changes: there is no
        // account field to change.
        assertEquals(10 * 60L, budget.remainingSeconds)

        // They use the remaining 10 minutes and take an override on the second account.
        budget = budget.copy(consumedSeconds = 30 * 60L)
        budget = EmergencyExtension.grant(
            state = budget,
            amountMinutes = 5,
            typedSentence = EmergencyExtension.requiredSentence(5),
            countdownComplete = true,
            enabled = true,
            maxMinutes = 30,
        ).getOrThrow()

        // Switching back to the first account, the override is already spent.
        assertTrue(budget.emergencyUsedToday)
        val secondAttempt = EmergencyExtension.grant(
            state = budget.copy(consumedSeconds = 35 * 60L),
            amountMinutes = 5,
            typedSentence = EmergencyExtension.requiredSentence(5),
            countdownComplete = true,
            enabled = true,
            maxMinutes = 30,
        )
        assertEquals(
            ExtensionRejection.ALREADY_USED_TODAY,
            (secondAttempt.exceptionOrNull() as ExtensionException).rejection,
        )
    }
}
