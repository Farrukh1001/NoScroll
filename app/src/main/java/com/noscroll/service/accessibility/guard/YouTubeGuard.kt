package com.noscroll.service.accessibility.guard

import com.noscroll.di.AppContainer
import com.noscroll.domain.detect.UiNode
import com.noscroll.domain.detect.YouTubeDetector
import com.noscroll.domain.protection.SettingKey
import com.noscroll.service.accessibility.BlockerAction
import com.noscroll.service.accessibility.BlockerConfig
import com.noscroll.service.accessibility.NoScrollAccessibilityService
import com.noscroll.service.accessibility.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps Shorts out of YouTube while leaving ordinary YouTube completely alone.
 *
 * The escalation is deliberate and gentle at every step:
 *  1. Mask Shorts shelves and the Shorts tab button so the entry points are simply not there.
 *  2. If the viewer opens anyway, try exactly one Back press — usually the user lands back where
 *     they were and never sees a blocker at all.
 *  3. If Back did not work, show a stable blocker with a way out.
 *
 * What it never does: force-stop YouTube, kill its process, or bounce Back in a loop. A repeated
 * automated Back is how these apps end up fighting the user and corrupting the host app's
 * navigation stack, so a cooldown gates it and after that the blocker just stays put.
 */
class YouTubeGuard(
    private val service: NoScrollAccessibilityService,
    private val overlays: OverlayController,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {

    private var lastBackAttemptMillis = 0L

    fun evaluate(root: UiNode) {
        scope.launch {
            val state = container.protectionRepository.current()
            if (!state.bool(SettingKey.YOUTUBE_MODULE)) {
                overlays.remove(OverlayController.ID_SHORTS_VIEWER)
                overlays.remove(OverlayController.ID_SHORTS_MASKS)
                overlays.remove(OverlayController.ID_SHORTS_NAV)
                return@launch
            }

            if (state.bool(SettingKey.YOUTUBE_VIEWER_BLOCK) && YouTubeDetector.isShortsViewer(root)) {
                handleShortsViewer()
                return@launch
            }

            // Not in the viewer: clear the blocker and go back to masking entry points.
            overlays.remove(OverlayController.ID_SHORTS_VIEWER)

            if (state.bool(SettingKey.YOUTUBE_SHELF_MASK)) {
                val shelves = YouTubeDetector.shortsShelves(root)
                if (shelves.isEmpty()) {
                    overlays.remove(OverlayController.ID_SHORTS_MASKS)
                } else {
                    overlays.showMasks(
                        OverlayController.ID_SHORTS_MASKS,
                        shelves,
                        service.getString(com.noscroll.R.string.mask_shorts_label),
                    )
                }
            }

            if (state.bool(SettingKey.YOUTUBE_TAB_MASK)) {
                val navButton = YouTubeDetector.shortsNavButton(root)
                if (navButton == null) {
                    // Not found confidently: cover nothing rather than guess at a rectangle and
                    // cover the wrong navigation button.
                    overlays.remove(OverlayController.ID_SHORTS_NAV)
                } else {
                    overlays.showMasks(OverlayController.ID_SHORTS_NAV, listOf(navButton), "")
                }
            }
        }
    }

    private fun handleShortsViewer() {
        container.protectionHealth.recordBlockedAttempt()

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackAttemptMillis > BACK_COOLDOWN_MILLIS && !overlays.isShowing(OverlayController.ID_SHORTS_VIEWER)) {
            lastBackAttemptMillis = now
            service.goBack()
            // The blocker still goes up: if Back worked, the next event clears it; if it did not,
            // the user is not left staring at a Short while we wait for another event.
        }

        overlays.showBlocker(
            OverlayController.ID_SHORTS_VIEWER,
            BlockerConfig(
                title = service.getString(com.noscroll.R.string.blocker_shorts_title),
                actions = listOf(
                    BlockerAction(service.getString(com.noscroll.R.string.blocker_shorts_action)) {
                        overlays.remove(OverlayController.ID_SHORTS_VIEWER)
                        returnToYouTube()
                    }
                ),
            ),
        )
    }

    /**
     * Sends the user back to normal YouTube by launching its own main activity. This is an ordinary
     * launch intent — nothing is force-stopped and nothing is killed.
     */
    private fun returnToYouTube() {
        val launch = service.packageManager.getLaunchIntentForPackage(com.noscroll.domain.surface.Packages.YOUTUBE)
        if (launch != null) {
            service.launchIntent(launch)
        } else {
            service.goHome()
        }
    }

    private companion object {
        const val BACK_COOLDOWN_MILLIS = 3_000L
    }
}
