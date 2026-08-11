package com.noscroll.work

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noscroll.NoScrollApplication
import com.noscroll.di.AppContainer
import com.noscroll.domain.notify.MessageCatalogue
import com.noscroll.domain.notify.NotificationKind
import com.noscroll.domain.notify.NotificationRequest
import com.noscroll.domain.notify.QuotaConfig
import com.noscroll.domain.wellbeing.TimeSavedCalculator
import com.noscroll.domain.wellbeing.UsageEventRecord
import com.noscroll.domain.wellbeing.UsageEventType
import com.noscroll.service.notify.Notifier
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Hourly housekeeping.
 *
 * Deliberately does the boring, idempotent things: re-read usage events, re-aggregate, prune past
 * the retention window, and refresh health. Running it twice in an hour changes nothing.
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? NoScrollApplication)?.container ?: return Result.success()

        // Reading protection state applies any elapsed waiting period.
        runCatching { container.protectionRepository.current() }
        runCatching { container.refreshHealth() }

        val prefs = runCatching { container.appPreferences.current() }.getOrNull() ?: return Result.success()
        if (prefs.analyticsEnabled && container.hasUsageAccess()) {
            runCatching { aggregateUsage(container, prefs.retentionMonths) }
        }
        return Result.success()
    }

    private fun aggregateUsage(container: AppContainer, retentionMonths: Int) {
        val manager = applicationContext.getSystemService(UsageStatsManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val today = container.timeSource.today()
        // Re-read a rolling window rather than everything: enough to fix up yesterday's tail without
        // re-processing a year of events every hour.
        val start = today.minusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = container.timeSource.wallMillis()

        val events = mutableListOf<UsageEventRecord>()
        val stream = manager.queryEvents(start, end)
        val event = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            val type = when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> UsageEventType.FOREGROUND
                UsageEvents.Event.MOVE_TO_BACKGROUND -> UsageEventType.BACKGROUND
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventType.SCREEN_NON_INTERACTIVE
                UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEventType.SCREEN_INTERACTIVE
                UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEventType.SHUTDOWN
                else -> continue
            }
            events += UsageEventRecord(event.packageName ?: continue, type, event.timeStamp)
        }

        val aggregate = container.usageAggregator.aggregate(events, start, end)
        container.wellbeingDatabase.replaceAggregate(aggregate)
        container.wellbeingDatabase.pruneOlderThan(today.minusMonths(retentionMonths.toLong()))
    }
}

/**
 * The evening summary.
 *
 * It reports what actually happened — today's screen time, the difference from yesterday at the same
 * point, whether the Instagram plan held — and then re-schedules itself for tomorrow. It goes
 * through the quota manager like everything else, so it cannot become a fourth notification.
 */
class EveningSummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? NoScrollApplication ?: return Result.success()
        val container = app.container
        val prefs = runCatching { container.appPreferences.current() }.getOrNull()

        if (prefs != null && prefs.eveningReminderEnabled && prefs.notificationsEnabled) {
            runCatching { postSummary(container, prefs) }
        }

        // Re-arm for tomorrow, recalculated in the current timezone.
        if (prefs != null && prefs.eveningReminderEnabled) {
            NoScrollWorkScheduler.scheduleEveningSummary(
                applicationContext,
                prefs.eveningReminderHour,
                prefs.eveningReminderMinute,
                LocalDateTime.now(),
                ZoneId.systemDefault(),
            )
        }
        return Result.success()
    }

    private suspend fun postSummary(
        container: AppContainer,
        prefs: com.noscroll.data.AppPreferencesRepository.Preferences,
    ) {
        val today = container.timeSource.today()
        val now = LocalDateTime.now()
        val aggregate = container.wellbeingDatabase.readAggregate(today.minusDays(8), today)
        val totalMillis = aggregate.totalFor(today)

        val comparison = TimeSavedCalculator()
            .compareWithYesterday(aggregate, today, now.hour, now.minute)
        val comparisonText = MessageCatalogue.timeSaved(comparison, prefs.tone)?.text

        val budget = container.instagramBudgetRepository.current(prefs.instagramGoalMinutes)
        val withinPlan = !budget.isExhausted

        val body = buildString {
            append("Screen time today: ${totalMillis / 60_000} minutes.")
            comparisonText?.let { append(" "); append(it) }
            append(" ")
            append(
                if (withinPlan) {
                    MessageCatalogue.instagramWithinPlan(prefs.tone).text
                } else {
                    "You used your full Instagram plan today."
                }
            )
        }

        Notifier(applicationContext, container).post(
            request = NotificationRequest(
                kind = NotificationKind.EVENING_SUMMARY,
                templateId = "evening.summary",
                whenMillis = container.timeSource.wallMillis(),
            ),
            config = QuotaConfig(
                dailyLimit = prefs.notificationDailyLimit,
                eveningSummaryEnabled = prefs.eveningReminderEnabled,
                notificationsEnabled = prefs.notificationsEnabled,
            ),
            title = "Today's summary",
            body = body,
        )
    }
}
