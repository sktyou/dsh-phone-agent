package com.dsh.phoneagent.adskip

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ad-skip rules, modelled on the GKD subscription format.
 *
 * The shape follows GKD closely enough that community subscriptions can be consumed
 * as-is: a subscription holds apps, an app holds groups, and a group holds the
 * selectors to look for. Reusing the established format means there is already a
 * large body of maintained rules instead of an empty one nobody will fill in.
 *
 * Only the fields that change behaviour are kept. Review metadata (`snapshotUrls`,
 * `desc`, author notes) is carried through for display but never consulted by the
 * engine.
 */
data class AdSubscription(
    val id: Long,
    val name: String,
    val version: Int,
    val author: String,
    /** Where it came from, so it can be refreshed. Empty for a local import. */
    val url: String,
    var apps: List<AdApp> = emptyList(),
    var enabled: Boolean = true,
    var lastUpdated: Long = 0L,
)

data class AdApp(
    val id: String,
    val name: String,
    var groups: List<AdGroup> = emptyList(),
    var enabled: Boolean = true,
)

data class AdGroup(
    val key: Int,
    val name: String,
    /** `click` or `back`. Anything else is treated as `click`. */
    val action: String,
    /** Selector expressions. Empty when the group uses keyed sub-rules. */
    var rules: List<String>,
    /** Multi-step groups: later steps only run after the referenced steps matched. */
    val steps: List<AdStep> = emptyList(),
    var activityIds: List<String> = emptyList(),
    val excludeActivityIds: List<String> = emptyList(),    /** Restrict to this package; empty means the owning app's package. */
    val matchesPackages: List<String> = emptyList(),
    /** How many times this group may fire before it stops. 0 = unlimited. */
    val actionMaximum: Int = 0,
    /** Milliseconds to wait after a match before acting. */
    val matchTime: Long = 0L,
    /** `app` or `activity`: when the repeat counter resets. */
    val resetMatch: String = "activity",
    /** Higher runs first. */
    val order: Int = 0,
    /** Milliseconds to ignore further matches after acting. */
    val actionCd: Long = 0L,
    /** Delay before performing the action. */
    val actionDelay: Long = 0L,
    var enabled: Boolean = true,
    var hitCount: Int = 0,
    var lastHitAt: Long = 0L,
)

data class AdStep(
    val key: Int,
    val name: String,
    val matches: List<String>,
    /** Steps that must have matched before this one is eligible. */
    val preKeys: List<Int> = emptyList(),
)

/** A rule that fired, for the activity log. */
data class AdHit(
    val at: Long,
    val packageName: String,
    val appName: String,
    val groupName: String,
    val action: String,
    val matched: String,
)

object AdFormat {

    /**
     * Parse a GKD-style subscription.
     *
     * The source is JSON5 in practice — comments, single quotes, unquoted keys and
     * trailing commas all appear in published subscriptions — so it is normalised to
     * strict JSON before being handed to the platform parser. Normalising text is
     * crude compared to a real JSON5 parser, but it keeps the app dependency-free and
     * is applied only to a structure we then validate field by field.
     */
    fun parseSubscription(text: String, url: String): AdSubscription {
        val json = JSONObject(normaliseJson5(text))
        val apps = mutableListOf<AdApp>()

        val appsArray = json.optJSONArray("apps") ?: JSONArray()
        for (i in 0 until appsArray.length()) {
            val appJson = appsArray.optJSONObject(i) ?: continue
            val pkg = appJson.optString("id")
            // `continue` is not allowed inside an inline lambda, so the check is
            // written as a plain condition rather than with `ifEmpty { continue }`.
            if (pkg.isEmpty()) continue
            val groups = mutableListOf<AdGroup>()

            val groupsArray = appJson.optJSONArray("groups") ?: JSONArray()
            for (g in 0 until groupsArray.length()) {
                val gj = groupsArray.optJSONObject(g) ?: continue
                groups.add(parseGroup(gj))
            }
            // A group that matched nothing is not worth showing; GKD subscriptions
            // ship many disabled and many empty scaffolding entries.
            apps.add(
                AdApp(
                    id = pkg,
                    name = appJson.optString("name").ifEmpty { pkg },
                    groups = groups,
                ),
            )
        }

        return AdSubscription(
            id = json.optLong("id", System.currentTimeMillis()),
            name = json.optString("name").ifEmpty { "未命名订阅" },
            version = json.optInt("version", 1),
            author = json.optString("author"),
            url = url,
            apps = apps,
            lastUpdated = System.currentTimeMillis(),
        )
    }

