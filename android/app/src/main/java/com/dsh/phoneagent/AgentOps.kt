package com.dsh.phoneagent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pre-execution safety check.
 *
 * An action that lands on nothing, or on a container that merely looks like a
 * button, does not fail loudly — the gesture is delivered, the framework reports
 * success, and the script carries on having done nothing. That is the worst failure
 * mode for automation because it is invisible: the run continues and every later
 * step is built on a lie.
 *
 * So before dispatching, look at what is actually under the target point. This does
 * not block the action; it reports. A caller that knows what it is doing (dragging
 * across a canvas, tapping a WebView) can ignore the verdict, but a caller that
 * merely aimed at a coordinate now gets told when that coordinate holds nothing
 * interactive.
 */
object SafetyNet {

    /** What the check concluded, and why. */
    data class Verdict(
        val code: String,
        val hint: String,
        val node: AccessibilityNodeInfo?,
        val bounds: Rect?,
        val clickableAncestor: AccessibilityNodeInfo?,
    ) {
        val ok: Boolean get() = code == "ok"
    }

    /**
     * Inspect the point [x],[y].
     *
     * Returns `ok` when something clickable is there or when the point falls inside a
     * scrollable container (scrolling is a legitimate reason to touch a non-clickable
     * area). Otherwise it names the specific reason, because "not clickable" and
     * "nothing at all here" need different responses from the caller.
     */
    fun check(service: AgentAccessibilityService, x: Int, y: Int): Verdict {
        val root = service.rootInActiveWindow
            ?: return Verdict("no-root", "拿不到界面节点树", null, null, null)

        val hit = deepestAt(root, x, y)
            ?: return Verdict(
                "empty",
                "($x, $y) 处没有任何节点 —— 可能点在了空白区或系统窗口上",
                null, null, null,
            )

        val rect = Rect().also { hit.getBoundsInScreen(it) }

        // A scrollable ancestor makes the point legitimate even when the hit node
        // itself is not clickable: you scroll by dragging anywhere in the list.
        val scrollable = ancestorMatching(hit) { it.isScrollable }
        val clickable = ancestorMatching(hit) { it.isClickable && it.isVisibleToUser }
        return when {
            clickable != null -> Verdict("ok", "命中可点节点", hit, rect, clickable)
            scrollable != null -> Verdict(
                "scrollable",
                "命中可滚动容器,拖动有效但点击无效",
                hit, rect, null,
            )
            else -> Verdict(
                "not-clickable",
                "($x, $y) 处的节点及其 5 层祖先都不可点 —— 点击不会触发任何东西",
                hit, rect, null,
            )
        }
    }

    /** Serialise a verdict for the wire. Nodes are summarised, never passed through. */
    fun toJson(verdict: Verdict): JSONObject {
        val out = JSONObject()
            .put("code", verdict.code)
            .put("ok", verdict.ok)
            .put("hint", verdict.hint)
        verdict.bounds?.let {
            out.put("bounds", JSONArray(listOf(it.left, it.top, it.right, it.bottom)))
        }
        verdict.node?.let { out.put("hit", describe(it)) }
        verdict.clickableAncestor?.let { out.put("clickable", describe(it)) }
        return out
    }

    /** The leaf-most node whose bounds contain the point. */
    private fun deepestAt(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        if (depth > 60) return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null

        // Children are drawn on top of their parent, so the deepest hit is the one
        // the user would actually be touching.
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            deepestAt(child, x, y, depth + 1)?.let { return it }
        }
        return node
    }

    /**
     * Walk up looking for an ancestor that satisfies [predicate].
     *
     * `predicate` is last so callers can use trailing-lambda syntax; Kotlin only
     * applies that sugar to the final parameter, so putting `maxLevels` after it would
     * silently try to use the lambda as the level count.
     */
    private fun ancestorMatching(
        node: AccessibilityNodeInfo,
        maxLevels: Int = 5,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var level = 0
        while (cur != null && level <= maxLevels) {
            if (predicate(cur)) return cur
            cur = cur.parent
            level++
        }
        return null
    }

    private fun describe(node: AccessibilityNodeInfo): JSONObject {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return JSONObject()
            .put("cls", node.className?.toString()?.substringAfterLast('.') ?: "")
            .put("text", node.text?.toString() ?: "")
            .put("desc", node.contentDescription?.toString() ?: "")
            .put("viewId", node.viewIdResourceName ?: "")
            .put("clickable", node.isClickable)
            .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
            .put("center", JSONArray(listOf(rect.centerX(), rect.centerY())))
    }

    /** The centre of a node, for callers that would rather aim than guess. */
    fun centerOf(node: AccessibilityNodeInfo): Pair<Int, Int> {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return rect.centerX() to rect.centerY()
    }
}

