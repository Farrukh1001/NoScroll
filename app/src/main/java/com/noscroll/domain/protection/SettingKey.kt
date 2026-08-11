package com.noscroll.domain.protection

/**
 * Every protected switch in the app, in one place.
 *
 * A key knows how to classify a change to itself as strengthening, weakening or neutral, which is
 * what decides whether a change applies immediately or has to sit in the delay queue. Keeping that
 * knowledge on the key (instead of scattered through the UI) is what makes it impossible to add a
 * new setting that quietly bypasses the waiting period.
 */
enum class SettingType { BOOLEAN, INT }

/**
 * How a boolean setting relates to protection strength.
 *  - [PROTECTIVE]: turning it on strengthens protection (the normal case).
 *  - [PERMISSIVE]: turning it on weakens protection (e.g. the Instagram emergency extension).
 */
enum class BooleanPolarity { PROTECTIVE, PERMISSIVE }

/**
 * How an integer setting relates to protection strength.
 *  - [LOWER_IS_STRONGER]: e.g. the Instagram daily limit; lowering it is a strengthening change.
 *  - [HIGHER_IS_STRONGER]: e.g. the weakening delay itself; raising it is a strengthening change.
 */
enum class IntPolarity { LOWER_IS_STRONGER, HIGHER_IS_STRONGER }

enum class ChangeDirection { STRENGTHENING, WEAKENING, NEUTRAL }

