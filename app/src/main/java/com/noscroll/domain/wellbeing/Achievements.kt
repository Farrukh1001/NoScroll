package com.noscroll.domain.wellbeing

import java.time.LocalDate

/**
 * A quiet, local acknowledgement of something the data actually shows.
 *
 * Deliberately not a game. There are no points, no streak that punishes you for breaking it, no
 * leaderboard, and no infinite feed of badges to chase. Each achievement is a statement of fact
 * with the number that backs it, and it disappears again when it is no longer true.
 */
data class Achievement(
    val id: String,
    val title: String,
    val detail: String,
)

/**
 * Works out which achievements currently hold.
 *
 * Every one of these requires real history, and the calculator returns an empty list rather than a
 * consolation prize when there is not enough. A fresh install earns nothing, which is correct: the
 * first day is not an accomplishment, it is a Tuesday.
 */
class AchievementCalculator {

    fun evaluate(
        aggregate: UsageAggregate,
        today: LocalDate,
        dailyGoalMinutes: Int,
        instagramSecondsByDay: Map<LocalDate, Long> = emptyMap(),
        instagramGoalMinutes: Int = 30,
    ): List<Achievement> {
        val goalMillis = dailyGoalMinutes * 60_000L
        val history = (1..30)
            .map { today.minusDays(it.toLong()) }
            .filter { aggregate.perDayHourly.containsKey(it) }

        if (history.isEmpty()) return emptyList()

        val achievements = mutableListOf<Achievement>()

        // Days inside the screen-time goal, counted over completed days only — today is still in
        // progress and cannot honestly be called a win yet.
        val withinGoal = history.count { aggregate.totalFor(it) <= goalMillis }
        if (withinGoal > 0) {
            achievements += Achievement(
                id = "days.within_goal",
                title = "$withinGoal ${if (withinGoal == 1) "day" else "days"} inside your goal",
                detail = "Out of the last ${history.size} ${if (history.size == 1) "day" else "days"} with data.",
            )
        }

        // A run of consecutive completed days inside the goal, most recent first.
        val consecutive = history
            .sortedDescending()
            .takeWhile { aggregate.totalFor(it) <= goalMillis }
            .size
        when {
            consecutive >= 7 -> achievements += Achievement(
                "consistency.seven",
                "Seven days running",
                "A full week inside your screen-time goal.",
            )
            consecutive >= 5 -> achievements += Achievement(
                "consistency.five",
                "Five days running",
                "Five consecutive days inside your goal.",
            )
        }

        // A personal best, but only against a baseline long enough to mean something.
        if (history.size >= 7) {
            val lowest = history.minByOrNull { aggregate.totalFor(it) }
            val lowestMillis = lowest?.let { aggregate.totalFor(it) } ?: 0L
            val yesterday = today.minusDays(1)
            if (lowest == yesterday && lowestMillis > 0) {
                achievements += Achievement(
                    "personal_best.low_day",
                    "Your lowest day yet",
                    "${lowestMillis / 60_000} minutes — the least of the last ${history.size} days.",
                )
            }
        }

        // Reduction against the user's own earlier baseline, comparing two full weeks.
        if (history.size >= 14) {
            val recent = (1..7).map { today.minusDays(it.toLong()) }
                .filter { aggregate.perDayHourly.containsKey(it) }
            val earlier = (8..14).map { today.minusDays(it.toLong()) }
                .filter { aggregate.perDayHourly.containsKey(it) }

            if (recent.isNotEmpty() && earlier.isNotEmpty()) {
                val recentAverage = recent.sumOf { aggregate.totalFor(it) } / recent.size
                val earlierAverage = earlier.sumOf { aggregate.totalFor(it) } / earlier.size
                if (earlierAverage > 0 && recentAverage < earlierAverage) {
                    val percent = ((earlierAverage - recentAverage) * 100 / earlierAverage).toInt()
                    if (percent >= 5) {
                        achievements += Achievement(
                            "reduction.vs_baseline",
                            "$percent% below your earlier baseline",
                            "This week's average against the week before.",
                        )
                    }
                }
            }
        }

        // Days inside the Instagram plan. Only counted where a figure was actually recorded.
        if (instagramSecondsByDay.isNotEmpty()) {
            val instagramGoalSeconds = instagramGoalMinutes * 60L
            val withinPlan = instagramSecondsByDay
                .filterKeys { it != today }
                .count { it.value <= instagramGoalSeconds }
            if (withinPlan > 0) {
                achievements += Achievement(
                    "instagram.days_within_plan",
                    "$withinPlan ${if (withinPlan == 1) "day" else "days"} inside your Instagram plan",
                    "Measured against your ${instagramGoalMinutes}-minute daily budget.",
                )
            }
        }

        return achievements
    }
}
