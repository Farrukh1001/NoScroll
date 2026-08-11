package com.noscroll.domain.detect

/**
 * A snapshot of an accessibility node, decoupled from `AccessibilityNodeInfo`.
 *
 * Two reasons this type exists:
 *  1. Detection becomes pure Kotlin, so every rule below is unit-testable against hand-built trees
 *     instead of needing a device and a logged-in Instagram account.
 *  2. It draws a hard line around screen content. A [UiNode] lives for the length of one detection
 *     pass and is then dropped. Nothing here is ever persisted, logged or sent anywhere — the
 *     diagnostics report carries the resulting [com.noscroll.domain.surface.Surface] enum, never the
 *     text that produced it.
 */
data class UiNode(
    val viewId: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: Bounds = Bounds.EMPTY,
    val visible: Boolean = true,
    /**
     * Position within a collection, straight from `CollectionItemInfo.rowIndex`.
     *
     * This is how single-item mode knows the user has swiped to the next Reel without reading a
     * single word of the content: a pager reports which page it is on, and that is all we need.
     */
    val collectionRowIndex: Int? = null,
    val children: List<UiNode> = emptyList(),
) {
    /** This node and every descendant, depth-first. */
    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.walk()) }
    }

    /** The part of the view id after the "/" — "com.instagram.android:id/clips_tab" -> "clips_tab". */
    val viewIdName: String?
        get() = viewId?.substringAfterLast('/', missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }

    fun idContains(vararg fragments: String): Boolean {
        val id = viewIdName?.lowercase() ?: return false
        return fragments.any { id.contains(it.lowercase()) }
    }

    fun descriptionEquals(vararg candidates: String): Boolean {
        val cd = contentDescription?.trim()?.lowercase() ?: return false
        return candidates.any { cd == it.lowercase() }
    }

    fun descriptionContains(vararg fragments: String): Boolean {
        val cd = contentDescription?.lowercase() ?: return false
        return fragments.any { cd.contains(it.lowercase()) }
    }

    fun textEquals(vararg candidates: String): Boolean {
        val t = text?.trim()?.lowercase() ?: return false
        return candidates.any { t == it.lowercase() }
    }

    fun classNameEndsWith(vararg suffixes: String): Boolean {
        val cn = className ?: return false
        return suffixes.any { cn.endsWith(it) }
    }
}

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}

/** Convenience helpers used by every detector. */
fun UiNode.anyDescendant(predicate: (UiNode) -> Boolean): Boolean = walk().any(predicate)

fun UiNode.findDescendants(predicate: (UiNode) -> Boolean): List<UiNode> = walk().filter(predicate).toList()
