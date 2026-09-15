package com.dsh.phoneagent.adskip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Rule persistence and subscription fetching.
 *
 * Everything lives in one JSON file under the app's private directory and is written
 * whole. That is fine at this scale (a few thousand small groups) and avoids the
 * failure mode of a half-written index: a partial write leaves the previous file
 * intact until the replacement is complete.
 */
object AdRuleStore {

    private const val FILE = "ad_rules.json"
    private const val HIT_LOG = "ad_hits.json"
    private const val MAX_HITS = 200

    /** Well-known community subscriptions, offered as one-tap additions. */
    val SUGGESTED = listOf(
        Suggestion(
            "ganlinte/GKD-subscription",
            "https://raw.githubusercontent.com/ganlinte/GKD-subscription/main/dist/ganlin_gkd.json5",
            "社区维护,规则量大",
        ),
        Suggestion(
            "AIsouler/GKD_subscription",
            "https://raw.githubusercontent.com/AIsouler/GKD_subscription/main/dist/gkd.json5",
            "社区维护",
        ),
        Suggestion(
            "Adpro-Team/GKD_subscription",
            "https://raw.githubusercontent.com/zcbjdzdty/GKD_subscription/main/dist/gkd.json5",
            "Adpro 团队维护",
        ),
    )

    data class Suggestion(val name: String, val url: String, val note: String)

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): MutableList<AdSubscription> {
        val f = file(context)
        if (!f.isFile) return mutableListOf()
        return runCatching {
            val root = JSONArray(f.readText(Charsets.UTF_8))
            val out = mutableListOf<AdSubscription>()
            for (i in 0 until root.length()) {
                out.add(fromJson(root.getJSONObject(i)))
            }
            out
        }.getOrElse { mutableListOf() }
    }

    fun save(context: Context, subs: List<AdSubscription>) {
        val arr = JSONArray()
        for (s in subs) arr.put(toJson(s))
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        // Atomic replace: a crash mid-write leaves the old file rather than a truncated
        // one that would silently drop every rule.
        if (!tmp.renameTo(file(context))) {
            file(context).writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun toJson(s: AdSubscription): JSONObject {
        val apps = JSONArray()
        for (a in s.apps) {
            val groups = JSONArray()
            for (g in a.groups) {
                groups.put(
                    JSONObject()
                        .put("key", g.key)
                        .put("name", g.name)
                        .put("action", g.action)
                        .put("rules", JSONArray(g.rules))
                        .put("skip", g.enabled)
                        .put("hits", g.hitCount),
                )
            }
            apps.put(JSONObject().put("id", a.id).put("name", a.name).put("groups", groups))
        }
        return JSONObject()
            .put("id", s.id)
            .put("name", s.name)
            .put("version", s.version)
            .put("author", s.author)
            .put("url", s.url)
            .put("enabled", s.enabled)
            .put("updated", s.lastUpdated)
            .put("apps", apps)
    }

    private fun fromJson(j: JSONObject): AdSubscription {
        val apps = mutableListOf<AdApp>()
        val appsArray = j.optJSONArray("apps") ?: JSONArray()
        for (i in 0 until appsArray.length()) {
            val aj = appsArray.optJSONObject(i) ?: continue
            val groups = mutableListOf<AdGroup>()
            val gArray = aj.optJSONArray("groups") ?: JSONArray()
            for (k in 0 until gArray.length()) {
                val gj = gArray.optJSONObject(k) ?: continue
                groups.add(
                    AdGroup(
                        key = gj.optInt("key"),
                        name = gj.optString("name"),
                        action = gj.optString("action", "click"),
                        rules = (gj.optJSONArray("rules") ?: JSONArray())
                            .let { arr -> (0 until arr.length()).map { arr.optString(it) } },
                        enabled = gj.optBoolean("skip", false),
                        hitCount = gj.optInt("hits"),
                    ),
                )
            }
            apps.add(
                AdApp(
                    id = aj.optString("id"),
                    name = aj.optString("name"),
                    groups = groups,
                ),
            )
        }
        return AdSubscription(
            id = j.optLong("id"),
            name = j.optString("name"),
            version = j.optInt("version"),
            author = j.optString("author"),
            url = j.optString("url"),
            enabled = j.optBoolean("enabled", true),
            lastUpdated = j.optLong("updated"),
            apps = apps,
        )
    }

    /**
     * A subscription created by hand from the console.
     *
     * Rules authored this way live in their own subscription, separate from any
     * downloaded one: a refresh of the community rules must not wipe out something
     * the user built by pointing at a node on screen.
     */
    const val MANUAL_NAME = "手动添加"
    const val MANUAL_URL = "manual://local"

    /** Get or create the manual subscription. */
    fun manualBucket(context: Context): AdSubscription {
        val subs = load(context)
        subs.firstOrNull { it.url == MANUAL_URL }?.let { return it }
        val fresh = AdSubscription(
            id = 1L,
            name = MANUAL_NAME,
            version = 1,
            author = "local",
            url = MANUAL_URL,
            enabled = true,
            lastUpdated = System.currentTimeMillis(),
        )
        subs.add(fresh)
        save(context, subs)
        return fresh
    }

    /** Download a subscription. Blocking; call from a worker thread. */
    fun download(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DSH-Phone-Agent")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /** Recent rule hits, newest first. */
    fun loadHits(context: Context): MutableList<AdHit> {
        val f = File(context.filesDir, HIT_LOG)
        if (!f.isFile) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i ->
                val j = arr.optJSONObject(i) ?: return@mapNotNull null
                AdHit(
                    at = j.optLong("at"),
                    packageName = j.optString("pkg"),
                    appName = j.optString("app"),
                    groupName = j.optString("group"),
                    action = j.optString("action"),
                    matched = j.optString("matched"),
                )
            }.toMutableList()
        }.getOrElse { mutableListOf() }
    }

    fun appendHit(context: Context, hit: AdHit) {
        val hits = loadHits(context)
        hits.add(0, hit)
        while (hits.size > MAX_HITS) hits.removeAt(hits.size - 1)
        val arr = JSONArray()
        for (h in hits) {
            arr.put(
                JSONObject()
                    .put("at", h.at).put("pkg", h.packageName).put("app", h.appName)
                    .put("group", h.groupName).put("action", h.action).put("matched", h.matched),
            )
        }
        File(context.filesDir, HIT_LOG).writeText(arr.toString(), Charsets.UTF_8)
    }

    fun clearHits(context: Context) {
        File(context.filesDir, HIT_LOG).delete()
    }
}
