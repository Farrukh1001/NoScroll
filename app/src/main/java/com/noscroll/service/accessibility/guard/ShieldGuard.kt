package com.noscroll.service.accessibility.guard

import android.content.Intent
import androidx.core.net.toUri
import com.noscroll.di.AppContainer
import com.noscroll.domain.filter.BrowserAction
import com.noscroll.domain.filter.SafeSearchEnforcer
import com.noscroll.domain.protection.SettingKey
import com.noscroll.service.accessibility.BlockerAction
import com.noscroll.service.accessibility.BlockerConfig
import com.noscroll.service.accessibility.NoScrollAccessibilityService
import com.noscroll.service.accessibility.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The browser half of the Explicit Content Shield.
 *
 * It exists for the cases DNS filtering cannot reach: an explicit search on a search engine that
 * resolves perfectly normally, and a browser session while the tunnel is down.
 *
 * Nothing it reads is kept. The URL is evaluated and discarded — it is never written to disk, never
 * logged, and never appears in the diagnostics report. The only record kept is an anonymous counter
 * of how many times something was blocked today.
 */
class ShieldGuard(
    private val service: NoScrollAccessibilityService,
    private val overlays: OverlayController,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {

    private var lastBackMillis = 0L

    fun evaluate(browserPackage: String, url: String?) {
        scope.launch {
            val state = container.protectionRepository.current()
            if (!state.bool(SettingKey.SHIELD_MODULE)) {
                overlays.remove(OverlayController.ID_EXPLICIT_BLOCK)
                overlays.remove(OverlayController.ID_BROWSER_FAIL_CLOSED)
                return@launch
            }

            // Fail closed: if the filter is supposed to be running and is not, supported browsers
            // are blocked rather than quietly left unprotected.
            if (state.bool(SettingKey.SHIELD_FAIL_CLOSED) && !container.protectionHealth.isFilterHealthy()) {
                showFailClosed()
                return@launch
            }
            overlays.remove(OverlayController.ID_BROWSER_FAIL_CLOSED)

            val ruleSet = container.ruleRepository.ruleSet()
            when (
                val action = SafeSearchEnforcer.evaluate(
                    rawUrl = url,
                    ruleSet = ruleSet,
                    safeSearchEnabled = state.bool(SettingKey.SHIELD_SAFE_SEARCH),
                    guardEnabled = state.bool(SettingKey.SHIELD_BROWSER_GUARD),
                )
            ) {
                BrowserAction.Allow -> overlays.remove(OverlayController.ID_EXPLICIT_BLOCK)
                BrowserAction.Block -> blockPage()
                is BrowserAction.RedirectToSafe -> redirect(browserPackage, action.url)
            }
        }
    }

    private fun blockPage() {
        container.protectionHealth.recordBlockedAttempt()

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBackMillis > BACK_COOLDOWN_MILLIS) {
            lastBackMillis = now
            service.goBack()
        }

        overlays.showBlocker(
            OverlayController.ID_EXPLICIT_BLOCK,
            BlockerConfig(
                title = service.getString(com.noscroll.R.string.blocker_explicit_title),
                body = "No emergency bypass exists for explicit content.",
                actions = listOf(
                    BlockerAction("Return") {
                        overlays.remove(OverlayController.ID_EXPLICIT_BLOCK)
                        // Back once more; if that does not land somewhere safe, go Home. The browser
                        // is never force-stopped and its tabs are never touched.
                        if (!service.goBack()) service.goHome()
                    }
                ),
            ),
        )
    }

    private fun showFailClosed() {
        overlays.showBlocker(
            OverlayController.ID_BROWSER_FAIL_CLOSED,
            BlockerConfig(
                title = service.getString(com.noscroll.R.string.filter_degraded_title),
                body = service.getString(com.noscroll.R.string.filter_degraded_body),
                actions = listOf(
                    BlockerAction("Open NoScroll") {
                        overlays.remove(OverlayController.ID_BROWSER_FAIL_CLOSED)
                        service.launchIntent(Intent(service, com.noscroll.ui.MainActivity::class.java))
                    }
                ),
            ),
        )
    }

    /** Re-issues the same search with SafeSearch on, in the same browser. */
    private fun redirect(browserPackage: String, safeUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, safeUrl.toUri()).setPackage(browserPackage)
        service.launchIntent(intent)
    }

    private companion object {
        const val BACK_COOLDOWN_MILLIS = 2_500L
    }
}
