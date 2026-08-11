package com.noscroll.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.noscroll.core.time.TimeSource
import com.noscroll.domain.protection.PendingBundle
import com.noscroll.domain.protection.ProtectedSettingsEngine
import com.noscroll.domain.protection.ProtectionCodec
import com.noscroll.domain.protection.ProtectionState
import com.noscroll.domain.protection.RequestOutcome
import com.noscroll.domain.protection.SettingKey
import com.noscroll.domain.protection.SettingType
import com.noscroll.domain.protection.SettingValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists protection configuration and the single pending weakening bundle.
 *
 * Reads always run through [ProtectedSettingsEngine.reconcile] before being handed out, so a change
 * whose waiting period elapsed while the app was closed is live the moment anything asks — there is
 * no window where the UI and the enforcement layers disagree about what is switched on.
 */
class ProtectionRepository(
    private val context: Context,
    private val time: TimeSource,
    private val engine: ProtectedSettingsEngine = ProtectedSettingsEngine(),
) {

    private object Keys {
        val ENTRIES = stringPreferencesKey("pending.entries")
        val REQUESTED_AT = longPreferencesKey("pending.requested_at")
        val TOTAL_DELAY = longPreferencesKey("pending.total_delay")
        val REMAINING = longPreferencesKey("pending.remaining")
        val ANCHOR_MONOTONIC = longPreferencesKey("pending.anchor_monotonic")
        val ANCHOR_WALL = longPreferencesKey("pending.anchor_wall")
        val CANCEL_COOLDOWN = longPreferencesKey("pending.cancel_cooldown")
        val LAST_DELAY_CHANGE = longPreferencesKey("pending.last_delay_change")
    }

    private fun settingKey(key: SettingKey): Preferences.Key<*> = when (key.type) {
        SettingType.BOOLEAN -> booleanPreferencesKey(key.id)
        SettingType.INT -> intPreferencesKey(key.id)
    }

    /** The live protection state, already reconciled against the clock. */
    val state: Flow<ProtectionState> =
        combine(context.protectionStore.data, context.pendingChangeStore.data) { config, pending ->
            val values = SettingKey.entries.associateWith { key ->
                when (key.type) {
                    SettingType.BOOLEAN ->
                        SettingValue.Bool(config[booleanPreferencesKey(key.id)] ?: key.defaultBoolean)
                    SettingType.INT ->
                        SettingValue.Num(config[intPreferencesKey(key.id)] ?: key.defaultInt)
                }
            }
            ProtectionState(
                values = values,
                pending = readPending(pending),
                cancelCooldownUntilWall = pending[Keys.CANCEL_COOLDOWN] ?: 0L,
                lastDelayChangeRequestWall = pending[Keys.LAST_DELAY_CHANGE],
            )
        }.map { stored ->
            val reconciled = engine.reconcile(stored, time.wallMillis(), time.monotonicMillis())
            // Persist only when the bundle actually applied. Writing back the advanced anchor on
            // every read would re-trigger this same flow and spin forever — and it is unnecessary,
            // because the anchor is the request time and the remaining wait is derived from it.
            if (stored.pending != null && reconciled.pending == null) {
                persistReconciled(reconciled)
            }
            reconciled
        }

    private fun readPending(prefs: Preferences): PendingBundle? {
        val entries = ProtectionCodec.decodeEntries(prefs[Keys.ENTRIES]) ?: return null
        return PendingBundle(
            entries = entries,
            requestedAtWall = prefs[Keys.REQUESTED_AT] ?: return null,
            totalDelayMillis = prefs[Keys.TOTAL_DELAY] ?: return null,
            remainingMillisAtAnchor = prefs[Keys.REMAINING] ?: return null,
            anchorMonotonic = prefs[Keys.ANCHOR_MONOTONIC] ?: return null,
            anchorWall = prefs[Keys.ANCHOR_WALL] ?: return null,
        )
    }

    /**
     * Writes back a state that [ProtectedSettingsEngine.reconcile] changed. Fire-and-forget on
     * purpose: the reconciled value has already been handed to the caller, and the next read
     * reconciles again from whatever is on disk, so a lost write costs nothing but a repeat.
     */
    private suspend fun persistReconciled(state: ProtectionState) {
        writeValues(state.values)
        writePending(state)
    }

    private suspend fun writeValues(values: Map<SettingKey, SettingValue>) {
        context.protectionStore.edit { prefs ->
            values.forEach { (key, value) ->
                when (value) {
                    is SettingValue.Bool -> prefs[booleanPreferencesKey(key.id)] = value.value
                    is SettingValue.Num -> prefs[intPreferencesKey(key.id)] = value.value
                }
            }
        }
    }

    private suspend fun writePending(state: ProtectionState) {
        context.pendingChangeStore.edit { prefs ->
            val bundle = state.pending
            if (bundle == null) {
                prefs.remove(Keys.ENTRIES)
                prefs.remove(Keys.REQUESTED_AT)
                prefs.remove(Keys.TOTAL_DELAY)
                prefs.remove(Keys.REMAINING)
                prefs.remove(Keys.ANCHOR_MONOTONIC)
                prefs.remove(Keys.ANCHOR_WALL)
            } else {
                prefs[Keys.ENTRIES] = ProtectionCodec.encodeEntries(bundle.entries)
                prefs[Keys.REQUESTED_AT] = bundle.requestedAtWall
                prefs[Keys.TOTAL_DELAY] = bundle.totalDelayMillis
                prefs[Keys.REMAINING] = bundle.remainingMillisAtAnchor
                prefs[Keys.ANCHOR_MONOTONIC] = bundle.anchorMonotonic
                prefs[Keys.ANCHOR_WALL] = bundle.anchorWall
            }
            prefs[Keys.CANCEL_COOLDOWN] = state.cancelCooldownUntilWall
            state.lastDelayChangeRequestWall?.let { prefs[Keys.LAST_DELAY_CHANGE] = it }
        }
    }

    suspend fun current(): ProtectionState = state.first()

    /** Requests one or more changes. See [ProtectedSettingsEngine.request] for the rules. */
    suspend fun request(changes: Map<SettingKey, SettingValue>): RequestOutcome {
        val outcome = engine.request(current(), changes, time.wallMillis(), time.monotonicMillis())
        when (outcome) {
            is RequestOutcome.Applied -> persistReconciled(outcome.state)
            is RequestOutcome.Queued -> persistReconciled(outcome.state)
            is RequestOutcome.Rejected -> Unit
        }
        return outcome
    }

    suspend fun setBoolean(key: SettingKey, value: Boolean): RequestOutcome =
        request(mapOf(key to SettingValue.Bool(value)))

    suspend fun setInt(key: SettingKey, value: Int): RequestOutcome =
        request(mapOf(key to SettingValue.Num(value)))

    suspend fun cancelPending() {
        persistReconciled(engine.cancelPending(current(), time.wallMillis()))
    }

    /** Remaining wait on the pending bundle, or null when nothing is queued. */
    suspend fun remainingMillis(): Long? = current().pending?.let {
        engine.remainingMillis(it, time.wallMillis(), time.monotonicMillis())
    }

    /** Applies a preset. Weakening parts of a preset still go through the waiting period. */
    suspend fun applyPreset(preset: ProtectionPreset): RequestOutcome = request(preset.values)
}

