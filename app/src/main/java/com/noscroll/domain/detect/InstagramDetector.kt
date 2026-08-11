package com.noscroll.domain.detect

import com.noscroll.domain.surface.Confidence
import com.noscroll.domain.surface.Surface
import com.noscroll.domain.surface.SurfaceDetection

/**
 * Works out which Instagram screen is in front of the user.
 *
 * One naming trap drives most of this file: inside Instagram's own view ids, **"clips" means Reels
 * and "reel" means Stories**. `clips_viewer_view_pager` is the Reels feed; `reel_viewer_*` is the
 * Stories player. Getting those two backwards would count Stories against the budget and let Reels
 * run free, so the Stories check runs first and wins.
 *
 * When nothing matches confidently we return [Surface.INSTAGRAM_OTHER] with LOW confidence, which
 * the caller treats as "don't count, don't block".
 */
object InstagramDetector {

    fun detect(root: UiNode, navigation: NavigationContext = NavigationContext.NONE): SurfaceDetection {
        // --- Never-budgeted surfaces first, so an ambiguous match can never swallow messages. ---
        stories(root)?.let { return it }
        direct(root, navigation)?.let { return it }

        reels(root, navigation)?.let { return it }
        savedOrCollection(root, navigation)?.let { return it }
        explore(root)?.let { return it }
        other(root)?.let { return it }

        return SurfaceDetection(Surface.INSTAGRAM_OTHER, Confidence.LOW)
    }

    /**
     * Stories. Instagram calls them "reel" internally, and the story tray/player ids are stable.
     */
    private fun stories(root: UiNode): SurfaceDetection? {
        val storyPlayer = root.anyDescendant { node ->
            node.idContains("reel_viewer_", "reel_dismiss", "story_viewer", "reel_progress_bar")
        }
        // "clips" ids must not be present, otherwise we are looking at Reels, not Stories.
        val hasClips = root.anyDescendant { it.idContains("clips_") }
        return if (storyPlayer && !hasClips) SurfaceDetection(Surface.INSTAGRAM_STORIES, Confidence.HIGH) else null
    }

    /** Direct messages: the inbox, a thread, or DM search. Always unrestricted. */
    private fun direct(root: UiNode, navigation: NavigationContext): SurfaceDetection? {
        val threadOrInbox = root.anyDescendant { node ->
            node.idContains("direct_thread", "direct_inbox", "thread_message", "row_thread", "direct_search")
        }
        if (!threadOrInbox) return null

        // A Reel opened from a DM still shows the clips viewer on top of the thread; that case is a
        // budgeted single-item view, not plain messaging.
        val clipsOnTop = root.anyDescendant { it.idContains("clips_viewer", "clips_video_container") }
        if (clipsOnTop) {
            return SurfaceDetection(Surface.INSTAGRAM_DM_REEL, Confidence.HIGH)
        }
        if (navigation == NavigationContext.FROM_DIRECT_MESSAGE) {
            return SurfaceDetection(Surface.INSTAGRAM_DIRECT, Confidence.HIGH)
        }
        return SurfaceDetection(Surface.INSTAGRAM_DIRECT, Confidence.HIGH)
    }

    /** The Reels viewer and the Reels tab. */
    private fun reels(root: UiNode, navigation: NavigationContext): SurfaceDetection? {
        val viewer = root.anyDescendant { node ->
            node.idContains("clips_viewer", "clips_video_container", "clips_swipe_refresh")
        }
        if (viewer) {
            val surface = if (navigation == NavigationContext.FROM_DIRECT_MESSAGE) {
                Surface.INSTAGRAM_DM_REEL
            } else {
                Surface.INSTAGRAM_REELS_VIEWER
            }
            return SurfaceDetection(surface, Confidence.HIGH)
        }
        val tab = root.anyDescendant { node ->
            node.idContains("clips_tab") || (node.idContains("tab_") && node.descriptionEquals("reels"))
        }
        return if (tab) SurfaceDetection(Surface.INSTAGRAM_REELS_TAB, Confidence.MEDIUM) else null
    }

