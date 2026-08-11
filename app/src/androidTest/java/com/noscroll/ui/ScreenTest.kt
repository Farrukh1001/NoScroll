package com.noscroll.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noscroll.domain.health.FilterStatus
import com.noscroll.domain.health.HealthSnapshot
import com.noscroll.domain.health.YouTubeMusicStatus
import com.noscroll.domain.instagram.InstagramBudgetState
import com.noscroll.domain.protection.PendingBundle
import com.noscroll.domain.protection.PendingEntry
import com.noscroll.domain.protection.SettingKey
import com.noscroll.domain.protection.SettingValue
import com.noscroll.ui.components.BudgetBar
import com.noscroll.ui.components.EmptyState
import com.noscroll.ui.components.SectionCard
import com.noscroll.ui.components.StatusRow
import com.noscroll.ui.screens.TodayScreen
import com.noscroll.ui.theme.NoScrollTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Compose tests for the screens that carry a promise: the dashboard must not claim protection that
 * is not running, the empty states must appear instead of fabricated numbers, and a pending change
 * must show what it will do and when.
 *
 * These need a device or emulator: `./gradlew connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val healthyState = NoScrollUiState(
        loading = false,
        health = HealthSnapshot(
            accessibilityConnected = true,
            filterStatus = FilterStatus.ACTIVE,
            usageAccessGranted = true,
            youTubeMusicStatus = YouTubeMusicStatus.DISABLED,
        ),
        preferences = com.noscroll.data.AppPreferencesRepository.Preferences(
            onboardingComplete = true,
            analyticsEnabled = true,
        ),
        today = LocalDate.of(2026, 8, 11),
    )

    @Test
    fun todayShowsTheEmptyStateWhenInsightsAreOff() {
        val state = healthyState.copy(
            preferences = healthyState.preferences.copy(analyticsEnabled = false),
        )
        compose.setContent { NoScrollTheme { TodayScreen(state) {} } }

        // No fabricated total in place of data we do not have.
        compose.onNodeWithText("Screen-time insights are off").assertIsDisplayed()
    }

    @Test
    fun todayReportsADisconnectedServiceHonestly() {
        val state = healthyState.copy(
            health = healthyState.health.copy(accessibilityConnected = false),
        )
        compose.setContent { NoScrollTheme { TodayScreen(state) {} } }

        compose.onNodeWithText("Not running").assertIsDisplayed()
    }

    @Test
    fun todayShowsTheExhaustedBudgetAndOffersExtraTime() {
        var extensionRequested = false
        val state = healthyState.copy(
            budget = InstagramBudgetState(
                date = LocalDate.of(2026, 8, 11),
                baseLimitMinutes = 30,
                consumedSeconds = 30 * 60L,
            ),
        )
        compose.setContent { NoScrollTheme { TodayScreen(state) { extensionRequested = true } } }

        compose.onNodeWithText("Today's scrolling time is finished. DMs and Stories are still available.")
            .assertIsDisplayed()
        compose.onNodeWithText("Request extra time").performClick()
        assert(extensionRequested)
    }

    @Test
    fun todayHidesExtraTimeOnceItHasBeenUsed() {
        val state = healthyState.copy(
            budget = InstagramBudgetState(
                date = LocalDate.of(2026, 8, 11),
                baseLimitMinutes = 30,
                consumedSeconds = 30 * 60L,
                emergencyUsedToday = true,
            ),
        )
        compose.setContent { NoScrollTheme { TodayScreen(state) {} } }

        compose.onNodeWithText("Request extra time").assertDoesNotExist()
    }

    @Test
    fun aPendingChangeShowsWhatItDoesAndWhen() {
        val state = healthyState.copy(
            pending = PendingBundle(
                entries = listOf(
                    PendingEntry(
                        SettingKey.SHIELD_MODULE,
                        SettingValue.Bool(true),
                        SettingValue.Bool(false),
                        "Turns off explicit content shield.",
                    )
                ),
                requestedAtWall = 0,
                totalDelayMillis = 24 * 3_600_000L,
                remainingMillisAtAnchor = 2 * 3_600_000L,
                anchorMonotonic = 0,
                anchorWall = 0,
            ),
            pendingRemainingMillis = 2 * 3_600_000L,
        )
        compose.setContent { NoScrollTheme { TodayScreen(state) {} } }

        compose.onNodeWithText("Explicit Content Shield").assertIsDisplayed()
        compose.onNodeWithText("Applies in 2h 0m.").assertIsDisplayed()
    }

    @Test
    fun statusRowAnnouncesItsStateInText() {
        // Colour alone is never the signal.
        compose.setContent {
            NoScrollTheme { StatusRow("Content filter", "Not running", ok = false) }
        }
        compose.onNodeWithText("Content filter").assertIsDisplayed()
        compose.onNodeWithText("Not running").assertIsDisplayed()
    }

    @Test
    fun budgetBarStatesTheRemainingTime() {
        compose.setContent {
            NoScrollTheme { BudgetBar(usedSeconds = 10 * 60, totalSeconds = 30 * 60) }
        }
        compose.onNodeWithText("20 min remaining of 30 min").assertIsDisplayed()
    }

    @Test
    fun emptyStateRendersItsMessage() {
        compose.setContent {
            NoScrollTheme { EmptyState("No baseline yet", "A comparison needs two days of data.") }
        }
        compose.onNodeWithText("No baseline yet").assertIsDisplayed()
        compose.onNodeWithText("A comparison needs two days of data.").assertIsDisplayed()
    }

    @Test
    fun sectionCardRendersItsTitleAndContent() {
        compose.setContent {
            NoScrollTheme { SectionCard(title = "Instagram") { Text("30 minutes remaining") } }
        }
        compose.onNodeWithText("Instagram").assertIsDisplayed()
        compose.onNodeWithText("30 minutes remaining").assertIsDisplayed()
    }
}
