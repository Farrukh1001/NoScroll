package com.noscroll.domain.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protected-settings rules are the backbone of the whole app, so these tests are deliberately
 * exhaustive about the ways somebody might try to get around a waiting period: queueing a second
 * bundle, cancelling and re-requesting, moving the system clock, rebooting, and shortening the delay
 * to shorten the wait for shortening the delay.
 */
class ProtectedSettingsEngineTest {

    private val engine = ProtectedSettingsEngine()

    private fun baseState(delayHours: Int = 24) = ProtectionState.withDefaults()
        .let { it.copy(values = it.values + (SettingKey.WEAKENING_DELAY_HOURS to SettingValue.Num(delayHours))) }

    private val hour = ProtectedSettingsEngine.MILLIS_PER_HOUR

    // ---- Strengthening applies immediately --------------------------------------------------

    @Test
    fun `enabling a protection applies immediately`() {
        val state = baseState().let {
            it.copy(values = it.values + (SettingKey.YOUTUBE_MODULE to SettingValue.Bool(false)))
        }
        val outcome = engine.request(
            state,
            mapOf(SettingKey.YOUTUBE_MODULE to SettingValue.Bool(true)),
            nowWall = 1_000,
            nowMonotonic = 1_000,
        )
        assertTrue(outcome is RequestOutcome.Applied)
        assertTrue((outcome as RequestOutcome.Applied).state.bool(SettingKey.YOUTUBE_MODULE))
    }

    @Test
    fun `lowering the instagram limit applies immediately`() {
        val outcome = engine.request(
            baseState(),
            mapOf(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES to SettingValue.Num(15)),
            1_000,
            1_000,
        )
        assertTrue(outcome is RequestOutcome.Applied)
        assertEquals(15, (outcome as RequestOutcome.Applied).state.int(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES))
    }

    @Test
    fun `disabling the emergency extension strengthens and applies immediately`() {
        // The emergency toggle has inverted polarity: turning it OFF is a strengthening change.
        val outcome = engine.request(
            baseState(),
            mapOf(SettingKey.INSTAGRAM_EMERGENCY_ENABLED to SettingValue.Bool(false)),
            1_000,
            1_000,
        )
        assertTrue(outcome is RequestOutcome.Applied)
    }

    @Test
    fun `enabling the emergency extension weakens and is queued`() {
        val state = baseState().let {
            it.copy(values = it.values + (SettingKey.INSTAGRAM_EMERGENCY_ENABLED to SettingValue.Bool(false)))
        }
        val outcome = engine.request(
            state,
            mapOf(SettingKey.INSTAGRAM_EMERGENCY_ENABLED to SettingValue.Bool(true)),
            1_000,
            1_000,
        )
        assertTrue(outcome is RequestOutcome.Queued)
    }

    // ---- Weakening is queued ------------------------------------------------------------------

    @Test
    fun `disabling a protection is queued for the active delay`() {
        val outcome = engine.request(
            baseState(delayHours = 24),
            mapOf(SettingKey.SHIELD_MODULE to SettingValue.Bool(false)),
            1_000,
            1_000,
        )
        assertTrue(outcome is RequestOutcome.Queued)
        val queued = outcome as RequestOutcome.Queued
        assertEquals(24 * hour, queued.bundle.totalDelayMillis)
        // Still on until the wait elapses.
        assertTrue(queued.state.bool(SettingKey.SHIELD_MODULE))
    }

    @Test
    fun `every delay preset is honoured`() {
        SettingKey.DELAY_PRESET_HOURS.forEach { hours ->
            val outcome = engine.request(
                baseState(delayHours = hours),
                mapOf(SettingKey.SHIELD_MODULE to SettingValue.Bool(false)),
                1_000,
                1_000,
            )
            assertEquals(
                "delay preset ${hours}h",
                hours * hour,
                (outcome as RequestOutcome.Queued).bundle.totalDelayMillis,
            )
        }
    }

    @Test
    fun `a mixed request applies the strengthening half and queues the weakening half`() {
        val outcome = engine.request(
            baseState(),
            mapOf(
                SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES to SettingValue.Num(10), // strengthening
                SettingKey.SHIELD_SAFE_SEARCH to SettingValue.Bool(false),       // weakening
            ),
            1_000,
            1_000,
        )
        val queued = outcome as RequestOutcome.Queued
        assertEquals(10, queued.state.int(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES))
        assertTrue(queued.state.bool(SettingKey.SHIELD_SAFE_SEARCH))
        assertEquals(1, queued.bundle.entries.size)
    }

