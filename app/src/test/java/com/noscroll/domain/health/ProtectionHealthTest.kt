package com.noscroll.domain.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The health model exists so the app can never claim protection it does not have. These tests are
 * about that promise, and about the fact that blocked attempts are counted but never described.
 */
class ProtectionHealthTest {

    private val today = LocalDate.of(2026, 8, 11)

    @Test
    fun aFreshSnapshotIsNotHealthy() {
        // Nothing is connected yet, so nothing may be claimed.
        assertFalse(HealthSnapshot().isHealthy)
    }

    @Test
    fun healthRequiresTheServiceToBeConnected() {
        val filterOnlyRunning = HealthSnapshot(accessibilityConnected = false, filterStatus = FilterStatus.ACTIVE)
        assertFalse("a running filter is not enough on its own", filterOnlyRunning.isHealthy)
    }

    @Test
    fun aDownFilterMakesTheAppUnhealthy() {
        val snapshot = HealthSnapshot(accessibilityConnected = true, filterStatus = FilterStatus.DOWN)
        assertFalse(snapshot.isHealthy)
    }

    @Test
    fun aDeliberatelyDisabledShieldIsNotAFailure() {
        // The user turning the shield off is a choice, not a broken protection.
        val snapshot = HealthSnapshot(accessibilityConnected = true, filterStatus = FilterStatus.DISABLED)
        assertTrue(snapshot.isHealthy)
    }

    @Test
    fun onlyDisabledOrUninstalledCountsAsFullYouTubeMusicProtection() {
        assertTrue(YouTubeMusicStatus.NOT_INSTALLED.isFullyProtected)
        assertTrue(YouTubeMusicStatus.DISABLED.isFullyProtected)
        // Accessibility fallback is real protection but weaker, and must not be reported as full.
        assertFalse(YouTubeMusicStatus.ENABLED_FALLBACK.isFullyProtected)
        // And an unanswerable query is never optimistically reported as protected.
        assertFalse(YouTubeMusicStatus.UNKNOWN.isFullyProtected)
    }

    @Test
    fun statesAreTracked() {
        val health = ProtectionHealth()
        health.setAccessibilityConnected(true)
        health.setFilterStatus(FilterStatus.ACTIVE)
        health.setUsageAccess(true)
        health.setYouTubeMusicStatus(YouTubeMusicStatus.DISABLED)
        health.setRuleSetVersion("bundled-1.0.0")

        val snapshot = health.snapshot.value
        assertTrue(snapshot.isHealthy)
        assertTrue(snapshot.usageAccessGranted)
        assertEquals("bundled-1.0.0", snapshot.ruleSetVersion)
    }

    @Test
    fun theFilterHealthCheckDrivesFailClosedBehaviour() {
        val health = ProtectionHealth()
        health.setFilterStatus(FilterStatus.ACTIVE)
        assertTrue(health.isFilterHealthy())

        health.setFilterStatus(FilterStatus.DOWN)
        assertFalse("browsers must fail closed when this is false", health.isFilterHealthy())
    }

    @Test
    fun onlyAnErrorCategoryIsRecorded() {
        val health = ProtectionHealth()
        assertNull(health.snapshot.value.lastErrorCategory)

        health.recordError("vpn_establish_failed")
        val category = health.snapshot.value.lastErrorCategory

        assertEquals("vpn_establish_failed", category)
        // A category, not a message, a URL, or a stack trace.
        assertFalse(category!!.contains("http"))
        assertFalse(category.contains(" "))
    }

    @Test
    fun blockedAttemptsAreCountedAndResetDaily() {
        val health = ProtectionHealth()
        repeat(3) { health.recordBlockedAttempt(today) }
        assertEquals(3, health.blockedAttemptsToday(today))

        // A new day starts from zero.
        health.recordBlockedAttempt(today.plusDays(1))
        assertEquals(1, health.blockedAttemptsToday(today.plusDays(1)))
        assertEquals("yesterday's count is not reported as today's", 0, health.blockedAttemptsToday(today))
    }
}
