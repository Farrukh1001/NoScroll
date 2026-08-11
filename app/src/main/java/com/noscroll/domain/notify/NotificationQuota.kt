package com.noscroll.domain.notify

import java.time.LocalDate

/**
 * The kinds of message NoScroll can send, in priority order. A protection failure always outranks
 * encouragement — if something is broken, that is the notification worth the user's attention.
 */
enum class NotificationKind(val priority: Int) {
    PROTECTION_FAILURE(0),
    EVENING_SUMMARY(1),
    BUDGET_MILESTONE(2),
    ENCOURAGEMENT(3),
    ;

    val isReservable: Boolean get() = this == EVENING_SUMMARY
}

data class NotificationRequest(
    val kind: NotificationKind,
    /** Identifies the wording, never the content. Used for dedupe and rotation. */
    val templateId: String,
    val whenMillis: Long,
)

sealed interface QuotaDecision {
    /** Post it as a new notification and consume a slot. */
    data class Post(val request: NotificationRequest) : QuotaDecision

    /**
     * The quota is spent but this still matters: fold it into the persistent status notification
     * instead of posting a fourth message.
     */
    data class UpdatePersistent(val request: NotificationRequest, val reason: SuppressionReason) : QuotaDecision

    data class Suppress(val reason: SuppressionReason) : QuotaDecision
}

enum class SuppressionReason {
    QUOTA_EXHAUSTED,
    RESERVED_FOR_EVENING_SUMMARY,
    TOO_SOON_AFTER_LAST,
    DUPLICATE_TODAY,
    TEMPLATE_REPEATED_RECENTLY,
    NOTIFICATIONS_DISABLED,
}

data class QuotaState(
    val date: LocalDate,
    val postedToday: Int = 0,
    val lastPostMillis: Long? = null,
    val kindsPostedToday: Set<NotificationKind> = emptySet(),
    val eveningSummaryPosted: Boolean = false,
    /** templateId -> the last time it was used, for the seven-day rotation rule. */
    val templateHistory: Map<String, Long> = emptyMap(),
)

data class QuotaConfig(
    /** 0..3. The hard ceiling is enforced regardless of what is stored. */
    val dailyLimit: Int = DEFAULT_DAILY_LIMIT,
    val eveningSummaryEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val minimumSpacingMillis: Long = DEFAULT_MIN_SPACING_MILLIS,
) {
    val effectiveLimit: Int get() = dailyLimit.coerceIn(0, HARD_MAX_DAILY)

    companion object {
        const val DEFAULT_DAILY_LIMIT = 2
        const val HARD_MAX_DAILY = 3
        const val DEFAULT_MIN_SPACING_MILLIS = 90 * 60 * 1000L
        const val TEMPLATE_REPEAT_WINDOW_MILLIS = 7 * 24 * 60 * 60 * 1000L
    }
}

/**
 * The single gate every discrete notification passes through.
 *
 * The ongoing VPN/foreground-service notification is *not* routed through here: it is a silent,
 * persistent status row required by Android, not a message competing for attention.
 *
 * Rules enforced, in order: notifications enabled, daily limit (hard max 3), a reserved slot for the
 * evening summary while one is still expected, minimum spacing, same-kind dedupe within a day, and a
 * seven-day cooldown on reusing the same wording when an alternative exists.
 */
class NotificationQuotaManager {

    fun rollover(state: QuotaState, today: LocalDate): QuotaState {
        if (!today.isAfter(state.date)) return state
        return QuotaState(
            date = today,
            postedToday = 0,
            lastPostMillis = null,
            kindsPostedToday = emptySet(),
            eveningSummaryPosted = false,
            // Template history intentionally survives the day boundary: rotation is a seven-day rule.
            templateHistory = state.templateHistory,
        )
    }

    fun evaluate(
        state: QuotaState,
        config: QuotaConfig,
        request: NotificationRequest,
        alternativesAvailable: Boolean = false,
    ): QuotaDecision {
        if (!config.notificationsEnabled) return QuotaDecision.Suppress(SuppressionReason.NOTIFICATIONS_DISABLED)

        val limit = config.effectiveLimit

        if (request.kind in state.kindsPostedToday && request.kind != NotificationKind.PROTECTION_FAILURE) {
            return QuotaDecision.Suppress(SuppressionReason.DUPLICATE_TODAY)
        }

        val repeatedRecently = state.templateHistory[request.templateId]
            ?.let { request.whenMillis - it in 0 until QuotaConfig.TEMPLATE_REPEAT_WINDOW_MILLIS }
            ?: false
        if (repeatedRecently && alternativesAvailable) {
            return QuotaDecision.Suppress(SuppressionReason.TEMPLATE_REPEATED_RECENTLY)
        }

        val remaining = limit - state.postedToday
        if (remaining <= 0) {
            return if (request.kind == NotificationKind.PROTECTION_FAILURE) {
                QuotaDecision.UpdatePersistent(request, SuppressionReason.QUOTA_EXHAUSTED)
            } else {
                QuotaDecision.Suppress(SuppressionReason.QUOTA_EXHAUSTED)
            }
        }

        // Hold the last slot for the evening summary while one is still coming.
        val eveningStillExpected = config.eveningSummaryEnabled &&
            !state.eveningSummaryPosted &&
            request.kind != NotificationKind.EVENING_SUMMARY
        if (eveningStillExpected && remaining <= 1 && request.kind != NotificationKind.PROTECTION_FAILURE) {
            return QuotaDecision.Suppress(SuppressionReason.RESERVED_FOR_EVENING_SUMMARY)
        }

        state.lastPostMillis?.let { last ->
            val gap = request.whenMillis - last
            if (gap in 0 until config.minimumSpacingMillis && request.kind != NotificationKind.PROTECTION_FAILURE) {
                return QuotaDecision.Suppress(SuppressionReason.TOO_SOON_AFTER_LAST)
            }
        }

        return QuotaDecision.Post(request)
    }

    /** Records a posted notification. Only call this once the post actually succeeded. */
    fun recordPost(state: QuotaState, request: NotificationRequest): QuotaState = state.copy(
        postedToday = state.postedToday + 1,
        lastPostMillis = request.whenMillis,
        kindsPostedToday = state.kindsPostedToday + request.kind,
        eveningSummaryPosted = state.eveningSummaryPosted || request.kind == NotificationKind.EVENING_SUMMARY,
        templateHistory = state.templateHistory + (request.templateId to request.whenMillis),
    )
}