    @Test
    fun `only one weakening bundle may be pending`() {
        val first = engine.request(
            baseState(),
            mapOf(SettingKey.SHIELD_MODULE to SettingValue.Bool(false)),
            1_000,
            1_000,
        ) as RequestOutcome.Queued

        val second = engine.request(
            first.state,
            mapOf(SettingKey.YOUTUBE_MODULE to SettingValue.Bool(false)),
            2_000,
            2_000,
        )
        assertEquals(RejectionReason.PENDING_ALREADY_EXISTS, (second as RequestOutcome.Rejected).reason)
    }

    @Test
    fun `a rejected request is not partially applied`() {
        val first = engine.request(
            baseState(),
            mapOf(SettingKey.SHIELD_MODULE to SettingValue.Bool(false)),
            1_000,
            1_000,
        ) as RequestOutcome.Queued

        val second = engine.request(
            first.state,
            mapOf(SettingKey.YOUTUBE_MODULE to SettingValue.Bool(false)),
            2_000,
            2_000,
        )
        assertTrue(second is RequestOutcome.Rejected)
        assertTrue(first.state.bool(SettingKey.YOUTUBE_MODULE))
    }

    // ---- Applying, cancelling, cooldown --------------------------------------------------------

    @Test
    fun `the bundle applies once the wait elapses`() {
        val queued = engine.request(
            baseState(delayHours = 1),
            mapOf(SettingKey.SHIELD_MODULE to SettingValue.Bool(false)),
            1_000,
            1_000,
        ) as RequestOutcome.Queued

        val justBefore = engine.reconcile(queued.state, 1_000 + hour - 1, 1_000 + hour - 1)
        assertTrue("still waiting", justBefore.bool(SettingKey.SHIELD_MODULE))

        val after = engine.reconcile(queued.state, 1_000 + hour, 1_000 + hour)
        assertTrue("applied", !after.bool(SettingKey.SHIELD_MODULE))
        assertNull(after.pending)
    }

    @Test
    fun `cancelling clears the bundle and starts a cooldown`() {
        val queued = engine.request(
            baseState(),
            mapOf(SettingKey.SHIELD_MODULE to SettingValue.Bool(false)),
            1_000,
            1_000,
        ) as RequestOutcome.Queued

        val cancelled = engine.cancelPending(queued.state, 5_000)
        assertNull(cancelled.pending)
        assertTrue(cancelled.bool(SettingKey.SHIELD_MODULE))

        val immediateRetry = engine.request(
            cancelled,
            mapOf(SettingKey.SHIELD_MODULE to SettingValue.Bool(false)),
            6_000,
            6_000,
        )
        assertEquals(RejectionReason.CANCEL_COOLDOWN, (immediateRetry as RequestOutcome.Rejected).reason)
    }

    @Test
    fun `a request is accepted again once the cooldown expires`() {
        val cooldownEnd = 5_000 + ProtectedSettingsEngine.DEFAULT_CANCEL_COOLDOWN_MILLIS
        val state = baseState().copy(cancelCooldownUntilWall = cooldownEnd)
        val outcome = engine.request(
            state,
            mapOf(SettingKey.SHIELD_MODULE to SettingValue.Bool(false)),
            cooldownEnd,
            cooldownEnd,
        )
        assertTrue(outcome is RequestOutcome.Queued)
    }

    // ---- Clock manipulation --------------------------------------------------------------------

    @Test
    fun `moving the wall clock forward does not shorten the wait`() {
        val queued = engine.request(
            baseState(delayHours = 24),
            mapOf(SettingKey.SHIELD_MODULE to SettingValue.Bool(false)),
            1_000,
            1_000,
        ) as RequestOutcome.Queued

        // Wall clock jumps a week; the monotonic clock has barely moved.
        val afterJump = engine.reconcile(queued.state, 1_000 + 7 * 24 * hour, 1_000 + 60_000)
        assertNotNull("still pending", afterJump.pending)
        assertTrue(afterJump.bool(SettingKey.SHIELD_MODULE))
    }

    @Test
    fun `time off is credited across a reboot`() {
        val queued = engine.request(
            baseState(delayHours = 2),
            mapOf(SettingKey.SHIELD_MODULE to SettingValue.Bool(false)),
            nowWall = 1_000_000,
            nowMonotonic = 500_000,
        ) as RequestOutcome.Queued

        // Reboot: the monotonic clock restarted, and three wall-clock hours passed.
        val afterReboot = engine.reconcile(
            queued.state,
            nowWall = 1_000_000 + 3 * hour,
            nowMonotonic = 4_000,
        )
        assertNull("the wait elapsed while the device was off", afterReboot.pending)
        assertTrue(!afterReboot.bool(SettingKey.SHIELD_MODULE))
    }

