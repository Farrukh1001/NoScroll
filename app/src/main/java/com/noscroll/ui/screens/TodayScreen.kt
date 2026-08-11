package com.noscroll.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noscroll.domain.health.FilterStatus
import com.noscroll.domain.health.YouTubeMusicStatus
import com.noscroll.ui.NoScrollUiState
import com.noscroll.ui.components.BudgetBar
import com.noscroll.ui.components.EmptyState
import com.noscroll.ui.components.SectionCard
import com.noscroll.ui.components.StatBlock
import com.noscroll.ui.components.StatusRow
import com.noscroll.ui.components.formatDuration
import java.time.Duration

/**
 * The home screen.
 *
 * It leads with what is true today and says nothing it cannot back with data. When there is no
 * baseline yet, the comparison card is simply absent rather than filled with a flattering guess.
 */
@Composable
fun TodayScreen(state: NoScrollUiState, onRequestExtension: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard {
                if (!state.preferences.analyticsEnabled || !state.health.usageAccessGranted) {
                    EmptyState(
                        title = "Screen-time insights are off",
                        body = "Turn on Usage Access in Settings to see totals here. Every protection " +
                            "works without it.",
                    )
                } else {
                    StatBlock(
                        value = formatDuration(state.screenTimeTodayMillis),
                        label = "Screen time today",
                    )
                    val goalMillis = state.preferences.dailyGoalMinutes * 60_000L
                    Spacer(Modifier.height(16.dp))
                    val remaining = (goalMillis - state.screenTimeTodayMillis).coerceAtLeast(0L)
                    Text(
                        "${remaining / 60_000} minutes remain in your ${state.preferences.dailyGoalMinutes}-minute goal.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Only rendered when a real, like-for-like comparison exists.
        state.encouragement?.let { message ->
            item {
                SectionCard {
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        state.budget?.let { budget ->
            item {
                SectionCard(title = "Instagram") {
                    BudgetBar(
                        usedSeconds = budget.consumedSeconds,
                        totalSeconds = budget.totalAllowanceSeconds,
                    )
                    if (budget.isExhausted) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Today's scrolling time is finished. DMs and Stories are still available.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!budget.emergencyUsedToday) {
                            TextButton(onClick = onRequestExtension) { Text("Request extra time") }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Protection") {
                StatusRow(
                    label = "Accessibility service",
                    statusText = if (state.health.accessibilityConnected) "Connected" else "Not running",
                    ok = state.health.accessibilityConnected,
                )
                StatusRow(
                    label = "Explicit Content Shield",
                    statusText = when (state.health.filterStatus) {
                        FilterStatus.ACTIVE -> "Filtering"
                        FilterStatus.DOWN -> "Not running"
                        FilterStatus.DISABLED -> "Off"
                    },
                    ok = state.health.filterStatus != FilterStatus.DOWN,
                )
                StatusRow(
                    label = "YouTube Music",
                    statusText = when (state.health.youTubeMusicStatus) {
                        YouTubeMusicStatus.NOT_INSTALLED -> "Not installed"
                        YouTubeMusicStatus.DISABLED -> "Disabled"
                        YouTubeMusicStatus.ENABLED_FALLBACK -> "Enabled — fallback blocking"
                        YouTubeMusicStatus.UNKNOWN -> "Unable to verify"
                    },
                    ok = state.health.youTubeMusicStatus.isFullyProtected,
                )
                if (state.blockedToday > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // A count and nothing more. What was blocked is never recorded.
                        "${state.blockedToday} distracting openings were handled today.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.pending?.let { pending ->
            item {
                SectionCard(title = "Waiting to apply") {
                    pending.entries.forEach { entry ->
                        Text(entry.key.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${entry.current.display()} → ${entry.requested.display()} · ${entry.reason}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "Applies in ${formatRemaining(state.pendingRemainingMillis)}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (state.preferences.analyticsEnabled && state.health.usageAccessGranted) {
            item {
                SectionCard(title = "Most used today") {
                    val top = state.aggregate.topApps(state.today, limit = 5)
                    if (top.isEmpty()) {
                        EmptyState("No data yet", "Usage appears here once the day has some activity.")
                    } else {
                        top.forEach { (packageName, millis) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(packageName, style = MaterialTheme.typography.bodyMedium)
                                Text(formatDuration(millis), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun formatRemaining(millis: Long): String {
    if (millis <= 0) return "less than a minute"
    val duration = Duration.ofMillis(millis)
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "less than a minute"
    }
}
