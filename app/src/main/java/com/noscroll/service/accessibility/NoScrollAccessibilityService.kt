package com.noscroll.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import com.noscroll.NoScrollApplication
import com.noscroll.di.AppContainer
import com.noscroll.domain.surface.Packages
import com.noscroll.service.accessibility.guard.InstagramGuard
import com.noscroll.service.accessibility.guard.ShieldGuard
import com.noscroll.service.accessibility.guard.YouTubeGuard
import com.noscroll.service.accessibility.guard.YouTubeMusicGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The one accessibility service in the app; every module hangs off it.
 *
 * Scope and restraint are the whole design here:
 *  - The service manifest limits delivery to the protected packages, so no event from any other app
 *    ever reaches this process.
 *  - Events are throttled, and the expensive tree walk only runs when something might have changed.
 *  - Nothing read from the tree is stored. A snapshot is taken, a decision is made, it is dropped.
 *  - It never blocks Android Settings, never covers the uninstall dialog, and never tries to make
 *    itself hard to switch off. That is a deliberate product decision, not an oversight — see
 *    THREAT_MODEL.md.
 */
class NoScrollAccessibilityService : AccessibilityService() {

    private lateinit var container: AppContainer
    private lateinit var overlays: OverlayController

    private lateinit var youTubeGuard: YouTubeGuard
    private lateinit var youTubeMusicGuard: YouTubeMusicGuard
    private lateinit var instagramGuard: InstagramGuard
    private lateinit var shieldGuard: ShieldGuard

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var lastEvaluationMillis = 0L
    private var currentPackage: String? = null

    @Volatile
    private var screenInteractive = true

    /**
     * Locking the screen has to stop the Instagram clock immediately, not at the next event — no
     * event arrives once the screen is off.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    instagramGuard.onScreenOff()
                    overlays.removeAll()
                }
                Intent.ACTION_SCREEN_ON -> screenInteractive = true
                Intent.ACTION_USER_PRESENT -> screenInteractive = true
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        container = (application as NoScrollApplication).container
        overlays = OverlayController(this)

        youTubeGuard = YouTubeGuard(this, overlays, container, scope)
        youTubeMusicGuard = YouTubeMusicGuard(this, overlays, container, scope)
        instagramGuard = InstagramGuard(this, overlays, container, scope)
        shieldGuard = ShieldGuard(this, overlays, container, scope)

        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        screenInteractive = getSystemService(PowerManager::class.java)?.isInteractive ?: true

        container.protectionHealth.setAccessibilityConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName !in Packages.WATCHED) {
            onLeftProtectedApp()
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        // A window change always gets looked at; content churn is throttled so scrolling a feed does
        // not turn into a tree walk on every frame.
        if (!isWindowChange && now - lastEvaluationMillis < CONTENT_THROTTLE_MILLIS) return
        lastEvaluationMillis = now

        if (currentPackage != packageName) {
            onPackageChanged(from = currentPackage, to = packageName)
            currentPackage = packageName
        }

        val root = runCatching { rootInActiveWindow }.getOrNull()

        when {
            packageName == Packages.YOUTUBE -> {
                val snapshot = NodeSnapshot.capture(root) ?: return
                youTubeGuard.evaluate(snapshot)
            }

            packageName == Packages.YOUTUBE_MUSIC -> youTubeMusicGuard.onForeground()

            packageName == Packages.INSTAGRAM -> {
                val snapshot = NodeSnapshot.capture(root) ?: return
                instagramGuard.evaluate(snapshot, screenInteractive)
            }

            packageName in Packages.SUPPORTED_BROWSERS -> {
                // Only the address bar is read, never the page content.
                shieldGuard.evaluate(packageName, NodeSnapshot.readAddressBar(root))
            }
        }
    }

    private fun onPackageChanged(from: String?, to: String) {
        // Overlays belong to the app they were raised over; carrying one across would put a blocker
        // on top of an innocent screen.
        overlays.removeAll()
        if (from == Packages.INSTAGRAM) instagramGuard.onLeftInstagram()
        if (from == Packages.YOUTUBE_MUSIC && to != Packages.YOUTUBE_MUSIC) youTubeMusicGuard.onLeft()
    }

    private fun onLeftProtectedApp() {
        if (currentPackage != null) {
            overlays.removeAll()
            instagramGuard.onLeftInstagram()
            youTubeMusicGuard.onLeft()
            currentPackage = null
        }
    }

    override fun onInterrupt() {
        overlays.removeAll()
        instagramGuard.flush()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        runCatching { unregisterReceiver(screenReceiver) }
        if (::instagramGuard.isInitialized) instagramGuard.flush()
        if (::overlays.isInitialized) overlays.removeAll()
        if (::container.isInitialized) container.protectionHealth.setAccessibilityConnected(false)
        scope.cancel()
    }

    /** Performs a single Back press. Guards rate-limit their own use of this. */
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun launchIntent(intent: Intent) {
        scope.launch(Dispatchers.Main) {
            runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    companion object {
        /** Feeds fire content-changed events continuously; this keeps the tree walk affordable. */
        const val CONTENT_THROTTLE_MILLIS = 250L
    }
}
