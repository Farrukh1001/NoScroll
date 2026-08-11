package com.noscroll.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.noscroll.core.time.TimeSource
import com.noscroll.domain.notify.NotificationKind
import com.noscroll.domain.notify.NotificationQuotaManager
import com.noscroll.domain.notify.NotificationRequest
import com.noscroll.domain.notify.QuotaState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * Persists the notification quota.
 *
 * The state has to survive process restarts, otherwise the daily limit resets every time Android
 * kills the app and the user gets a stream of notifications instead of at most three.
 *
 * Template history is stored as `templateId@timestamp` strings — identifiers only. What a message
 * said is derived from the template at send time and never written down.
 */
class NotificationRepository(
    private val context: Context,
    private val time: TimeSource,
    private val quota: NotificationQuotaManager = NotificationQuotaManager(),
) {

    private object Keys {
        val DATE = stringPreferencesKey("quota.date")
        val POSTED = intPreferencesKey("quota.posted")
        val LAST_POST = longPreferencesKey("quota.last_post")
        val KINDS = stringSetPreferencesKey("quota.kinds")
        val EVENING_POSTED = booleanPreferencesKey("quota.evening_posted")
        val TEMPLATES = stringSetPreferencesKey("quota.templates")
    }

    private val mutex = Mutex()

    private fun decode(prefs: androidx.datastore.preferences.core.Preferences): QuotaState {
        val stored = QuotaState(
            date = prefs[Keys.DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: time.today(),
            postedToday = prefs[Keys.POSTED] ?: 0,
            lastPostMillis = prefs[Keys.LAST_POST],
            kindsPostedToday = (prefs[Keys.KINDS] ?: emptySet()).mapNotNull { name ->
                runCatching { NotificationKind.valueOf(name) }.getOrNull()
            }.toSet(),
            eveningSummaryPosted = prefs[Keys.EVENING_POSTED] ?: false,
            templateHistory = (prefs[Keys.TEMPLATES] ?: emptySet()).mapNotNull { entry ->
                val id = entry.substringBeforeLast('@', "")
                val at = entry.substringAfterLast('@', "").toLongOrNull()
                if (id.isEmpty() || at == null) null else id to at
            }.toMap(),
        )
        return quota.rollover(stored, time.today())
    }

    suspend fun state(): QuotaState = context.notificationStore.data.map(::decode).first()

    /**
     * Runs the quota decision and, if it says post, records the post atomically. Doing both under
     * one lock is what stops two concurrent triggers from each seeing "one slot left".
     */
    suspend fun evaluateAndReserve(
        request: NotificationRequest,
        config: com.noscroll.domain.notify.QuotaConfig,
        alternativesAvailable: Boolean = false,
    ): com.noscroll.domain.notify.QuotaDecision = mutex.withLock {
        val current = state()
        val decision = quota.evaluate(current, config, request, alternativesAvailable)
        if (decision is com.noscroll.domain.notify.QuotaDecision.Post) {
            persist(quota.recordPost(current, request))
        }
        decision
    }

    private suspend fun persist(state: QuotaState) {
        context.notificationStore.edit { prefs ->
            prefs[Keys.DATE] = state.date.toString()
            prefs[Keys.POSTED] = state.postedToday
            state.lastPostMillis?.let { prefs[Keys.LAST_POST] = it }
            prefs[Keys.KINDS] = state.kindsPostedToday.map { it.name }.toSet()
            prefs[Keys.EVENING_POSTED] = state.eveningSummaryPosted
            // Keep the history bounded; anything older than the rotation window is dead weight.
            val cutoff = time.wallMillis() - com.noscroll.domain.notify.QuotaConfig.TEMPLATE_REPEAT_WINDOW_MILLIS
            prefs[Keys.TEMPLATES] = state.templateHistory
                .filterValues { it >= cutoff }
                .map { (id, at) -> "$id@$at" }
                .toSet()
        }
    }
}
