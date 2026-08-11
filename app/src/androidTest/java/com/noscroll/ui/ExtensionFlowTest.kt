package com.noscroll.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noscroll.domain.instagram.CountdownState
import com.noscroll.domain.instagram.EmergencyExtension
import com.noscroll.domain.instagram.ExtensionRejection
import com.noscroll.domain.instagram.InstagramBudgetState
import com.noscroll.ui.theme.NoScrollTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The emergency-extension screen.
 *
 * The friction is the feature, so these tests check that the friction is actually there: the flow is
 * unavailable while the base budget has time left, unavailable a second time in one day, and the
 * confirmation is a sentence the user has to type rather than a button they can tap.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 8, 11)

    private fun stateWith(
        budget: InstagramBudgetState,
        countdown: CountdownState? = null,
        error: ExtensionRejection? = null,
        granted: Int? = null,
    ) = NoScrollUiState(
        loading = false,
        budget = budget,
        countdown = countdown,
        extensionError = error,
        extensionGranted = granted,
        today = today,
        preferences = com.noscroll.data.AppPreferencesRepository.Preferences(onboardingComplete = true),
    )

    private val exhausted = InstagramBudgetState(today, baseLimitMinutes = 30, consumedSeconds = 30 * 60L)

    @Test
    fun theFlowIsClosedWhileTheBudgetHasTimeLeft() {
        val state = stateWith(exhausted.copy(consumedSeconds = 10 * 60L))
        compose.setContent { NoScrollTheme { ExtensionScreenHarness(state) } }

        compose.onNodeWithText("Not yet").assertIsDisplayed()
        compose.onNodeWithText("Extra time only unlocks once your daily budget is used up.").assertIsDisplayed()
    }

    @Test
    fun theFlowIsClosedOnceUsedToday() {
        val state = stateWith(exhausted.copy(emergencyUsedToday = true))
        compose.setContent { NoScrollTheme { ExtensionScreenHarness(state) } }

        compose.onNodeWithText("Already used today").assertIsDisplayed()
        compose.onNodeWithText("One extension per day. The next one is available tomorrow.").assertIsDisplayed()
    }

    @Test
    fun theCountdownIsShownAndCannotBeSkipped() {
        val state = stateWith(exhausted, countdown = CountdownState(10, 42, running = true))
        compose.setContent { NoScrollTheme { ExtensionScreenHarness(state) } }

        compose.onNodeWithText("42 seconds").assertIsDisplayed()
        compose.onNodeWithText("Requesting 10 extra minutes.").assertIsDisplayed()
        // No confirmation control exists until the countdown completes.
        compose.onNodeWithText("Add 10 minutes").assertDoesNotExist()
    }

    @Test
    fun aPausedCountdownSaysSo() {
        val state = stateWith(exhausted, countdown = CountdownState(10, 42, running = false))
        compose.setContent { NoScrollTheme { ExtensionScreenHarness(state) } }

        compose.onNodeWithText("Paused. The countdown continues when NoScroll is open.").assertIsDisplayed()
    }

    @Test
    fun theSentenceMustBeTypedBeforeConfirmingIsPossible() {
        val state = stateWith(exhausted, countdown = CountdownState(17, 0, running = false))
        compose.setContent { NoScrollTheme { ExtensionScreenHarness(state) } }

        compose.onNodeWithText(EmergencyExtension.requiredSentence(17)).assertIsDisplayed()
        // Empty field: the confirm button is not usable.
        compose.onNodeWithText("Add 17 minutes").assertIsNotEnabled()

        compose.onNodeWithText("Type it exactly").performTextInput(EmergencyExtension.requiredSentence(17))
        compose.onNodeWithText("Add 17 minutes").assertIsDisplayed()
    }

    @Test
    fun aMismatchedSentenceIsReported() {
        val state = stateWith(
            exhausted,
            countdown = CountdownState(5, 0, running = false),
            error = ExtensionRejection.SENTENCE_MISMATCH,
        )
        compose.setContent { NoScrollTheme { ExtensionScreenHarness(state) } }

        compose.onNodeWithText("That does not match. Type the sentence exactly as shown.").assertIsDisplayed()
    }

    @Test
    fun aGrantSaysItIsTheOnlyOneToday() {
        val state = stateWith(exhausted, granted = 17)
        compose.setContent { NoScrollTheme { ExtensionScreenHarness(state) } }

        compose.onNodeWithText("17 extra minutes were added for today.").assertIsDisplayed()
        compose.onNodeWithText("That is the only extension available today.").assertIsDisplayed()
    }
}
