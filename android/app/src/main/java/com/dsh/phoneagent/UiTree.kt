package com.dsh.phoneagent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises the live accessibility node tree into plain JSON.
 *
 * The tree is bounded on both depth and node count: a WebView or a chat list can
 * easily contain tens of thousands of nodes, and an unbounded walk would both
 * stall the caller and blow up the reply.
 */
object UiTree {

    private const val DEFAULT_MAX_DEPTH = 30
    private const val DEFAULT_MAX_NODES = 2000

    fun dump(
        root: AccessibilityNodeInfo?,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxNodes: Int = DEFAULT_MAX_NODES,
        interactiveOnly: Boolean = false,
    ): JSONObject {
        val counter = intArrayOf(0)
        if (root == null) {
            return JSONObject()
                .put("nodeCount", 0)
                .put("truncated", false)
                .put("root", JSONObject().put("unavailable", true))
        }

        // Interactive-only mode returns a FLAT list rather than the hierarchy.
        // A chat list or a WebView is mostly layout scaffolding; keeping only the
        // nodes a caller can actually act on shrinks the payload by an order of
        // magnitude, and a flat list answers the question callers actually have
        // ("what can I press on this screen"). The full tree stays available for
        // when the shape itself is what matters.
        if (interactiveOnly) {
            val flat = JSONArray()
            collectInteractive(root, 0, maxDepth, maxNodes, counter, flat)
            return JSONObject()
                .put("nodeCount", counter[0])
                .put("returned", flat.length())
                .put("truncated", counter[0] >= maxNodes)
                .put("interactiveOnly", true)
                .put("nodes", flat)
        }

        // Walk first, then build. Chaining `.put("nodeCount", counter[0])` before the
        // walk reads the counter before it has been filled, which reported a
        // populated tree as "0 nodes".
        val tree = walk(root, 0, maxDepth, maxNodes, counter)
        return JSONObject()
            .put("nodeCount", counter[0])
            .put("truncated", counter[0] >= maxNodes)
            .put("root", tree)
    }

    fun screenBounds(root: AccessibilityNodeInfo?): Rect? {
        val target = root ?: return null
        val rect = Rect()
        target.getBoundsInScreen(rect)
        return rect
    }

    /** Nodes a caller can actually act on: press, type into, scroll, or toggle. */
    private fun isInteractive(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isLongClickable || node.isEditable ||
            node.isScrollable || node.isCheckable || node.isFocused

    private fun collectInteractive(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
        counter: IntArray,
        out: JSONArray,
    ) {
        counter[0]++
        if (isInteractive(node)) out.put(describe(node, depth))
        if (depth >= maxDepth || counter[0] >= maxNodes) return
        for (i in 0 until node.childCount) {
            if (counter[0] >= maxNodes) return
            val child = try {
                node.getChild(i)
            } catch (t: Throwable) {
                null
            } ?: continue
            collectInteractive(child, depth + 1, maxDepth, maxNodes, counter, out)
        }
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
        counter: IntArray,
    ): JSONObject {
        counter[0]++
        val out = describe(node, depth)

        if (depth < maxDepth && counter[0] < maxNodes) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                if (counter[0] >= maxNodes) break
                val child = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    null
                } ?: continue
                children.put(walk(child, depth + 1, maxDepth, maxNodes, counter))
            }
            if (children.length() > 0) out.put("children", children)
        }

        return out
    }

    /** Shared node projection used by both the tree and the flat list. */
    private fun describe(node: AccessibilityNodeInfo, depth: Int): JSONObject {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return JSONObject()
            .put("cls", shortClass(node.className?.toString()))
            .put("text", node.text?.toString() ?: "")
            .put("desc", node.contentDescription?.toString() ?: "")
            .put("viewId", node.viewIdResourceName ?: "")
            .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
            .put("center", JSONArray(listOf((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)))
            .put("clickable", node.isClickable)
            .put("longClickable", node.isLongClickable)
            .put("scrollable", node.isScrollable)
            .put("editable", node.isEditable)
            .put("enabled", node.isEnabled)
            .put("focused", node.isFocused)
            .put("selected", node.isSelected)
            .put("checkable", node.isCheckable)
            .put("checked", node.isChecked)
            .put("depth", depth)
    }

    /** `android.widget.TextView` → `TextView`; keeps the payload readable. */
    private fun shortClass(name: String?): String {
        if (name.isNullOrEmpty()) return ""
        val dot = name.lastIndexOf('.')
        return if (dot >= 0 && dot < name.length - 1) name.substring(dot + 1) else name
    }
}
