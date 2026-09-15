package com.dsh.phoneagent.adskip

import android.view.accessibility.AccessibilityNodeInfo

/**
 * The subset of GKD's selector language that the ad-skip engine understands.
 *
 * GKD's full grammar is a small query language: attribute tests, `&&`/`||`
 * composition, class names, and a family of structural combinators (`>`, `>n`, `+`,
 * `-`, `<`, `<<n`, `@`). Implementing all of it faithfully is a project in itself,
 * and getting it *subtly* wrong is worse than not supporting it — a rule that
 * mis-parses does not fail loudly, it clicks the wrong thing.
 *
 * So this handles what the overwhelming majority of published rules actually use:
 * attribute tests with the standard operators, class-name prefixes, and `&&`/`||`.
 * A selector containing a structural combinator is reported as unsupported and the
 * rule is skipped, with the reason recorded so it is visible rather than silent.
 */
object AdSelector {

    /** Result of evaluating a selector, carrying why it did not apply. */
    data class Result(val node: AccessibilityNodeInfo?, val unsupported: String? = null) {
        val ok: Boolean get() = node != null
    }

    private data class Cond(val attr: String, val op: String, val value: String)

    private data class Parsed(
        val className: String?,
        val groups: List<List<Cond>>,   // outer = OR, inner = AND
        val target: Boolean,
    )

    /** Cached parse results: the same handful of selectors is evaluated constantly. */
    private val cache = HashMap<String, Parsed?>()

    /**
     * Find the first node in [root]'s subtree matching [selector].
     *
     * `@` marks which node in a chain is the *action* target rather than an
     * intermediate hop. Without structural support there is no chain, so `@` is
     * simply stripped — its node is the one being tested.
     */
    fun find(root: AccessibilityNodeInfo, selector: String): Result {
        val parsed = parse(selector) ?: return Result(null, reasonFor(selector))
        val hit = search(root, parsed)
        return Result(hit)
    }

    /** Why a selector could not be used, or null if it parsed. */
    fun reasonFor(selector: String): String? {
        if (parse(selector) != null) return null
        val combinators = listOf(">", "+", "<", ">>")
        val found = combinators.firstOrNull { selector.contains(it) }
        return if (found != null) "不支持的结构组合符 '$found'" else "无法解析的选择器"
    }

    fun isSupported(selector: String): Boolean = parse(selector) != null

    // ------------------------------------------------------------------ parsing

    private fun parse(raw: String): Parsed? {
        cache[raw]?.let { return it }
        if (cache.containsKey(raw)) return null

        val parsed = parseUncached(raw)
        cache[raw] = parsed
        return parsed
    }

    private fun parseUncached(raw: String): Parsed? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        // Structural combinators are not supported; refuse rather than approximate.
        if (Regex("(^|\\s)[>+<]").containsMatchIn(s)) return null
        if (Regex("[>+<]\\s*\\(?\\d*\\)?").containsMatchIn(s.trimStart('@'))) return null

        var target = false
        if (s.startsWith("@")) {
            target = true
            s = s.substring(1).trim()
        }

        // Class name prefix, e.g. TextView[id=...]
        var className: String? = null
        val classMatch = Regex("^([A-Za-z_][A-Za-z0-9_.]*)\\s*\\[").find(s)
        if (classMatch != null) {
            className = classMatch.groupValues[1]
            s = s.substring(classMatch.value.length - 1)
        } else if (Regex("^[A-Za-z_][A-Za-z0-9_.]*$").matches(s)) {
            // Bare class name
            return Parsed(s.substringAfterLast('.'), emptyList(), target)
        }

        if (!s.startsWith("[")) return null

        // Split into OR groups on top-level `||`, then AND on `&&`.
        val orParts = splitTop(s, "||")
        val groups = mutableListOf<List<Cond>>()
        for (part in orParts) {
            val andParts = splitTop(part, "&&")
            val conds = andParts.mapNotNull { parseCond(it) }
            if (conds.isEmpty() && andParts.any { it.isNotBlank() }) return null
            if (conds.isNotEmpty()) groups.add(conds)
        }