    @Test
    fun `rolling the clock back across a reboot credits nothing but does not lock out`() {
        val queued = engine.request(
            baseState(delayHours = 2),
            mapOf(SettingKey.SHIELD_MODULE to SettingValue.Bool(false)),
            nowWall = 1_000_000,
            nowMonotonic = 500_000,
        ) as RequestOutcome.Queued

        // Reboot with the clock moved backwards: no credit.
        val rolledBack = engine.reconcile(queued.state, nowWall = 500_000, nowMonotonic = 1_000)
        assertNotNull(rolledBack.pending)
        assertEquals(2 * hour, rolledBack.pending!!.remainingMillisAtAnchor)

        // And normal monotonic progress still applies afterwards, so the user is never stuck.
        val later = engine.reconcile(rolledBack, nowWall = 500_000 + 2 * hour, nowMonotonic = 1_000 + 2 * hour)
        assertNull(later.pending)
    }

    // ---- The delay setting itself ---------------------------------------------------------------

    @Test
    fun `shortening the delay waits for the old delay`() {
        val outcome = engine.request(
            baseState(delayHours = 48),
            mapOf(SettingKey.WEAKENING_DELAY_HOURS to SettingValue.Num(1)),
            1_000,
            1_000,
        )
        val queued = outcome as RequestOutcome.Queued
        assertEquals("48 hours to 1 hour still waits 48 hours", 48 * hour, queued.bundle.totalDelayMillis)
    }

    @Test
    fun `lengthening the delay still waits the mandatory hour`() {
        val outcome = engine.request(
            baseState(delayHours = 2),
            mapOf(SettingKey.WEAKENING_DELAY_HOURS to SettingValue.Num(24)),
            1_000,
            1_000,
        )
        assertEquals(hour, (outcome as RequestOutcome.Queued).bundle.totalDelayMillis)
    }

    @Test
    fun `the delay may only be changed once per rolling 24 hours`() {
        val first = engine.request(
            baseState(delayHours = 24),
            mapOf(SettingKey.WEAKENING_DELAY_HOURS to SettingValue.Num(12)),
            1_000,
            1_000,
        ) as RequestOutcome.Queued

        val applied = engine.reconcile(first.state, 1_000 + 24 * hour, 1_000 + 24 * hour)
        assertEquals(12, applied.int(SettingKey.WEAKENING_DELAY_HOURS))

        // Less than 24h after the *request*, a second change is refused.
        val second = engine.request(
            applied,
            mapOf(SettingKey.WEAKENING_DELAY_HOURS to SettingValue.Num(4)),
            1_000 + 20 * hour,
            1_000 + 20 * hour,
        )
        assertEquals(
            RejectionReason.DELAY_CHANGE_RATE_LIMITED,
            (second as RequestOutcome.Rejected).reason,
        )
    }

    @Test
    fun `a delay change must be requested on its own`() {
        val outcome = engine.request(
            baseState(),
            mapOf(
                SettingKey.WEAKENING_DELAY_HOURS to SettingValue.Num(1),
                SettingKey.SHIELD_MODULE to SettingValue.Bool(false),
            ),
            1_000,
            1_000,
        )
        assertEquals(
            RejectionReason.DELAY_CHANGE_MUST_BE_ALONE,
            (outcome as RequestOutcome.Rejected).reason,
        )
    }

    // ---- Validation -----------------------------------------------------------------------------

    @Test
    fun `an out of range value is refused`() {
        val outcome = engine.request(
            baseState(),
            mapOf(SettingKey.INSTAGRAM_EMERGENCY_MAX_MINUTES to SettingValue.Num(31)),
            1_000,
            1_000,
        )
        assertEquals(RejectionReason.VALUE_OUT_OF_RANGE, (outcome as RequestOutcome.Rejected).reason)
    }

    @Test
    fun `a no-op request changes nothing`() {
        val outcome = engine.request(
            baseState(),
            mapOf(SettingKey.YOUTUBE_MODULE to SettingValue.Bool(true)),
            1_000,
            1_000,
        )
        assertEquals(RejectionReason.NOTHING_TO_DO, (outcome as RequestOutcome.Rejected).reason)
    }

    @Test
    fun `the bundle explains why each change weakens protection`() {
        val queued = engine.request(
            baseState(),
            mapOf(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES to SettingValue.Num(90)),
            1_000,
            1_000,
        ) as RequestOutcome.Queued
        val entry = queued.bundle.entries.single()
        assertEquals(SettingValue.Num(30), entry.current)
        assertEquals(SettingValue.Num(90), entry.requested)
        assertTrue(entry.reason.contains("30"))
        assertTrue(entry.reason.contains("90"))
    }
}