/**
 * Every protection on, with the three values that distinguish one preset from another.
 *
 * A top-level function rather than one in the enum's companion: enum constructor arguments are
 * evaluated before the companion exists.
 */
private fun presetValues(
    delayHours: Int,
    instagramMinutes: Int,
    emergency: Boolean,
): Map<SettingKey, SettingValue> =
    SettingKey.entries.associateWith { key ->
        when (key) {
            SettingKey.WEAKENING_DELAY_HOURS -> SettingValue.Num(delayHours)
            SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES -> SettingValue.Num(instagramMinutes)
            SettingKey.INSTAGRAM_EMERGENCY_MAX_MINUTES -> SettingValue.Num(30)
            SettingKey.INSTAGRAM_EMERGENCY_ENABLED -> SettingValue.Bool(emergency)
            else -> when (key.type) {
                SettingType.BOOLEAN -> SettingValue.Bool(true)
                SettingType.INT -> SettingValue.Num(key.defaultInt)
            }
        }
    }

/** The starting points offered during onboarding. Custom is simply "leave everything as it is". */
enum class ProtectionPreset(val label: String, val description: String, val values: Map<SettingKey, SettingValue>) {
    RECOMMENDED(
        "Recommended",
        "Every protection on, a 30-minute Instagram budget, and a 24-hour wait before anything can be loosened.",
        presetValues(delayHours = 24, instagramMinutes = 30, emergency = true),
    ),
    STRICT(
        "Strict",
        "The same protections with a 15-minute budget, no emergency extension, and a 48-hour wait.",
        presetValues(delayHours = 48, instagramMinutes = 15, emergency = false),
    ),
    COMMUNICATION_FIRST(
        "Communication-first",
        "Messaging stays completely free. Discovery surfaces get a 20-minute budget and a 12-hour wait.",
        presetValues(delayHours = 12, instagramMinutes = 20, emergency = true),
    ),
}
