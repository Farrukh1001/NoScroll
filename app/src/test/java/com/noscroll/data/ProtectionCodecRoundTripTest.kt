package com.noscroll.data

import com.noscroll.domain.protection.SettingKey
import com.noscroll.domain.protection.SettingType
import com.noscroll.domain.protection.SettingValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The presets are what a new user actually gets, so they are worth asserting rather than trusting.
 */
class ProtectionPresetTest {

    @Test
    fun everyPresetTurnsEveryProtectionOn() {
        ProtectionPreset.entries.forEach { preset ->
            SettingKey.entries
                .filter { it.type == SettingType.BOOLEAN && it != SettingKey.INSTAGRAM_EMERGENCY_ENABLED }
                .forEach { key ->
                    assertEquals(
                        "${preset.name} should enable $key",
                        SettingValue.Bool(true),
                        preset.values[key],
                    )
                }
        }
    }

    @Test
    fun theRecommendedPresetMatchesTheDocumentedDefaults() {
        val recommended = ProtectionPreset.RECOMMENDED.values
        assertEquals(SettingValue.Num(30), recommended[SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES])
        assertEquals(SettingValue.Num(24), recommended[SettingKey.WEAKENING_DELAY_HOURS])
        assertEquals(SettingValue.Bool(true), recommended[SettingKey.INSTAGRAM_EMERGENCY_ENABLED])
    }

    @Test
    fun strictIsActuallyStricter() {
        val strict = ProtectionPreset.STRICT.values
        val recommended = ProtectionPreset.RECOMMENDED.values

        val strictLimit = (strict[SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES] as SettingValue.Num).value
        val recommendedLimit = (recommended[SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES] as SettingValue.Num).value
        assertTrue("a smaller budget", strictLimit < recommendedLimit)

        val strictDelay = (strict[SettingKey.WEAKENING_DELAY_HOURS] as SettingValue.Num).value
        val recommendedDelay = (recommended[SettingKey.WEAKENING_DELAY_HOURS] as SettingValue.Num).value
        assertTrue("a longer wait", strictDelay > recommendedDelay)

        assertEquals(
            "and no way to buy extra time",
            SettingValue.Bool(false),
            strict[SettingKey.INSTAGRAM_EMERGENCY_ENABLED],
        )
    }

    @Test
    fun communicationFirstStillProtectsMessagingByLeavingItAlone() {
        // There is no "block DMs" setting to check, which is the point: messaging is never a
        // budgeted surface in any preset, so no preset can restrict it.
        val preset = ProtectionPreset.COMMUNICATION_FIRST.values
        assertEquals(SettingValue.Num(20), preset[SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES])
        assertEquals(SettingValue.Num(12), preset[SettingKey.WEAKENING_DELAY_HOURS])
    }

    @Test
    fun everyPresetCoversEverySetting() {
        // A preset that silently omitted a key would leave it at whatever it happened to be.
        ProtectionPreset.entries.forEach { preset ->
            assertEquals(
                "${preset.name} should specify every setting",
                SettingKey.entries.size,
                preset.values.size,
            )
        }
    }

    @Test
    fun presetDelaysAreAllOfferedInTheUi() {
        ProtectionPreset.entries.forEach { preset ->
            val hours = (preset.values[SettingKey.WEAKENING_DELAY_HOURS] as SettingValue.Num).value
            assertTrue(
                "${preset.name} uses a delay of ${hours}h, which is not one of the presets offered",
                hours in SettingKey.DELAY_PRESET_HOURS,
            )
        }
    }
}
