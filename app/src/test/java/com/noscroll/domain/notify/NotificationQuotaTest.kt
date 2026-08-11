package com.noscroll.domain.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The quota is the promise "at most three notifications a day, two by default". These tests are what
 * make that a property of the code rather than an intention.
 */
class NotificationQuotaTest {

    private val manager = NotificationQuotaManager()
    private val today = LocalDate.of(2026, 8, 11)
    private val noon = 1_770_000_000_000L
    private val hour = 3_600_000L

    private fun state(
        posted: Int = 0,
        lastPost: Long? = null,
        kinds: Set<NotificationKind> = emptySet(),
        eveningPosted: Boolean = false,
        templates: Map<String, Long> = emptyMap(),
    ) = QuotaState(today, posted, lastPost, kinds, eveningPosted, templates)

    private fun request(
        kind: NotificationKind,
        templateId: String = "t.${kind.name}",
        at: Long = noon,
    ) = NotificationRequest(kind, templateId, at)

    @Test
    fun `the default limit is two`() {
        assertEquals(2, QuotaConfig().effectiveLimit)
    }

    @Test
    fun `the limit is configurable from zero to three`() {
        (0..3).forEach { limit ->
            assertEquals(limit, QuotaConfig(dailyLimit = limit).effectiveLimit)
        }
    }

    @Test
    fun `the hard maximum of three is enforced whatever is stored`() {
        // A corrupted or hand-edited value must not buy a fourth notification.
        assertEquals(3, QuotaConfig(dailyLimit = 99).effectiveLimit)
        assertEquals(0, QuotaConfig(dailyLimit = -5).effectiveLimit)
    }

    @Test
    fun `a notification is posted when there is room`() {
        val decision = manager.evaluate(state(), QuotaConfig(eveningSummaryEnabled = false), request(NotificationKind.ENCOURAGEMENT))
        assertTrue(decision is QuotaDecision.Post)
    }

    @Test
    fun `nothing is posted once the quota is spent`() {
        val decision = manager.evaluate(
            state(posted = 2),
            QuotaConfig(dailyLimit = 2),
            request(NotificationKind.ENCOURAGEMENT),
        )
        assertEquals(
            SuppressionReason.QUOTA_EXHAUSTED,
            (decision as QuotaDecision.Suppress).reason,
        )
    }

    @Test
    fun `a zero limit posts nothing at all`() {
        val decision = manager.evaluate(state(), QuotaConfig(dailyLimit = 0), request(NotificationKind.EVENING_SUMMARY))
        assertTrue(decision is QuotaDecision.Suppress)
    }

    @Test
    fun `notifications disabled suppresses everything`() {
        val decision = manager.evaluate(
            state(),
            QuotaConfig(notificationsEnabled = false),
            request(NotificationKind.PROTECTION_FAILURE),
        )
        assertEquals(
            SuppressionReason.NOTIFICATIONS_DISABLED,
            (decision as QuotaDecision.Suppress).reason,
        )
    }

    @Test
    fun `the last slot is held for the evening summary`() {
        // One slot left and the summary has not been sent: encouragement waits.
        val decision = manager.evaluate(
            state(posted = 1),
            QuotaConfig(dailyLimit = 2, eveningSummaryEnabled = true),
            request(NotificationKind.ENCOURAGEMENT),
        )
        assertEquals(
            SuppressionReason.RESERVED_FOR_EVENING_SUMMARY,
            (decision as QuotaDecision.Suppress).reason,
        )
    }

    @Test
    fun `the evening summary itself may take the reserved slot`() {
        val decision = manager.evaluate(
            state(posted = 1),
            QuotaConfig(dailyLimit = 2, eveningSummaryEnabled = true),
            request(NotificationKind.EVENING_SUMMARY),
        )
        assertTrue(decision is QuotaDecision.Post)
    }

    @Test
    fun `a protection failure outranks the reserved slot`() {
        val decision = manager.evaluate(
            state(posted = 1),
            QuotaConfig(dailyLimit = 2, eveningSummaryEnabled = true),
            request(NotificationKind.PROTECTION_FAILURE),
        )
        assertTrue("something being broken is worth the slot", decision is QuotaDecision.Post)
    }

    @Test
    fun `a protection failure with no slots left updates the persistent notification`() {
        // Rather than posting a fourth notification.
        val decision = manager.evaluate(
            state(posted = 3),
            QuotaConfig(dailyLimit = 3),
            request(NotificationKind.PROTECTION_FAILURE),
        )
        assertTrue(decision is QuotaDecision.UpdatePersistent)
        assertEquals(
            SuppressionReason.QUOTA_EXHAUSTED,
            (decision as QuotaDecision.UpdatePersistent).reason,
        )
    }

    @Test
    fun `two notifications are never posted close together`() {
        val decision = manager.evaluate(
            state(posted = 1, lastPost = noon, eveningPosted = true),
            QuotaConfig(dailyLimit = 3),
            request(NotificationKind.BUDGET_MILESTONE, at = noon + 5 * 60_000),
        )
        assertEquals(
            SuppressionReason.TOO_SOON_AFTER_LAST,
            (decision as QuotaDecision.Suppress).reason,
        )
    }

