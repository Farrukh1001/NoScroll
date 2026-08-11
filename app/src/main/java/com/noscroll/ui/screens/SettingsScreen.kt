package com.noscroll.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noscroll.BuildConfig
import com.noscroll.domain.notify.MessageTone
import com.noscroll.domain.protection.RequestOutcome
import com.noscroll.domain.protection.SettingKey
import com.noscroll.ui.NoScrollUiState
import com.noscroll.ui.NoScrollViewModel
import com.noscroll.ui.components.SectionCard

/**
 * Settings, split along one clear line.
 *
 * Anything that weakens protection lives behind the waiting period. Anything about the user's own
 * data — notifications, retention, deleting history — happens immediately, because making somebody
 * wait a day to turn off a notification or delete their own records would be a dark pattern wearing
 * a protection costume.
 */
@Composable
fun SettingsScreen(viewModel: NoScrollViewModel, state: NoScrollUiState) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        message?.let { text ->
            item { SectionCard { Text(text, style = MaterialTheme.typography.bodyMedium) } }
        }

        state.pending?.let { pending ->
            item {
                SectionCard(title = "Change waiting to apply") {
                    pending.entries.forEach { entry ->
                        Text(entry.key.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Now: ${entry.current.display()}   Requested: ${entry.requested.display()}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            entry.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("Applies in ${formatRemaining(state.pendingRemainingMillis)}.")
                    Text(
                        "It can be cancelled, but not sped up.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { viewModel.cancelPending() }) { Text("Cancel this change") }
                }
            }
        }

        item {
            SectionCard(title = "Waiting period") {
                val currentHours = state.protection.int(SettingKey.WEAKENING_DELAY_HOURS)
                Text("Currently $currentHours hours.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Shortening it waits for the current period, not the new one. It can be changed " +
                        "once a day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingKey.DELAY_PRESET_HOURS.forEach { hours ->
                        FilterChip(
                            selected = hours == currentHours,
                            onClick = {
                                viewModel.setInt(SettingKey.WEAKENING_DELAY_HOURS, hours) { outcome ->
                                    message = when (outcome) {
                                        is RequestOutcome.Applied -> "Applied."
                                        is RequestOutcome.Queued -> "Queued — it applies after the wait."
                                        is RequestOutcome.Rejected -> viewModel.rejectionMessage(outcome.reason)
                                    }
                                }
                            },
                            label = { Text("${hours}h") },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Permissions") {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Accessibility settings") }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Usage Access") }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("VPN settings — always-on and Block connections without VPN") }
                Text(
                    "Turning on always-on VPN with \"Block connections without VPN\" is what stops the " +
                        "filter being bypassed by simply switching it off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Notifications") {
                ToggleRow("Allow notifications", state.preferences.notificationsEnabled) {
                    viewModel.setNotificationsEnabled(it)
                }
                Text(
                    "Daily maximum: ${state.preferences.notificationDailyLimit} " +
                        "(the hard ceiling is 3, whatever else is set).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..3).forEach { limit ->
                        FilterChip(
                            selected = limit == state.preferences.notificationDailyLimit,
                            onClick = { viewModel.setNotificationLimit(limit) },
                            label = { Text("$limit") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("Evening summary", state.preferences.eveningReminderEnabled) {
                    viewModel.setEveningReminder(
                        it,
                        state.preferences.eveningReminderHour,
                        state.preferences.eveningReminderMinute,
                    )
                }
                Text(
                    "Delivered around %02d:%02d. NoScroll uses battery-friendly scheduling and does not "
                        .format(state.preferences.eveningReminderHour, state.preferences.eveningReminderMinute) +
                        "request alarm permissions, so Android may shift it by a few minutes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Message tone") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MessageTone.entries.forEach { tone ->
                        FilterChip(
                            selected = tone == state.preferences.tone,
                            onClick = { viewModel.setTone(tone) },
                            label = { Text(tone.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Your data") {
                ToggleRow("Screen-time analytics", state.preferences.analyticsEnabled) {
                    viewModel.setAnalyticsEnabled(it)
                }
                Text(
                    "Optional, local, and never uploaded. Turning it off takes effect immediately.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Keep history for ${state.preferences.retentionMonths} months")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 6, 12).forEach { months ->
                        FilterChip(
                            selected = months == state.preferences.retentionMonths,
                            onClick = { viewModel.setRetentionMonths(months) },
                            label = { Text("${months}m") },
                        )
                    }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete screen-time history") }
                Text(
                    "Deleting history does not reset today's Instagram budget or any change that is " +
                        "waiting to apply.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Diagnostics") {
                Text("Accessibility: ${if (state.health.accessibilityConnected) "connected" else "not running"}")
                Text("Filter: ${state.health.filterStatus.name.lowercase()}")
                Text("VPN consent: ${if (state.health.vpnPrepared) "granted" else "not granted"}")
                Text("Usage Access: ${if (state.health.usageAccessGranted) "granted" else "not granted"}")
                Text("Notifications: ${if (state.health.notificationsAllowed) "allowed" else "blocked"}")
                Text("YouTube Music: ${state.health.youTubeMusicStatus.name.lowercase()}")
                Text("Rule set: ${state.health.ruleSetVersion}")
                Text("Last error category: ${state.health.lastErrorCategory ?: "none"}")
                Spacer(Modifier.height(8.dp))
                Text(
                    "This report contains no usernames, messages, titles, searches, domains or raw " +
                        "accessibility text.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "About") {
                Text("NoScroll ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text("GPL-3.0-only. No accounts, no ads, no telemetry, no backend.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "NoScroll does not block Android Settings and does not prevent its own uninstall. " +
                        "The waiting periods apply to changes made inside the app, not to Android's " +
                        "Uninstall button.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete screen-time history?") },
            text = {
                Column {
                    Text("This removes all stored daily totals from this device. It cannot be undone.")
                    Spacer(Modifier.height(8.dp))
                    Text("Your Instagram budget and any pending protected change are not affected.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteWellbeingData { message = "Screen-time history deleted." }
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
        )
    }
}
