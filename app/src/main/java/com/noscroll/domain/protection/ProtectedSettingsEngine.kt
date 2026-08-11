package com.noscroll.domain.protection

/** One line of a pending change bundle: what it is now, what it would become, and why that weakens. */
data class PendingEntry(
    val key: SettingKey,
    val current: SettingValue,
    val requested: SettingValue,
    val reason: String,
)

/**
 * A queued weakening change.
 *
 * The remaining wait is anchored to the monotonic clock rather than to a wall-clock deadline. While
 * the device is running, moving the system clock forward therefore buys nothing. Across a reboot the
 * monotonic clock restarts, so the bundle re-anchors against the wall clock and credits only time
 * that actually moved forward — a backwards clock change credits zero rather than locking the user
 * out forever.
 */
data class PendingBundle(
    val entries: List<PendingEntry>,
    val requestedAtWall: Long,
    val totalDelayMillis: Long,
    val remainingMillisAtAnchor: Long,
    val anchorMonotonic: Long,
    val anchorWall: Long,
) {
    val isDelayChange: Boolean get() = entries.any { it.key == SettingKey.WEAKENING_DELAY_HOURS }
}

data class ProtectionState(
    val values: Map<SettingKey, SettingValue>,
    val pending: PendingBundle? = null,
    /** Wall time until which no new weakening request is accepted, after a cancellation. */
    val cancelCooldownUntilWall: Long = 0L,
    /** Wall time of the last accepted delay-change request, for the once-per-24h rule. */
    val lastDelayChangeRequestWall: Long? = null,
) {
    fun bool(key: SettingKey): Boolean =
        (values[key] as? SettingValue.Bool)?.value ?: key.defaultBoolean

    fun int(key: SettingKey): Int =
        (values[key] as? SettingValue.Num)?.value ?: key.defaultInt

    fun value(key: SettingKey): SettingValue = values[key] ?: when (key.type) {
        SettingType.BOOLEAN -> SettingValue.Bool(key.defaultBoolean)
        SettingType.INT -> SettingValue.Num(key.defaultInt)
    }

    companion object {
        fun withDefaults(): ProtectionState =
            ProtectionState(SettingKey.entries.associateWith { key ->
                when (key.type) {
                    SettingType.BOOLEAN -> SettingValue.Bool(key.defaultBoolean)
                    SettingType.INT -> SettingValue.Num(key.defaultInt)
                }
            })
    }
}

enum class RejectionReason {
    /** Only one weakening bundle may be pending at a time. */
    PENDING_ALREADY_EXISTS,

    /** A bundle was cancelled recently; a short cooldown applies before the next request. */
    CANCEL_COOLDOWN,

    /** The waiting period may only be changed once per rolling 24 hours. */
    DELAY_CHANGE_RATE_LIMITED,

    /** A waiting-period change must be requested on its own. */
    DELAY_CHANGE_MUST_BE_ALONE,

    /** The requested value is outside the allowed range for this setting. */
    VALUE_OUT_OF_RANGE,

    /** Nothing in the request would actually change anything. */
    NOTHING_TO_DO,
}

sealed interface RequestOutcome {
    /** Every requested change strengthened protection (or was neutral) and is already live. */
    data class Applied(val state: ProtectionState) : RequestOutcome

    /**
     * Strengthening parts (if any) were applied immediately; the weakening parts are queued.
     */
    data class Queued(val state: ProtectionState, val bundle: PendingBundle) : RequestOutcome

    data class Rejected(val reason: RejectionReason) : RequestOutcome
}

/**
 * The rules engine behind every protected setting.
 *
 * It is deliberately pure: it takes a state and a clock reading and returns a new state. Persistence,
 * DataStore and Compose all live outside, which is what makes the reboot / process-death / clock
 * manipulation cases straightforward to test.
 */
