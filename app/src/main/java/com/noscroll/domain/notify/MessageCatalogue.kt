package com.noscroll.domain.notify

import com.noscroll.domain.wellbeing.SavingBaseline
import com.noscroll.domain.wellbeing.TimeSavedResult

/** How blunt the app is allowed to be. Purely a wording choice; the numbers never change. */
enum class MessageTone { CALM, DIRECT, MINIMAL }

data class EncouragementMessage(val templateId: String, val text: String)

/**
 * Every user-facing message that reports on progress.
 *
 * The constraint that shapes this file: **a message may only state something the data supports.**
 * There are no motivational quotes, no "you crushed it", no invented counterfactuals, and no
 * negative saving dressed up as a positive one. When there is nothing true to say, the catalogue
 * returns null and the app says nothing, which is a perfectly good outcome.
 */
object MessageCatalogue {

    fun timeSaved(result: TimeSavedResult, tone: MessageTone): EncouragementMessage? = when (result) {
        is TimeSavedResult.Saved -> {
            val baselineText = when (result.baseline) {
                SavingBaseline.YESTERDAY -> "compared with yesterday by this time"
                SavingBaseline.SEVEN_DAY_AVERAGE -> "less than your seven-day average by this time"
            }
            val id = "saved.${result.baseline.name.lowercase()}.${tone.name.lowercase()}"
            val text = when (tone) {
                MessageTone.MINIMAL -> "${result.minutes} min saved $baselineText."
                MessageTone.DIRECT -> when (result.baseline) {
                    SavingBaseline.YESTERDAY ->
                        "You used ${result.minutes} minutes less than yesterday by this time."
                    SavingBaseline.SEVEN_DAY_AVERAGE ->
                        "You used ${result.minutes} minutes less than your seven-day average by this time."
                }
                MessageTone.CALM -> when (result.baseline) {
                    SavingBaseline.YESTERDAY ->
                        "You saved ${result.minutes} minutes compared with yesterday by this time. " +
                            "That's ${result.minutes} minutes back in your day."
                    SavingBaseline.SEVEN_DAY_AVERAGE ->
                        "You used ${result.minutes} minutes less than your seven-day average by this time."
                }
            }
            EncouragementMessage(id, text)
        }

        is TimeSavedResult.Higher -> {
            // Factual, not punitive. No "you failed", no red screen, no shame.
            val id = "higher.${result.baseline.name.lowercase()}"
            val baselineText = when (result.baseline) {
                SavingBaseline.YESTERDAY -> "yesterday"
                SavingBaseline.SEVEN_DAY_AVERAGE -> "your seven-day average"
            }
            EncouragementMessage(id, "You're ${result.minutes} minutes above $baselineText by this time.")
        }

        is TimeSavedResult.Level -> EncouragementMessage(
            "level.${result.baseline.name.lowercase()}",
            "Your screen time is level with your usual by this time.",
        )

        // Nothing honest to say yet. Say nothing.
        TimeSavedResult.InsufficientData -> null
    }

    fun instagramWithinPlan(tone: MessageTone): EncouragementMessage = EncouragementMessage(
        "instagram.within_plan.${tone.name.lowercase()}",
        when (tone) {
            MessageTone.MINIMAL -> "Instagram: inside your plan today."
            MessageTone.DIRECT -> "You stayed inside your Instagram plan today."
            MessageTone.CALM -> "You stayed inside your Instagram plan today. Quiet win."
        },
    )

    fun instagramRemaining(minutes: Long): EncouragementMessage = EncouragementMessage(
        "instagram.remaining",
        "$minutes ${if (minutes == 1L) "minute remains" else "minutes remain"} in your Instagram plan today.",
    )

    fun goalRemaining(minutes: Long): EncouragementMessage = EncouragementMessage(
        "goal.remaining",
        "$minutes ${if (minutes == 1L) "minute remains" else "minutes remain"} in your screen-time goal.",
    )

    fun protectionsIntact(): EncouragementMessage = EncouragementMessage(
        "protection.all_active",
        "You've kept every protection active today.",
    )

    /**
     * Blocked openings are reported as a plain count. We do not translate them into minutes saved,
     * because we have no way to know how long a scroll that never started would have run.
     */
    fun blockedCount(count: Int): EncouragementMessage? {
        if (count <= 0) return null
        return EncouragementMessage(
            "blocked.count",
            if (count == 1) {
                "One distracting opening was handled. You kept moving."
            } else {
                "$count distracting openings were handled. You kept moving."
            },
        )
    }

    fun percentBelowAverage(percent: Int): EncouragementMessage? {
        if (percent <= 0) return null
        return EncouragementMessage(
            "screen_time.percent_below_average",
            "Your screen time is $percent% lower than your seven-day average.",
        )
    }

    /**
     * Picks the best message that has not been used in the last seven days, falling back to the
     * highest-value one if every candidate is recent. Returning null is normal and means the evening
     * summary carries the numbers on its own.
     */
    fun selectRotating(
        candidates: List<EncouragementMessage>,
        templateHistory: Map<String, Long>,
        nowMillis: Long,
    ): EncouragementMessage? {
        if (candidates.isEmpty()) return null
        val fresh = candidates.firstOrNull { candidate ->
            val last = templateHistory[candidate.templateId] ?: return@firstOrNull true
            nowMillis - last >= QuotaConfig.TEMPLATE_REPEAT_WINDOW_MILLIS
        }
        return fresh ?: candidates.first()
    }
}
