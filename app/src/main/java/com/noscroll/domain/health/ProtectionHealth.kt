package com.noscroll.domain.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

enum class YouTubeMusicStatus {
    /** The package is not on the device. Fully protected. */
    NOT_INSTALLED,

    /** Installed but disabled by the user. Fully protected. */
    DISABLED,

    /** Installed and enabled. Accessibility fallback blocking is in force. */
    ENABLED_FALLBACK,

    /** The package manager did not answer. Reported honestly rather than guessed. */
    UNKNOWN,
    ;

    val isFullyProtected: Boolean get() = this == NOT_INSTALLED || this == DISABLED
}

enum class FilterStatus {
    /** The tunnel is up and filtering. */
    ACTIVE,

    /** Protection is on but the tunnel is not running — browsers fail closed. */
    DOWN,

    /** The user has switched the shield off. Not a failure. */
    DISABLED,
}

data class HealthSnapshot(
    val accessibilityConnected: Boolean = false,
    val filterStatus: FilterStatus = FilterStatus.DISABLED,
    val vpnPrepared: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val youTubeMusicStatus: YouTubeMusicStatus = YouTubeMusicStatus.UNKNOWN,
    val ruleSetVersion: String = "bundled",
    val lastErrorCategory: String? = null,
) {
    /**
     * Whether the app can honestly say protection is working. Setup is never marked complete while
     * this is false.
     */
    val isHealthy: Boolean
        get() = accessibilityConnected && filterStatus != FilterStatus.DOWN
}

/**
 * The single place that knows whether protection is actually working.
 *
 * Everything that could claim "you are protected" reads from here first. The app is not allowed to
 * show a green state because a setting is switched on; it shows green when the service is connected
 * and the filter is genuinely running, and says so plainly when it is not.
 *
 * Blocked-attempt counts are aggregate only — a number, never what was blocked.
 */
class ProtectionHealth {

    private val _snapshot = MutableStateFlow(HealthSnapshot())
    val snapshot: StateFlow<HealthSnapshot> = _snapshot

    private val blockedToday = AtomicInteger(0)
    private var blockedDate: LocalDate = LocalDate.now()

    fun setAccessibilityConnected(connected: Boolean) =
        _snapshot.update { it.copy(accessibilityConnected = connected) }

    fun setFilterStatus(status: FilterStatus) = _snapshot.update { it.copy(filterStatus = status) }

    fun setVpnPrepared(prepared: Boolean) = _snapshot.update { it.copy(vpnPrepared = prepared) }

    fun setUsageAccess(granted: Boolean) = _snapshot.update { it.copy(usageAccessGranted = granted) }

    fun setNotificationsAllowed(allowed: Boolean) = _snapshot.update { it.copy(notificationsAllowed = allowed) }

    fun setYouTubeMusicStatus(status: YouTubeMusicStatus) =
        _snapshot.update { it.copy(youTubeMusicStatus = status) }

    fun setRuleSetVersion(version: String) = _snapshot.update { it.copy(ruleSetVersion = version) }

    /**
     * Records a category of error for diagnostics. The category is a fixed enum-like string; no
     * message, stack trace, URL or user content is ever stored.
     */
    fun recordError(category: String) = _snapshot.update { it.copy(lastErrorCategory = category) }

    fun isFilterHealthy(): Boolean = _snapshot.value.filterStatus != FilterStatus.DOWN

    /** Aggregate count only. What was blocked is never recorded. */
    fun recordBlockedAttempt(today: LocalDate = LocalDate.now()) {
        synchronized(this) {
            if (today != blockedDate) {
                blockedDate = today
                blockedToday.set(0)
            }
        }
        blockedToday.incrementAndGet()
    }

    fun blockedAttemptsToday(today: LocalDate = LocalDate.now()): Int =
        if (today == blockedDate) blockedToday.get() else 0
}
