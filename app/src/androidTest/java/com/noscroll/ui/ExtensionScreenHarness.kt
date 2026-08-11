package com.noscroll.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.test.core.app.ApplicationProvider
import com.noscroll.ui.extension.ExtensionScreen

/**
 * Renders [ExtensionScreen] against a supplied [NoScrollUiState].
 *
 * The screen takes a view model for its callbacks but renders entirely from the state object, so the
 * tests drive it by constructing states directly. A real view model is used rather than a fake
 * because it is cheap here and keeps the test honest about the screen's actual dependencies.
 */
@Composable
fun ExtensionScreenHarness(state: NoScrollUiState, onDone: () -> Unit = {}) {
    val application: Application = ApplicationProvider.getApplicationContext()
    val viewModel = remember { NoScrollViewModel(application) }
    ExtensionScreen(viewModel = viewModel, state = state, onDone = onDone)
}