class ProtectedSettingsEngine(
    private val cancelCooldownMillis: Long = DEFAULT_CANCEL_COOLDOWN_MILLIS,
) {

    /**
     * Advances the pending bundle against the clock and applies it if the wait has elapsed.
     * Safe (and expected) to call often — on app start, on every settings screen composition, and
     * from the periodic worker.
     */
    fun reconcile(state: ProtectionState, nowWall: Long, nowMonotonic: Long): ProtectionState {
        val pending = state.pending ?: return state
        val advanced = advance(pending, nowWall, nowMonotonic)
        return if (advanced.remainingMillisAtAnchor <= 0L) {
            state.copy(values = applyEntries(state.values, advanced.entries), pending = null)
        } else {
            state.copy(pending = advanced)
        }
    }

    /** Milliseconds still to wait, after reconciling against the supplied clock readings. */
    fun remainingMillis(pending: PendingBundle, nowWall: Long, nowMonotonic: Long): Long =
        advance(pending, nowWall, nowMonotonic).remainingMillisAtAnchor.coerceAtLeast(0L)

    private fun advance(pending: PendingBundle, nowWall: Long, nowMonotonic: Long): PendingBundle {
        val credited: Long = if (nowMonotonic < pending.anchorMonotonic) {
            // The monotonic clock restarted: the device rebooted (or the reading came from a
            // previous boot). Fall back to the wall clock and credit only forward movement, so a
            // rolled-back clock costs the user nothing but buys them nothing either.
            (nowWall - pending.anchorWall).coerceAtLeast(0L)
        } else {
            nowMonotonic - pending.anchorMonotonic
        }
        return pending.copy(
            remainingMillisAtAnchor = pending.remainingMillisAtAnchor - credited,
            anchorMonotonic = nowMonotonic,
            anchorWall = nowWall,
        )
    }

    /**
     * Requests a set of changes.
     *
     * Strengthening and neutral changes are applied on the spot. Weakening changes are bundled and
     * queued behind the currently active waiting period — note "currently active", so shortening the
     * delay always serves the old, longer sentence.
     */
    fun request(
        state: ProtectionState,
        requested: Map<SettingKey, SettingValue>,
        nowWall: Long,
        nowMonotonic: Long,
    ): RequestOutcome {
        for ((key, value) in requested) {
            if (key.type == SettingType.INT) {
                val v = value.asInt()
                if (v < key.minInt || v > key.maxInt) return RequestOutcome.Rejected(RejectionReason.VALUE_OUT_OF_RANGE)
            }
        }

        val effective = requested.filter { (key, value) -> state.value(key) != value }
        if (effective.isEmpty()) return RequestOutcome.Rejected(RejectionReason.NOTHING_TO_DO)

        val touchesDelay = effective.containsKey(SettingKey.WEAKENING_DELAY_HOURS)
        if (touchesDelay && effective.size > 1) {
            return RequestOutcome.Rejected(RejectionReason.DELAY_CHANGE_MUST_BE_ALONE)
        }

        if (touchesDelay) return requestDelayChange(state, effective, nowWall, nowMonotonic)

        val grouped = effective.entries.groupBy { (key, value) ->
            ChangeClassifier.classify(key, state.value(key), value)
        }
        val immediate = (grouped[ChangeDirection.STRENGTHENING].orEmpty() + grouped[ChangeDirection.NEUTRAL].orEmpty())
            .associate { it.key to it.value }
        val weakening = grouped[ChangeDirection.WEAKENING].orEmpty()

        val afterImmediate = if (immediate.isEmpty()) state else state.copy(values = state.values + immediate)

        if (weakening.isEmpty()) return RequestOutcome.Applied(afterImmediate)

        if (state.pending != null) return RequestOutcome.Rejected(RejectionReason.PENDING_ALREADY_EXISTS)
        if (nowWall < state.cancelCooldownUntilWall) return RequestOutcome.Rejected(RejectionReason.CANCEL_COOLDOWN)

        val delayMillis = state.int(SettingKey.WEAKENING_DELAY_HOURS).toLong() * MILLIS_PER_HOUR
        val bundle = buildBundle(afterImmediate, weakening.associate { it.key to it.value }, delayMillis, nowWall, nowMonotonic)
        return RequestOutcome.Queued(afterImmediate.copy(pending = bundle), bundle)
    }

    private fun requestDelayChange(
        state: ProtectionState,
        effective: Map<SettingKey, SettingValue>,
        nowWall: Long,
        nowMonotonic: Long,
    ): RequestOutcome {
        if (state.pending != null) return RequestOutcome.Rejected(RejectionReason.PENDING_ALREADY_EXISTS)
        if (nowWall < state.cancelCooldownUntilWall) return RequestOutcome.Rejected(RejectionReason.CANCEL_COOLDOWN)

        state.lastDelayChangeRequestWall?.let { last ->
            // A rolled-back clock must not unlock a second change: only forward movement counts.
            val elapsed = nowWall - last
            if (elapsed in 0 until ROLLING_24H_MILLIS) {
                return RequestOutcome.Rejected(RejectionReason.DELAY_CHANGE_RATE_LIMITED)
            }
            if (elapsed < 0) return RequestOutcome.Rejected(RejectionReason.DELAY_CHANGE_RATE_LIMITED)
        }

        val currentHours = state.int(SettingKey.WEAKENING_DELAY_HOURS)
        val requestedHours = effective.getValue(SettingKey.WEAKENING_DELAY_HOURS).asInt()

        // Shortening serves the old sentence; lengthening still waits the mandatory hour.
        val waitHours = if (requestedHours < currentHours) maxOf(currentHours, MIN_DELAY_CHANGE_HOURS) else MIN_DELAY_CHANGE_HOURS
        val delayMillis = waitHours.toLong() * MILLIS_PER_HOUR

        val bundle = buildBundle(state, effective, delayMillis, nowWall, nowMonotonic)
        return RequestOutcome.Queued(
            state.copy(pending = bundle, lastDelayChangeRequestWall = nowWall),
            bundle,
        )
    }

    private fun buildBundle(
        state: ProtectionState,
        changes: Map<SettingKey, SettingValue>,
        delayMillis: Long,
        nowWall: Long,
        nowMonotonic: Long,
    ): PendingBundle {
        val entries = changes.map { (key, requested) ->
            val current = state.value(key)
            PendingEntry(key, current, requested, ChangeClassifier.explain(key, current, requested))
        }.sortedBy { it.key.ordinal }
        return PendingBundle(
            entries = entries,
            requestedAtWall = nowWall,
            totalDelayMillis = delayMillis,
            remainingMillisAtAnchor = delayMillis,
            anchorMonotonic = nowMonotonic,
            anchorWall = nowWall,
        )
    }

    /** Cancelling is always allowed and always immediate; a short cooldown follows. */
    fun cancelPending(state: ProtectionState, nowWall: Long): ProtectionState {
        if (state.pending == null) return state
        return state.copy(pending = null, cancelCooldownUntilWall = nowWall + cancelCooldownMillis)
    }

    private fun applyEntries(
        values: Map<SettingKey, SettingValue>,
        entries: List<PendingEntry>,
    ): Map<SettingKey, SettingValue> =
        values + entries.associate { it.key to it.requested }

    companion object {
        const val MILLIS_PER_HOUR = 3_600_000L
        const val ROLLING_24H_MILLIS = 24 * MILLIS_PER_HOUR
        const val MIN_DELAY_CHANGE_HOURS = 1
        const val DEFAULT_CANCEL_COOLDOWN_MILLIS = 30 * 60 * 1000L
    }
}