    /**
     * Saved, the collection list, a collection grid, and anything opened from one.
     *
     * Once the user is inside a collection, opening a post keeps the budget running: the caller
     * passes [NavigationContext.FROM_SAVED_COLLECTION] so provenance survives the navigation even
     * though the opened post looks like an ordinary feed item.
     */
    private fun savedOrCollection(root: UiNode, navigation: NavigationContext): SurfaceDetection? {
        val savedHome = root.anyDescendant { it.idContains("save_home", "saved_feed", "saved_collections") }
        if (savedHome) return SurfaceDetection(Surface.INSTAGRAM_SAVED, Confidence.HIGH)

        val collectionGrid = root.anyDescendant { it.idContains("collection_grid", "save_collection", "collection_feed") }
        if (collectionGrid) return SurfaceDetection(Surface.INSTAGRAM_COLLECTION, Confidence.HIGH)

        if (navigation == NavigationContext.FROM_SAVED_COLLECTION) {
            val looksLikeContent = root.anyDescendant { it.idContains("media_group", "carousel_", "row_feed_photo", "video_container") }
            if (looksLikeContent) return SurfaceDetection(Surface.INSTAGRAM_COLLECTION_ITEM, Confidence.MEDIUM)
        }
        return null
    }

    /** Explore grid and search results browsing — but never DM search. */
    private fun explore(root: UiNode): SurfaceDetection? {
        val exploreGrid = root.anyDescendant { it.idContains("explore_grid", "discovery_grid", "explore_recycler") }
        if (exploreGrid) return SurfaceDetection(Surface.INSTAGRAM_EXPLORE, Confidence.HIGH)

        val searchDiscovery = root.anyDescendant { it.idContains("search_result", "serp_", "search_grid") } &&
            !root.anyDescendant { it.idContains("direct_search", "direct_inbox") }
        return if (searchDiscovery) SurfaceDetection(Surface.INSTAGRAM_SEARCH_DISCOVERY, Confidence.MEDIUM) else null
    }

    /** Everything else we can name confidently, so the tracker knows the user left a budgeted screen. */
    private fun other(root: UiNode): SurfaceDetection? {
        if (root.anyDescendant { it.idContains("profile_header", "user_detail", "profile_tab") }) {
            return SurfaceDetection(Surface.INSTAGRAM_PROFILE, Confidence.HIGH)
        }
        if (root.anyDescendant { it.idContains("news_inbox", "activity_feed", "notification_") }) {
            return SurfaceDetection(Surface.INSTAGRAM_NOTIFICATIONS, Confidence.HIGH)
        }
        if (root.anyDescendant { it.idContains("gallery_", "camera_", "share_", "creation_") }) {
            return SurfaceDetection(Surface.INSTAGRAM_CREATE, Confidence.MEDIUM)
        }
        if (root.anyDescendant { it.idContains("settings_", "preference_") }) {
            return SurfaceDetection(Surface.INSTAGRAM_SETTINGS, Confidence.MEDIUM)
        }
        if (root.anyDescendant { it.idContains("feed_timeline", "main_feed", "row_feed_") }) {
            return SurfaceDetection(Surface.INSTAGRAM_HOME, Confidence.HIGH)
        }
        return null
    }

    /**
     * Reel cards embedded in the Home feed, returned as bounds for the masking overlay.
     *
     * Only nodes that are positively a Reel module are returned; an ordinary photo or carousel has
     * no `clips_` id and is left alone. If nothing matches we mask nothing and let the viewer-level
     * check handle it.
     */
    /**
     * Which page of the Reels pager is showing, read from the collection metadata the pager already
     * publishes for screen readers. Null when the pager does not report a position.
     *
     * Single-item mode compares this against the page the Reel was opened on. That is the whole
     * mechanism: no content is read, and no gesture is intercepted.
     */
    fun reelPositionIndex(root: UiNode): Int? =
        root.walk()
            .filter { it.visible && it.collectionRowIndex != null && it.idContains("clips_") }
            .map { it.collectionRowIndex!! }
            .minOrNull()
            ?: root.walk()
                .firstOrNull { it.visible && it.idContains("clips_viewer") && it.collectionRowIndex != null }
                ?.collectionRowIndex

    fun homeFeedReelCards(root: UiNode): List<Bounds> =
        root.findDescendants { node ->
            node.visible &&
                !node.bounds.isEmpty &&
                node.idContains("clips_", "reels_tray", "clips_netego", "reels_netego")
        }.map { it.bounds }
}

/**
 * How the user arrived at the current screen.
 *
 * Instagram reuses the same viewer for a Reel opened from the Reels tab and for one a friend sent,
 * so the surface alone is not enough — provenance has to be carried across the navigation.
 */
enum class NavigationContext {
    NONE,
    FROM_DIRECT_MESSAGE,
    FROM_SAVED_COLLECTION,
    FROM_EXPLORE,
}