        return Parsed(className, groups, target)
    }

    /** Split on a separator that appears inside `[...]` boundaries. */
    private fun splitTop(s: String, sep: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> depth--
            }
            if (depth == 0 && s.startsWith(sep, i)) {
                out.add(s.substring(start, i))
                i += sep.length
                start = i
                continue
            }
            i++
        }
        out.add(s.substring(start))
        return out
    }

    /** Parse one `[attr op "value"]` or a bare `[attr]`. */
    private fun parseCond(chunk: String): Cond? {
        var c = chunk.trim()
        if (!c.startsWith("[")) return null
        c = c.removePrefix("[").removeSuffix("]").trim()
        if (c.isEmpty()) return null

        val opMatch = Regex("^(.*?)(!=|\\*=|\\^=|\\$=|<=|>=|=|<|>)(.*)$").find(c)
            ?: return Cond(c, "exists", "")

        val attr = opMatch.groupValues[1].trim()
        val op = opMatch.groupValues[2]
        var value = opMatch.groupValues[3].trim()

        // Values may be quoted with either style; the normaliser already handled
        // most of this, but selectors arrive unwrapped from the raw rule text.
        value = value.removeSurrounding("\"").removeSurrounding("'")
        if (attr.isEmpty()) return null
        return Cond(attr, op, value)
    }

    // ------------------------------------------------------------------ matching

    private fun search(node: AccessibilityNodeInfo, parsed: Parsed): AccessibilityNodeInfo? {
        if (test(node, parsed)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = search(child, parsed)
            if (hit != null) return hit
        }
        return null
    }

    private fun test(node: AccessibilityNodeInfo, parsed: Parsed): Boolean {
        if (parsed.className != null) {
            val cls = node.className?.toString() ?: return false
            val short = cls.substringAfterLast('.')
            if (!short.equals(parsed.className, ignoreCase = true) &&
                !cls.equals(parsed.className, ignoreCase = true)
            ) {
                return false
            }
        }
        if (parsed.groups.isEmpty()) return true
        // Outer list is OR: any group fully satisfied wins.
        return parsed.groups.any { group -> group.all { testCond(node, it) } }
    }

    private fun testCond(node: AccessibilityNodeInfo, cond: Cond): Boolean {
        // `vid` is GKD's short-form id and matches a suffix of the full resource name:
        // rules say `[vid="tv_ad_close"]` while the node reports
        // `com.example:id/tv_ad_close`. `id` keeps exact semantics.
        if (cond.attr == "vid") {
            val full = node.viewIdResourceName ?: return false
            val short = full.substringAfterLast('/')
            return cond.value == short || full.endsWith(cond.value) || full == cond.value
        }

        val actual: String = when (cond.attr) {
            "id", "viewId" -> node.viewIdResourceName ?: ""
            "text", "name" -> node.text?.toString() ?: ""
            "desc", "description" -> node.contentDescription?.toString() ?: ""
            "text.length" -> (node.text?.length ?: 0).toString()
            "desc.length" -> (node.contentDescription?.length ?: 0).toString()
            "clickable" -> node.isClickable.toString()
            "visibleToUser" -> node.isVisibleToUser.toString()
            "enabled" -> node.isEnabled.toString()
            "checkable" -> node.isCheckable.toString()
            "checked" -> node.isChecked.toString()
            "focused" -> node.isFocused.toString()
            "selected" -> node.isSelected.toString()
            "scrollable" -> node.isScrollable.toString()
            "editable" -> node.isEditable.toString()
            "childCount" -> node.childCount.toString()
            "depth" -> depthOf(node).toString()
            "index" -> indexOf(node).toString()
            else -> return false      // unknown attribute: refuse rather than guess
        }

        return when (cond.op) {
            "=" -> actual == cond.value || actual.equals(cond.value, ignoreCase = true)
            "!=" -> actual != cond.value
            "*=" -> actual.contains(cond.value)
            "^=" -> actual.startsWith(cond.value)
            "$=" -> actual.endsWith(cond.value)
            "exists" -> actual.isNotEmpty()
            "<", ">", "<=", ">=" -> {
                val a = actual.toDoubleOrNull() ?: return false
                val b = cond.value.toDoubleOrNull() ?: return false
                when (cond.op) {
                    "<" -> a < b
                    ">" -> a > b
                    "<=" -> a <= b
                    else -> a >= b
                }
            }
            else -> false
        }
    }

    /**
     * Depth, computed by walking up. `AccessibilityNodeInfo` has no depth field, and
     * `[depth=n]` appears in real rules, so it has to be derived.
     */
    private fun depthOf(node: AccessibilityNodeInfo): Int {
        var d = 0
        var cur = node.parent
        while (cur != null && d < 64) {
            d++
            cur = cur.parent
        }
        return d
    }

    /** Index within the parent, for `[index=n]`. */
    private fun indexOf(node: AccessibilityNodeInfo): Int {
        val parent = node.parent ?: return 0
        for (i in 0 until parent.childCount) {
            if (parent.getChild(i) === node) return i
        }
        return 0
    }

    /**
     * Walk up from [node] to the nearest clickable ancestor.
     *
     * The node carrying the text is frequently not the node that accepts a click —
     * a `TextView` inside a `LinearLayout` that owns the handler is the common case.
     * Clicking the text's own bounds does nothing, which looks like the rule failing.
     */
    fun clickableAncestor(node: AccessibilityNodeInfo, maxLevels: Int = 5): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var level = 0
        while (cur != null && level <= maxLevels) {
            if (cur.isClickable && cur.isVisibleToUser) return cur
            cur = cur.parent
            level++
        }
        return node
    }
}
