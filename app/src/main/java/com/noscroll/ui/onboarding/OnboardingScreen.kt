package com.noscroll.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noscroll.data.ProtectionPreset
import com.noscroll.domain.surface.Packages
import com.noscroll.service.vpn.NoScrollVpnService
import com.noscroll.ui.NoScrollUiState
import com.noscroll.ui.NoScrollViewModel
import com.noscroll.ui.components.SectionCard
import com.noscroll.ui.components.StatusRow

/**
 * Setup, one honest step at a time.
 *
 * Two rules shape this flow. Every permission is explained *before* the system dialog appears, in
 * plain language about what it does and what it does not do. And the final step refuses to declare
 * setup complete while essential protection is unhealthy — a green tick over a service that is not
 * running would be the single most damaging thing this app could do.
 */
@Composable
fun OnboardingScreen(viewModel: NoScrollViewModel, state: NoScrollUiState, onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            NoScrollVpnService.start(context)
        }
        viewModel.refreshWellbeing()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshWellbeing() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("NoScroll", style = MaterialTheme.typography.displaySmall)
        Text(
            "Step ${step + 1} of $TOTAL_STEPS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (step) {
            0 -> SectionCard(title = "What this does") {
                Text(
                    "NoScroll keeps Shorts and Reels out of your way, puts a shared daily budget on " +
                        "Instagram's discovery surfaces, blocks YouTube Music, and filters sexually " +
                        "explicit sites.",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "It does not block your messages, your Stories, or ordinary videos and photos. It " +
                        "has no account, no ads and no servers, and nothing it sees leaves your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            1 -> SectionCard(title = "Choose a starting point") {
                ProtectionPreset.entries.forEach { preset ->
                    TextButton(onClick = { viewModel.applyPreset(preset) }) { Text(preset.label) }
                    Text(
                        preset.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("You can change any of it later — see the next step for the catch.")
            }

            2 -> SectionCard(title = "The waiting period") {
                Text(
                    "Turning a protection ON happens immediately. Turning one OFF waits — 24 hours by " +
                        "default — and shows you exactly what will change and when.",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "You can cancel a pending change at any time, but you cannot speed it up. " +
                        "Shortening the waiting period itself waits for the old one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            3 -> SectionCard(title = "Accessibility permission") {
                Text(
                    "NoScroll needs the accessibility service to tell which screen of YouTube, " +
                        "YouTube Music or Instagram is open.",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "It only receives events from those apps and supported browsers — the service is " +
                        "restricted to that list, so it never sees your banking app or your keyboard. " +
                        "It reads which screen you are on, never what you write. Nothing it reads is " +
                        "stored or sent anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Open Accessibility settings") }
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    "Service",
                    if (state.health.accessibilityConnected) "Connected" else "Not running yet",
                    state.health.accessibilityConnected,
                )
                Text(
                    "If NoScroll was installed from an APK, Android may hide the toggle until you " +
                        "choose \"Allow restricted settings\" from the app info menu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            4 -> SectionCard(title = "Screen-time insights (optional)") {
                Text(
                    "Usage Access lets NoScroll show your screen time and honest comparisons. Every " +
                        "protection works without it, and the data never leaves this device.",
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Open Usage Access") }
                TextButton(onClick = { viewModel.setAnalyticsEnabled(true) }) { Text("Enable insights") }
                StatusRow(
                    "Usage Access",
                    if (state.health.usageAccessGranted) "Granted" else "Not granted",
                    state.health.usageAccessGranted,
                )
            }

            5 -> SectionCard(title = "Content filter") {
                Text(
                    "The Explicit Content Shield runs as a local VPN. Android shows a key icon while " +
                        "any VPN is active — that is the platform's notice, not a sign of data leaving.",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "No traffic is uploaded, no browsing history is kept, and there is no certificate " +
                        "to install. Only DNS lookups pass through it; everything else uses the normal " +
                        "network path untouched.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    val prepare = VpnService.prepare(context)
                    if (prepare == null) NoScrollVpnService.start(context) else vpnLauncher.launch(prepare)
                }) { Text("Set up the filter") }
                StatusRow(
                    "VPN consent",
                    if (state.health.vpnPrepared) "Granted" else "Not granted",
                    state.health.vpnPrepared,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "For stronger protection, turn on always-on VPN and \"Block connections without " +
                        "VPN\" in Android's VPN settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Open VPN settings") }
            }

            6 -> SectionCard(title = "Background permission") {
                Text(
                    "Some manufacturers stop background apps aggressively. If protection keeps " +
                        "stopping, exempt NoScroll from battery optimisation in Android's settings.",
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Open battery settings") }
            }

            7 -> SectionCard(title = "YouTube Music") {
                Text(
                    "An ordinary app cannot disable another app for you. NoScroll can block YouTube " +
                        "Music while it is on screen, but disabling or uninstalling it is genuinely " +
                        "stronger — it also stops background and Bluetooth playback.",
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", Packages.YOUTUBE_MUSIC, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }) { Text("Open YouTube Music app info") }
                StatusRow(
                    "YouTube Music",
                    state.health.youTubeMusicStatus.name.lowercase().replace('_', ' '),
                    state.health.youTubeMusicStatus.isFullyProtected,
                )
            }

            8 -> SectionCard(title = "Notifications") {
                Text(
                    "At most three notifications a day, two by default, and you can set it to zero. " +
                        "The evening summary defaults to 10:00 PM.",
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text("Allow notifications") }
                Text(
                    "NoScroll does not use alarms and does not request alarm permissions, so delivery " +
                        "is best-effort around the time you choose.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> SectionCard(title = "Protection check") {
                StatusRow(
                    "Accessibility service",
                    if (state.health.accessibilityConnected) "Connected" else "Not running",
                    state.health.accessibilityConnected,
                )
                StatusRow(
                    "Content filter",
                    if (state.health.vpnPrepared) "Ready" else "Not set up",
                    state.health.vpnPrepared,
                )
                Spacer(Modifier.height(8.dp))
                if (state.health.accessibilityConnected) {
                    Text("Everything essential is running.")
                } else {
                    // Setup is never marked complete over a service that is not running.
                    Text(
                        "The accessibility service is not running, so NoScroll cannot protect " +
                            "anything yet. Go back and switch it on.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth()) {
            if (step < TOTAL_STEPS - 1) {
                Button(onClick = { step++ }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            } else {
                Button(
                    onClick = onFinish,
                    enabled = state.health.accessibilityConnected,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Finish") }
            }
            if (step > 0) {
                TextButton(onClick = { step-- }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
        }
    }
}

private const val TOTAL_STEPS = 10
