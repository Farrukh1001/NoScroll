package com.noscroll.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.noscroll.domain.notify.MessageTone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Preferences that are the user's own business rather than protection settings.
 *
 * Nothing in here goes through the waiting period. Notification preferences, data retention, tone
 * and the privacy switches are choices about how the app treats the user, and making somebody wait
 * 24 hours to turn off a notification or delete their own history would be a dark pattern, not a
 * protection.
 */
class AppPreferencesRepository(private val context: Context) {

    private object Keys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding.complete")
        val DAILY_GOAL_MINUTES = intPreferencesKey("goal.daily_minutes")
        val INSTAGRAM_GOAL_MINUTES = intPreferencesKey("goal.instagram_minutes")
        val BEDTIME_HOUR = intPreferencesKey("goal.bedtime_hour")
        val TONE = stringPreferencesKey("message.tone")

        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notify.enabled")
        val NOTIFICATION_LIMIT = intPreferencesKey("notify.daily_limit")
        val EVENING_REMINDER_ENABLED = booleanPreferencesKey("notify.evening_enabled")
        val EVENING_REMINDER_HOUR = intPreferencesKey("notify.evening_hour")
        val EVENING_REMINDER_MINUTE = intPreferencesKey("notify.evening_minute")

        val ANALYTICS_ENABLED = booleanPreferencesKey("wellbeing.enabled")
        val RETENTION_MONTHS = intPreferencesKey("wellbeing.retention_months")
    }

    data class Preferences(
        val onboardingComplete: Boolean = false,
        val dailyGoalMinutes: Int = 180,
        val instagramGoalMinutes: Int = 30,
        val bedtimeHour: Int = 23,
        val tone: MessageTone = MessageTone.CALM,
        val notificationsEnabled: Boolean = true,
        val notificationDailyLimit: Int = 2,
        val eveningReminderEnabled: Boolean = true,
        val eveningReminderHour: Int = 22,
        val eveningReminderMinute: Int = 0,
        val analyticsEnabled: Boolean = false,
        val retentionMonths: Int = 12,
    )

    val preferences: Flow<Preferences> = context.appPrefsStore.data.map { prefs ->
        Preferences(
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
            dailyGoalMinutes = prefs[Keys.DAILY_GOAL_MINUTES] ?: 180,
            instagramGoalMinutes = prefs[Keys.INSTAGRAM_GOAL_MINUTES] ?: 30,
            bedtimeHour = prefs[Keys.BEDTIME_HOUR] ?: 23,
            tone = prefs[Keys.TONE]?.let { runCatching { MessageTone.valueOf(it) }.getOrNull() } ?: MessageTone.CALM,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            notificationDailyLimit = (prefs[Keys.NOTIFICATION_LIMIT] ?: 2).coerceIn(0, 3),
            eveningReminderEnabled = prefs[Keys.EVENING_REMINDER_ENABLED] ?: true,
            eveningReminderHour = prefs[Keys.EVENING_REMINDER_HOUR] ?: 22,
            eveningReminderMinute = prefs[Keys.EVENING_REMINDER_MINUTE] ?: 0,
            analyticsEnabled = prefs[Keys.ANALYTICS_ENABLED] ?: false,
            retentionMonths = prefs[Keys.RETENTION_MONTHS] ?: 12,
        )
    }

    suspend fun current(): Preferences = preferences.first()

    suspend fun setOnboardingComplete(value: Boolean) = edit { it[Keys.ONBOARDING_COMPLETE] = value }
    suspend fun setDailyGoalMinutes(value: Int) = edit { it[Keys.DAILY_GOAL_MINUTES] = value.coerceIn(15, 1440) }
    suspend fun setInstagramGoalMinutes(value: Int) = edit { it[Keys.INSTAGRAM_GOAL_MINUTES] = value.coerceIn(0, 600) }
    suspend fun setBedtimeHour(value: Int) = edit { it[Keys.BEDTIME_HOUR] = value.coerceIn(0, 23) }
    suspend fun setTone(tone: MessageTone) = edit { it[Keys.TONE] = tone.name }

    suspend fun setNotificationsEnabled(value: Boolean) = edit { it[Keys.NOTIFICATIONS_ENABLED] = value }
    suspend fun setNotificationLimit(value: Int) = edit { it[Keys.NOTIFICATION_LIMIT] = value.coerceIn(0, 3) }
    suspend fun setEveningReminderEnabled(value: Boolean) = edit { it[Keys.EVENING_REMINDER_ENABLED] = value }
    suspend fun setEveningReminderTime(hour: Int, minute: Int) = edit {
        it[Keys.EVENING_REMINDER_HOUR] = hour.coerceIn(0, 23)
        it[Keys.EVENING_REMINDER_MINUTE] = minute.coerceIn(0, 59)
    }

    /** Revoking analytics takes effect immediately; it never waits on a protection delay. */
    suspend fun setAnalyticsEnabled(value: Boolean) = edit { it[Keys.ANALYTICS_ENABLED] = value }
    suspend fun setRetentionMonths(value: Int) = edit { it[Keys.RETENTION_MONTHS] = value.coerceIn(1, 24) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.appPrefsStore.edit(block)
    }
}
