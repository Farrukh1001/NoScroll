package com.noscroll.domain.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budgeted/unrestricted split is the single most important classification in the app. Getting it
 * wrong in one direction wastes a few minutes of somebody's budget; getting it wrong in the other
 * covers their messages.
 */
class SurfaceTest {

    @Test
    fun everyDiscoverySurfaceIsBudgeted() {
        listOf(
            Surface.INSTAGRAM_REELS_VIEWER,
            Surface.INSTAGRAM_REELS_TAB,
            Surface.INSTAGRAM_EXPLORE,
            Surface.INSTAGRAM_SEARCH_DISCOVERY,
            Surface.INSTAGRAM_SAVED,
            Surface.INSTAGRAM_COLLECTION,
            Surface.INSTAGRAM_COLLECTION_ITEM,
            Surface.INSTAGRAM_DM_REEL,
        ).forEach { surface ->
            assertTrue("$surface should count against the budget", surface.isInstagramBudgeted)
        }
    }

    @Test
    fun messagingAndStoriesAreNeverBudgeted() {
        listOf(
            Surface.INSTAGRAM_DIRECT,
            Surface.INSTAGRAM_STORIES,
            Surface.INSTAGRAM_HOME,
            Surface.INSTAGRAM_PROFILE,
            Surface.INSTAGRAM_NOTIFICATIONS,
            Surface.INSTAGRAM_CREATE,
            Surface.INSTAGRAM_SETTINGS,
            Surface.INSTAGRAM_OTHER,
        ).forEach { surface ->
            assertFalse("$surface must not count against the budget", surface.isInstagramBudgeted)
        }
    }

    @Test
    fun messagingAndStoriesCanNeverBeBlocked() {
        assertTrue(Surface.INSTAGRAM_DIRECT.isInstagramProtectedFromBlocking)
        assertTrue(Surface.INSTAGRAM_STORIES.isInstagramProtectedFromBlocking)

        // Everything else may be blocked once the budget is gone.
        assertFalse(Surface.INSTAGRAM_REELS_VIEWER.isInstagramProtectedFromBlocking)
        assertFalse(Surface.INSTAGRAM_EXPLORE.isInstagramProtectedFromBlocking)
    }

    @Test
    fun noYouTubeSurfaceTouchesTheInstagramBudget() {
        Surface.entries
            .filter { it.packageName == Packages.YOUTUBE || it.packageName == Packages.YOUTUBE_MUSIC }
            .forEach { assertFalse("$surfaceName", it.isInstagramBudgeted) }
    }

    private val surfaceName get() = "YouTube surfaces must not consume the Instagram budget"

    @Test
    fun theBudgetedSetMatchesTheFlag() {
        Surface.entries.forEach { surface ->
            assertEquals(
                "$surface disagrees with Surface.BUDGETED",
                surface in Surface.BUDGETED,
                surface.isInstagramBudgeted,
            )
        }
    }

    @Test
    fun watchedPackagesCoverEveryProtectedApp() {
        assertTrue(Packages.YOUTUBE in Packages.WATCHED)
        assertTrue(Packages.YOUTUBE_MUSIC in Packages.WATCHED)
        assertTrue(Packages.INSTAGRAM in Packages.WATCHED)
        Packages.SUPPORTED_BROWSERS.forEach { assertTrue("$it should be watched", it in Packages.WATCHED) }
    }

    @Test
    fun onlyConfidentDetectionsAreActed_on() {
        assertTrue(Confidence.HIGH.isActionable)
        assertTrue(Confidence.MEDIUM.isActionable)
        // The whole fail-open design rests on this being false.
        assertFalse(Confidence.LOW.isActionable)

        assertFalse(SurfaceDetection.unknown().isActionable)
    }
}
