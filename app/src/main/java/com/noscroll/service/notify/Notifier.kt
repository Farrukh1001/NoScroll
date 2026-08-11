package com.noscroll.service.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.noscroll.NoScrollApplication
import com.noscroll.R
import com.noscroll.di.AppContainer
import com.noscroll.domain.notify.NotificationKind
import com.noscroll.domain.notify.NotificationRequest
import com.noscroll.domain.notify.QuotaConfig
import com.noscroll.domain.notify.QuotaDecision
import com.noscroll.ui.MainActivity

/**
 * Posts notifications, but only through the quota.
 *
 * Every discrete message in the app comes through here, which is what makes "at most three a day,
 * two by default" a property of the code rather than a promise in a readme. The persistent
 * filter-status row is not routed through here — it is a status row Android requires, not a message.
 *
 * Nothing user-identifying is ever put in a notification: no domains, no search terms, no Instagram
 * content, no account names.
 */
class Notifier(private val context: Context, private val container: AppContainer) {

    suspend fun post(
        request: NotificationRequest,
        config: QuotaConfig,
        title: String,
        body: String,
        alternativesAvailable: Boolean = false,
    ): Boolean {
        if (!hasPermission()) return false

        return when (val decision = container.notificationRepository.evaluateAndReserve(request, config, alternativesAvailable)) {
            is QuotaDecision.Post -> {
                show(idFor(request.kind), title, body, request.kind)
                true
            }

            is QuotaDecision.UpdatePersistent -> {
                // The quota is spent but this still matters. Fold it into the persistent status row
                // rather than posting a fourth notification.
                updatePersistentStatus(title, body)
                true
            }

            is QuotaDecision.Suppress -> false
        }
    }

    private fun show(id: Int, title: String, body: String, kind: NotificationKind) {
        val channel = when (kind) {
            NotificationKind.PROTECTION_FAILURE -> NoScrollApplication.CHANNEL_HEALTH
            else -> NoScrollApplication.CHANNEL_UPDATES
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_shield_status)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    // Immutable: nothing downstream may rewrite this intent.
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

        // The permission is checked in post() before we get here, but the user can revoke it
        // between the two. Catching SecurityException explicitly is the documented way to handle
        // that, and losing an encouragement message is not worth crashing over.
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications were revoked after the check. Nothing to do and nothing to report.
        }
    }

    private fun updatePersistentStatus(title: String, body: String) {
        val notification = NotificationCompat.Builder(context, NoScrollApplication.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_shield_status)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setSilent(true)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify(NoScrollApplication.NOTIFICATION_ID_FILTER, notification)
        } catch (_: SecurityException) {
            // Same as above: the permission can be revoked between the check and the post.
        }
    }

    private fun idFor(kind: NotificationKind): Int = when (kind) {
        NotificationKind.PROTECTION_FAILURE -> NoScrollApplication.NOTIFICATION_ID_HEALTH
        else -> NoScrollApplication.NOTIFICATION_ID_MESSAGE
    }

    private fun hasPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
}
