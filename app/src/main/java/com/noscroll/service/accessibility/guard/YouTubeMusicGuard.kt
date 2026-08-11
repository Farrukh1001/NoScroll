package com.noscroll.service.accessibility.guard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.noscroll.di.AppContainer
import com.noscroll.domain.protection.SettingKey
import com.noscroll.domain.surface.Packages
import com.noscroll.service.accessibility.BlockerAction
import com.noscroll.service.accessibility.BlockerConfig
import com.noscroll.service.accessibility.NoScrollAccessibilityService
import com.noscroll.service.accessibility.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Blocks YouTube Music, with no override of any kind.
 *
 * The honest framing, which the app repeats to the user rather than hiding: an ordinary Android app
 * cannot silently disable another app. Accessibility-based blocking is a fallback that works while
 * the app is in the foreground and does not reliably stop background or Bluetooth playback. The
 * strong protection is to disable or uninstall the package, which is why the dashboard treats
 * "disabled or not installed" as fully protected and "installed and enabled" as fallback mode, and
 * why the blocker offers a route straight to the App Info screen.
 *
 * The Instagram emergency extension has no effect here. There is no extension, no countdown, no
 * "open anyway".
 */
class YouTubeMusicGuard(
    private val service: NoScrollAccessibilityService,
    private val overlays: OverlayController,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {

    private var lastHomeAttemptMillis = 0L

    fun onForeground() {
        scope.launch {
            val state = container.protectionRepository.current()
            if (!state.bool(SettingKey.YOUTUBE_MUSIC_MODULE)) {
                overlays.remove(OverlayController.ID_YOUTUBE_MUSIC)
                return@launch
            }

            container.protectionHealth.recordBlockedAttempt()

            overlays.showBlocker(
                OverlayController.ID_YOUTUBE_MUSIC,
                BlockerConfig(
                    title = service.getString(com.noscroll.R.string.blocker_ytm_title),
                    body = service.getString(com.noscroll.R.string.blocker_ytm_body),
                    actions = listOf(
                        BlockerAction(service.getString(com.noscroll.R.string.blocker_ytm_home)) {
                            overlays.remove(OverlayController.ID_YOUTUBE_MUSIC)
                            service.goHome()
                        },
                        BlockerAction(service.getString(com.noscroll.R.string.blocker_ytm_disable)) {
                            openAppInfo()
                        },
                    ),
                ),
            )

            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastHomeAttemptMillis > HOME_COOLDOWN_MILLIS) {
                lastHomeAttemptMillis = now
                service.goHome()
            }
            // If Home did not take, the blocker simply stays. We do not retry in a loop and we never
            // force-stop the app.
        }
    }

    /** Called once YouTube Music is no longer in the foreground. */
    fun onLeft() {
        overlays.remove(OverlayController.ID_YOUTUBE_MUSIC)
    }

    /**
     * Opens the system App Info page for YouTube Music, where the user can tap Disable or Uninstall
     * themselves. NoScroll does not automate taps inside Settings.
     */
    private fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", Packages.YOUTUBE_MUSIC, null)
        }
        service.launchIntent(intent)
    }

    private companion object {
        const val HOME_COOLDOWN_MILLIS = 4_000L
    }
}
