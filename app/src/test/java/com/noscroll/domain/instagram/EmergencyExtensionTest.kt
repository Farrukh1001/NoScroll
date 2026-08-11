package com.noscroll.domain.instagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EmergencyExtensionTest {

    private val exhausted = InstagramBudgetState(
        date = LocalDate.of(2026, 8, 11),
        baseLimitMinutes = 30,
        consumedSeconds = 30 * 60L,
    )

    // ---- Amount validation ------------------------------------------------------------------

    @Test
    fun `valid amounts are accepted`() {
        listOf(1, 5, 17, 30).forEach { minutes ->
            assertEquals("$minutes should parse", minutes, EmergencyExtension.parseAmount("$minutes", 30))
        }
    }

    @Test
    fun `invalid amounts are rejected`() {
        listOf("0", "-1", "31", "1.5", "0.5", "abc", "", "  ", "5 minutes", "+5", "1e3", "٥")
            .forEach { input ->
                assertNull("\"$input\" must be rejected", EmergencyExtension.parseAmount(input, 30))
            }
    }

    @Test
    fun `surrounding whitespace on the amount is forgiven`() {
        assertEquals(12, EmergencyExtension.parseAmount("  12 ", 30))
    }

    @Test
    fun `the maximum is configurable and enforced`() {
        assertNull(EmergencyExtension.parseAmount("20", maxMinutes = 15))
        assertEquals(15, EmergencyExtension.parseAmount("15", maxMinutes = 15))
    }

    // ---- The sentence -------------------------------------------------------------------------

    @Test
    fun `the sentence includes the chosen amount`() {
        assertEquals(
            "I choose 17 extra minutes intentionally today.",
            EmergencyExtension.requiredSentence(17),
        )
    }

    @Test
    fun `only outer whitespace is forgiven when matching the sentence`() {
        assertTrue(EmergencyExtension.sentenceMatches("  I choose 5 extra minutes intentionally today.  ", 5))
        assertFalse("wrong number", EmergencyExtension.sentenceMatches(EmergencyExtension.requiredSentence(6), 5))
        assertFalse("missing full stop", EmergencyExtension.sentenceMatches("I choose 5 extra minutes intentionally today", 5))
        assertFalse("wrong case", EmergencyExtension.sentenceMatches("i choose 5 extra minutes intentionally today.", 5))
        assertFalse("paraphrased", EmergencyExtension.sentenceMatches("I choose 5 more minutes intentionally today.", 5))
    }

    // ---- The gate -----------------------------------------------------------------------------

    @Test
    fun `a complete flow grants exactly the chosen amount`() {
        val granted = EmergencyExtension.grant(
            state = exhausted,
            amountMinutes = 17,
            typedSentence = EmergencyExtension.requiredSentence(17),
            countdownComplete = true,
            enabled = true,
            maxMinutes = 30,
        ).getOrThrow()

        assertEquals(17, granted.extraMinutesGranted)
        assertTrue(granted.emergencyUsedToday)
        assertEquals(17 * 60L, granted.remainingSeconds)
    }

    @Test
    fun `the extension is locked until the base budget is gone`() {
        val withTimeLeft = exhausted.copy(consumedSeconds = 10 * 60L)
        val result = EmergencyExtension.grant(
            withTimeLeft, 5, EmergencyExtension.requiredSentence(5), true, true, 30,
        )
        assertEquals(
            ExtensionRejection.BASE_BUDGET_NOT_EXHAUSTED,
            (result.exceptionOrNull() as ExtensionException).rejection,
        )
    }

    @Test
    fun `only one extension per day`() {
        val used = exhausted.copy(emergencyUsedToday = true)
        val result = EmergencyExtension.grant(used, 5, EmergencyExtension.requiredSentence(5), true, true, 30)
        assertEquals(
            ExtensionRejection.ALREADY_USED_TODAY,
            (result.exceptionOrNull() as ExtensionException).rejection,
        )
    }

    @Test
    fun `an incomplete countdown blocks the grant`() {
        val result = EmergencyExtension.grant(
            exhausted, 5, EmergencyExtension.requiredSentence(5), countdownComplete = false, enabled = true, maxMinutes = 30,
        )
        assertEquals(
            ExtensionRejection.COUNTDOWN_INCOMPLETE,
            (result.exceptionOrNull() as ExtensionException).rejection,
        )
    }

    @Test
    fun `a wrong sentence blocks the grant and consumes nothing`() {
        val result = EmergencyExtension.grant(exhausted, 5, "I choose 5 extra minutes today.", true, true, 30)
        assertEquals(
            ExtensionRejection.SENTENCE_MISMATCH,
            (result.exceptionOrNull() as ExtensionException).rejection,
        )
        assertFalse("nothing consumed on failure", exhausted.emergencyUsedToday)
    }

    @Test
    fun `a disabled extension cannot be granted`() {
        val result = EmergencyExtension.grant(
            exhausted, 5, EmergencyExtension.requiredSentence(5), true, enabled = false, maxMinutes = 30,
        )
        assertEquals(
            ExtensionRejection.DISABLED,
            (result.exceptionOrNull() as ExtensionException).rejection,
        )
    }

    @Test
    fun `thirty one minutes is refused even with a matching sentence`() {
        val result = EmergencyExtension.grant(
            exhausted, 31, EmergencyExtension.requiredSentence(31), true, true, 30,
        )
        assertEquals(
            ExtensionRejection.INVALID_AMOUNT,
            (result.exceptionOrNull() as ExtensionException).rejection,
        )
    }

    // ---- Countdown ----------------------------------------------------------------------------

    @Test
    fun `the countdown starts at sixty seconds and completes`() {
        var countdown = CountdownState.start(10)
        assertEquals(60, countdown.remainingSeconds)
        repeat(60) { countdown = countdown.tick() }
        assertTrue(countdown.complete)
    }

    @Test
    fun `a paused countdown does not advance`() {
        var countdown = CountdownState.start(10).pause()
        repeat(30) { countdown = countdown.tick() }
        assertEquals("backgrounding the app pauses it", 60, countdown.remainingSeconds)

        countdown = countdown.resume()
        repeat(30) { countdown = countdown.tick() }
        assertEquals(30, countdown.remainingSeconds)
    }

    @Test
    fun `a completed countdown stays completed`() {
        var countdown = CountdownState(10, 0, running = true)
        countdown = countdown.tick()
        assertEquals(0, countdown.remainingSeconds)
        assertTrue(countdown.complete)
    }

    /** Rotation and process death restore the countdown because it is plain data. */
    @Test
    fun `countdown state round trips`() {
        val original = CountdownState(17, 42, running = true)
        val restored = CountdownState(original.amountMinutes, original.remainingSeconds, original.running)
        assertEquals(original, restored)
    }
}
