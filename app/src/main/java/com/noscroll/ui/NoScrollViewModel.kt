package com.noscroll.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noscroll.NoScrollApplication
import com.noscroll.data.AppPreferencesRepository
import com.noscroll.data.ProtectionPreset
import com.noscroll.di.AppContainer
import com.noscroll.domain.health.HealthSnapshot
import com.noscroll.domain.instagram.CountdownState
import com.noscroll.domain.instagram.EmergencyExtension
import com.noscroll.domain.instagram.ExtensionRejection
import com.noscroll.domain.instagram.InstagramBudgetState
import com.noscroll.domain.instagram.ExtensionException
import com.noscroll.domain.notify.MessageCatalogue
import com.noscroll.domain.protection.PendingBundle
import com.noscroll.domain.protection.ProtectedSettingsEngine
import com.noscroll.domain.protection.ProtectionState
import com.noscroll.domain.protection.RejectionReason
import com.noscroll.domain.protection.RequestOutcome
import com.noscroll.domain.protection.SettingKey
import com.noscroll.domain.wellbeing.TimeSavedCalculator
import com.noscroll.domain.wellbeing.TimeSavedResult
import com.noscroll.domain.wellbeing.UsageAggregate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

data class NoScrollUiState(
    val loading: Boolean = true,
    val protection: ProtectionState = ProtectionState.withDefaults(),
    val pending: PendingBundle? = null,
    val pendingRemainingMillis: Long = 0L,
    val budget: InstagramBudgetState? = null,
    val preferences: AppPreferencesRepository.Preferences = AppPreferencesRepository.Preferences(),
    val health: HealthSnapshot = HealthSnapshot(),
    val aggregate: UsageAggregate = UsageAggregate(),
    val today: LocalDate = LocalDate.now(),
    val comparisonWithYesterday: TimeSavedResult = TimeSavedResult.InsufficientData,
    val comparisonWithAverage: TimeSavedResult = TimeSavedResult.InsufficientData,
    val blockedToday: Int = 0,
    val achievements: List<com.noscroll.domain.wellbeing.Achievement> = emptyList(),
    val lastMessage: String? = null,
    val countdown: CountdownState? = null,
    val extensionError: ExtensionRejection? = null,
    val extensionGranted: Int? = null,
) {
    val screenTimeTodayMillis: Long get() = aggregate.totalFor(today)

    /** Only ever a real, measured comparison — never a fabricated one. See [MessageCatalogue]. */
    val encouragement: String?
        get() = MessageCatalogue.timeSaved(comparisonWithYesterday, preferences.tone)?.text
            ?: MessageCatalogue.timeSaved(comparisonWithAverage, preferences.tone)?.text
}

/**
 * One view model for the whole app.
 *
 * With this few screens, a single state object is easier to keep consistent than five that have to
 * agree with each other — and consistency matters here, because the protection state shown on
 * Settings and the one enforced by the accessibility service must never diverge.
 */
class NoScrollViewModel(application: Application) : AndroidViewModel(application) {

    private val container: AppContainer = (application as NoScrollApplication).container
    private val engine = ProtectedSettingsEngine()
    private val timeSavedCalculator = TimeSavedCalculator()
    private val achievementCalculator = com.noscroll.domain.wellbeing.AchievementCalculator()

    private val _state = MutableStateFlow(NoScrollUiState())
    val state: StateFlow<NoScrollUiState> = _state.asStateFlow()

    init {
        observe()
        refreshWellbeing()
    }

