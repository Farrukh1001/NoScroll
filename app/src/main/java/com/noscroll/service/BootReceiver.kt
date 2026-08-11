package com.noscroll.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noscroll.NoScrollApplication
import com.noscroll.domain.protection.SettingKey
import com.noscroll.service.vpn.NoScrollVpnService
import com.noscroll.work.NoScrollWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Puts protection back after a reboot, an app update, or a timezone change.
 *
 * All three matter for correctness, not just convenience:
 *  - a reboot restarts the monotonic clock, so pending waiting periods must re-anchor;
 *  - an app update stops the services without the user doing anything;
 *  - a timezone change moves midnight, which moves the daily rollover and the evening reminder.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return

        val pending = goAsync()
        val app = context.applicationContext as? NoScrollApplication
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        scope.launch {
            try {
                // Re-scheduling is idempotent: WorkManager replaces the existing unique work rather
                // than stacking a second copy.
                NoScrollWorkScheduler.scheduleAll(context)

                val container = app?.container ?: return@launch
                // Reading the state applies any waiting period that elapsed while the device was off.
                val state = container.protectionRepository.current()
                container.refreshHealth()

                if (state.bool(SettingKey.SHIELD_MODULE) && container.isVpnPrepared()) {
                    // Consent is already granted, so the filter can come back without prompting.
                    NoScrollVpnService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
