package com.noscroll.service.accessibility.guard

import android.content.Intent
import com.noscroll.di.AppContainer
import com.noscroll.domain.detect.InstagramDetector
import com.noscroll.domain.detect.NavigationContext
import com.noscroll.domain.detect.UiNode
import com.noscroll.domain.instagram.InstagramBudgetEngine
import com.noscroll.domain.protection.SettingKey
import com.noscroll.domain.surface.Surface
import com.noscroll.service.accessibility.BlockerAction
import com.noscroll.service.accessibility.BlockerConfig
import com.noscroll.service.accessibility.NoScrollAccessibilityService
import com.noscroll.service.accessibility.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Runs the shared Instagram budget.
 *
 * The rule that shapes everything: **messaging and Stories are never touched.** Every ambiguous
 * detection resolves in favour of not blocking, the exhaustion blocker lifts the moment a
 * non-budgeted screen is positively detected, and Instagram as a whole is never closed or blocked.
 *
 * Time accrues only while Instagram is foreground, the screen is on, a budgeted surface is
 * positively detected, and no blocker is already up. It is measured on the monotonic clock and
 * flushed to disk every few seconds and at every exit path, so the budget survives process death,
 * a reboot, or Instagram crashing without handing back free minutes.
 *
 * A note on single-item mode: NoScroll does not intercept swipe gestures. Doing so requires putting
 * the device into touch-exploration mode, which changes how every app on the phone responds to
 * touch — far too invasive for this. Instead it watches the pager's own reported position and, the
 * moment a Reel opened from a DM advances to a second one, returns the user to the conversation.
 */
