package com.noscroll.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.toColorInt
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.noscroll.domain.detect.Bounds

/**
 * Owns every overlay NoScroll puts on screen.
 *
 * Windows are `TYPE_ACCESSIBILITY_OVERLAY`, which an accessibility service may use without the
 * draw-over-other-apps permission. That is a deliberate choice: NoScroll never asks for
 * SYSTEM_ALERT_WINDOW, so it cannot draw over anything outside the apps it protects.
 *
 * Every overlay is keyed by an id and the map is the single source of truth, which is what prevents
 * the classic failure of this kind of app: a stale blocker left floating over the launcher, or three
 * copies of the same mask stacked after a rotation. [removeAll] runs on service teardown, on leaving
 * the protected package, and on interrupt.
 */
class OverlayController(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val active = mutableMapOf<String, View>()

    val activeIds: Set<String> get() = active.keys.toSet()

    fun isShowing(id: String): Boolean = active.containsKey(id)

    /**
     * A full-screen blocker that consumes touches.
     *
     * It covers the offending app and offers a way out; it never tries to be un-dismissable, and it
     * never covers system UI outside the app it was raised for.
     */
    fun showBlocker(id: String, config: BlockerConfig) {
        runOnMain {
            if (active.containsKey(id)) {
                // Already up. Re-showing would stack windows and flicker.
                return@runOnMain
            }
            val view = buildBlockerView(config)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            addView(id, view, params)
        }
    }

    /**
     * Masks over specific rectangles — a Shorts shelf, a Reel card in the Home feed.
     *
     * The rest of the screen stays fully interactive: only the masked rectangles swallow touches, so
     * the user can keep scrolling past them normally.
     */
    fun showMasks(id: String, rects: List<Bounds>, label: String) {
        runOnMain {
            remove(id)
            if (rects.isEmpty()) return@runOnMain
            val container = FrameLayout(service)
            rects.forEach { bounds ->
                val mask = buildMaskView(label)
                val lp = FrameLayout.LayoutParams(bounds.width, bounds.height).apply {
                    leftMargin = bounds.left
                    topMargin = bounds.top
                }
                container.addView(mask, lp)
            }
            // The container itself must not eat touches; only the mask children do.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            addView(id, container, params)
        }
    }

    /** A small, non-blocking status strip, e.g. the remaining-time banner over Collections. */
    fun showBanner(id: String, text: String) {
        runOnMain {
            active[id]?.let { existing ->
                (existing as? TextView)?.text = text
                return@runOnMain
            }
            val view = TextView(service).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor("#E612303A".toColorInt())
                }
                // Announced by TalkBack, but not focusable so it never traps navigation.
                isFocusable = false
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(72)
            }
            addView(id, view, params)
        }
    }

    fun remove(id: String) {
        runOnMain {
            active.remove(id)?.let { view ->
                runCatching { windowManager.removeViewImmediate(view) }
            }
        }
    }

    /** Tears down everything. Safe to call repeatedly and from teardown paths. */
    fun removeAll() {
        runOnMain {
            active.values.forEach { view -> runCatching { windowManager.removeViewImmediate(view) } }
            active.clear()
        }
    }

    /** Removes every overlay whose id is not in [keep]. Used when the surface changes. */
    fun retainOnly(keep: Set<String>) {
        runOnMain {
            active.keys.toList().filterNot { it in keep }.forEach { remove(it) }
        }
    }

    private fun addView(id: String, view: View, params: WindowManager.LayoutParams) {
        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (added) active[id] = view
    }

    private fun buildBlockerView(config: BlockerConfig): View {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor("#F2101A1F".toColorInt())
            setPadding(dp(32), dp(32), dp(32), dp(32))
            swallowTouches()
        }

        root.addView(
            TextView(service).apply {
                text = config.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
            }
        )

        config.body?.let { body ->
            root.addView(
                TextView(service).apply {
                    text = body
                    setTextColor("#C7D6DD".toColorInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, 0)
                }
            )
        }

        config.actions.forEach { action ->
            root.addView(
                Button(service).apply {
                    text = action.label
                    isAllCaps = false
                    setTextColor("#0E1417".toColorInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(24).toFloat()
                        setColor("#6FE3C4".toColorInt())
                    }
                    setPadding(dp(24), dp(12), dp(24), dp(12))
                    setOnClickListener { action.onClick() }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(20) }
            )
        }

        return root
    }

    private fun buildMaskView(label: String): View = TextView(service).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor("#9FB3BD".toColorInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor("#F2131E24".toColorInt())
        }
        // Swallow taps so the hidden card underneath cannot be opened.
        swallowTouches()
        contentDescription = label
    }

    /**
     * Makes a view absorb every touch that lands on it.
     *
     * Returning true from `onTouch` is what stops the card underneath from being tapped or scrolled,
     * but a view that consumes touches without ever calling `performClick` is invisible to
     * accessibility services and to TalkBack. Routing the up-event through `performClick` keeps the
     * mask a well-behaved view while still swallowing the gesture.
     */
    @Suppress("ClickableViewAccessibility")
    private fun View.swallowTouches() {
        isClickable = true
        setOnClickListener { /* Deliberately does nothing; the point is to absorb the tap. */ }
        setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(block)
        }
    }

    companion object {
        const val ID_SHORTS_VIEWER = "shorts_viewer"
        const val ID_SHORTS_MASKS = "shorts_masks"
        const val ID_SHORTS_NAV = "shorts_nav"
        const val ID_YOUTUBE_MUSIC = "youtube_music"
        const val ID_INSTAGRAM_EXHAUSTED = "instagram_exhausted"
        const val ID_INSTAGRAM_MASKS = "instagram_masks"
        const val ID_INSTAGRAM_BANNER = "instagram_banner"
        const val ID_INSTAGRAM_SINGLE_ITEM = "instagram_single_item"
        const val ID_EXPLICIT_BLOCK = "explicit_block"
        const val ID_BROWSER_FAIL_CLOSED = "browser_fail_closed"
    }
}

data class BlockerAction(val label: String, val onClick: () -> Unit)

data class BlockerConfig(
    val title: String,
    val body: String? = null,
    val actions: List<BlockerAction> = emptyList(),
)
