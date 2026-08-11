package com.noscroll.domain.surface

/**
 * The screens NoScroll can recognise.
 *
 * Detection is best-effort pattern matching against a third-party app's UI, so every detection also
 * carries a [Confidence]. The rule throughout the app is that we only take a visible action
 * (masking, blocking, counting time) on a positive, confident detection — when we are unsure we let
 * the user through and rely on the next, more specific check. That asymmetry is deliberate: a false
 * negative costs a few seconds of scrolling, a false positive covers somebody's messages.
 */
enum class Surface(val packageName: String) {
    // YouTube
    YOUTUBE_HOME(Packages.YOUTUBE),
    YOUTUBE_SHORTS_VIEWER(Packages.YOUTUBE),
    YOUTUBE_WATCH(Packages.YOUTUBE),
    YOUTUBE_OTHER(Packages.YOUTUBE),

    // YouTube Music
    YOUTUBE_MUSIC(Packages.YOUTUBE_MUSIC),

    // Instagram — budgeted
    INSTAGRAM_REELS_VIEWER(Packages.INSTAGRAM),
    INSTAGRAM_REELS_TAB(Packages.INSTAGRAM),
    INSTAGRAM_EXPLORE(Packages.INSTAGRAM),
    INSTAGRAM_SEARCH_DISCOVERY(Packages.INSTAGRAM),
    INSTAGRAM_SAVED(Packages.INSTAGRAM),
    INSTAGRAM_COLLECTION(Packages.INSTAGRAM),
    INSTAGRAM_COLLECTION_ITEM(Packages.INSTAGRAM),
    INSTAGRAM_DM_REEL(Packages.INSTAGRAM),

    // Instagram — never budgeted
    INSTAGRAM_DIRECT(Packages.INSTAGRAM),
    INSTAGRAM_STORIES(Packages.INSTAGRAM),
    INSTAGRAM_HOME(Packages.INSTAGRAM),
    INSTAGRAM_PROFILE(Packages.INSTAGRAM),
    INSTAGRAM_NOTIFICATIONS(Packages.INSTAGRAM),
    INSTAGRAM_CREATE(Packages.INSTAGRAM),
    INSTAGRAM_SETTINGS(Packages.INSTAGRAM),
    INSTAGRAM_OTHER(Packages.INSTAGRAM),

    // Browsers
    BROWSER(""),

    UNKNOWN(""),
    ;

    /** Surfaces that consume the shared Instagram budget. */
    val isInstagramBudgeted: Boolean
        get() = this in BUDGETED

    /** Surfaces we must never cover, even when the budget is gone. */
    val isInstagramProtectedFromBlocking: Boolean
        get() = this == INSTAGRAM_DIRECT || this == INSTAGRAM_STORIES

    companion object {
        val BUDGETED = setOf(
            INSTAGRAM_REELS_VIEWER,
            INSTAGRAM_REELS_TAB,
            INSTAGRAM_EXPLORE,
            INSTAGRAM_SEARCH_DISCOVERY,
            INSTAGRAM_SAVED,
            INSTAGRAM_COLLECTION,
            INSTAGRAM_COLLECTION_ITEM,
            INSTAGRAM_DM_REEL,
        )
    }
}

enum class Confidence {
    /** Matched a stable, unambiguous signal (resource id, dedicated activity). Act on it. */
    HIGH,

    /** Matched several weaker signals that agree. Act on it. */
    MEDIUM,

    /** A guess. Never mask or block on this alone. */
    LOW,
    ;

    val isActionable: Boolean get() = this == HIGH || this == MEDIUM
}

data class SurfaceDetection(
    val surface: Surface,
    val confidence: Confidence,
) {
    val isActionable: Boolean get() = confidence.isActionable

    companion object {
        fun unknown() = SurfaceDetection(Surface.UNKNOWN, Confidence.LOW)
    }
}

object Packages {
    const val YOUTUBE = "com.google.android.youtube"
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    const val INSTAGRAM = "com.instagram.android"

    /** Browsers whose address bar we can read well enough to fail closed. */
    val SUPPORTED_BROWSERS = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "org.mozilla.firefox",
        "org.mozilla.focus",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.sec.android.app.sbrowser",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
    )

    val WATCHED = setOf(YOUTUBE, YOUTUBE_MUSIC, INSTAGRAM) + SUPPORTED_BROWSERS
}
