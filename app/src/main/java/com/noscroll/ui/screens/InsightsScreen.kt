package com.noscroll.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noscroll.ui.NoScrollUiState
import com.noscroll.ui.components.BarChart
import com.noscroll.ui.components.EmptyState
import com.noscroll.ui.components.SectionCard
import com.noscroll.ui.components.formatDuration
import java.time.format.DateTimeFormatter

/**
 * Trends over time.
 *
 * Every chart carries a written summary alongside it, so the data is available to a screen reader
 * and to anyone who simply reads faster than they parse bars.
 */
@Composable
fun InsightsScreen(state: NoScrollUiState) {
    val dayFormat = DateTimeFormatter.ofPattern("EEE")

    if (!state.preferences.analyticsEnabled || !state.health.usageAccessGranted) {
        EmptyState(
            title = "Insights need Usage Access",
            body = "Screen-time analytics are optional and stay on this device. Protection works " +
                "whether or not you turn them on.",
            modifier = Modifier.padding(24.dp),
        )
        return
    }

    val lastSeven = (6 downTo 0).map { state.today.minusDays(it.toLong()) }
    val sevenValues = lastSeven.map { state.aggregate.totalFor(it) }
    val sevenLabels = lastSeven.map { it.format(dayFormat) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(title = "Last seven days") {
                if (sevenValues.all { it == 0L }) {
                    EmptyState("Not enough data yet", "Come back tomorrow — a trend needs more than one day.")
                } else {
                    val average = sevenValues.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0
                    BarChart(
                        values = sevenValues,
                        labels = sevenLabels,
                        textSummary = buildString {
                            append("Daily screen time over the last seven days. ")
                            append("Average ${formatDuration(average.toLong())}. ")
                            append("Highest ${formatDuration(sevenValues.max())}, lowest ${formatDuration(sevenValues.min())}.")
                        },
                    )
                }
            }
        }

        item {
            SectionCard(title = "This week versus last") {
                val thisWeek = (0..6).sumOf { state.aggregate.totalFor(state.today.minusDays(it.toLong())) }
                val lastWeek = (7..13).sumOf { state.aggregate.totalFor(state.today.minusDays(it.toLong())) }
                if (lastWeek == 0L) {
                    EmptyState("No baseline yet", "A week-over-week comparison needs two weeks of data.")
                } else {
                    val delta = thisWeek - lastWeek
                    Text(
                        if (delta < 0) {
                            "You are ${formatDuration(-delta)} below last week."
                        } else if (delta > 0) {
                            "You are ${formatDuration(delta)} above last week."
                        } else {
                            "Level with last week."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Usage by hour") {
                val hourly = state.aggregate.perDayHourly[state.today]
                if (hourly == null || hourly.all { it == 0L }) {
                    EmptyState("Nothing recorded today", "Hourly usage appears once there is activity.")
                } else {
                    val busiest = hourly.indexOf(hourly.max())
                    BarChart(
                        values = hourly,
                        labels = listOf("00", "06", "12", "18", "23"),
                        textSummary = "Screen time by hour today. Busiest hour: $busiest:00 with " +
                            "${formatDuration(hourly.max())}.",
                    )
                }
            }
        }

        item {
            SectionCard(title = "Apps") {
                val top = state.aggregate.topApps(state.today, limit = 8)
                if (top.isEmpty()) {
                    EmptyState("No app data today", "App totals appear once the day has activity.")
                } else {
                    top.forEach { (packageName, millis) ->
                        Text(
                            "$packageName — ${formatDuration(millis)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Blocked distractions") {
                Text(
                    // Aggregate only. Never a domain, a search, or a title.
                    "${state.blockedToday} handled today.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "This is a count of times a blocked surface was opened. NoScroll does not record " +
                        "what was blocked, and does not estimate minutes from it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
