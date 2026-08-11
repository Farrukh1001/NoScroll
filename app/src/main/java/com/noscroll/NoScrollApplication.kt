package com.noscroll

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.noscroll.di.AppContainer
import com.noscroll.work.NoScrollWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NoScrollApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()

        scope.launch {
            // Reading the protection state applies any waiting period that elapsed while the app was
            // closed, before anything else in the process can observe a stale value.
            container.protectionRepository.current()
            container.ruleRepository.reload().let { container.protectionHealth.setRuleSetVersion(it.version) }
            container.refreshHealth()
        }

        NoScrollWorkScheduler.scheduleAll(this)
    }

    /**
     * Three channels, each with a different job.
     *
     * The status channel is deliberately IMPORTANCE_LOW: the ongoing notification the VPN requires
     * is a status row, not a message, and it should never make a sound or count against the daily
     * quota.
     */
    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_status_description)
                setShowBadge(false)
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, getString(R.string.channel_updates_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.channel_updates_description)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_HEALTH, getString(R.string.channel_health_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.channel_health_description)
            }
        )
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_UPDATES = "updates"
        const val CHANNEL_HEALTH = "health"

        const val NOTIFICATION_ID_FILTER = 1001
        const val NOTIFICATION_ID_MESSAGE = 1002
        const val NOTIFICATION_ID_HEALTH = 1003
    }
}
