package com.noscroll.domain.detect

import com.noscroll.domain.surface.Confidence
import com.noscroll.domain.surface.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detector tests run against hand-built accessibility trees, which is why the detectors take a
 * plain [UiNode] rather than an `AccessibilityNodeInfo`. No device, no logged-in account, and the
 * awkward cases (Stories versus Reels, a Shorts shelf versus the Shorts player) can be written down
 * exactly.
 */
class DetectorTest {

    private fun node(
        id: String? = null,
        cd: String? = null,
        text: String? = null,
        bounds: Bounds = Bounds(0, 0, 100, 100),
        rowIndex: Int? = null,
        children: List<UiNode> = emptyList(),
    ) = UiNode(
        viewId = id?.let { "com.example:id/$it" },
        contentDescription = cd,
        text = text,
        bounds = bounds,
        collectionRowIndex = rowIndex,
        children = children,
    )

    private fun tree(vararg children: UiNode) = node(id = "root", children = children.toList())

    // ---- YouTube ---------------------------------------------------------------------------

    @Test
    fun `the shorts viewer is detected`() {
        val root = tree(node(id = "reel_recycler"), node(id = "reel_progress_bar"))
        assertTrue(YouTubeDetector.isShortsViewer(root))
        assertEquals(Surface.YOUTUBE_SHORTS_VIEWER, YouTubeDetector.detect(root).surface)
    }

    @Test
    fun `a normal watch page is not a shorts viewer`() {
        val root = tree(node(id = "watch_player"), node(id = "player_control_overlay"))
        assertFalse(YouTubeDetector.isShortsViewer(root))
        assertEquals(Surface.YOUTUBE_WATCH, YouTubeDetector.detect(root).surface)
    }

    @Test
    fun `a shorts shelf on home does not count as the viewer`() {
        // The shelf uses reel_ ids for its items but never the pager root; treating it as the viewer
        // would block the whole Home feed.
        val root = tree(node(id = "browse_items"), node(id = "reel_shelf", cd = "Shorts"))
        assertFalse(YouTubeDetector.isShortsViewer(root))
    }

    @Test
    fun `shorts shelves are returned for masking and normal rows are not`() {
        val root = tree(
            node(id = "reel_shelf", bounds = Bounds(0, 200, 1080, 700)),
            node(id = "video_lockup", bounds = Bounds(0, 800, 1080, 1200)),
        )
        val shelves = YouTubeDetector.shortsShelves(root)
        assertEquals(1, shelves.size)
        assertEquals(Bounds(0, 200, 1080, 700), shelves.first())
    }

    @Test
    fun `the shorts nav button is found by its description`() {
        val root = tree(
            node(
                id = "pivot_bar",
                children = listOf(
                    node(cd = "Home", bounds = Bounds(0, 1900, 200, 2000)),
                    node(cd = "Shorts", bounds = Bounds(200, 1900, 400, 2000)),
                ),
            )
        )
        assertEquals(Bounds(200, 1900, 400, 2000), YouTubeDetector.shortsNavButton(root))
    }

    @Test
    fun `no nav bar means nothing is masked`() {
        // Guessing at a rectangle would risk covering the wrong button.
        assertNull(YouTubeDetector.shortsNavButton(tree(node(id = "browse_items"))))
    }

    @Test
    fun `an unrecognised youtube screen is low confidence`() {
        val detection = YouTubeDetector.detect(tree(node(id = "something_new")))
        assertEquals(Confidence.LOW, detection.confidence)
        assertFalse("low confidence must not trigger action", detection.isActionable)
    }

    // ---- Instagram: the clips/reel naming trap -------------------------------------------------

    @Test
    fun `stories are detected and never confused with reels`() {
        // Instagram calls Stories "reel" internally. Getting this backwards would count Stories
        // against the budget.
        val root = tree(node(id = "reel_viewer_texture_view"), node(id = "reel_progress_bar"))
        val detection = InstagramDetector.detect(root)
        assertEquals(Surface.INSTAGRAM_STORIES, detection.surface)
        assertFalse(detection.surface.isInstagramBudgeted)
    }

    @Test
    fun `reels are detected from clips ids`() {
        val root = tree(node(id = "clips_viewer_view_pager"))
        val detection = InstagramDetector.detect(root)
        assertEquals(Surface.INSTAGRAM_REELS_VIEWER, detection.surface)
        assertTrue(detection.surface.isInstagramBudgeted)
    }

    @Test
    fun `direct messages are never budgeted or blocked`() {
        val root = tree(node(id = "direct_thread_toolbar"), node(id = "row_thread_message"))
        val detection = InstagramDetector.detect(root)
        assertEquals(Surface.INSTAGRAM_DIRECT, detection.surface)
        assertFalse(detection.surface.isInstagramBudgeted)
        assertTrue(detection.surface.isInstagramProtectedFromBlocking)
    }

