package com.dsh.phoneagent.adskip

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dsh.phoneagent.AgentAccessibilityService

/**
 * Watches accessibility events and fires matching ad-skip rules.
 *
 * The engine is deliberately conservative about *when* it acts. Accessibility event
 * streams are dense — a scrolling list emits hundreds of content-changed events per
 * second — and evaluating every enabled selector against the whole tree on each one
 * would burn battery and, worse, click things at random because the tree is caught
 * mid-transition.
 *
 * Three guards keep that in check:
 *  - a debounce window, so at most a few evaluations per second;
 *  - a settle delay before acting, so the tree is read after it stopped changing;
 *  - a post-action cooldown, so one rule cannot fire repeatedly on the same screen.
 */
class AdRuleEngine(private val context: Context) {

    @Volatile
    var enabled: Boolean = false

    private var subscriptions: MutableList<AdSubscription> = mutableListOf()

    /** Repeat counters, keyed by "pkg|groupKey". Reset per app or per activity. */
    private val counters = HashMap<String, Int>()

    /** Last time a group acted, for `actionCd`. */
    private val lastActionAt = HashMap<String, Long>()

    private var lastEvaluateAt = 0L
    private var lastPackage = ""
    private var lastActivity = ""

    fun reload() {
        subscriptions = AdRuleStore.load(context)
    }

    fun snapshot(): List<AdSubscription> = subscriptions

    fun save() = AdRuleStore.save(context, subscriptions)

    /** Packages that have at least one enabled group, for a cheap pre-filter. */
    private fun activePackages(): Set<String> {
        val out = HashSet<String>()
        for (s in subscriptions) {
            if (!s.enabled) continue
            for (a in s.apps) {
                if (!a.enabled) continue
                for (g in a.groups) {
                    if (g.enabled && (g.rules.isNotEmpty() || g.steps.isNotEmpty())) {
                        out.add(a.id)
                        break
                    }
                }
            }
        }
        return out
    }

    fun onEvent(event: AccessibilityEvent, service: AgentAccessibilityService) {
        if (!enabled) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastEvaluateAt < DEBOUNCE_MS) return

