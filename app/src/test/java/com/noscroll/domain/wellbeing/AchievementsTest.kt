package com.noscroll.domain.wellbeing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Achievements must only ever state something the data supports, and must say nothing at all when
 * there is not enough history. Most of these tests are about the second half of that.
 */
class AchievementsTest {

    private val calculator = AchievementCalculator()
    private val today = LocalDate.of(2026, 8, 11)
    private val goalMinutes = 180

    /** Builds an aggregate where each of the last [days] days used [minutesPerDay]. */
    private fun aggregateOf(days: Int, minutesPerDay: Long, exceptions: Map<Int, Long> = emptyMap()): UsageAggregate {
        val perDayHourly = mutableMapOf<LocalDate, List<Long>>()
        val perDayPerPackage = mutableMapOf<LocalDate, Map<String, Long>>()
        (1..days).forEach { ago ->
            val date = today.minusDays(ago.toLong())
            val minutes = exceptions[ago] ?: minutesPerDay
            val millis = minutes * 60_000L
            perDayHourly[date] = List(24) { hour -> if (hour == 12) millis else 0L }
            perDayPerPackage[date] = mapOf("app" to millis)
        }
        return UsageAggregate(perDayPerPackage, perDayHourly)
    }

    @Test
    fun aFreshInstallEarnsNothing() {
        val empty = UsageAggregate()
        assertTrue(calculator.evaluate(empty, today, goalMinutes).isEmpty())
    }

    @Test
    fun daysInsideTheGoalAreCounted() {
        // Five days at 120 minutes, all inside a 180-minute goal.
        val achievements = calculator.evaluate(aggregateOf(5, 120), today, goalMinutes)
        val within = achievements.single { it.id == "days.within_goal" }
        assertTrue(within.title.contains("5 days"))
    }

    @Test
    fun daysOverTheGoalAreNotCounted() {
        val achievements = calculator.evaluate(aggregateOf(5, 240), today, goalMinutes)
        assertFalse(achievements.any { it.id == "days.within_goal" })
    }

    @Test
    fun sevenConsecutiveDaysEarnsTheWeeklyAchievement() {
        val achievements = calculator.evaluate(aggregateOf(7, 100), today, goalMinutes)
        assertTrue(achievements.any { it.id == "consistency.seven" })
        assertFalse("only the higher one is awarded", achievements.any { it.id == "consistency.five" })
    }

    @Test
    fun fiveConsecutiveDaysEarnsTheSmallerOne() {
        // Six days of data, but the sixth broke the goal, so the run is five.
        val achievements = calculator.evaluate(
            aggregateOf(6, 100, exceptions = mapOf(6 to 400L)),
            today,
            goalMinutes,
        )
        assertTrue(achievements.any { it.id == "consistency.five" })
        assertFalse(achievements.any { it.id == "consistency.seven" })
    }

    @Test
    fun aBrokenRunEndsTheStreak() {
        // Yesterday was over the goal, so there is no run at all.
        val achievements = calculator.evaluate(
            aggregateOf(10, 100, exceptions = mapOf(1 to 400L)),
            today,
            goalMinutes,
        )
        assertFalse(achievements.any { it.id.startsWith("consistency") })
    }

    @Test
    fun aPersonalBestNeedsAWeekOfBaseline() {
        // Three days is not enough history to call anything a personal best.
        val short = calculator.evaluate(aggregateOf(3, 100, exceptions = mapOf(1 to 10L)), today, goalMinutes)
        assertFalse(short.any { it.id == "personal_best.low_day" })

        val long = calculator.evaluate(aggregateOf(8, 100, exceptions = mapOf(1 to 10L)), today, goalMinutes)
        assertTrue(long.any { it.id == "personal_best.low_day" })
    }

    @Test
    fun aReductionIsMeasuredAgainstTwoFullWeeks() {
        // Recent week averages 60 minutes; the week before averaged 120.
        val exceptions = (1..7).associateWith { 60L }
        val achievements = calculator.evaluate(
            aggregateOf(14, 120, exceptions = exceptions),
            today,
            goalMinutes,
        )
        val reduction = achievements.single { it.id == "reduction.vs_baseline" }
        assertTrue("should report roughly 50%", reduction.title.contains("50%"))
    }

    @Test
    fun aReductionNeedsTwoWeeksOfData() {
        val exceptions = (1..7).associateWith { 60L }
        val achievements = calculator.evaluate(
            aggregateOf(9, 120, exceptions = exceptions),
            today,
            goalMinutes,
        )
        assertFalse(achievements.any { it.id == "reduction.vs_baseline" })
    }

    @Test
    fun anIncreaseIsNeverDressedUpAsAReduction() {
        // Recent week is worse than the week before. Nothing is claimed.
        val exceptions = (1..7).associateWith { 200L }
        val achievements = calculator.evaluate(
            aggregateOf(14, 100, exceptions = exceptions),
            today,
            goalMinutes,
        )
        assertFalse(achievements.any { it.id == "reduction.vs_baseline" })
    }

    @Test
    fun aTrivialReductionIsNotCelebrated() {
        // A 2% difference is noise, not an accomplishment.
        val exceptions = (1..7).associateWith { 98L }
        val achievements = calculator.evaluate(
            aggregateOf(14, 100, exceptions = exceptions),
            today,
            goalMinutes,
        )
        assertFalse(achievements.any { it.id == "reduction.vs_baseline" })
    }

    @Test
    fun todayIsNeverCountedAsACompletedDay() {
        // Today is still in progress; only completed days can be judged.
        val aggregate = aggregateOf(5, 100)
        val achievements = calculator.evaluate(aggregate, today, goalMinutes)
        val within = achievements.single { it.id == "days.within_goal" }
        assertTrue("counts the five completed days, not six", within.title.contains("5 days"))
    }

    @Test
    fun instagramDaysAreCountedWhenRecorded() {
        val instagram = (1..4).associate { today.minusDays(it.toLong()) to 20L * 60 }
        val achievements = calculator.evaluate(
            aggregateOf(5, 100),
            today,
            goalMinutes,
            instagramSecondsByDay = instagram,
            instagramGoalMinutes = 30,
        )
        val plan = achievements.single { it.id == "instagram.days_within_plan" }
        assertTrue(plan.title.contains("4 days"))
    }

    @Test
    fun everyAchievementCarriesAnIdTitleAndDetail() {
        calculator.evaluate(aggregateOf(14, 60), today, goalMinutes).forEach { achievement ->
            assertTrue(achievement.id.isNotBlank())
            assertTrue(achievement.title.isNotBlank())
            assertTrue(achievement.detail.isNotBlank())
            // No gamified filler.
            assertFalse(achievement.title.contains("!"))
        }
    }
}
