package com.noscroll.ui.shorts

import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.noscroll.NoScrollApplication
import com.noscroll.domain.detect.ShortsLinkParser
import com.noscroll.domain.protection.SettingKey
import com.noscroll.domain.surface.Packages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens a shared Shorts link as one ordinary video.
 *
 * When a friend sends a Short, the point is that one clip — not the feed behind it. This activity
 * takes the video id out of the URL, throws away every other parameter, and opens the normal watch
 * page instead. The Shorts viewer never appears, so there is nothing to swipe up into.
 *
 * It is the only exported component besides the launcher, so it treats its input as hostile: the URL
 * is validated by [ShortsLinkParser] against an explicit host allowlist and an exact video-id shape,
 * and anything it does not fully understand is handed straight back to the system rather than acted
 * on. It has no UI, holds nothing, and finishes immediately.
 */
class ShortsLinkActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data?.toString()
        val videoId = data?.let { ShortsLinkParser.videoIdFrom(it) }

        if (videoId == null) {
            // Not a Shorts link we can parse. Do nothing rather than guess at the user's intent.
            finish()
            return
        }

        CoroutineScope(Dispatchers.Default).launch {
            val container = (application as? NoScrollApplication)?.container
            val enabled = container?.protectionRepository?.current()
                ?.bool(SettingKey.YOUTUBE_EXTERNAL_LINKS) ?: true

            withContext(Dispatchers.Main) {
                if (enabled) {
                    openIsolated(videoId)
                } else {
                    openOriginal(data)
                }
                finish()
            }
        }
    }

    /** The single-video watch page, launched in YouTube when it is installed. */
    private fun openIsolated(videoId: String) {
        val url = ShortsLinkParser.isolatedWatchUrl(videoId)
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            setPackage(Packages.YOUTUBE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { startActivity(intent); true }.getOrDefault(false)
        if (!launched) {
            // No YouTube app: fall back to whatever handles the URL, still the isolated watch page.
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun openOriginal(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri())
                    .setPackage(Packages.YOUTUBE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
