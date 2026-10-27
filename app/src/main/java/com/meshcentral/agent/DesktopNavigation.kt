package com.meshcentral.agent

import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

internal class DesktopNavigation(private val root: AccessibilityNodeInfo) {
    fun focused(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

    private fun findFocus(type: Int): AccessibilityNodeInfo? {
        // Focus events can arrive after the next remote key, leaving a cached former target.
        repeat(2) {
            val node = root.findFocus(type) ?: return null
            if (node.refresh() && usable(node) &&
                (if (type == AccessibilityNodeInfo.FOCUS_INPUT) node.isFocused else node.isAccessibilityFocused)) return node
        }
        return null
    }

    fun editable(): AccessibilityNodeInfo? {
        focused()?.takeIf { it.isEditable }?.let { return it }
        var incomplete = false
        val fields = nodes(root) { incomplete = true }.filter { it.isEditable && it.refresh() && usable(it) }.toList()
        fields.firstOrNull { it.isFocused }?.let { return it }
        // Custom TV keyboards can hold focus while the only editor remains unfocused.
        return if (incomplete) null else fields.singleOrNull()
    }

    fun moveFocus(direction: Int): Boolean {
        var current = focused()
        if (current == null) {
            val candidates = nodes(root).filter { focusable(it) }
            val first = if (direction == View.FOCUS_BACKWARD) candidates.lastOrNull() else candidates.firstOrNull()
            return first != null && focus(first)
        }
        var moved = false
        // Leanback lists may put input focus on the container instead of an item.
        val child = nodes(current).drop(1).firstOrNull { focusable(it) }
        if (child != null && focus(child)) {
            current = child
            moved = true
        }
        val next = current.focusSearch(direction)
        if (next != null && next != current && focusable(next) && focus(next)) return true
        val fallback = nearestInDirection(current, direction)
        return (fallback != null && focus(fallback)) || moved
    }

    fun clickFocused(): Boolean {
        var node = focused()
        repeat(MAX_DEPTH) {
            val current = node ?: return false
            if (usable(current) && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            if (current == root) return false
            node = current.parent
        }
        return false
    }

    internal fun nearestInDirection(from: AccessibilityNodeInfo, direction: Int): AccessibilityNodeInfo? {
        val candidates = nodes(root).filter { focusable(it) }.toList()
        if (direction == View.FOCUS_FORWARD || direction == View.FOCUS_BACKWARD) {
            val index = candidates.indexOf(from)
            if (index < 0) return null
            return candidates.getOrNull(index + if (direction == View.FOCUS_FORWARD) 1 else -1)
        }
        val origin = Rect().also { from.getBoundsInScreen(it) }
        if (origin.isEmpty) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = Double.MAX_VALUE
        val bounds = Rect()
        for (node in candidates) {
            if (node == from) continue
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty || bounds.contains(origin) || origin.contains(bounds)) continue
            val (main, cross) = when (direction) {
                View.FOCUS_LEFT -> Pair(origin.left.toDouble() - bounds.right, abs(bounds.exactCenterY() - origin.exactCenterY()).toDouble())
                View.FOCUS_RIGHT -> Pair(bounds.left.toDouble() - origin.right, abs(bounds.exactCenterY() - origin.exactCenterY()).toDouble())
                View.FOCUS_UP -> Pair(origin.top.toDouble() - bounds.bottom, abs(bounds.exactCenterX() - origin.exactCenterX()).toDouble())
                View.FOCUS_DOWN -> Pair(bounds.top.toDouble() - origin.bottom, abs(bounds.exactCenterX() - origin.exactCenterX()).toDouble())
                else -> return null
            }
            if (main < 0) continue
            val score = main * main + 4 * cross * cross
            if (score < bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }

    private fun focus(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

    private fun usable(node: AccessibilityNodeInfo): Boolean =
        node.windowId == root.windowId && node.isEnabled && node.isVisibleToUser

    private fun focusable(node: AccessibilityNodeInfo): Boolean = usable(node) && node.isFocusable

    private fun nodes(start: AccessibilityNodeInfo, onLimit: () -> Unit = {}): Sequence<AccessibilityNodeInfo> = sequence {
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val seen = HashSet<AccessibilityNodeInfo>()
        pending.addLast(start to 0)
        while (pending.isNotEmpty() && seen.size < MAX_NODES) {
            val (node, depth) = pending.removeLast()
            if (!seen.add(node) || node.windowId != root.windowId) continue
            yield(node)
            if (depth >= MAX_DEPTH) {
                if (node.childCount > 0) onLimit()
                continue
            }
            if (node.childCount > MAX_NODES) onLimit()
            for (i in minOf(node.childCount, MAX_NODES) - 1 downTo 0) {
                if (seen.size + pending.size >= MAX_NODES) {
                    onLimit()
                    break
                }
                node.getChild(i)?.let { pending.addLast(it to depth + 1) }
            }
        }
        if (pending.isNotEmpty()) onLimit()
    }

    companion object {
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 2048
    }
}