        var pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) pkg = service.foregroundPackageName()
        if (pkg.isEmpty() || pkg == context.packageName) return

        // Cheap rejection before touching the tree.
        if (pkg !in activePackages()) return

        val activity = service.currentActivityName()
        if (pkg != lastPackage || activity != lastActivity) {
            if (pkg != lastPackage) {
                // A different app: every `resetMatch=app` counter starts over.
                counters.keys.removeAll { it.startsWith("$pkg|") }
            }
            lastPackage = pkg
            lastActivity = activity
            counters.keys.removeAll { it.endsWith("|$activity") }
        }

        lastEvaluateAt = now

        val root = service.rootInActiveWindow ?: return
        if (!root.isVisibleToUser) return

        for (sub in subscriptions) {
            if (!sub.enabled) continue
            for (app in sub.apps) {
                if (!app.enabled || app.id != pkg) continue
                for (group in app.groups) {
                    if (!group.enabled) continue
                    if (!activityAllowed(group, activity)) continue
                    if (!withinLimits(app, group, activity)) continue
                    if (tryGroup(service, root, app, group, pkg)) return
                }
            }
        }
    }

    private fun activityAllowed(group: AdGroup, activity: String): Boolean {
        if (activity.isEmpty()) return group.activityIds.isEmpty()
        if (group.excludeActivityIds.any { activityMatches(it, activity) }) return false
        if (group.activityIds.isEmpty()) return true
        return group.activityIds.any { activityMatches(it, activity) }
    }

    /** Activity ids in rules are sometimes suffixes (`.ui.MainActivity`). */
    private fun activityMatches(rule: String, actual: String): Boolean =
        actual == rule || actual.endsWith(rule) || actual.endsWith(".$rule") ||
            actual.contains(rule)

    private fun withinLimits(app: AdApp, group: AdGroup, activity: String): Boolean {
        val key = counterKey(app.id, group, activity)
        if (group.actionMaximum > 0 && (counters[key] ?: 0) >= group.actionMaximum) return false
        val last = lastActionAt[key] ?: 0L
        if (group.actionCd > 0 && System.currentTimeMillis() - last < group.actionCd) return false
        return true
    }

    private fun counterKey(pkg: String, group: AdGroup, activity: String): String =
        if (group.resetMatch == "app") "$pkg|${group.key}" else "$pkg|${group.key}|$activity"

    /**
     * Evaluate one group and act if it matches. Returns true when it acted, which
     * stops the scan — acting twice on one event would click through two dialogs at
     * once, which real rules are not written to expect.
     */
    private fun tryGroup(
        service: AgentAccessibilityService,
        root: AccessibilityNodeInfo,
        app: AdApp,
        group: AdGroup,
        pkg: String,
    ): Boolean {
        var matchedSelector: String? = null
        var target: AccessibilityNodeInfo? = null

        // Multi-step groups: only steps whose prerequisites already matched are tried.
        if (group.steps.isNotEmpty()) {
            val done = matchedStepKeys(group)
            for (step in group.steps) {
                if (step.preKeys.isNotEmpty() && !step.preKeys.all { it in done }) continue
                for (sel in step.matches) {
                    val r = AdSelector.find(root, sel)
                    if (r.ok) {
                        matchedSelector = sel
                        target = r.node
                        markStepMatched(group, step.key)
                        break
                    }
                }
                if (target != null) break
            }
        }

        if (target == null) {
            for (sel in group.rules) {
                val r = AdSelector.find(root, sel)
                if (r.ok) {
                    matchedSelector = sel
                    target = r.node
                    break
                }
            }
        }

        if (target == null || matchedSelector == null) return false

        if (group.actionDelay > 0) {
            runCatching { Thread.sleep(group.actionDelay) }
        }

        val performed = when (group.action) {
            "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            else -> {
                val node = AdSelector.clickableAncestor(target)
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            }
        }

        if (!performed) return false

        val activity = lastActivity
        val key = counterKey(pkg, group, activity)
        counters[key] = (counters[key] ?: 0) + 1
        lastActionAt[key] = System.currentTimeMillis()
        group.hitCount++
        group.lastHitAt = System.currentTimeMillis()

        AdRuleStore.appendHit(
            context,
            AdHit(
                at = System.currentTimeMillis(),
                packageName = pkg,
                appName = app.name,
                groupName = group.name,
                action = group.action,
                matched = matchedSelector,
            ),
        )
        Log.i(TAG, "跳过广告: ${app.name} / ${group.name} / $matchedSelector")
        return true
    }

    private val matchedSteps = HashMap<String, MutableSet<Int>>()

    private fun stepKey(group: AdGroup) = "steps|${group.key}"

    private fun matchedStepKeys(group: AdGroup): Set<Int> =
        matchedSteps[stepKey(group)] ?: emptySet()

    private fun markStepMatched(group: AdGroup, key: Int) {
        matchedSteps.getOrPut(stepKey(group)) { mutableSetOf() }.add(key)
    }

    fun resetCounters() {
        counters.clear()
        lastActionAt.clear()
        matchedSteps.clear()
    }

    /** Statistics for the UI. */
    fun stats(): Triple<Int, Int, Int> {
        var subs = 0
        var groups = 0
        var active = 0
        for (s in subscriptions) {
            if (!s.enabled) continue
            subs++
            for (a in s.apps) {
                for (g in a.groups) {
                    groups++
                    if (g.enabled) active++
                }
            }
        }
        return Triple(subs, groups, active)
    }

    companion object {
        private const val TAG = "DSHAdSkip"

        /**
         * Minimum gap between evaluations.
         *
         * Content-changed events arrive in bursts during scrolling and animation. At
         * 400ms the engine still reacts well inside a typical 3-5s splash ad while
         * evaluating a few times a second instead of a few hundred.
         */
        private const val DEBOUNCE_MS = 400L
    }
}