enum class SettingKey(
    val id: String,
    val type: SettingType,
    val booleanPolarity: BooleanPolarity? = null,
    val intPolarity: IntPolarity? = null,
    val defaultBoolean: Boolean = false,
    val defaultInt: Int = 0,
    val minInt: Int = Int.MIN_VALUE,
    val maxInt: Int = Int.MAX_VALUE,
    /** Shown verbatim in the pending-change bundle to explain why a change weakens protection. */
    val label: String,
) {
    // ---- YouTube -------------------------------------------------------------------------
    YOUTUBE_MODULE("youtube.module", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "YouTube protection"),
    YOUTUBE_SHELF_MASK("youtube.shelf_mask", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Mask Shorts shelves on Home"),
    YOUTUBE_TAB_MASK("youtube.tab_mask", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Cover the Shorts navigation button"),
    YOUTUBE_VIEWER_BLOCK("youtube.viewer_block", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Block the Shorts viewer"),
    YOUTUBE_EXTERNAL_LINKS("youtube.external_links", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Open shared Shorts links as one isolated video"),

    // ---- YouTube Music -------------------------------------------------------------------
    YOUTUBE_MUSIC_MODULE("youtube_music.module", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "YouTube Music lockdown"),

    // ---- Instagram -----------------------------------------------------------------------
    INSTAGRAM_MODULE("instagram.module", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Instagram protection"),
    INSTAGRAM_SHARED_BUDGET("instagram.shared_budget", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Shared daily scrolling budget"),
    INSTAGRAM_REELS_TIMER("instagram.reels_timer", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Count Reels against the budget"),
    INSTAGRAM_EXPLORE_TIMER("instagram.explore_timer", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Count Explore and Search discovery against the budget"),
    INSTAGRAM_SAVED_TIMER("instagram.saved_timer", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Count Saved and Collections against the budget"),
    INSTAGRAM_HOME_REEL_MASK("instagram.home_reel_mask", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Mask Reel cards in the Home feed"),
    INSTAGRAM_FRIEND_SENT_SINGLE("instagram.friend_sent_single", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Friend-sent Reels open one at a time"),
    INSTAGRAM_REMAINING_BANNER("instagram.remaining_banner", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Show remaining time while browsing Collections"),
    INSTAGRAM_EMERGENCY_ENABLED(
        "instagram.emergency_enabled",
        SettingType.BOOLEAN,
        // Turning the emergency extension ON gives you a way to buy more scrolling: that weakens
        // protection, so it is the one boolean whose polarity is inverted.
        BooleanPolarity.PERMISSIVE,
        defaultBoolean = true,
        label = "Emergency extra Instagram time",
    ),
    INSTAGRAM_BASE_LIMIT_MINUTES(
        "instagram.base_limit_minutes",
        SettingType.INT,
        intPolarity = IntPolarity.LOWER_IS_STRONGER,
        defaultInt = 30,
        minInt = 0,
        maxInt = 600,
        label = "Daily Instagram scrolling budget (minutes)",
    ),
    INSTAGRAM_EMERGENCY_MAX_MINUTES(
        "instagram.emergency_max_minutes",
        SettingType.INT,
        intPolarity = IntPolarity.LOWER_IS_STRONGER,
        defaultInt = 30,
        minInt = 1,
        maxInt = 30,
        label = "Largest emergency extension (minutes)",
    ),

    // ---- Explicit Content Shield -----------------------------------------------------------
    SHIELD_MODULE("shield.module", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Explicit Content Shield"),
    SHIELD_SAFE_SEARCH("shield.safe_search", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Enforce SafeSearch"),
    SHIELD_FAIL_CLOSED("shield.fail_closed", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Block supported browsers when the filter is down"),
    SHIELD_BROWSER_GUARD("shield.browser_guard", SettingType.BOOLEAN, BooleanPolarity.PROTECTIVE, defaultBoolean = true, label = "Catch explicit addresses typed into browsers"),

    // ---- Global --------------------------------------------------------------------------
    WEAKENING_DELAY_HOURS(
        "global.weakening_delay_hours",
        SettingType.INT,
        intPolarity = IntPolarity.HIGHER_IS_STRONGER,
        defaultInt = 24,
        minInt = 1,
        maxInt = 48,
        label = "Waiting period before a weakening change applies (hours)",
    ),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): SettingKey? = byId[id]

        /** The delays the UI offers, in hours. */
        val DELAY_PRESET_HOURS = listOf(1, 2, 4, 8, 12, 24, 48)
    }
}

/** A boolean-or-int value; keeping both in one type lets the pending bundle stay generic. */
sealed interface SettingValue {
    data class Bool(val value: Boolean) : SettingValue
    data class Num(val value: Int) : SettingValue

    fun asBoolean(): Boolean = (this as Bool).value
    fun asInt(): Int = (this as Num).value

    /** Human-readable, used in the pending-change bundle. */
    fun display(): String = when (this) {
        is Bool -> if (value) "On" else "Off"
        is Num -> value.toString()
    }
}

object ChangeClassifier {

    /**
     * Classifies a single setting change. Anything that gives the user more access, less counting
     * or a shorter wait is [ChangeDirection.WEAKENING] and therefore has to go through the delay.
     */
    fun classify(key: SettingKey, current: SettingValue, requested: SettingValue): ChangeDirection {
        if (current == requested) return ChangeDirection.NEUTRAL
        return when (key.type) {
            SettingType.BOOLEAN -> {
                val turningOn = requested.asBoolean()
                when (key.booleanPolarity ?: BooleanPolarity.PROTECTIVE) {
                    BooleanPolarity.PROTECTIVE ->
                        if (turningOn) ChangeDirection.STRENGTHENING else ChangeDirection.WEAKENING
                    BooleanPolarity.PERMISSIVE ->
                        if (turningOn) ChangeDirection.WEAKENING else ChangeDirection.STRENGTHENING
                }
            }

            SettingType.INT -> {
                val delta = requested.asInt() - current.asInt()
                when (key.intPolarity ?: IntPolarity.LOWER_IS_STRONGER) {
                    IntPolarity.LOWER_IS_STRONGER ->
                        if (delta < 0) ChangeDirection.STRENGTHENING else ChangeDirection.WEAKENING
                    IntPolarity.HIGHER_IS_STRONGER ->
                        if (delta > 0) ChangeDirection.STRENGTHENING else ChangeDirection.WEAKENING
                }
            }
        }
    }

    /** Short sentence explaining the weakening, shown in the pending-change bundle. */
    fun explain(key: SettingKey, current: SettingValue, requested: SettingValue): String =
        when (classify(key, current, requested)) {
            ChangeDirection.STRENGTHENING -> "Strengthens protection."
            ChangeDirection.NEUTRAL -> "No change."
            ChangeDirection.WEAKENING -> when (key) {
                SettingKey.INSTAGRAM_BASE_LIMIT_MINUTES ->
                    "Raises the daily budget from ${current.display()} to ${requested.display()} minutes."
                SettingKey.INSTAGRAM_EMERGENCY_MAX_MINUTES ->
                    "Allows a larger emergency extension (${current.display()} -> ${requested.display()} minutes)."
                SettingKey.WEAKENING_DELAY_HOURS ->
                    "Shortens the waiting period from ${current.display()} to ${requested.display()} hours."
                SettingKey.INSTAGRAM_EMERGENCY_ENABLED ->
                    "Makes extra Instagram time available again."
                else -> "Turns off ${key.label.lowercase()}."
            }
        }
}
