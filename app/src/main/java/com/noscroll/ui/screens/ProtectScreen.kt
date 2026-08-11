package com.noscroll.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noscroll.domain.health.YouTubeMusicStatus
import com.noscroll.domain.protection.RequestOutcome
import com.noscroll.domain.protection.SettingKey
import com.noscroll.domain.surface.Packages
import com.noscroll.ui.NoScrollUiState
import com.noscroll.ui.NoScrollViewModel
import com.noscroll.ui.components.SectionCard
import com.noscroll.ui.components.StatusRow

/**
 * Every protection, grouped by the app it protects.
 *
 * Switching something on takes effect at once. Switching something off queues it behind the waiting
 * period, and the toggle stays where it was until the wait elapses — showing it as already off would
 * be a lie about what the phone is currently doing.
 */
@Composable
fun ProtectScreen(viewModel: NoScrollViewModel, state: NoScrollUiState) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }

    fun toggle(key: SettingKey, value: Boolean) {
        viewModel.setBoolean(key, value) { outcome ->
            message = when (outcome) {
                is RequestOutcome.Applied -> null
                is RequestOutcome.Queued -> "Queued. It applies after the waiting period."
                is RequestOutcome.Rejected -> viewModel.rejectionMessage(outcome.reason)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        message?.let { text ->
            item {
                SectionCard { Text(text, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        item {
            SectionCard(title = "YouTube") {
                StatusRow(
                    "Accessibility service",
                    if (state.health.accessibilityConnected) "Connected" else "Not running",
                    state.health.accessibilityConnected,
                )
                ToggleRow("Block Shorts protections", state.protection.bool(SettingKey.YOUTUBE_MODULE)) {
                    toggle(SettingKey.YOUTUBE_MODULE, it)
                }
                ToggleRow("Mask Shorts shelves on Home", state.protection.bool(SettingKey.YOUTUBE_SHELF_MASK)) {
                    toggle(SettingKey.YOUTUBE_SHELF_MASK, it)
                }
                ToggleRow("Cover the Shorts tab button", state.protection.bool(SettingKey.YOUTUBE_TAB_MASK)) {
                    toggle(SettingKey.YOUTUBE_TAB_MASK, it)
                }
                ToggleRow("Block the Shorts viewer", state.protection.bool(SettingKey.YOUTUBE_VIEWER_BLOCK)) {
                    toggle(SettingKey.YOUTUBE_VIEWER_BLOCK, it)
                }
                ToggleRow("Open shared Shorts as one video", state.protection.bool(SettingKey.YOUTUBE_EXTERNAL_LINKS)) {
                    toggle(SettingKey.YOUTUBE_EXTERNAL_LINKS, it)
                }
                Text(
                    "Normal YouTube videos are never affected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "YouTube Music") {
                StatusRow(
                    "Status",
                    when (state.health.youTubeMusicStatus) {
                        YouTubeMusicStatus.NOT_INSTALLED -> "Not installed"
                        YouTubeMusicStatus.DISABLED -> "Disabled"
                        YouTubeMusicStatus.ENABLED_FALLBACK -> "Enabled — fallback blocking active"
                        YouTubeMusicStatus.UNKNOWN -> "Unable to verify status"
                    },
                    state.health.youTubeMusicStatus.isFullyProtected,
                )
                ToggleRow("YouTube Music lockdown", state.protection.bool(SettingKey.YOUTUBE_MUSIC_MODULE)) {
                    toggle(SettingKey.YOUTUBE_MUSIC_MODULE, it)
                }
                Text(
                    "Disabling or uninstalling the app is stronger than accessibility blocking — it " +
                        "also stops background and Bluetooth playback, which a foreground blocker cannot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.health.youTubeMusicStatus.isFullyProtected) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", Packages.YOUTUBE_MUSIC, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }) { Text("Open App Info to disable it") }
                }
            }
        }

        item {
            SectionCard(title = "Instagram") {
                ToggleRow("Instagram protection", state.protection.bool(SettingKey.INSTAGRAM_MODULE)) {
                    toggle(SettingKey.INSTAGRAM_MODULE, it)
                }
                ToggleRow("Shared daily budget", state.protection.bool(SettingKey.INSTAGRAM_SHARED_BUDGET)) {
                    toggle(SettingKey.INSTAGRAM_SHARED_BUDGET, it)
                }
                ToggleRow("Count Reels", state.protection.bool(SettingKey.INSTAGRAM_REELS_TIMER)) {
                    toggle(SettingKey.INSTAGRAM_REELS_TIMER, it)
                }
                ToggleRow("Count Explore and Search", state.protection.bool(SettingKey.INSTAGRAM_EXPLORE_TIMER)) {
                    toggle(SettingKey.INSTAGRAM_EXPLORE_TIMER, it)
                }
                ToggleRow("Count Saved and Collections", state.protection.bool(SettingKey.INSTAGRAM_SAVED_TIMER)) {
                    toggle(SettingKey.INSTAGRAM_SAVED_TIMER, it)
                }
                ToggleRow("Mask Reels in the Home feed", state.protection.bool(SettingKey.INSTAGRAM_HOME_REEL_MASK)) {
                    toggle(SettingKey.INSTAGRAM_HOME_REEL_MASK, it)
                }
                ToggleRow("Friend-sent Reels one at a time", state.protection.bool(SettingKey.INSTAGRAM_FRIEND_SENT_SINGLE)) {
                    toggle(SettingKey.INSTAGRAM_FRIEND_SENT_SINGLE, it)
                }
                ToggleRow("Emergency extra time", state.protection.bool(SettingKey.INSTAGRAM_EMERGENCY_ENABLED)) {
                    toggle(SettingKey.INSTAGRAM_EMERGENCY_ENABLED, it)
                }
                ToggleRow("Remaining-time banner in Collections", state.protection.bool(SettingKey.INSTAGRAM_REMAINING_BANNER)) {
                    toggle(SettingKey.INSTAGRAM_REMAINING_BANNER, it)
                }

                Spacer(Modifier.height(16.dp))
                val currentLimit = state.protection.int(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES)
                var sliderValue by remember(currentLimit) { mutableStateOf(currentLimit.toFloat()) }
                Text("Daily budget: ${sliderValue.toInt()} minutes", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        viewModel.setInt(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES, sliderValue.toInt()) { outcome ->
                            message = when (outcome) {
                                is RequestOutcome.Applied -> "Lower limit applied straight away."
                                is RequestOutcome.Queued -> "A higher limit waits for the protected period, then starts tomorrow."
                                is RequestOutcome.Rejected -> viewModel.rejectionMessage(outcome.reason)
                            }
                        }
                    },
                    valueRange = 0f..180f,
                )
                Text(
                    "DMs and Stories are never counted and never blocked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Explicit Content Shield") {
                StatusRow(
                    "Filter",
                    when (state.health.filterStatus) {
                        com.noscroll.domain.health.FilterStatus.ACTIVE -> "Running"
                        com.noscroll.domain.health.FilterStatus.DOWN -> "Not running"
                        com.noscroll.domain.health.FilterStatus.DISABLED -> "Off"
                    },
                    state.health.filterStatus != com.noscroll.domain.health.FilterStatus.DOWN,
                )
                ToggleRow("Explicit Content Shield", state.protection.bool(SettingKey.SHIELD_MODULE)) {
                    toggle(SettingKey.SHIELD_MODULE, it)
                }
                ToggleRow("Enforce SafeSearch", state.protection.bool(SettingKey.SHIELD_SAFE_SEARCH)) {
                    toggle(SettingKey.SHIELD_SAFE_SEARCH, it)
                }
                ToggleRow("Block browsers when the filter is down", state.protection.bool(SettingKey.SHIELD_FAIL_CLOSED)) {
                    toggle(SettingKey.SHIELD_FAIL_CLOSED, it)
                }
                ToggleRow("Catch explicit addresses in browsers", state.protection.bool(SettingKey.SHIELD_BROWSER_GUARD)) {
                    toggle(SettingKey.SHIELD_BROWSER_GUARD, it)
                }
                Text(
                    "This blocks sexually explicit material only. Gore, violence, horror, crime and " +
                        "ordinary manga or manhwa are not targeted, and medical, educational and " +
                        "abuse-prevention resources are deliberately left reachable.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Rule set: ${state.health.ruleSetVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
