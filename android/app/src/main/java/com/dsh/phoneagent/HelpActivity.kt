package com.dsh.phoneagent

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dsh.phoneagent.Ui.card
import com.dsh.phoneagent.Ui.dp
import com.dsh.phoneagent.Ui.rounded
import com.dsh.phoneagent.Ui.text

/**
 * The capability reference.
 *
 * Narrow screens cannot show a list and its detail side by side, so the list and
 * the detail are the same surface: tapping an entry expands it in place. That
 * keeps the whole catalogue scannable in one scroll — you never lose your place
 * by navigating away and back — which is the behaviour a reference page wants,
 * as opposed to a document you read linearly.
 */
class HelpActivity : Activity() {

    private val expanded = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        root.addView(header())

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(32))
        }

        HelpContent.categories.forEach { category ->
            body.addView(categoryHeader(category))
            category.entries.forEach { entry -> body.addView(entryCard(entry)) }
        }

        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(body)
            },
        )
        return root
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.BG)
        setPadding(dp(20), dp(26), dp(20), dp(10))

        addView(
            LinearLayout(this@HelpActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(
                    text("←", 20f, Ui.BRAND, bold = true).apply {
                        gravity = Gravity.CENTER
                        background = rounded(Ui.BRAND_SOFT, 12, this@HelpActivity)
                        setPadding(dp(13), dp(6), dp(13), dp(6))
                        isClickable = true
                        setOnClickListener { finish() }
                    },
                )
                addView(
                    LinearLayout(this@HelpActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(13), 0, 0, 0)
                        addView(text("功能帮助", 21f, Ui.INK, bold = true))
                        addView(
                            text(
                                "${HelpContent.totalEntries} 项能力 · 点击展开详情与示例",
                                11.5f,
                                Ui.MUTED,
                            ).apply { setPadding(0, dp(3), 0, 0) },
                        )
                    },
                )
            },
        )
    }

    private fun categoryHeader(category: HelpContent.Category): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(20), 0, dp(8))
            addView(text(category.title, 15f, Ui.INK, bold = true))
            addView(
                text(category.blurb, 11.5f, Ui.MUTED).apply { setPadding(0, dp(3), 0, 0) },
            )
        }

    private fun entryCard(entry: HelpContent.Entry): LinearLayout {
        val key = entry.name

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded.contains(key)) View.VISIBLE else View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        detail.addView(
            text(entry.detail, 12.5f, Ui.MUTED).apply { setLineSpacing(dp(3).toFloat(), 1.15f) },
        )

        if (entry.params.isNotEmpty()) {
            detail.addView(
                text("参数", 11.5f, Ui.INK, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) },
            )
            entry.params.forEach { (name, meaning) ->
                detail.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(2), 0, dp(2))
                        addView(
                            text(name, 11f, Ui.BRAND, bold = true, mono = true).apply {
                                minWidth = dp(112)
                            },
                        )
                        addView(
                            text(meaning, 11f, Ui.MUTED).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    0,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    1f,
                                )
                            },
                        )
                    },
                )
            }
        }

        if (entry.example != null) {
            detail.addView(
                text("示例", 11.5f, Ui.INK, bold = true).apply { setPadding(0, dp(12), 0, dp(5)) },
            )
            detail.addView(
                TextView(this).apply {
                    text = entry.example
                    textSize = 10.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Ui.CODE_INK)
                    background = rounded(Ui.CODE_BG, 10, this@HelpActivity)
                    setPadding(dp(11), dp(10), dp(11), dp(10))
                    setLineSpacing(dp(2).toFloat(), 1.1f)
                    setTextIsSelectable(true)
                },
            )
        }

        val chevron = text("＋", 14f, Ui.FAINT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, 0, 0)
        }

        val card = card(paddingDp = 14).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(7) }

            addView(
                LinearLayout(this@HelpActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        text(entry.name, 14f, Ui.INK, bold = true, mono = true),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        text(entry.summary, 11.5f, Ui.MUTED).apply { gravity = Gravity.END },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { leftMargin = dp(10) },
                    )
                    addView(chevron)
                },
            )
            addView(detail)

            isClickable = true
            setOnClickListener {
                val nowVisible = detail.visibility != View.VISIBLE
                detail.visibility = if (nowVisible) View.VISIBLE else View.GONE
                chevron.text = if (nowVisible) "－" else "＋"
                if (nowVisible) expanded.add(key) else expanded.remove(key)
            }
        }

        chevron.text = if (expanded.contains(key)) "－" else "＋"
        return card
    }
}
