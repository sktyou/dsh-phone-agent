package com.dsh.phoneagent.adskip

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dsh.phoneagent.Ui
import com.dsh.phoneagent.Ui.card
import com.dsh.phoneagent.Ui.dp
import com.dsh.phoneagent.Ui.pillButton
import com.dsh.phoneagent.Ui.rounded
import com.dsh.phoneagent.Ui.sectionTitle
import com.dsh.phoneagent.Ui.text
import kotlin.concurrent.thread

/**
 * Ad-skip rule manager.
 *
 * Built from the same `Ui` helpers as the main screen — same card radius, palette and
 * type scale — so moving between the two does not feel like moving between two apps.
 *
 * The local card helper is named `adCard` rather than `card` on purpose: a member
 * function takes precedence over an extension of the same name, so a method called
 * `card` whose body calls `this.card()` resolves to itself and recurses until the
 * stack overflows. That is exactly the crash this screen shipped with once.
 */
class AdSkipActivity : Activity() {

    private lateinit var engine: AdRuleEngine
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = AdRuleEngine(applicationContext)
        engine.reload()

        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(32))
        }
        scroll.addView(content)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(header())
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        engine.reload()
        render()
    }

    /** Header matching the main screen's: title left, state right. */
    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Ui.BG)
        setPadding(dp(16), dp(22), dp(16), dp(10))

        addView(
            text("\u2190", 20f, Ui.BRAND, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), 0, dp(14), 0)
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        addView(
            LinearLayout(this@AdSkipActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("自动跳过广告", 22f, Ui.INK, bold = true))
                addView(
                    text("匹配到广告界面时自动点击", 12.5f, Ui.MUTED).apply {
                        setPadding(0, dp(3), 0, 0)
                    },
                )
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )

        val on = AdSkipSettings.isEnabled(this@AdSkipActivity)
        addView(
            text(if (on) "已开启" else "已关闭", 12.5f, if (on) Ui.OK else Ui.MUTED, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(if (on) Ui.OK_SOFT else Ui.BRAND_SOFT, 12, this@AdSkipActivity)
                setPadding(dp(14), dp(9), dp(14), dp(9))
            },
        )
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        content.removeAllViews()

        val enabled = AdSkipSettings.isEnabled(this)
        content.addView(
            adCard().apply {
                addView(
                    LinearLayout(this@AdSkipActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            LinearLayout(this@AdSkipActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(text("总开关", 15f, Ui.INK, bold = true))
                                addView(
                                    text(
                                        if (enabled) "正在监听并跳过广告" else "关闭时不会执行任何规则",
                                        12f, Ui.MUTED,
                                    ).apply { setPadding(0, dp(3), 0, 0) },
                                )
                                layoutParams = LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                                )
                            },
                        )
                        addView(
                            Switch(this@AdSkipActivity).apply {
                                isChecked = enabled
                                setOnCheckedChangeListener { _, checked ->
                                    AdSkipSettings.setEnabled(this@AdSkipActivity, checked)
                                    com.dsh.phoneagent.AgentAccessibilityService.instance?.adEngine?.let {
                                        it.enabled = checked
                                        if (checked) it.resetCounters()
                                    }
                                    render()
                                }
                            },
                        )
                    },
                )
            },
        )

        val subs = engine.snapshot()
        val stats = engine.stats()
        content.addView(
            sectionTitle("订阅 (${stats.first}) · 规则 ${stats.second} 条 / 启用 ${stats.third} 条"),
        )

        if (subs.isEmpty()) {
            content.addView(
                adCard().apply {
                    addView(text("还没有订阅。从下方推荐列表一键添加,或粘贴自定义 URL。", 12.5f, Ui.MUTED))
                },
            )
        }

        for (sub in subs) {
            content.addView(
                adCard().apply {
                    addView(
                        LinearLayout(this@AdSkipActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                LinearLayout(this@AdSkipActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    addView(text(sub.name, 15f, Ui.INK, bold = true))
                                    addView(
                                        text(
                                            "${sub.apps.size} 个应用 · ${formatTime(sub.lastUpdated)}",
                                            12f, Ui.MUTED,
                                        ).apply { setPadding(0, dp(3), 0, 0) },
                                    )
                                    layoutParams = LinearLayout.LayoutParams(
                                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                                    )
                                },
                            )
                            addView(
                                Switch(this@AdSkipActivity).apply {
                                    isChecked = sub.enabled
                                    setOnCheckedChangeListener { _, checked ->
                                        sub.enabled = checked
                                        engine.save()
                                        render()
                                    }
                                },
                            )
                        },
                    )
                    addView(
                        LinearLayout(this@AdSkipActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, dp(12), 0, 0)
                            addView(smallButton("展开规则", Ui.BRAND_SOFT, Ui.BRAND) { showRules(sub) })
                            if (sub.url.startsWith("http")) {
                                addView(smallButton("更新", Ui.BRAND_SOFT, Ui.BRAND) { refresh(sub) })
                            }
                            addView(smallButton("删除", Ui.WARN_SOFT, Ui.WARN) { confirmDelete(sub) })
                        },
                    )
                },
            )
        }

        content.addView(sectionTitle("添加订阅 · 社区维护的 GKD 规则"))
        for (s in AdRuleStore.SUGGESTED) {
            content.addView(
                adCard().apply {
                    addView(text(s.name, 14f, Ui.INK, bold = true))
                    addView(text(s.note, 12f, Ui.MUTED).apply { setPadding(0, dp(3), 0, dp(10)) })
                    addView(
                        pillButton("下载并添加", Ui.BRAND, 0xFFFFFFFF.toInt()) {
                            addFrom(s.url, s.name)
                        },
                    )
                },
            )
        }

        content.addView(
            adCard().apply {
                addView(text("自定义 URL", 14f, Ui.INK, bold = true))
                addView(
                    text("任何返回 GKD 格式 JSON 的地址", 12f, Ui.MUTED)
                        .apply { setPadding(0, dp(3), 0, dp(10)) },
                )
                val input = EditText(this@AdSkipActivity).apply {
                    hint = "https://..."
                    textSize = 13f
                    inputType = InputType.TYPE_TEXT_VARIATION_URI
                    setTextColor(Ui.INK)
                    background = rounded(Ui.BG, 10, this@AdSkipActivity)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                addView(input)
                addView(
                    pillButton("添加", Ui.BRAND, 0xFFFFFFFF.toInt()) {
                        val url = input.text.toString().trim()
                        if (url.isEmpty()) toast("请输入 URL") else addFrom(url, "")
                    }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) },
                )
            },
        )

        val hits = AdRuleStore.loadHits(this)
        if (hits.isNotEmpty()) {
            content.addView(sectionTitle("最近跳过 (${hits.size})"))
            content.addView(
                adCard().apply {
                    for (h in hits.take(30)) {
                        addView(
                            text("${formatTime(h.at)}  ${h.appName} · ${h.groupName}", 12.5f, Ui.INK)
                                .apply { setPadding(0, dp(5), 0, 0) },
                        )
                        addView(
                            text(h.matched, 11f, Ui.FAINT, mono = true)
                                .apply { setPadding(0, dp(1), 0, dp(5)) },
                        )
                    }
                    addView(
                        smallButton("清空记录", Ui.WARN_SOFT, Ui.WARN) {
                            AdRuleStore.clearHits(this@AdSkipActivity)
                            render()
                        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) },
                    )
                },
            )
        }
    }

    /** One subscription's apps and groups, each group with its own switch. */
    private fun showRules(sub: AdSubscription) {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(18), dp(16), dp(18), dp(24))
        }
        scroll.addView(box)

        for (app in sub.apps) {
            box.addView(
                text(app.name, 14f, Ui.INK, bold = true).apply { setPadding(0, dp(14), 0, dp(2)) },
            )
            box.addView(
                text(app.id, 11f, Ui.FAINT, mono = true).apply { setPadding(0, 0, 0, dp(6)) },
            )
            for (g in app.groups) {
                box.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, dp(4))
                        addView(
                            LinearLayout(this@AdSkipActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(text(g.name, 13f, Ui.INK))
                                val detail = when {
                                    g.rules.isNotEmpty() -> g.rules.first()
                                    g.steps.isNotEmpty() -> "${g.steps.size} 个步骤"
                                    else -> "无选择器"
                                }
                                addView(
                                    text(detail, 10.5f, Ui.FAINT, mono = true)
                                        .apply { setPadding(0, dp(2), 0, 0) },
                                )
                                layoutParams = LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                                )
                            },
                        )
                        addView(
                            Switch(this@AdSkipActivity).apply {
                                isChecked = g.enabled
                                setOnCheckedChangeListener { _, checked ->
                                    g.enabled = checked
                                    engine.save()
                                }
                            },
                        )
                    },
                )
            }
        }

        AlertDialog.Builder(this)
            .setTitle(sub.name)
            .setView(scroll)
            .setPositiveButton("完成") { _, _ -> engine.reload(); render() }
            .show()
    }

    // ------------------------------------------------------------------ actions

    private fun addFrom(url: String, label: String) {
        toast("下载中…")
        thread {
            val outcome = runCatching {
                val parsed = AdFormat.parseSubscription(AdRuleStore.download(url), url)
                if (parsed.apps.isEmpty()) error("规则文件里没有应用")
                parsed
            }
            runOnUiThread {
                outcome.onSuccess { sub ->
                    val subs = engine.snapshot().toMutableList()
                    subs.removeAll { it.url == url }
                    subs.add(sub)
                    AdRuleStore.save(this, subs)
                    engine.reload()
                    toast("已添加: ${sub.name} (${sub.apps.size} 应用)")
                    render()
                }.onFailure { e ->
                    AlertDialog.Builder(this)
                        .setTitle("添加失败")
                        .setMessage("$url\n\n${e.message}")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }
    }

    private fun refresh(sub: AdSubscription) {
        toast("更新中…")
        thread {
            val outcome = runCatching {
                AdFormat.parseSubscription(AdRuleStore.download(sub.url), sub.url)
            }
            runOnUiThread {
                outcome.onSuccess { fresh ->
                    // Carry the user's switches across: a refresh must not silently
                    // re-enable rules they had turned off.
                    val keep = HashMap<String, Boolean>()
                    for (a in sub.apps) for (g in a.groups) keep["${a.id}|${g.key}"] = g.enabled
                    for (a in fresh.apps) for (g in a.groups) {
                        keep["${a.id}|${g.key}"]?.let { g.enabled = it }
                    }
                    fresh.enabled = sub.enabled
                    val subs = engine.snapshot().toMutableList()
                    subs.removeAll { it.url == sub.url }
                    subs.add(fresh)
                    AdRuleStore.save(this, subs)
                    engine.reload()
                    toast("已更新")
                    render()
                }.onFailure { toast("更新失败: ${it.message}") }
            }
        }
    }

    private fun confirmDelete(sub: AdSubscription) {
        AlertDialog.Builder(this)
            .setTitle("删除订阅")
            .setMessage("将删除「${sub.name}」及其所有规则,无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val subs = engine.snapshot().toMutableList()
                subs.removeAll { it.url == sub.url && it.id == sub.id }
                AdRuleStore.save(this, subs)
                engine.reload()
                render()
            }
            .show()
    }

    // ------------------------------------------------------------------ small views

    /**
     * A card with this screen's bottom margin.
     *
     * See the class comment for why this is not named `card`.
     */
    private fun adCard(): LinearLayout = this.card().apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    private fun smallButton(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
        text(label, 12.5f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(bg, 10, this@AdSkipActivity)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(8) }
        }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "未更新"
        return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