/**
 * Dynamic-first, coordinate-fallback locating.
 *
 * The caller has a *word* for what it wants — "登录", "关闭", "#FF5722" — and no good
 * way to know which mechanism will find it: a resource id if the app is well built, a
 * text match if it is not, OCR if it draws its own controls, a colour if it is a
 * game. Asking the caller to choose means every caller re-implements the same
 * guess-and-check, and gets it subtly different.
 *
 * [Locator] runs the chain itself and reports which strategy won, so the caller can
 * tell "found by text, high confidence" from "found by colour, verify this".
 */
object Locator {

    /** Strategies in default priority order: most specific and stable first. */
    val STRATEGIES = listOf("viewId", "text", "textContains", "desc", "ocr", "color")

    fun locate(
        service: AgentAccessibilityService,
        target: String,
        strategies: List<String>,
        region: Rect?,
        imageSearch: (String) -> JSONObject?,
    ): JSONObject {
        val attempts = JSONArray()
        val root = service.rootInActiveWindow

        for (strategy in strategies) {
            val result = runCatching { attempt(root, strategy, target, region, imageSearch) }
                .getOrElse { e ->
                    JSONObject().put("ok", false).put("reason", e.message ?: "error")
                }
            attempts.put(
                JSONObject()
                    .put("strategy", strategy)
                    .put("ok", result.optBoolean("ok"))
                    .put("reason", result.optString("reason")),
            )
            if (result.optBoolean("ok")) {
                return JSONObject()
                    .put("found", true)
                    .put("strategy", strategy)
                    .put("node", result.optJSONObject("node"))
                    .put("center", result.optJSONArray("center"))
                    .put("bounds", result.optJSONArray("bounds"))
                    .put("confidence", confidenceOf(strategy))
                    .put("attempts", attempts)
            }
        }

        return JSONObject()
            .put("found", false)
            .put("strategy", JSONObject.NULL)
            .put("attempts", attempts)
            .put("hint", "所有策略都没命中。可以用 uitree 看看页面上到底有什么,或改用截图找图。")
    }

    /**
     * How much to trust each strategy.
     *
     * A resource id survives rebuilds and translations; a colour match survives
     * nothing. Reporting this lets the caller decide whether to act immediately or
     * verify first — which is the difference between a script that works and one that
     * works until the app updates.
     */
    private fun confidenceOf(strategy: String): Double = when (strategy) {
        "viewId" -> 0.98
        "text" -> 0.95
        "textContains" -> 0.85
        "desc" -> 0.85
        "ocr" -> 0.7
        "color" -> 0.5
        else -> 0.5
    }

