package com.noscroll.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noscroll.NoScrollApplication
import com.noscroll.domain.surface.Packages

/**
 * Keeps the YouTube Music status honest.
 *
 * Whether that package is missing, disabled, or installed and enabled is the difference between
 * "fully protected" and "running on accessibility fallback", and the dashboard says which. Rather
 * than re-querying on every screen, we re-check when the package state actually changes.
 *
 * Only events for the packages NoScroll protects are acted on; everything else is ignored without
 * being inspected.
 */
class PackageStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // These are protected broadcasts, so only the system can send them — but checking the action
        // anyway means a spoofed intent with a missing or unexpected action does nothing.
        if (intent.action !in HANDLED_ACTIONS) return

        val changed = intent.data?.schemeSpecificPart ?: return
        if (changed != Packages.YOUTUBE_MUSIC && changed !in Packages.WATCHED) return

        val container = (context.applicationContext as? NoScrollApplication)?.container ?: return
        container.refreshHealth()
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED,
        )
    }
}