    private fun observe() {
        viewModelScope.launch {
            combine(
                container.protectionRepository.state,
                container.appPreferences.preferences,
                container.protectionHealth.snapshot,
            ) { protection, prefs, health ->
                Triple(protection, prefs, health)
            }.collect { (protection, prefs, health) ->
                val budget = container.instagramBudgetRepository
                    .current(protection.int(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES))
                _state.update { current ->
                    current.copy(
                        loading = false,
                        protection = protection,
                        pending = protection.pending,
                        pendingRemainingMillis = protection.pending?.let {
                            engine.remainingMillis(it, container.timeSource.wallMillis(), container.timeSource.monotonicMillis())
                        } ?: 0L,
                        budget = budget,
                        preferences = prefs,
                        health = health,
                        blockedToday = container.protectionHealth.blockedAttemptsToday(),
                    )
                }
            }
        }

        // Keeps the pending countdown honest without re-reading the whole store every second.
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val pending = _state.value.pending ?: continue
                _state.update {
                    it.copy(
                        pendingRemainingMillis = engine.remainingMillis(
                            pending,
                            container.timeSource.wallMillis(),
                            container.timeSource.monotonicMillis(),
                        )
                    )
                }
            }
        }
    }

    fun refreshWellbeing() {
        viewModelScope.launch {
            container.refreshHealth()
            val today = container.timeSource.today()
            val aggregate = withContext(Dispatchers.IO) {
                runCatching { container.wellbeingDatabase.readAggregate(today.minusDays(35), today) }
                    .getOrDefault(UsageAggregate())
            }
            val now = LocalDateTime.now()
            val prefs = container.appPreferences.current()
            val achievements = runCatching {
                achievementCalculator.evaluate(
                    aggregate = aggregate,
                    today = today,
                    dailyGoalMinutes = prefs.dailyGoalMinutes,
                    instagramGoalMinutes = prefs.instagramGoalMinutes,
                )
            }.getOrDefault(emptyList())

            _state.update {
                it.copy(
                    today = today,
                    aggregate = aggregate,
                    achievements = achievements,
                    comparisonWithYesterday = timeSavedCalculator.compareWithYesterday(aggregate, today, now.hour, now.minute),
                    comparisonWithAverage = timeSavedCalculator.compareWithAverage(aggregate, today, now.hour, now.minute),
                )
            }
        }
    }

    /**
     * Builds the sanitised diagnostics report for the copy button.
     *
     * Service and permission states, an error category, and version strings. Never a username,
     * message, title, search, domain, or any raw accessibility text.
     */
    fun diagnosticsReport(): String {
        val state = _state.value
        return buildString {
            appendLine("NoScroll diagnostics")
            appendLine("app version: ${com.noscroll.BuildConfig.VERSION_NAME}")
            appendLine("android sdk: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("accessibility connected: ${state.health.accessibilityConnected}")
            appendLine("filter status: ${state.health.filterStatus}")
            appendLine("vpn prepared: ${state.health.vpnPrepared}")
            appendLine("usage access: ${state.health.usageAccessGranted}")
            appendLine("notifications allowed: ${state.health.notificationsAllowed}")
            appendLine("youtube music: ${state.health.youTubeMusicStatus}")
            appendLine("rule set: ${state.health.ruleSetVersion}")
            appendLine("last error category: ${state.health.lastErrorCategory ?: "none"}")
            appendLine("analytics enabled: ${state.preferences.analyticsEnabled}")
            appendLine("notification limit: ${state.preferences.notificationDailyLimit}")
            appendLine("weakening delay hours: ${state.protection.int(SettingKey.WEAKENING_DELAY_HOURS)}")
            appendLine("pending change: ${state.pending?.entries?.size ?: 0} entries")
            appendLine("instagram budget minutes: ${state.protection.int(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES)}")
            appendLine("blocked attempts today: ${state.blockedToday}")
            appendLine("(contains no usernames, messages, titles, searches, domains or screen text)")
        }
    }

    /**
     * Exports wellbeing data as CSV.
     *
     * Aggregates only — a date, a package name, and a duration. This is the same data the Insights
     * screen shows, in a form the user can keep. Nothing else in the app is exportable because
     * nothing else is theirs to take.
     */
    fun exportWellbeingCsv(): String {
        val state = _state.value
        return buildString {
            appendLine("date,package,foreground_millis")
            state.aggregate.perDayPerPackage.toSortedMap().forEach { (day, packages) ->
                packages.toSortedMap().forEach { (packageName, millis) ->
                    appendLine("$day,$packageName,$millis")
                }
            }
        }
    }

    // ---- Protected settings -----------------------------------------------------------------

    fun setBoolean(key: SettingKey, value: Boolean, onResult: (RequestOutcome) -> Unit = {}) {
        viewModelScope.launch { onResult(container.protectionRepository.setBoolean(key, value)) }
    }

    fun setInt(key: SettingKey, value: Int, onResult: (RequestOutcome) -> Unit = {}) {
        viewModelScope.launch { onResult(container.protectionRepository.setInt(key, value)) }
    }

    fun applyPreset(preset: ProtectionPreset) {
        viewModelScope.launch { container.protectionRepository.applyPreset(preset) }
    }

    /** Cancelling is immediate. Acceleration is not offered, because it would defeat the point. */
    fun cancelPending() {
        viewModelScope.launch { container.protectionRepository.cancelPending() }
    }

    // ---- Emergency Instagram extension -------------------------------------------------------

    /**
     * Starts the 60-second countdown. It runs only while this screen is in the foreground: see
     * [pauseCountdown]/[resumeCountdown], wired to the lifecycle.
     */
    fun startCountdown(amountMinutes: Int) {
        val max = _state.value.protection.int(SettingKey.INSTAGRAM_EMERGENCY_MAX_MINUTES)
        if (!EmergencyExtension.isValidAmount(amountMinutes, max)) {
            _state.update { it.copy(extensionError = ExtensionRejection.INVALID_AMOUNT) }
            return
        }
        _state.update { it.copy(countdown = CountdownState.start(amountMinutes), extensionError = null) }
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val countdown = _state.value.countdown ?: return@launch
                if (countdown.complete) return@launch
                _state.update { it.copy(countdown = it.countdown?.tick()) }
            }
        }
    }

    /** Surfaces the out-of-range message without starting anything. */
    fun reportInvalidAmount() = _state.update { it.copy(extensionError = ExtensionRejection.INVALID_AMOUNT) }

    fun pauseCountdown() = _state.update { it.copy(countdown = it.countdown?.pause()) }

    fun resumeCountdown() = _state.update { it.copy(countdown = it.countdown?.resume()) }

    fun cancelCountdown() = _state.update { it.copy(countdown = null, extensionError = null) }

    /** Confirms the extension. Nothing is consumed unless the whole flow succeeded. */
    fun confirmExtension(typedSentence: String) {
        viewModelScope.launch {
            val countdown = _state.value.countdown ?: return@launch
            val protection = _state.value.protection
            val limit = protection.int(SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES)
            val budget = container.instagramBudgetRepository.current(limit)

            val result = EmergencyExtension.grant(
                state = budget,
                amountMinutes = countdown.amountMinutes,
                typedSentence = typedSentence,
                countdownComplete = countdown.complete,
                enabled = protection.bool(SettingKey.INSTAGRAM_EMERGENCY_ENABLED),
                maxMinutes = protection.int(SettingKey.INSTAGRAM_EMERGENCY_MAX_MINUTES),
            )

            result.fold(
                onSuccess = { granted ->
                    container.instagramBudgetRepository.update(limit) { granted }
                    _state.update {
                        it.copy(countdown = null, extensionGranted = countdown.amountMinutes, extensionError = null)
                    }
                },
                onFailure = { error ->
                    val rejection = (error as? ExtensionException)?.rejection ?: ExtensionRejection.INVALID_AMOUNT
                    _state.update { it.copy(extensionError = rejection) }
                },
            )
        }
    }

    fun clearExtensionResult() = _state.update { it.copy(extensionGranted = null, extensionError = null) }

    // ---- Privacy and preferences (never delayed) ----------------------------------------------

    fun setAnalyticsEnabled(enabled: Boolean) {
        viewModelScope.launch { container.appPreferences.setAnalyticsEnabled(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { container.appPreferences.setNotificationsEnabled(enabled) }
    }

    fun setNotificationLimit(limit: Int) {
        viewModelScope.launch { container.appPreferences.setNotificationLimit(limit) }
    }

    fun setEveningReminder(enabled: Boolean, hour: Int, minute: Int) {
        viewModelScope.launch {
            container.appPreferences.setEveningReminderEnabled(enabled)
            container.appPreferences.setEveningReminderTime(hour, minute)
            if (enabled) {
                com.noscroll.work.NoScrollWorkScheduler.scheduleEveningSummary(getApplication(), hour, minute)
            } else {
                com.noscroll.work.NoScrollWorkScheduler.cancelEveningSummary(getApplication())
            }
        }
    }

    fun setTone(tone: com.noscroll.domain.notify.MessageTone) {
        viewModelScope.launch { container.appPreferences.setTone(tone) }
    }

    fun setDailyGoalMinutes(minutes: Int) {
        viewModelScope.launch { container.appPreferences.setDailyGoalMinutes(minutes) }
    }

    fun setRetentionMonths(months: Int) {
        viewModelScope.launch { container.appPreferences.setRetentionMonths(months) }
    }

    /**
     * Deletes screen-time history and nothing else. The Instagram budget and any pending protected
     * change live in separate stores and are untouched by design.
     */
    fun deleteWellbeingData(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { container.wellbeingDatabase.deleteAllWellbeingData() } }
            refreshWellbeing()
            onDone()
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { container.appPreferences.setOnboardingComplete(true) }
    }

    fun rejectionMessage(reason: RejectionReason): String = when (reason) {
        RejectionReason.PENDING_ALREADY_EXISTS ->
            "There is already a change waiting. Cancel it first, or wait for it to apply."
        RejectionReason.CANCEL_COOLDOWN ->
            "You cancelled a change recently. Try again shortly."
        RejectionReason.DELAY_CHANGE_RATE_LIMITED ->
            "The waiting period can only be changed once a day."
        RejectionReason.DELAY_CHANGE_MUST_BE_ALONE ->
            "Change the waiting period on its own."
        RejectionReason.VALUE_OUT_OF_RANGE ->
            "That value is outside the allowed range."
        RejectionReason.NOTHING_TO_DO -> "Nothing to change."
    }
}
