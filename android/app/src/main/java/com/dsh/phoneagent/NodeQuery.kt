package com.dsh.phoneagent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Selector-based node lookup, in the spirit of AScript/uiautomator selectors.
 *
 * Matching happens on the phone, so the caller receives only the hits rather
 * than the whole tree. That is the whole point: a chat list or a WebView easily
 * holds thousands of nodes, and shipping all of them to a model is both slow and
 * expensive, while the phone can filter them in milliseconds.
 */
object NodeQuery {

    private const val DEFAULT_MAX = 10
    private const val HARD_MAX = 100
    private const val DEFAULT_SCAN_LIMIT = 20_000

    /** Fields read off each hit and returned to the caller. */
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
            .put("checked", node.isChecked)
            .put("selected", node.isSelected)
            .put("focused", node.isFocused)
            .put("depth", depth)
    }

    /**
     * Walk the tree and return nodes satisfying every supplied criterion.
     *
     * Criteria are ANDed: `{"text":"登录","clickable":true}` matches only a
     * clickable node whose text is exactly "登录". Absent criteria never
     * constrain. `textRegex` and `viewId` accept either an exact value or a
     * substring, which is what callers actually want — full view ids are long
     * and version-dependent, so `viewId:"btn_login"` matching by suffix is far
     * more useful than demanding the whole `com.foo:id/btn_login`.
     */
    fun find(root: AccessibilityNodeInfo?, selector: JSONObject, max: Int): JSONObject {
        if (root == null) {
            return JSONObject().put("count", 0).put("matches", JSONArray()).put("scanned", 0)
        }

        val limit = max.coerceIn(1, HARD_MAX)
        val matches = JSONArray()
        var scanned = 0
        var truncated = false

        // Pre-compile the regex once rather than per node.
        val textPattern: Pattern? = selector.optString("textRegex", "").takeIf { it.isNotEmpty() }?.let {
            runCatching { Pattern.compile(it) }.getOrNull()
        }

        fun textOf(node: AccessibilityNodeInfo): String = node.text?.toString() ?: ""
        fun descOf(node: AccessibilityNodeInfo): String = node.contentDescription?.toString() ?: ""
        fun idOf(node: AccessibilityNodeInfo): String = node.viewIdResourceName ?: ""
        fun clsOf(node: AccessibilityNodeInfo): String =
            node.className?.toString()?.substringAfterLast('.') ?: ""

        fun accepts(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (selector.has("text") && textOf(node) != selector.getString("text")) return false
            if (selector.has("textContains") && !textOf(node).contains(selector.getString("textContains"))) return false
            if (textPattern != null && !textPattern.matcher(textOf(node)).find()) return false
            if (selector.has("desc") && descOf(node) != selector.getString("desc")) return false
            if (selector.has("descContains") && !descOf(node).contains(selector.getString("descContains"))) return false
            if (selector.has("viewId")) {
                // Match by suffix so callers can pass just the name segment.
                if (!idOf(node).endsWith(selector.getString("viewId")) && idOf(node) != selector.getString("viewId")) {
                    return false
                }
            }
            if (selector.has("viewIdContains") && !idOf(node).contains(selector.getString("viewIdContains"))) return false
            if (selector.has("cls") && !clsOf(node).equals(selector.getString("cls"), ignoreCase = true)) return false

            for ((key, getter) in BOOLEAN_FIELDS) {
                if (selector.has(key) && selector.getBoolean(key) != getter(node)) return false
            }

            if (selector.has("depthMax") && depth > selector.getInt("depthMax")) return false
            if (selector.has("depthMin") && depth < selector.getInt("depthMin")) return false
            return true
        }

        fun walk(node: AccessibilityNodeInfo, depth: Int, scanLimit: Int) {
            if (matches.length() >= limit || scanned >= scanLimit) {
                truncated = true
                return
            }
            scanned++
            if (accepts(node, depth)) matches.put(describe(node, depth))
            for (i in 0 until node.childCount) {
                if (matches.length() >= limit || scanned >= scanLimit) {
                    truncated = true
                    return
                }
                val child = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    null
                } ?: continue
                walk(child, depth + 1, scanLimit)
            }
        }

        walk(root, 0, selector.optInt("scanLimit", DEFAULT_SCAN_LIMIT).coerceIn(1, 100_000))

        return JSONObject()
            .put("count", matches.length())
            .put("scanned", scanned)
            .put("truncated", truncated)
            .put("matches", matches)
    }

    /** Convenience for the common "just tap this label" case. */
    fun findOne(root: AccessibilityNodeInfo?, selector: JSONObject): JSONObject {
        val result = find(root, selector, 1)
        return result
    }

    /**
     * Find the node sitting at a screen point.
     *
     * This is what turns "I can see the word 登录" into "I can tap it". OCR gives
     * a text rectangle but not the widget that reacts: on most screens the label
     * lives in a non-clickable TextView whose parent LinearLayout is the real
     * target. So the walk keeps both the smallest containing node and the
     * smallest containing *clickable* node, and the caller picks.
     *
     * "Deepest, then smallest" matters — a full-screen container also contains the
     * point, and returning it would aim the tap at the middle of the screen.
     */
    fun hitTest(
        root: AccessibilityNodeInfo?,
        x: Int,
        y: Int,
        requireClickable: Boolean,
    ): JSONObject? {
        if (root == null) return null

        var bestNode: AccessibilityNodeInfo? = null
        var bestNodeDepth = -1
        var bestNodeArea = Long.MAX_VALUE

        var bestTap: AccessibilityNodeInfo? = null
        var bestTapDepth = -1
        var bestTapArea = Long.MAX_VALUE

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.contains(x, y)) {
                val area = rect.width().toLong() * rect.height().toLong()
                if (depth > bestNodeDepth || (depth == bestNodeDepth && area < bestNodeArea)) {
                    bestNode = node
                    bestNodeDepth = depth
                    bestNodeArea = area
                }
                if (node.isClickable &&
                    (depth > bestTapDepth || (depth == bestTapDepth && area < bestTapArea))
                ) {
                    bestTap = node
                    bestTapDepth = depth
                    bestTapArea = area
                }
            }
            for (i in 0 until node.childCount) {
                if (depth > 60) return
                val child = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    null
                } ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)

        val target: AccessibilityNodeInfo?
        val depth: Int
        if (requireClickable) {
            target = bestTap
            depth = bestTapDepth
        } else {
            target = bestNode
            depth = bestNodeDepth
        }
        if (target == null) return null
        return describe(target, depth).put("hitTest", true).put("clickable", requireClickable || target.isClickable)
    }

    /** Depth-first search for any node whose screen bounds contain the point. */
    fun contains(root: AccessibilityNodeInfo?, x: Int, y: Int): Boolean =
        hitTest(root, x, y, requireClickable = false) != null

    /** Nth scrollable container in depth-first order, or null when there is none. */
    fun findScrollable(root: AccessibilityNodeInfo?, index: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        val found = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || found.size > index) return
            if (node.isScrollable) found.add(node)
            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    null
                } ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return found.getOrNull(index)
    }

    /** Compact description of a container, so a caller learns which one was scrolled. */
    fun describeScrollable(node: AccessibilityNodeInfo): JSONObject {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return JSONObject()
            .put("cls", shortClass(node.className?.toString()))
            .put("viewId", node.viewIdResourceName ?: "")
            .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
            .put("center", JSONArray(listOf((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)))
            .put("childCount", node.childCount)
    }

    /**
     * A cheap fingerprint of the visible tree, used to decide whether the UI settled.
     *
     * Comparing whole trees is wasteful and a hash of class + text + bounds already
     * captures everything that visibly moved. Bounded, because a deep tree would
     * make the "has it stopped?" check cost more than the wait itself.
     */
    fun fingerprint(root: AccessibilityNodeInfo?, maxNodes: Int = 400): String {
        if (root == null) return "empty"
        val builder = StringBuilder()
        val counter = intArrayOf(0)
        val rect = Rect()

        fun walk(node: AccessibilityNodeInfo) {
            if (counter[0] >= maxNodes) return
            counter[0]++
            node.getBoundsInScreen(rect)
            builder.append(node.className).append('|')
                .append(node.text).append('|')
                .append(rect.left).append(',').append(rect.top).append(';')
            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (t: Throwable) {
                    null
                } ?: continue
                walk(child)
            }
        }

        walk(root)
        return Integer.toHexString(builder.toString().hashCode())
    }

    private val BOOLEAN_FIELDS: List<Pair<String, (AccessibilityNodeInfo) -> Boolean>> = listOf(
        "clickable" to { n: AccessibilityNodeInfo -> n.isClickable },
        "longClickable" to { n: AccessibilityNodeInfo -> n.isLongClickable },
        "scrollable" to { n: AccessibilityNodeInfo -> n.isScrollable },
        "editable" to { n: AccessibilityNodeInfo -> n.isEditable },
        "enabled" to { n: AccessibilityNodeInfo -> n.isEnabled },
        "checked" to { n: AccessibilityNodeInfo -> n.isChecked },
        "selected" to { n: AccessibilityNodeInfo -> n.isSelected },
        "focused" to { n: AccessibilityNodeInfo -> n.isFocused },
    )

    /** `android.widget.TextView` → `TextView`. */
    private fun shortClass(name: String?): String {
        if (name.isNullOrEmpty()) return ""
        val dot = name.lastIndexOf('.')
        return if (dot >= 0 && dot < name.length - 1) name.substring(dot + 1) else name
    }
}
