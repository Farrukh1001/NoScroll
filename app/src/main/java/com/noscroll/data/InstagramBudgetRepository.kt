package com.noscroll.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.noscroll.core.time.TimeSource
import com.noscroll.domain.instagram.InstagramBudgetEngine
import com.noscroll.domain.instagram.InstagramBudgetState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * Persists the shared Instagram budget.
 *
 * Two properties matter more than anything else here:
 *  - **It is not keyed by account.** There is one row for the device and the day. Switching accounts,
 *    logging out, or reinstalling Instagram continues the same budget, and no username is ever read
 *    or written.
 *  - **Restarting cannot mint time.** The accumulated seconds are flushed frequently and the daily
 *    rollover is idempotent, so process death, a reboot or an app update resumes where it left off
 *    rather than starting a fresh 30 minutes.
 */
class InstagramBudgetRepository(
    private val context: Context,
    private val time: TimeSource,
    private val engine: InstagramBudgetEngine = InstagramBudgetEngine(),
) {

    private object Keys {
        val DATE = stringPreferencesKey("budget.date")
        val BASE_LIMIT = intPreferencesKey("budget.base_limit_minutes")
        val CONSUMED = longPreferencesKey("budget.consumed_seconds")
        val EXTRA = intPreferencesKey("budget.extra_minutes")
        val EMERGENCY_USED = booleanPreferencesKey("budget.emergency_used")
        val PENDING_LIMIT = intPreferencesKey("budget.pending_next_day_limit")
    }

    /** Serialises read-modify-write cycles so a flush and a rollover cannot interleave. */
    private val mutex = Mutex()

    fun observe(defaultLimitMinutes: Int): Flow<InstagramBudgetState> =
        context.instagramStore.data.map { prefs ->
            val stored = InstagramBudgetState(
                date = prefs[Keys.DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: time.today(),
                baseLimitMinutes = prefs[Keys.BASE_LIMIT] ?: defaultLimitMinutes,
                consumedSeconds = prefs[Keys.CONSUMED] ?: 0L,
                extraMinutesGranted = prefs[Keys.EXTRA] ?: 0,
                emergencyUsedToday = prefs[Keys.EMERGENCY_USED] ?: false,
                pendingNextDayBaseLimitMinutes = prefs[Keys.PENDING_LIMIT],
            )
            engine.rollover(stored, time.today())
        }

    suspend fun current(defaultLimitMinutes: Int): InstagramBudgetState = observe(defaultLimitMinutes).first()

    /** Applies [transform] atomically, rolling the day over first. */
    suspend fun update(
        defaultLimitMinutes: Int,
        transform: (InstagramBudgetState) -> InstagramBudgetState,
    ): InstagramBudgetState = mutex.withLock {
        var result: InstagramBudgetState? = null
        context.instagramStore.edit { prefs ->
            val stored = InstagramBudgetState(
                date = prefs[Keys.DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: time.today(),
                baseLimitMinutes = prefs[Keys.BASE_LIMIT] ?: defaultLimitMinutes,
                consumedSeconds = prefs[Keys.CONSUMED] ?: 0L,
                extraMinutesGranted = prefs[Keys.EXTRA] ?: 0,
                emergencyUsedToday = prefs[Keys.EMERGENCY_USED] ?: false,
                pendingNextDayBaseLimitMinutes = prefs[Keys.PENDING_LIMIT],
            )
            val rolled = engine.rollover(stored, time.today())
            val next = transform(rolled)

            prefs[Keys.DATE] = next.date.toString()
            prefs[Keys.BASE_LIMIT] = next.baseLimitMinutes
            prefs[Keys.CONSUMED] = next.consumedSeconds.coerceAtLeast(0L)
            prefs[Keys.EXTRA] = next.extraMinutesGranted
            prefs[Keys.EMERGENCY_USED] = next.emergencyUsedToday
            next.pendingNextDayBaseLimitMinutes
                ?.let { prefs[Keys.PENDING_LIMIT] = it }
                ?: prefs.remove(Keys.PENDING_LIMIT)

            result = next
        }
        result ?: current(defaultLimitMinutes)
    }

    /** Adds a measured slice of active scrolling. */
    suspend fun addConsumedSeconds(defaultLimitMinutes: Int, seconds: Long): InstagramBudgetState =
        update(defaultLimitMinutes) { state ->
            if (seconds <= 0) state
            else state.copy(
                consumedSeconds = (state.consumedSeconds + seconds)
                    .coerceAtMost(state.totalAllowanceSeconds + 60L)
            )
        }

    suspend fun setBaseLimit(defaultLimitMinutes: Int, minutes: Int): InstagramBudgetState =
        update(defaultLimitMinutes) { engine.applyBaseLimit(it, minutes) }

    /**
     * Clears the day's usage. Only ever called from the debug/diagnostics reset path — deleting
     * wellbeing history never routes here, which is what keeps "delete my screen-time data" from
     * doubling as "give me a fresh 30 minutes".
     */
    suspend fun resetForTesting(defaultLimitMinutes: Int) = update(defaultLimitMinutes) {
        it.copy(consumedSeconds = 0, extraMinutesGranted = 0, emergencyUsedToday = false)
    }
}