    @Test
    fun `a reel opened inside a conversation is budgeted as a friend-sent reel`() {
        val root = tree(node(id = "direct_thread_toolbar"), node(id = "clips_viewer_view_pager"))
        val detection = InstagramDetector.detect(root, NavigationContext.FROM_DIRECT_MESSAGE)
        assertEquals(Surface.INSTAGRAM_DM_REEL, detection.surface)
        assertTrue(detection.surface.isInstagramBudgeted)
    }

    @Test
    fun `explore is budgeted but dm search is not`() {
        assertEquals(
            Surface.INSTAGRAM_EXPLORE,
            InstagramDetector.detect(tree(node(id = "explore_grid"))).surface,
        )
        // A search inside DMs must not start the clock.
        val dmSearch = tree(node(id = "direct_search_bar"), node(id = "direct_inbox_recycler"))
        assertEquals(Surface.INSTAGRAM_DIRECT, InstagramDetector.detect(dmSearch).surface)
    }

    @Test
    fun `saved and collections are budgeted`() {
        assertEquals(Surface.INSTAGRAM_SAVED, InstagramDetector.detect(tree(node(id = "save_home_grid"))).surface)
        assertEquals(
            Surface.INSTAGRAM_COLLECTION,
            InstagramDetector.detect(tree(node(id = "collection_grid_recycler"))).surface,
        )
    }

    @Test
    fun `a post opened from a collection keeps counting through provenance`() {
        // The opened post looks like an ordinary feed item; only the navigation context knows better.
        val openedPost = tree(node(id = "media_group"), node(id = "carousel_viewpager"))
        assertEquals(
            Surface.INSTAGRAM_COLLECTION_ITEM,
            InstagramDetector.detect(openedPost, NavigationContext.FROM_SAVED_COLLECTION).surface,
        )
        // Without that provenance the same tree is not budgeted.
        assertFalse(InstagramDetector.detect(openedPost, NavigationContext.NONE).surface.isInstagramBudgeted)
    }

    @Test
    fun `an unrecognised instagram screen is low confidence and not budgeted`() {
        val detection = InstagramDetector.detect(tree(node(id = "brand_new_surface")))
        assertEquals(Surface.INSTAGRAM_OTHER, detection.surface)
        assertEquals(Confidence.LOW, detection.confidence)
        assertFalse(detection.surface.isInstagramBudgeted)
    }

    @Test
    fun `home reel cards are masked and ordinary photos are not`() {
        val root = tree(
            node(id = "clips_netego_container", bounds = Bounds(0, 400, 1080, 900)),
            node(id = "row_feed_photo_imageview", bounds = Bounds(0, 1000, 1080, 1900)),
        )
        val cards = InstagramDetector.homeFeedReelCards(root)
        assertEquals(1, cards.size)
        assertEquals(Bounds(0, 400, 1080, 900), cards.first())
    }

    @Test
    fun `the reel pager position is read from collection metadata`() {
        val root = tree(node(id = "clips_viewer_item", rowIndex = 3))
        assertEquals(3, InstagramDetector.reelPositionIndex(root))
    }

    @Test
    fun `no reported position means single item mode does nothing`() {
        assertNull(InstagramDetector.reelPositionIndex(tree(node(id = "clips_viewer_view_pager"))))
    }

    // ---- Shorts links ---------------------------------------------------------------------------

    @Test
    fun `shorts links are parsed from every supported host`() {
        listOf(
            "https://youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://m.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ?feature=share",
        ).forEach { url ->
            assertEquals("parsing $url", "dQw4w9WgXcQ", ShortsLinkParser.videoIdFrom(url))
        }
    }

    @Test
    fun `malformed and hostile links are refused`() {
        listOf(
            "",
            "not a url",
            "https://evil.example/shorts/dQw4w9WgXcQ",
            "https://youtube.com.evil.example/shorts/dQw4w9WgXcQ",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "https://youtube.com/shorts/",
            "https://youtube.com/shorts/tooshort",
            "https://youtube.com/shorts/waaaaaaaaaaaytoolong",
            "https://youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/shorts/bad!chars@@",
        ).forEach { url ->
            assertNull("\"$url\" must be refused", ShortsLinkParser.videoIdFrom(url))
        }
    }

    @Test
    fun `the isolated url drops every extra parameter`() {
        val url = ShortsLinkParser.isolatedWatchUrl("dQw4w9WgXcQ")
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", url)
        assertFalse("no playlist can follow the video", url.contains("list="))
    }
}