    private fun attempt(
        root: AccessibilityNodeInfo?,
        strategy: String,
        target: String,
        region: Rect?,
        imageSearch: (String) -> JSONObject?,
    ): JSONObject {
        if (strategy == "color" || strategy == "ocr") {
            // Delegated to the image/OCR engines, which capture their own frame.
            return imageSearch(strategy) ?: JSONObject().put("ok", false).put("reason", "未命中")
        }
        if (root == null) return JSONObject().put("ok", false).put("reason", "没有节点树")

        val match = findNode(root, strategy, target, region)
            ?: return JSONObject().put("ok", false).put("reason", "未命中")

        val rect = Rect().also { match.getBoundsInScreen(it) }
        return JSONObject()
            .put("ok", true)
            .put("node", JSONObject()
                .put("cls", match.className?.toString()?.substringAfterLast('.') ?: "")
                .put("text", match.text?.toString() ?: "")
                .put("desc", match.contentDescription?.toString() ?: "")
                .put("viewId", match.viewIdResourceName ?: "")
                .put("clickable", match.isClickable))
            .put("center", JSONArray(listOf(rect.centerX(), rect.centerY())))
            .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        strategy: String,
        target: String,
        region: Rect?,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        if (depth > 60) return null

        val rect = Rect().also { node.getBoundsInScreen(it) }
        val inRegion = region == null || Rect.intersects(rect, region)

        if (inRegion && node.isVisibleToUser) {
            val hit = when (strategy) {
                "viewId" -> {
                    val vid = node.viewIdResourceName ?: ""
                    vid.isNotEmpty() && (vid == target || vid.endsWith("/$target") || vid.endsWith(target))
                }
                "text" -> node.text?.toString() == target
                "textContains" -> node.text?.toString()?.contains(target) == true
                "desc" -> {
                    val d = node.contentDescription?.toString() ?: ""
                    d == target || d.contains(target)
                }
                else -> false
            }
            // Prefer the node that can actually be acted on. Several nodes share the
            // same text (a label and its container); the clickable one is the target.
            if (hit && node.isClickable) return node
            if (hit) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    findNode(child, strategy, target, region, depth + 1)?.let { return it }
                }
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, strategy, target, region, depth + 1)?.let { return it }
        }
        return null
    }
}

/**
 * Execution incidents.
 *
 * A failed action that just returns `false` is forgotten by the next call, so the
 * agent has no way to know it left something unfinished — a dialog it failed to
 * dismiss, a permission prompt it could not accept. The run continues, and the
 * failure resurfaces much later as an inexplicable wrong screen.
 *
 * An incident is that failure kept open. It carries the original reason and a count
 * of how many times the action was attempted, and it is closed when a later action
 * of the same kind succeeds. This is what lets a caller ask "is anything still
 * broken?" instead of hoping.
 */
object IncidentLog {

    private const val MAX_OPEN = 20
    private val seq = AtomicInteger(1)
    private val open = LinkedHashMap<Int, JSONObject>()
    private val resolved = ArrayDeque<JSONObject>()

    @Synchronized
    fun record(action: String, target: String, reason: String) {
        // Recurring failures of the same action and target are one incident with a
        // rising count, not a new entry each time — otherwise a stuck loop fills the
        // list with duplicates and hides everything else.
        val existing = open.values.firstOrNull {
            it.optString("action") == action && it.optString("target") == target
        }
        if (existing != null) {
            existing.put("attempts", existing.optInt("attempts") + 1)
            existing.put("lastAt", System.currentTimeMillis())
            existing.put("reason", reason)
            return
        }

        val id = seq.getAndIncrement()
        open[id] = JSONObject()
            .put("id", id)
            .put("action", action)
            .put("target", target)
            .put("reason", reason)
            .put("attempts", 1)
            .put("at", System.currentTimeMillis())
            .put("lastAt", System.currentTimeMillis())
        while (open.size > MAX_OPEN) {
            val oldest = open.keys.first()
            open.remove(oldest)
        }
    }

    /** Close incidents for [action] — a success means whatever was broken now works. */
    @Synchronized
    fun resolve(action: String) {
        if (open.isEmpty()) return
        val it = open.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.optString("action") != action) continue
            entry.value.put("resolvedAt", System.currentTimeMillis())
            resolved.addFirst(entry.value)
            it.remove()
        }
        while (resolved.size > MAX_OPEN) resolved.removeLast()
    }

    @Synchronized
    fun snapshot(): JSONObject {
        val openArr = JSONArray()
        for (v in open.values) openArr.put(v)
        val resolvedArr = JSONArray()
        for (v in resolved) resolvedArr.put(v)
        return JSONObject()
            .put("openCount", open.size)
            .put("open", openArr)
            .put("resolvedCount", resolved.size)
            .put("resolved", resolvedArr)
    }

    @Synchronized
    fun clear() {
        open.clear()
        resolved.clear()
    }
}
