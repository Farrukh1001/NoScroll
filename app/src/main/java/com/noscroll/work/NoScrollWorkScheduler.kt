package com.noscroll.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Schedules NoScroll's background work.
 *
 * **There is no alarm clock here, and no exact-alarm permission.** `SCHEDULE_EXACT_ALARM` and
 * `USE_EXACT_ALARM` are for alarm clocks and calendar reminders; a wellbeing summary is neither, and
 * asking for them would be both a policy problem and a battery one. WorkManager delivers the evening
 * summary at the user's chosen time on a best-effort basis, and the app says "around 10:00 PM"
 * rather than claiming a guarantee Android does not give it.
 *
 * The evening job re-schedules itself after each run instead of repeating on a fixed period, which
 * is what makes it survive DST shifts and timezone changes without drifting.
 */
object NoScrollWorkScheduler {

    private const val WORK_EVENING = "noscroll.evening_summary"
    private const val WORK_MAINTENANCE = "noscroll.maintenance"

    fun scheduleAll(context: Context) {
        scheduleMaintenance(context)
        scheduleEveningSummary(context)
    }

    /**
     * Hourly housekeeping: re-aggregate usage, prune past the retention window, and apply any
     * protected change whose waiting period has elapsed.
     */
    fun scheduleMaintenance(context: Context) {
        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.HOURS)
            .setInitialDelay(5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_MAINTENANCE,
            // KEEP, so a restart does not reset the period and cause a burst of runs.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Queues the next evening summary. Called at startup, after a reboot, after a timezone change,
     * and by the worker itself once it has run.
     */
    fun scheduleEveningSummary(
        context: Context,
        hour: Int = 22,
        minute: Int = 0,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val delay = millisUntilNext(hour, minute, now, zone)
        val request = OneTimeWorkRequestBuilder<EveningSummaryWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_EVENING,
            // REPLACE, so changing the reminder time takes effect immediately rather than after the
            // old one fires.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelEveningSummary(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_EVENING)
    }

    /**
     * Milliseconds from [now] to the next occurrence of [hour]:[minute] in [zone].
     *
     * Computed through the zone rather than by adding 24 hours, so the day a clock shifts still
     * lands on the right wall-clock time.
     */
    fun millisUntilNext(hour: Int, minute: Int, now: LocalDateTime, zone: ZoneId): Long {
        val today: LocalDate = now.toLocalDate()
        var target = today.atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        if (!target.isAfter(now)) target = target.plusDays(1)
        val from = now.atZone(zone).toInstant()
        val to = target.atZone(zone).toInstant()
        return Duration.between(from, to).toMillis().coerceAtLeast(0L)
    }
}
