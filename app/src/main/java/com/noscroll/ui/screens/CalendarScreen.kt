package com.noscroll.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noscroll.ui.NoScrollUiState
import com.noscroll.ui.components.EmptyState
import com.noscroll.ui.components.SectionCard
import com.noscroll.ui.components.formatDuration
import java.time.LocalDate

private enum class DayStatus(val label: String, val color: Color) {
    MET("Goal met", Color(0xFF2F8F76)),
    PARTIAL("Partly met", Color(0xFF7FA9A0)),
    EXCEEDED("Over goal", Color(0xFFB8813A)),
    NO_DATA("No data", Color(0xFF9AA5A9)),
}

/**
 * A month at a glance.
 *
 * "Over goal" is amber, not red, and the wording is descriptive rather than a verdict. A day where
 * somebody used their phone more than they planned is information, not a failure to be punished
 * with a red square.
 */
@Composable
fun CalendarScreen(state: NoScrollUiState) {
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val goalMillis = state.preferences.dailyGoalMinutes * 60_000L
    val days = (29 downTo 0).map { state.today.minusDays(it.toLong()) }

    fun statusFor(date: LocalDate): DayStatus {
        val total = state.aggregate.totalFor(date)
        val hasData = state.aggregate.perDayHourly.containsKey(date)
        return when {
            !hasData -> DayStatus.NO_DATA
            total <= goalMillis -> DayStatus.MET
            total <= goalMillis * 1.25 -> DayStatus.PARTIAL
            else -> DayStatus.EXCEEDED
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        if (!state.preferences.analyticsEnabled || !state.health.usageAccessGranted) {
            EmptyState(
                "The calendar needs Usage Access",
                "Daily history is optional and stays on this device.",
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(days) { date ->
                val status = statusFor(date)
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(status.color.copy(alpha = if (status == DayStatus.NO_DATA) 0.25f else 0.85f))
                        .clickable { selected = date }
                        .semantics {
                            contentDescription = "${date}: ${status.label}"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        selected?.let { date ->
            SectionCard(title = date.toString()) {
                val total = state.aggregate.totalFor(date)
                Text("Screen time: ${formatDuration(total)}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Goal: ${statusFor(date).label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val top = state.aggregate.topApps(date, limit = 4)
                if (top.isEmpty()) {
                    Text("No app data for this day.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    top.forEach { (packageName, millis) ->
                        Text(
                            "$packageName — ${formatDuration(millis)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Never a domain, a search term, or anything the shield blocked.
                Text(
                    "NoScroll does not keep a record of what was blocked, so none appears here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } ?: SectionCard {
            Text("Tap a day to see its detail.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
