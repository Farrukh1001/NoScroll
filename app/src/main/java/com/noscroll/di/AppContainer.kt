package com.noscroll.di

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import com.noscroll.core.time.SystemTimeSource
import com.noscroll.core.time.TimeSource
import com.noscroll.data.AppPreferencesRepository
import com.noscroll.data.InstagramBudgetRepository
import com.noscroll.data.NotificationRepository
import com.noscroll.data.ProtectionRepository
import com.noscroll.data.filter.RuleRepository
import com.noscroll.data.wellbeing.WellbeingDatabase
import com.noscroll.domain.health.ProtectionHealth
import com.noscroll.domain.health.YouTubeMusicStatus
import com.noscroll.domain.surface.Packages
import com.noscroll.domain.wellbeing.UsageAggregator
import java.time.ZoneId

/**
 * Hand-rolled dependency container.
 *
 * A dependency-injection framework would add an annotation processor to a build whose release
 * artefact is meant to be reproducible from source by anyone. For an app with this few singletons,
 * plain constructor wiring behind one object is easier to read and leaves nothing to generate.
 *
 * Everything here is lazy so that neither the accessibility service nor the VPN service pays for
 * things it does not touch.
 */
class AppContainer(private val context: Context) {

    val timeSource: TimeSource = SystemTimeSource()

    val protectionHealth: ProtectionHealth by lazy { ProtectionHealth() }

    val protectionRepository: ProtectionRepository by lazy { ProtectionRepository(context, timeSource) }

    val instagramBudgetRepository: InstagramBudgetRepository by lazy {
        InstagramBudgetRepository(context, timeSource)
    }

    val appPreferences: AppPreferencesRepository by lazy { AppPreferencesRepository(context) }

    val notificationRepository: NotificationRepository by lazy { NotificationRepository(context, timeSource) }

    val ruleRepository: RuleRepository by lazy { RuleRepository(context) }

    val wellbeingDatabase: WellbeingDatabase by lazy { WellbeingDatabase(context) }

    val usageAggregator: UsageAggregator by lazy { UsageAggregator(ZoneId.systemDefault()) }

    // ---- Platform state queries -----------------------------------------------------------

    /**
     * Reads the YouTube Music package state.
     *
     * Answers honestly rather than optimistically: if the package manager throws, the result is
     * [YouTubeMusicStatus.UNKNOWN] and the dashboard says "Unable to verify status" rather than
     * claiming protection it cannot confirm.
     */
    fun youTubeMusicStatus(): YouTubeMusicStatus = try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(Packages.YOUTUBE_MUSIC, 0)
        if (info.enabled) YouTubeMusicStatus.ENABLED_FALLBACK else YouTubeMusicStatus.DISABLED
    } catch (_: PackageManager.NameNotFoundException) {
        YouTubeMusicStatus.NOT_INSTALLED
    } catch (_: Exception) {
        YouTubeMusicStatus.UNKNOWN
    }

    /** True when the user has granted Usage Access by hand. Analytics stay off until they do. */
    fun hasUsageAccess(): Boolean = try {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    /** True when the VPN consent dialog has already been accepted for this app. */
    fun isVpnPrepared(): Boolean = VpnService.prepare(context) == null

    fun areNotificationsAllowed(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Refreshes every platform-derived health field in one pass. */
    fun refreshHealth() {
        protectionHealth.setYouTubeMusicStatus(youTubeMusicStatus())
        protectionHealth.setUsageAccess(hasUsageAccess())
        protectionHealth.setVpnPrepared(isVpnPrepared())
        protectionHealth.setNotificationsAllowed(areNotificationsAllowed())
    }
}
