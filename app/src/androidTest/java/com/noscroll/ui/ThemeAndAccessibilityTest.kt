package com.noscroll.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noscroll.ui.components.BarChart
import com.noscroll.ui.components.BudgetBar
import com.noscroll.ui.components.SectionCard
import com.noscroll.ui.components.StatusRow
import com.noscroll.ui.theme.NoScrollTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility and theming.
 *
 * The rule being tested: nothing in this app communicates state through colour alone, and no chart
 * is the only way to read its data. Both matter for a screen-reader user, and the second one also
 * matters for anyone who reads faster than they parse bars.
 */
@RunWith(AndroidJUnit4::class)
class ThemeAndAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyChartCarriesATextSummary() {
        val summary = "Daily screen time over the last seven days. Average 2h 10m."
        compose.setContent {
            NoScrollTheme {
                BarChart(
                    values = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L),
                    labels = listOf("M", "T", "W", "T", "F", "S", "S"),
                    textSummary = summary,
                )
            }
        }
        // Present both as visible text and as the canvas's content description.
        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.onNodeWithContentDescription(summary).assertExists()
    }

    @Test
    fun statusIsReadableWithoutColour() {
        compose.setContent {
            NoScrollTheme {
                Column {
                    StatusRow("Accessibility service", "Connected", ok = true)
                    StatusRow("Content filter", "Not running", ok = false)
                }
            }
        }
        compose.onNodeWithContentDescription("Accessibility service: Connected").assertExists()
        compose.onNodeWithContentDescription("Content filter: Not running").assertExists()
    }

    @Test
    fun budgetBarIsDescribedForScreenReaders() {
        compose.setContent {
            NoScrollTheme { BudgetBar(usedSeconds = 20 * 60, totalSeconds = 30 * 60) }
        }
        compose.onNodeWithContentDescription("10 minutes remaining of the Instagram budget").assertExists()
    }

    @Test
    fun contentSurvivesTheLargestFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            // 2x is beyond Android's own maximum; if the layout holds here it holds anywhere.
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2.0f)
            ) {
                NoScrollTheme {
                    SectionCard(title = "Instagram") {
                        Text("Today's scrolling time is finished.")
                    }
                }
            }
        }
        compose.onNodeWithText("Instagram").assertIsDisplayed()
        compose.onNodeWithText("Today's scrolling time is finished.").assertIsDisplayed()
    }

    @Test
    fun darkThemeRendersTheSameContent() {
        compose.setContent {
            NoScrollTheme(darkTheme = true) {
                SectionCard(title = "Protection") { Text("Everything essential is running.") }
            }
        }
        compose.onNodeWithText("Protection").assertIsDisplayed()
        compose.onNodeWithText("Everything essential is running.").assertIsDisplayed()
    }

    @Test
    fun lightThemeRendersTheSameContent() {
        compose.setContent {
            NoScrollTheme(darkTheme = false) {
                SectionCard(title = "Protection") { Text("Everything essential is running.") }
            }
        }
        compose.onNodeWithText("Protection").assertIsDisplayed()
        compose.onNodeWithText("Everything essential is running.").assertIsDisplayed()
    }
}