class InstagramGuard(
    private val service: NoScrollAccessibilityService,
    private val overlays: OverlayController,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {

    private val engine = InstagramBudgetEngine()

    private var navigationContext = NavigationContext.NONE
    private var lastSurface: Surface = Surface.UNKNOWN

    /** Monotonic reading of the last counted tick, or null when the clock is not running. */
    private var sessionAnchorMillis: Long? = null

    /** Seconds counted but not yet written to disk. */
    private var unflushedSeconds = 0L
    private var lastFlushMillis = 0L

    /** The pager page a friend-sent Reel was opened on. */
    private var dmReelBaselineIndex: Int? = null

    fun evaluate(root: UiNode, screenInteractive: Boolean) {
        val detection = InstagramDetector.detect(root, navigationContext)
        val surface = detection.surface

        updateNavigationContext(surface)

        scope.launch {
            val state = container.protectionRepository.current()
            if (!state.bool(SettingKey.INSTAGRAM_MODULE)) {
                stopClock()
                overlays.retainOnly(emptySet())
                return@launch
            }

            val limitMinutes = state.int(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES)
            val budget = container.instagramBudgetRepository.current(limitMinutes)

            // Never cover DMs or Stories, whatever else is going on.
            if (surface.isInstagramProtectedFromBlocking) {
                stopClock()
                flushIfDue(force = true)
                overlays.removeAll()
                lastSurface = surface
                return@launch
            }

            val counts = detection.isActionable &&
                surface.isInstagramBudgeted &&
                surfaceEnabled(state, surface) &&
                state.bool(SettingKey.INSTAGRAM_SHARED_BUDGET)

            if (budget.isExhausted && counts) {
                stopClock()
                flushIfDue(force = true)
                showExhaustedBlocker(state.bool(SettingKey.INSTAGRAM_EMERGENCY_ENABLED) && !budget.emergencyUsedToday)
                lastSurface = surface
                return@launch
            }

            overlays.remove(OverlayController.ID_INSTAGRAM_EXHAUSTED)

            if (counts && engine.canConsume(
                    instagramForeground = true,
                    screenOnAndUnlocked = screenInteractive,
                    budgetedSurfaceDetected = true,
                    blockingOverlayShown = overlays.isShowing(OverlayController.ID_INSTAGRAM_EXHAUSTED),
                )
            ) {
                tickClock(limitMinutes)
            } else {
                stopClock()
                flushIfDue(force = true)
            }

            handleSingleItemMode(root, surface, state.bool(SettingKey.INSTAGRAM_FRIEND_SENT_SINGLE))
            handleHomeMasking(root, surface, state.bool(SettingKey.INSTAGRAM_HOME_REEL_MASK))
            handleRemainingBanner(surface, budget.remainingSeconds, state.bool(SettingKey.INSTAGRAM_REMAINING_BANNER))

            lastSurface = surface
        }
    }

    /** Sub-switches let a user count Reels but not Explore, and so on. */
    private fun surfaceEnabled(
        state: com.noscroll.domain.protection.ProtectionState,
        surface: Surface,
    ): Boolean = when (surface) {
        Surface.INSTAGRAM_REELS_VIEWER, Surface.INSTAGRAM_REELS_TAB, Surface.INSTAGRAM_DM_REEL ->
            state.bool(SettingKey.INSTAGRAM_REELS_TIMER)
        Surface.INSTAGRAM_EXPLORE, Surface.INSTAGRAM_SEARCH_DISCOVERY ->
            state.bool(SettingKey.INSTAGRAM_EXPLORE_TIMER)
        Surface.INSTAGRAM_SAVED, Surface.INSTAGRAM_COLLECTION, Surface.INSTAGRAM_COLLECTION_ITEM ->
            state.bool(SettingKey.INSTAGRAM_SAVED_TIMER)
        else -> false
    }

    /**
     * Carries provenance across a navigation.
     *
     * Instagram opens the same viewer whether a Reel came from the Reels tab or from a friend, so
     * the surface alone cannot tell them apart — where the user was a moment ago can.
     */
    private fun updateNavigationContext(surface: Surface) {
        navigationContext = when (surface) {
            Surface.INSTAGRAM_DIRECT -> NavigationContext.FROM_DIRECT_MESSAGE
            Surface.INSTAGRAM_SAVED, Surface.INSTAGRAM_COLLECTION -> NavigationContext.FROM_SAVED_COLLECTION
            Surface.INSTAGRAM_EXPLORE -> NavigationContext.FROM_EXPLORE
            // Staying inside the opened item keeps the provenance; leaving for an unrelated screen
            // clears it.
            Surface.INSTAGRAM_DM_REEL, Surface.INSTAGRAM_COLLECTION_ITEM, Surface.INSTAGRAM_REELS_VIEWER ->
                navigationContext
            Surface.INSTAGRAM_HOME, Surface.INSTAGRAM_PROFILE, Surface.INSTAGRAM_NOTIFICATIONS,
            Surface.INSTAGRAM_SETTINGS, Surface.INSTAGRAM_CREATE, Surface.INSTAGRAM_STORIES,
            -> NavigationContext.NONE
            else -> navigationContext
        }
    }

    private fun tickClock(limitMinutes: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val anchor = sessionAnchorMillis
        if (anchor == null) {
            sessionAnchorMillis = now
            lastFlushMillis = now
            return
        }
        val delta = now - anchor
        if (delta in 1..InstagramBudgetEngine.MAX_PLAUSIBLE_SLICE_MILLIS) {
            unflushedSeconds += delta / 1000
        }
        sessionAnchorMillis = now
        flushIfDue(force = false, limitMinutes = limitMinutes)
    }

    private fun stopClock() {
        sessionAnchorMillis?.let { anchor ->
            val delta = android.os.SystemClock.elapsedRealtime() - anchor
            if (delta in 1..InstagramBudgetEngine.MAX_PLAUSIBLE_SLICE_MILLIS) {
                unflushedSeconds += delta / 1000
            }
        }
        sessionAnchorMillis = null
    }

    private fun flushIfDue(force: Boolean, limitMinutes: Int? = null) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastFlushMillis < FLUSH_INTERVAL_MILLIS) return
        lastFlushMillis = now
        val seconds = unflushedSeconds
        if (seconds <= 0) return
        unflushedSeconds = 0
        scope.launch {
            val minutes = limitMinutes
                ?: container.protectionRepository.current().int(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES)
            container.instagramBudgetRepository.addConsumedSeconds(minutes, seconds)
        }
    }

    private fun handleSingleItemMode(root: UiNode, surface: Surface, enabled: Boolean) {
        if (!enabled || surface != Surface.INSTAGRAM_DM_REEL) {
            dmReelBaselineIndex = null
            overlays.remove(OverlayController.ID_INSTAGRAM_SINGLE_ITEM)
            return
        }

        val index = InstagramDetector.reelPositionIndex(root)
        if (index == null) {
            // The pager did not report a position; do nothing rather than guess. Playback, sound and
            // Back all keep working normally.
            return
        }
        val baseline = dmReelBaselineIndex
        if (baseline == null) {
            dmReelBaselineIndex = index
            return
        }
        if (index != baseline) {
            // The user moved on to a second Reel. Send them back to the conversation.
            overlays.showBlocker(
                OverlayController.ID_INSTAGRAM_SINGLE_ITEM,
                BlockerConfig(
                    title = "One Reel at a time",
                    body = "Return to the conversation to open another Reel.",
                    actions = listOf(
                        BlockerAction("Back to messages") {
                            overlays.remove(OverlayController.ID_INSTAGRAM_SINGLE_ITEM)
                            dmReelBaselineIndex = null
                            service.goBack()
                        }
                    ),
                ),
            )
        }
    }

    private fun handleHomeMasking(root: UiNode, surface: Surface, enabled: Boolean) {
        if (!enabled || surface != Surface.INSTAGRAM_HOME) {
            overlays.remove(OverlayController.ID_INSTAGRAM_MASKS)
            return
        }
        val cards = InstagramDetector.homeFeedReelCards(root)
        if (cards.isEmpty()) {
            // No positively identified Reel module. Ordinary photos and carousels are left alone.
            overlays.remove(OverlayController.ID_INSTAGRAM_MASKS)
        } else {
            overlays.showMasks(OverlayController.ID_INSTAGRAM_MASKS, cards, "Reels hidden.")
        }
    }

    private fun handleRemainingBanner(surface: Surface, remainingSeconds: Long, enabled: Boolean) {
        val show = enabled && surface in setOf(
            Surface.INSTAGRAM_SAVED,
            Surface.INSTAGRAM_COLLECTION,
            Surface.INSTAGRAM_COLLECTION_ITEM,
        )
        if (!show) {
            overlays.remove(OverlayController.ID_INSTAGRAM_BANNER)
            return
        }
        val minutes = remainingSeconds / 60
        overlays.showBanner(
            OverlayController.ID_INSTAGRAM_BANNER,
            "${service.getString(com.noscroll.R.string.collection_timer_running)} · $minutes min left",
        )
    }

    private fun showExhaustedBlocker(extensionAvailable: Boolean) {
        val actions = mutableListOf(
            BlockerAction(service.getString(com.noscroll.R.string.blocker_instagram_return)) {
                overlays.remove(OverlayController.ID_INSTAGRAM_EXHAUSTED)
                service.goBack()
            }
        )
        if (extensionAvailable) {
            actions += BlockerAction(service.getString(com.noscroll.R.string.blocker_instagram_extra)) {
                overlays.remove(OverlayController.ID_INSTAGRAM_EXHAUSTED)
                service.launchIntent(
                    Intent(service, com.noscroll.ui.MainActivity::class.java)
                        .putExtra(com.noscroll.ui.MainActivity.EXTRA_OPEN_EXTENSION, true)
                )
            }
        }
        container.protectionHealth.recordBlockedAttempt()
        overlays.showBlocker(
            OverlayController.ID_INSTAGRAM_EXHAUSTED,
            BlockerConfig(
                title = service.getString(com.noscroll.R.string.blocker_instagram_title),
                body = service.getString(com.noscroll.R.string.blocker_instagram_body),
                actions = actions,
            ),
        )
    }

    fun onScreenOff() {
        stopClock()
        flushIfDue(force = true)
    }

    fun onLeftInstagram() {
        stopClock()
        flushIfDue(force = true)
        navigationContext = NavigationContext.NONE
        dmReelBaselineIndex = null
        lastSurface = Surface.UNKNOWN
    }

    /**
     * Last-chance flush on service teardown. Blocking is acceptable here and nowhere else: the
     * process may be about to go away, and losing the slice would hand back free minutes.
     */
    fun flush() {
        stopClock()
        val seconds = unflushedSeconds
        if (seconds <= 0) return
        unflushedSeconds = 0
        runCatching {
            runBlocking {
                val minutes = container.protectionRepository.current().int(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES)
                container.instagramBudgetRepository.addConsumedSeconds(minutes, seconds)
            }
        }
    }

    private companion object {
        const val FLUSH_INTERVAL_MILLIS = 5_000L
    }
}