    private fun parseGroup(gj: JSONObject): AdGroup {
        val steps = mutableListOf<AdStep>()
        val flatRules = mutableListOf<String>()

        when (val r = gj.opt("rules")) {
            is String -> flatRules.add(r)
            is JSONArray -> {
                // Two different things share the `rules` name: a list of selector
                // strings, and a list of multi-step objects. Distinguish by element
                // type rather than by a flag, because the format does not flag it.
                for (i in 0 until r.length()) {
                    when (val item = r.opt(i)) {
                        is String -> flatRules.add(item)
                        is JSONObject -> steps.add(parseStep(item, i))
                    }
                }
            }
            is JSONObject -> steps.add(parseStep(r, 0))
        }

        return AdGroup(
            key = gj.optInt("key", 0),
            name = gj.optString("name").ifEmpty { "未命名规则" },
            action = gj.optString("action").ifEmpty { "click" },
            rules = flatRules,
            steps = steps,
            activityIds = stringList(gj.opt("activityIds")),
            excludeActivityIds = stringList(gj.opt("excludeActivityIds")),
            matchesPackages = stringList(gj.opt("matches")),
            actionMaximum = gj.optInt("actionMaximum", 0),
            matchTime = gj.optLong("matchTime", 0L),
            resetMatch = gj.optString("resetMatch").ifEmpty { "activity" },
            order = gj.optInt("order", 0),
            actionCd = gj.optLong("actionCd", 0L),
            actionDelay = gj.optLong("actionDelay", 0L),
            // GKD omits `enable` for many groups; those are opt-in, not opt-out.
            enabled = gj.optBoolean("enable", false),
        )
    }

    private fun parseStep(sj: JSONObject, index: Int): AdStep = AdStep(
        key = sj.optInt("key", index),
        name = sj.optString("name").ifEmpty { "步骤 $index" },
        matches = stringList(sj.opt("matches")).ifEmpty { stringList(sj.opt("rules")) },
        preKeys = intList(sj.opt("preKeys")),
    )

    private fun stringList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).ifEmpty { null } }
        else -> emptyList()
    }

    private fun intList(value: Any?): List<Int> = when (value) {
        null -> emptyList()
        is Int -> listOf(value)
        is Number -> listOf(value.toInt())
        is JSONArray -> (0 until value.length()).map { value.optInt(it) }
        else -> emptyList()
    }

    /**
     * Turn JSON5 into JSON.
     *
     * Deliberately conservative: it strips comments, unquotes keys, swaps single for
     * double quotes and removes trailing commas — the four constructs that actually
     * appear in published subscriptions. It does not attempt to be a general JSON5
     * implementation, and anything it cannot handle will surface as a parse error
     * rather than as silently wrong rules.
     */
    fun normaliseJson5(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        var quote = ' '

        while (i < text.length) {
            val c = text[i]

            if (inString) {
                if (c == '\\' && i + 1 < text.length) {
                    out.append(c).append(text[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) {
                    // The CLOSING delimiter needs converting too. Emitting the original
                    // `'` here produced `"name":"甘霖的GKD订阅'` — one stray quote that
                    // makes the whole document invalid, and the parser reports it far
                    // away as an unterminated object.
                    inString = false
                    out.append('"')
                    i++
                    continue
                }
                // A single-quoted string may contain double quotes — and in this
                // format it usually does, because selectors are written as
                // '[text="跳过"]'. Those have to be escaped or they end the string.
                if (quote == '\'' && c == '"') {
                    out.append("\\\"")
                    i++
                    continue
                }
                out.append(c)
                i++
                continue
            }

            // Line comment
            if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n') i++
                continue
            }
            // Block comment
            if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                i += 2
                while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                i += 2
                continue
            }
            if (c == '"' || c == '\'') {
                inString = true
                quote = c
                out.append('"')
                i++
                continue
            }
            out.append(c)
            i++
        }

        var result = out.toString()
        // Trailing commas before a closing brace or bracket.
        result = result.replace(Regex(",\\s*([}\\]])"), "$1")
        // Unquoted object keys: { key: ... } and {key: ...}. Anchored to a brace or
        // comma and a following colon so it cannot reach inside string values.
        result = result.replace(Regex("([{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)(\\s*:)"), "$1\"$2\"$3")
        return result
    }
}
