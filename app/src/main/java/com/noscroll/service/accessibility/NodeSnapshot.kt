package com.noscroll.service.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.noscroll.domain.detect.Bounds
import com.noscroll.domain.detect.UiNode

/**
 * Converts a live `AccessibilityNodeInfo` tree into the plain [UiNode] the detectors work on.
 *
 * Bounded on purpose. A Instagram or YouTube feed can hold thousands of nodes, and walking all of
 * them on every content-changed event would make the device feel slow — which is how these apps end
 * up being switched off. Depth and node count are capped, and the traversal stops early once it has
 * enough to identify the screen.
 *
 * The resulting tree is transient. It is handed to a detector, a decision comes back, and the tree
 * is dropped. Nothing from it is persisted, logged or transmitted.
 */
object NodeSnapshot {

    const val MAX_DEPTH = 24
    const val MAX_NODES = 1200

    fun capture(root: AccessibilityNodeInfo?): UiNode? {
        if (root == null) return null
        val counter = intArrayOf(0)
        return convert(root, 0, counter)
    }

    private fun convert(node: AccessibilityNodeInfo, depth: Int, counter: IntArray): UiNode? {
        if (depth > MAX_DEPTH || counter[0] >= MAX_NODES) return null
        counter[0]++

        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }

        val children = mutableListOf<UiNode>()
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until childCount) {
            if (counter[0] >= MAX_NODES) break
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            convert(child, depth + 1, counter)?.let { children += it }
        }

        return UiNode(
            viewId = runCatching { node.viewIdResourceName }.getOrNull(),
            className = runCatching { node.className?.toString() }.getOrNull(),
            text = runCatching { node.text?.toString() }.getOrNull(),
            contentDescription = runCatching { node.contentDescription?.toString() }.getOrNull(),
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            visible = runCatching { node.isVisibleToUser }.getOrDefault(true),
            collectionRowIndex = runCatching { node.collectionItemInfo?.rowIndex }.getOrNull(),
            children = children,
        )
    }

    /**
     * Reads a browser's address bar.
     *
     * The URL is used for one decision and then discarded — it is never written to storage, never
     * logged, and never included in the diagnostics report.
     */
    fun readAddressBar(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val candidates = listOf(
            "com.android.chrome:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.brave.browser:id/url_bar",
            "com.microsoft.emmx:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.duckduckgo.mobile.android:id/omnibarTextInput",
            "com.opera.browser:id/url_field",
            "com.kiwibrowser.browser:id/url_bar",
            "com.vivaldi.browser:id/url_bar",
        )
        for (id in candidates) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull().orEmpty()
            val value = nodes.firstNotNullOfOrNull { node ->
                runCatching { node.text?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            if (value != null) return value
        }
        return null
    }
}
