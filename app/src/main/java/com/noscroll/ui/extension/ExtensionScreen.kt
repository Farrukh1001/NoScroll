package com.noscroll.ui.extension

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noscroll.domain.instagram.EmergencyExtension
import com.noscroll.domain.instagram.ExtensionRejection
import com.noscroll.domain.protection.SettingKey
import com.noscroll.ui.NoScrollUiState
import com.noscroll.ui.NoScrollViewModel
import com.noscroll.ui.components.SectionCard

/**
 * The emergency extension flow: choose an amount, wait a minute, type a sentence.
 *
 * The friction is the feature. A minute is long enough for the impulse that opened this screen to
 * pass, and typing the sentence by hand makes the amount a decision rather than a tap. Paste and
 * autofill are switched off as far as the platform allows, and the countdown pauses whenever the app
 * leaves the foreground so it cannot be waited out in a pocket.
 */
@Composable
fun ExtensionScreen(viewModel: NoScrollViewModel, state: NoScrollUiState, onDone: () -> Unit) {
    val maxMinutes = state.protection.int(SettingKey.INSTAGRAM_EMERGENCY_MAX_MINUTES)
    var amountText by remember { mutableStateOf("") }
    var typedSentence by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.extensionGranted?.let { granted ->
            SectionCard(title = "Extra time added") {
                Text("$granted extra minutes were added for today.")
                Text(
                    "That is the only extension available today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { viewModel.clearExtensionResult(); onDone() }) { Text("Done") }
            }
            return@Column
        }

        val budget = state.budget
        if (budget != null && !budget.baseExhausted) {
            SectionCard(title = "Not yet") {
                Text("Extra time only unlocks once your daily budget is used up.")
                Text(
                    "${budget.baseRemainingSeconds / 60} minutes still remain.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDone) { Text("Back") }
            }
            return@Column
        }

        if (budget != null && budget.emergencyUsedToday) {
            SectionCard(title = "Already used today") {
                Text("One extension per day. The next one is available tomorrow.")
                TextButton(onClick = onDone) { Text("Back") }
            }
            return@Column
        }

        val countdown = state.countdown

        when {
            countdown == null -> {
                SectionCard(title = "How many extra minutes?") {
                    Text(
                        "Choose a whole number between ${EmergencyExtension.MIN_MINUTES} and $maxMinutes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 20, 30).filter { it <= maxMinutes }.forEach { preset ->
                            FilterChip(
                                selected = amountText == preset.toString(),
                                onClick = { amountText = preset.toString() },
                                label = { Text("$preset") },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.extensionError == ExtensionRejection.INVALID_AMOUNT) {
                        Text(
                            "Enter a whole number between ${EmergencyExtension.MIN_MINUTES} and $maxMinutes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            // parseAmount is the single place that decides what is valid, so the
                            // screen and the tests cannot disagree about it.
                            val parsed = EmergencyExtension.parseAmount(amountText, maxMinutes)
                            if (parsed == null) {
                                viewModel.reportInvalidAmount()
                            } else {
                                viewModel.startCountdown(parsed)
                            }
                        },
                        enabled = amountText.isNotBlank(),
                    ) { Text("Continue") }
                    TextButton(onClick = onDone) { Text("Cancel") }
                }
            }

            !countdown.complete -> {
                SectionCard(title = "Take a minute") {
                    Text(
                        "${countdown.remainingSeconds} seconds",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = {
                            1f - countdown.remainingSeconds.toFloat() / EmergencyExtension.COUNTDOWN_SECONDS
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (countdown.running) {
                            "Requesting ${countdown.amountMinutes} extra minutes."
                        } else {
                            "Paused. The countdown continues when NoScroll is open."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { viewModel.cancelCountdown(); onDone() }) { Text("Cancel") }
                }
            }

            else -> {
                SectionCard(title = "Type this to confirm") {
                    Text(
                        EmergencyExtension.requiredSentence(countdown.amountMinutes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = typedSentence,
                        onValueChange = { typedSentence = it },
                        label = { Text("Type it exactly") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            // Autocorrect off so the keyboard cannot type the sentence for them.
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text,
                        ),
                    )
                    if (state.extensionError == ExtensionRejection.SENTENCE_MISMATCH) {
                        Text(
                            "That does not match. Type the sentence exactly as shown.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.confirmExtension(typedSentence) },
                        enabled = typedSentence.isNotBlank(),
                    ) { Text("Add ${countdown.amountMinutes} minutes") }
                    TextButton(onClick = { viewModel.cancelCountdown(); onDone() }) { Text("Cancel") }
                }
            }
        }
    }
}
