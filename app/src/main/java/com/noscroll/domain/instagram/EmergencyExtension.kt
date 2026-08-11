package com.noscroll.domain.instagram

/**
 * The deliberate-friction flow for buying extra Instagram minutes.
 *
 * The point of the countdown and the typed sentence is not security — anyone can type a sentence.
 * It is to put a minute of unavoidable, boring delay between the impulse and the extra time, and to
 * make the amount a conscious choice rather than a reflex tap.
 */
sealed interface ExtensionStep {
    data object ChooseAmount : ExtensionStep
    data class CountingDown(val amountMinutes: Int, val remainingSeconds: Int) : ExtensionStep
    data class TypeSentence(val amountMinutes: Int) : ExtensionStep
    data class Granted(val amountMinutes: Int) : ExtensionStep
}

enum class ExtensionRejection {
    /** The base budget still has time left; the extension is not unlocked yet. */
    BASE_BUDGET_NOT_EXHAUSTED,

    /** One completed extension per local day. */
    ALREADY_USED_TODAY,

    /** The emergency extension is switched off in protected settings. */
    DISABLED,

    /** Not a whole number between 1 and the configured maximum. */
    INVALID_AMOUNT,

    /** The typed sentence did not match. */
    SENTENCE_MISMATCH,

    /** The countdown has not finished. */
    COUNTDOWN_INCOMPLETE,
}

object EmergencyExtension {

    const val COUNTDOWN_SECONDS = 60
    const val MIN_MINUTES = 1

    /**
     * Accepts only whole numbers in range. Text, decimals, negatives, zero and anything above the
     * configured maximum are rejected — this is the single place that decides, so the UI and the
     * tests cannot disagree.
     */
    fun parseAmount(raw: String, maxMinutes: Int): Int? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // ASCII digits only. This rejects a leading '+'/'-' and any decimal point outright rather
        // than letting toIntOrNull quietly accept "+5", and it also rejects other Unicode digit
        // forms such as Arabic-Indic numerals — those parse as numbers, but the confirmation
        // sentence is generated with ASCII digits, so accepting them would produce a sentence the
        // user could never match.
        if (!trimmed.all { it in '0'..'9' }) return null
        val value = trimmed.toIntOrNull() ?: return null
        return if (isValidAmount(value, maxMinutes)) value else null
    }

    fun isValidAmount(minutes: Int, maxMinutes: Int): Boolean =
        minutes >= MIN_MINUTES && minutes <= maxMinutes

    /** The sentence the user has to type by hand, with no paste and no autofill. */
    fun requiredSentence(minutes: Int): String =
        "I choose $minutes extra minutes intentionally today."

    /**
     * Compares the typed sentence to the required one. Only outer whitespace is forgiven — the
     * wording, the number and the punctuation all have to match.
     */
    fun sentenceMatches(typed: String, minutes: Int): Boolean =
        typed.trim() == requiredSentence(minutes)

    /**
     * The single gate every extension has to pass. Returns the granted state or the reason it
     * cannot be granted; nothing is marked as used unless the whole flow succeeded.
     */
    fun grant(
        state: InstagramBudgetState,
        amountMinutes: Int,
        typedSentence: String,
        countdownComplete: Boolean,
        enabled: Boolean,
        maxMinutes: Int,
    ): Result<InstagramBudgetState> {
        if (!enabled) return Result.failure(ExtensionException(ExtensionRejection.DISABLED))
        if (!state.baseExhausted) return Result.failure(ExtensionException(ExtensionRejection.BASE_BUDGET_NOT_EXHAUSTED))
        if (state.emergencyUsedToday) return Result.failure(ExtensionException(ExtensionRejection.ALREADY_USED_TODAY))
        if (!isValidAmount(amountMinutes, maxMinutes)) return Result.failure(ExtensionException(ExtensionRejection.INVALID_AMOUNT))
        if (!countdownComplete) return Result.failure(ExtensionException(ExtensionRejection.COUNTDOWN_INCOMPLETE))
        if (!sentenceMatches(typedSentence, amountMinutes)) return Result.failure(ExtensionException(ExtensionRejection.SENTENCE_MISMATCH))

        return Result.success(
            state.copy(
                extraMinutesGranted = state.extraMinutesGranted + amountMinutes,
                emergencyUsedToday = true,
            )
        )
    }
}

class ExtensionException(val rejection: ExtensionRejection) : Exception(rejection.name)

/**
 * The countdown, kept as data so rotation and process death can restore it.
 *
 * It only advances while NoScroll is in the foreground and the device is unlocked: backgrounding the
 * app or locking the screen pauses it rather than letting the minute run down in a pocket.
 */
data class CountdownState(
    val amountMinutes: Int,
    val remainingSeconds: Int,
    val running: Boolean,
) {
    val complete: Boolean get() = remainingSeconds <= 0

    fun tick(): CountdownState =
        if (!running || complete) this else copy(remainingSeconds = remainingSeconds - 1)

    fun pause(): CountdownState = copy(running = false)
    fun resume(): CountdownState = if (complete) this else copy(running = true)

    companion object {
        fun start(amountMinutes: Int) =
            CountdownState(amountMinutes, EmergencyExtension.COUNTDOWN_SECONDS, running = true)
    }
}
