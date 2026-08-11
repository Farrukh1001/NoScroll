package com.noscroll.domain.detect

import com.noscroll.domain.surface.Confidence
import com.noscroll.domain.surface.Surface
import com.noscroll.domain.surface.SurfaceDetection

/**
 * Recognises the Shorts surfaces inside YouTube without touching normal videos.
 *
 * YouTube keeps almost everything inside one activity, so the split has to come from the view tree.
 * Shorts live under `reel_*` ids (YouTube's internal name for Shorts), while a normal video is a
 * `watch_*` / player-page tree. The bar for acting is a positive Shorts match — an unrecognised
 * screen is treated as ordinary YouTube and left completely alone.
 */
object YouTubeDetector {

    fun detect(root: UiNode): SurfaceDetection {
        if (isShortsViewer(root)) return SurfaceDetection(Surface.YOUTUBE_SHORTS_VIEWER, Confidence.HIGH)
        if (isWatch(root)) return SurfaceDetection(Surface.YOUTUBE_WATCH, Confidence.HIGH)
        if (isHome(root)) return SurfaceDetection(Surface.YOUTUBE_HOME, Confidence.MEDIUM)
        return SurfaceDetection(Surface.YOUTUBE_OTHER, Confidence.LOW)
    }

    /**
     * The full-screen Shorts player.
     *
     * `reel_recycler` / `reel_player_*` are the vertical Shorts pager. We additionally require that
     * we are *not* in the normal watch page, because a normal video's related-items list can carry a
     * Shorts shelf without the user being in Shorts.
     */
    fun isShortsViewer(root: UiNode): Boolean {
        val reelPlayer = root.anyDescendant { node ->
            node.idContains(
                "reel_recycler",
                "reel_player_page_container",
                "reel_watch_fragment",
                "shorts_video_container",
                "reel_progress_bar",
            )
        }
        if (!reelPlayer) return false
        // A Shorts shelf on Home also uses "reel_" ids for its items, but never the pager root.
        val shelfOnly = root.anyDescendant { it.idContains("reel_shelf", "shorts_shelf") } &&
            !root.anyDescendant { it.idContains("reel_recycler", "reel_player_page_container", "reel_watch_fragment") }
        return !shelfOnly
    }

    private fun isWatch(root: UiNode): Boolean =
        root.anyDescendant { it.idContains("watch_player", "player_control", "watch_while_") } &&
            !isShortsViewer(root)

    private fun isHome(root: UiNode): Boolean =
        root.anyDescendant { it.idContains("results", "browse_items", "pivot_bar") }

    /**
     * Shorts shelves and Shorts cards in a browse feed, returned as bounds to mask.
     *
     * Only nodes that positively identify as a Shorts module are returned. Normal video rows have no
     * `reel_`/`shorts_` id and are never masked.
     */
    fun shortsShelves(root: UiNode): List<Bounds> =
        root.findDescendants { node ->
            node.visible && !node.bounds.isEmpty && (
                node.idContains("reel_shelf", "shorts_shelf", "reel_item_container", "shorts_lockup") ||
                    (node.idContains("shelf", "rich_shelf") && node.descriptionContains("shorts"))
                )
        }.map { it.bounds }

    /**
     * The Shorts button in the bottom navigation bar.
     *
     * Matched by its content description within the pivot bar rather than by label text, so it keeps
     * working when the UI is not in English — and if we cannot find it confidently we simply do not
     * cover anything, leaving the viewer-level block to do the work.
     */
    fun shortsNavButton(root: UiNode): Bounds? {
        val pivotBar = root.walk().firstOrNull { it.idContains("pivot_bar", "bottom_bar", "navigation_bar") }
            ?: return null
        val button = pivotBar.walk().firstOrNull { node ->
            node.visible && !node.bounds.isEmpty &&
                (node.descriptionEquals("shorts") || node.textEquals("shorts") || node.idContains("pivot_shorts"))
        }
        return button?.bounds
    }
}

/**
 * Parses YouTube Shorts links so a link a friend shares can be opened as one ordinary video.
 *
 * Only the video id is taken from the URL; everything else (playlist, index, autoplay, feed
 * parameters) is dropped, which is what stops the "one video" from turning into an endless feed.
 */
object ShortsLinkParser {

    private val ALLOWED_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
    )

    /** YouTube video ids are exactly 11 characters of [A-Za-z0-9_-]. */
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /**
     * Returns the video id for a Shorts URL, or null for anything we do not fully understand.
     * Rejecting is always safe here: an unparsed link is handed back to the system unchanged.
     */
    fun videoIdFrom(url: String): String? {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null

        val host = uri.host?.lowercase() ?: return null
        if (host !in ALLOWED_HOSTS) return null

        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val id = when {
            segments.size >= 2 && segments[0].equals("shorts", ignoreCase = true) -> segments[1]
            else -> return null
        }
        return id.takeIf { VIDEO_ID.matches(it) }
    }

    /** The isolated, single-video watch URL we open instead of the Shorts feed. */
    fun isolatedWatchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
}