    @Test
    fun `spacing is satisfied after the minimum interval`() {
        val config = QuotaConfig(dailyLimit = 3, eveningSummaryEnabled = false)
        val decision = manager.evaluate(
            state(posted = 1, lastPost = noon),
            config,
            request(NotificationKind.BUDGET_MILESTONE, at = noon + config.minimumSpacingMillis),
        )
        assertTrue(decision is QuotaDecision.Post)
    }

    @Test
    fun `the same kind is not repeated within a day`() {
        val decision = manager.evaluate(
            state(kinds = setOf(NotificationKind.ENCOURAGEMENT), eveningPosted = true),
            QuotaConfig(dailyLimit = 3),
            request(NotificationKind.ENCOURAGEMENT),
        )
        assertEquals(
            SuppressionReason.DUPLICATE_TODAY,
            (decision as QuotaDecision.Suppress).reason,
        )
    }

    @Test
    fun `the same wording is not repeated within seven days when there is an alternative`() {
        val decision = manager.evaluate(
            state(templates = mapOf("t.ENCOURAGEMENT" to noon - 2 * 24 * hour), eveningPosted = true),
            QuotaConfig(dailyLimit = 3),
            request(NotificationKind.ENCOURAGEMENT),
            alternativesAvailable = true,
        )
        assertEquals(
            SuppressionReason.TEMPLATE_REPEATED_RECENTLY,
            (decision as QuotaDecision.Suppress).reason,
        )
    }

    @Test
    fun `a repeated template is allowed once the seven days pass`() {
        val decision = manager.evaluate(
            state(templates = mapOf("t.ENCOURAGEMENT" to noon - 8 * 24 * hour), eveningPosted = true),
            QuotaConfig(dailyLimit = 3, eveningSummaryEnabled = false),
            request(NotificationKind.ENCOURAGEMENT),
            alternativesAvailable = true,
        )
        assertTrue(decision is QuotaDecision.Post)
    }

    @Test
    fun `a repeated template is used anyway when nothing else is available`() {
        val decision = manager.evaluate(
            state(templates = mapOf("t.ENCOURAGEMENT" to noon - 60_000), eveningPosted = true),
            QuotaConfig(dailyLimit = 3, eveningSummaryEnabled = false),
            request(NotificationKind.ENCOURAGEMENT),
            alternativesAvailable = false,
        )
        assertTrue(decision is QuotaDecision.Post)
    }

    @Test
    fun `priority ordering puts a failure first and encouragement last`() {
        val ordered = NotificationKind.entries.sortedBy { it.priority }
        assertEquals(
            listOf(
                NotificationKind.PROTECTION_FAILURE,
                NotificationKind.EVENING_SUMMARY,
                NotificationKind.BUDGET_MILESTONE,
                NotificationKind.ENCOURAGEMENT,
            ),
            ordered,
        )
    }

    @Test
    fun `recording a post updates the count, kind, time and history`() {
        val after = manager.recordPost(state(), request(NotificationKind.EVENING_SUMMARY, "evening.summary"))
        assertEquals(1, after.postedToday)
        assertEquals(noon, after.lastPostMillis)
        assertTrue(after.eveningSummaryPosted)
        assertTrue(after.kindsPostedToday.contains(NotificationKind.EVENING_SUMMARY))
        assertEquals(noon, after.templateHistory["evening.summary"])
    }

    @Test
    fun `the daily count resets at local midnight but template history survives`() {
        val used = state(posted = 3, kinds = setOf(NotificationKind.ENCOURAGEMENT), eveningPosted = true, templates = mapOf("t" to noon))
        val tomorrow = manager.rollover(used, today.plusDays(1))

        assertEquals(0, tomorrow.postedToday)
        assertTrue(tomorrow.kindsPostedToday.isEmpty())
        assertEquals(false, tomorrow.eveningSummaryPosted)
        // Rotation is a seven-day rule, so history must not reset with the day.
        assertEquals(noon, tomorrow.templateHistory["t"])
    }

    @Test
    fun `rollover is idempotent within a day`() {
        val used = state(posted = 2)
        assertEquals(2, manager.rollover(used, today).postedToday)
    }

    @Test
    fun `message rotation prefers wording that has not been used recently`() {
        val candidates = listOf(
            EncouragementMessage("a", "A"),
            EncouragementMessage("b", "B"),
        )
        val selected = MessageCatalogue.selectRotating(
            candidates,
            templateHistory = mapOf("a" to noon - 60_000),
            nowMillis = noon,
        )
        assertEquals("b", selected!!.templateId)
    }

    @Test
    fun `rotation falls back to the best candidate when everything is recent`() {
        val candidates = listOf(EncouragementMessage("a", "A"))
        val selected = MessageCatalogue.selectRotating(
            candidates,
            templateHistory = mapOf("a" to noon - 60_000),
            nowMillis = noon,
        )
        assertEquals("a", selected!!.templateId)
    }
}
