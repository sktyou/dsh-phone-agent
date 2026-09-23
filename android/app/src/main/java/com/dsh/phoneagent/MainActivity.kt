package com.dsh.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dsh.phoneagent.Ui.card
import com.dsh.phoneagent.Ui.circle
import com.dsh.phoneagent.Ui.dp
import com.dsh.phoneagent.Ui.glyph
import com.dsh.phoneagent.Ui.pillButton
import com.dsh.phoneagent.Ui.rounded
import com.dsh.phoneagent.Ui.sectionTitle
import com.dsh.phoneagent.Ui.statRow
import com.dsh.phoneagent.Ui.text
import com.dsh.phoneagent.Ui.twoColumn
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Status surface for the two grants the agent depends on, plus a live view of
 * what it is actually doing.
 *
 * The screen is re-rendered from state on a one-second tick rather than mutated
 * field by field: with service state, accessibility binding, client count,
 * uptime and an activity trail all moving independently, targeted updates are
 * error-prone and a full rebuild at this size is imperceptible.
 */
class MainActivity : Activity() {

    private lateinit var pill: TextView
    private lateinit var pillDot: View
    private lateinit var clientLabel: TextView
    private lateinit var addressView: TextView
    private lateinit var a11yState: TextView
    private lateinit var a11yIcon: LinearLayout
    private lateinit var serverState: TextView
    private lateinit var serverIcon: LinearLayout
    private lateinit var serverButton: TextView
    private lateinit var tokenValue: TextView
    private lateinit var tokenState: TextView
    private lateinit var tokenButton: TextView
    private lateinit var a11yCardRef: LinearLayout
    private lateinit var serverCardRef: LinearLayout
    /** The permission-card pair is height-matched once, after the first layout. */
    private var cardsAligned = false
    private lateinit var statValues: List<TextView>
    private lateinit var logContainer: LinearLayout

    private val ticker = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        // Opening the app is already an explicit intent to serve, so bring the
        // control channel up immediately instead of asking for a second tap.
        // Starting it from a visible activity also sidesteps the Android 12+
        // background foreground-service start restriction.
        if (!ControlServerService.isRunning) {
            ControlServerService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(refresh)
        ticker.post(refresh)
    }

    override fun onPause() {
        ticker.removeCallbacks(refresh)
        super.onPause()
    }

    // ------------------------------------------------------------------ layout

    private fun buildScreen(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }

        root.addView(header())
        root.addView(primaryCard(), marginTop(16))

        root.addView(sectionTitle("权限状态"))
        // Kept as fields so the pair can be height-matched after layout.
        a11yCardRef = accessibilityCard()
        serverCardRef = serverCard()
        root.addView(twoColumn(a11yCardRef, serverCardRef))

        // Sits above the stats because it is configuration, not a readout — and
        // because it no longer belongs inside the capability card, where it made
        // the two columns uneven and mixed "is it allowed" with "is it locked".
        root.addView(sectionTitle("访问令牌"))
        root.addView(tokenCard())

        root.addView(sectionTitle("MCP 服务"))
        root.addView(mcpCard())

        root.addView(sectionTitle("运行统计"))
        statValues = listOf(statValue(), statValue(), statValue())
        root.addView(
            statRow(
                listOf(
                    statTile("累计操作", statValues[0]),
                    statTile("当前连接", statValues[1]),
                    statTile("运行时长", statValues[2]),
                ),
            ),
        )

        root.addView(sectionTitle("最近活动"))
        logContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            card(paddingDp = 16).apply { addView(logContainer) },
        )

        root.addView(
            // Read from the package, never typed here.
            //
            // This line said "v0.2.1" by hand through two releases, so the app claimed
            // one version while reporting another over the wire — and the only way to
            // find out which build was actually installed was to ask adb. A version
            // number that can drift from the real one is worse than none.
            text("v${appVersionName()} · 局域网直连 · 不依赖 adb", 11.5f, Ui.FAINT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(4))
                isClickable = true
                setOnClickListener {
                    // A long-press-free way to see the full build identity, since the
                    // footer deliberately stays to one short line.
                    toast("v${appVersionName()} (build ${appVersionCode()})")
                }
            },
        )

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        val logo = View(this@MainActivity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF3B82F6.toInt(), 0xFF0EA36B.toInt()),
            ).apply { cornerRadius = dp(16).toFloat() }
            elevation = dp(6).toFloat()
        }
        addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(text("DSH Phone Agent", 22f, Ui.INK, bold = true))
                addView(
                    text("手机端无障碍控制服务", 12.5f, Ui.MUTED).apply {
                        setPadding(0, dp(3), 0, 0)
                    },
                )
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            },
        )

        addView(
            text("帮助", 12.5f, Ui.BRAND, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(Ui.BRAND_SOFT, 12, this@MainActivity)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                isClickable = true
                setOnClickListener {
                    runCatching { startActivity(Intent(this@MainActivity, HelpActivity::class.java)) }
                }
            },
        )

        // Ad-skip lives beside Help rather than in the main column: it is an optional
        // extra, and its own screen already carries the switch and the rule list.
        addView(
            text("跳广告", 12.5f, Ui.BRAND, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(Ui.BRAND_SOFT, 12, this@MainActivity)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                isClickable = true
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.leftMargin = dp(8)
                layoutParams = lp
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(this@MainActivity, com.dsh.phoneagent.adskip.AdSkipActivity::class.java))
                    }
                }
            },
        )
    }

    private fun primaryCard(): LinearLayout = card().apply {
        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                pillDot = circle(Ui.OK, 8, this@MainActivity).apply {
                    (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8)
                }
                pill = TextView(this@MainActivity).apply {
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(8), dp(2), dp(8))
                }
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = rounded(Ui.OK_SOFT, 999, this@MainActivity)
                        setPadding(dp(14), dp(7), dp(16), dp(7))
                        addView(pillDot)
                        addView(pill)
                    },
                )

                clientLabel = text("", 12.5f, Ui.MUTED, bold = true).apply {
                    gravity = Gravity.END
                }
                addView(
                    clientLabel,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { leftMargin = dp(10) },
                )
            },
        )

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Ui.CODE_BG, 16, this@MainActivity)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = marginTop(14)

                addressView = text("—", 20f, Ui.CODE_INK, bold = true, mono = true)
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(addressView)
                        addView(
                            text("在同一 Wi-Fi 的电脑上连接此地址", 11.5f, Ui.CODE_MUTED).apply {
                                setPadding(0, dp(5), 0, 0)
                            },
                        )
                        // The console is HTTP on its own port; the control port speaks a
                        // raw protocol a browser cannot use, so showing both avoids the
                        // natural-but-wrong guess of appending a path to 7912.
                        addView(
                            text(
                                "网页测试台 http://${localIpv4() ?: "手机IP"}:${WebConsole.DEFAULT_PORT}",
                                11.5f,
                                Ui.CODE_MUTED,
                            ).apply { setPadding(0, dp(3), 0, 0) },
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "复制"
                        textSize = 12.5f
                        setTextColor(0xFFE2E8F0.toInt())
                        setTypeface(typeface, Typeface.BOLD)
                        background = rounded(0x22FFFFFF, 12, this@MainActivity)
                        setPadding(dp(16), dp(9), dp(16), dp(9))
                        isClickable = true
                        setOnClickListener { copyEndpoint() }
                    },
                )
            },
        )
    }

    private fun copyEndpoint() {
        val value = addressView.text.toString()
        if (value == "—") return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("phone endpoint", value))
        Toast.makeText(this, "已复制 $value", Toast.LENGTH_SHORT).show()
    }

    /**
     * MCP service switch.
     *
     * Off by default. What the switch gates is not the control channel — that stays
     * open for the console and any existing script — but the handout: the pre-filled
     * configuration and the downloadable bridge under the mcp path. A bridge that a
     * machine on the LAN can fetch and run in two minutes is worth requiring a
     * deliberate opt-in for.
     *
     * The card owns a container that the switch rewrites in place. Rebuilding the
     * whole screen instead made every other card flicker on each toggle, which reads
     * as a glitch rather than as a state change.
     */
    private fun mcpCard(): LinearLayout = card(paddingDp = 16).apply {
        val host = "${DeviceStatus.localIpAddress()}:${WebConsole.DEFAULT_PORT}"
        val on = McpSettings.isEnabled(this@MainActivity)

        // Captured directly rather than looked up by tag: the switch needs to rewrite
        // exactly this line and the body, and holding the references is both simpler
        // and typo-proof.
        val stateText = text(mcpStateLine(on), 12f, Ui.MUTED).apply {
            setPadding(0, dp(3), 0, 0)
        }
        val body = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text("MCP 服务", 15f, Ui.INK, bold = true))
                        addView(stateText)
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                        )
                    },
                )
                addView(
                    Switch(this@MainActivity).apply {
                        isChecked = on
                        setOnCheckedChangeListener { _, checked ->
                            McpSettings.setEnabled(this@MainActivity, checked)
                            stateText.text = mcpStateLine(checked)
                            renderMcpBody(body, checked, host)
                        }
                    },
                )
            },
        )

        addView(body)
        renderMcpBody(body, on, host)
    }

    private fun mcpStateLine(enabled: Boolean): String =
        if (enabled) "已开启 · 局域网可下载并接入" else "关闭时 /mcp 返回 403"

    /** Everything below the switch, rewritten in place when it toggles. */
    private fun renderMcpBody(body: LinearLayout, enabled: Boolean, host: String) {
        body.removeAllViews()
        if (!enabled) {
            body.addView(
                text(
                    "开启后,同一局域网的电脑可以打开上面的地址," +
                        "下载 MCP 桥接文件、拿到填好的配置,直接接入 Claude Code / Cursor / Codex。",
                    12f, Ui.MUTED,
                ).apply { setPadding(0, dp(12), 0, 0) },
            )
            return
        }

        body.addView(
            text("http://$host/mcp/", 12.5f, Ui.BRAND, mono = true).apply {
                setPadding(0, dp(14), 0, dp(10))
                isClickable = true
                setOnClickListener { openUrl("http://$host/mcp/") }
            },
        )
        body.addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    pillButton("打开帮助页", Ui.BRAND, 0xFFFFFFFF.toInt()) {
                        openUrl("http://$host/mcp/")
                    }.apply {
                        (layoutParams as LinearLayout.LayoutParams).weight = 1f
                        (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8)
                    },
                )
                addView(
                    pillButton("复制接入信息", Ui.BRAND_SOFT, Ui.BRAND) {
                        copyMcpInfo(host)
                    }.apply { (layoutParams as LinearLayout.LayoutParams).weight = 1f },
                )
            },
        )
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            toast("没有可用的浏览器,地址已复制")
            copyToClipboard(url)
        }
    }

    private fun copyMcpInfo(host: String) {
        val text = buildString {
            appendLine("# DSH Phone Agent · MCP 接入")
            appendLine()
            appendLine("帮助页:  http://$host/mcp/")
            appendLine("桥接文件: http://$host/mcp/server.mjs")
            appendLine("控制端口: ${host.substringBefore(":")}:7912")
            appendLine()
            appendLine("Claude Code / Cursor / Windsurf 配置:")
            appendLine(
                """{"mcpServers":{"phone":{"command":"node","args":["server.mjs","--host","${host.substringBefore(":")}"]}}}""",
            )
            appendLine()
            appendLine("Codex:")
            appendLine("[mcp_servers.phone]")
            appendLine("command = \"node\"")
            appendLine("args = [\"server.mjs\", \"--host\", \"${host.substringBefore(":")}\"]")
            appendLine()
            append("先自检: node server.mjs --host ${host.substringBefore(":")} --selftest")
        }
        copyToClipboard(text)
        toast("接入信息已复制")
    }

    private fun copyToClipboard(value: String) {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh-phone-agent", value))
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** The installed version name, straight from the package manager. */
    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** The installed version code, straight from the package manager. */
    private fun appVersionCode(): Long = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    private fun accessibilityCard(): LinearLayout = card(paddingDp = 15).apply {
        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                a11yIcon = glyph(this@MainActivity, Ui.Glyph.ACCESSIBILITY, Ui.OK, Ui.OK_SOFT)
                addView(a11yIcon)
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(11), 0, 0, 0)
                        addView(text("无障碍", 15f, Ui.INK, bold = true))
                        a11yState = text("", 12.5f, Ui.OK, bold = true)
                        addView(a11yState)
                    },
                )
            },
        )
        addView(
            text("读取界面节点、截图并注入手势", 11.5f, Ui.MUTED).apply {
                setPadding(0, dp(12), 0, dp(12))
            },
        )
        addView(
            pillButton("打开设置", Ui.BRAND_SOFT, Ui.BRAND) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            },
        )
    }

    private fun serverCard(): LinearLayout = card(paddingDp = 15).apply {
        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                serverIcon = glyph(this@MainActivity, Ui.Glyph.SERVER, Ui.OK, Ui.OK_SOFT)
                addView(serverIcon)
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(11), 0, 0, 0)
                        addView(text("控制服务", 15f, Ui.INK, bold = true))
                        serverState = text("", 12.5f, Ui.OK, bold = true)
                        addView(serverState)
                    },
                )
            },
        )
        addView(
            text("前台服务保活,端口 ${ControlServerService.PORT}", 11.5f, Ui.MUTED).apply {
                setPadding(0, dp(12), 0, dp(12))
            },
        )
        serverButton = pillButton("启动服务", Ui.BRAND, Color.WHITE) { toggleServer() }
        addView(serverButton)
    }

    /**
     * The access-token section.
     *
     * Lifted out of the capability card: mixing "may this run at all" with "is the
     * port locked" made the two permission cards uneven, and the secret is
     * configuration that deserves room to be read and copied — which is the one
     * thing a user actually needs from it.
     */
    private fun tokenCard(): LinearLayout = card(paddingDp = 16).apply {
        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    text("访问令牌", 14f, Ui.INK, bold = true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                tokenState = text("", 12f, Ui.MUTED, bold = true)
                addView(tokenState)
            },
        )

        addView(
            text(
                "开启后每次调用都必须携带此令牌;关闭则同一局域网内的任意设备都可连接。",
                11.5f,
                Ui.MUTED,
            ).apply { setPadding(0, dp(7), 0, dp(11)) },
        )

        tokenValue = text("—", 14f, Ui.INK, bold = true, mono = true).apply {
            background = rounded(Ui.BG, 10, this@MainActivity)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            isClickable = true
            setOnClickListener { copyToken() }
        }
        addView(tokenValue)

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(11), 0, 0)
                tokenButton = pillButton("启用保护", Ui.BRAND, Color.WHITE) { toggleToken() }
                addView(
                    tokenButton,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { rightMargin = dp(5) },
                )
                addView(
                    pillButton("更换令牌", Ui.BRAND_SOFT, Ui.BRAND) { confirmRotateToken() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { leftMargin = dp(5) },
                )
            },
        )
    }

    private fun copyToken() {
        val value = ControlServerService.readToken(this)
        if (value.isEmpty()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("phone agent token", value))
        Toast.makeText(this, "令牌已复制", Toast.LENGTH_SHORT).show()
    }

    private fun toggleToken() {
        val enabling = !ControlServerService.isTokenEnabled(this)
        ControlServerService.setTokenEnabled(this, enabling)
        restartServerForToken()
        Toast.makeText(
            this,
            if (enabling) "访问令牌已启用" else "访问令牌已关闭",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * Rotate the secret, behind a confirmation.
     *
     * Rotation cannot be undone and it breaks every existing client at once: a
     * running script or a configured DSH plugin starts getting `unauthorized` the
     * moment the new value takes effect, and will keep failing until someone
     * updates it. That consequence is worth one deliberate click, so this is never
     * a silent side effect of tapping the key itself.
     */
    private fun confirmRotateToken() {
        AlertDialog.Builder(this)
            .setTitle("更换访问令牌?")
            .setMessage(
                "更换后,正在使用旧令牌的脚本或插件会立即失效,并持续返回 unauthorized," +
                    "直到把新令牌配置进去。\n\n" +
                    "此操作无法撤销。",
            )
            .setPositiveButton("确认更换") { _, _ ->
                ControlServerService.writeToken(this, ControlServerService.newToken())
                restartServerForToken()
                Toast.makeText(this, "令牌已更换,请更新调用方", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * The token is read once at server start, so changing it needs a restart.
     *
     * That is the trade that keeps the request hot path free of a preference
     * lookup on every single call.
     */
    private fun restartServerForToken() {
        ControlServerService.stop(this)
        ControlServerService.start(this)
        ticker.postDelayed({ render() }, 500L)
    }

    private fun statValue(): TextView = text("—", 22f, Ui.INK, bold = true)

    private fun statTile(label: String, value: TextView): LinearLayout = card(paddingDp = 14).apply {
        addView(value)
        addView(text(label, 11.5f, Ui.MUTED).apply { setPadding(0, dp(3), 0, 0) })
    }

    private fun toggleServer() {
        if (ControlServerService.isRunning) ControlServerService.stop(this)
        else ControlServerService.start(this)
        ticker.postDelayed({ render() }, 350L)
    }

    /**
     * Force the two capability cards to the same height.
     *
     * They are siblings in a horizontal row, so nothing makes them match: the
     * server card carries an extra token note and is naturally taller, which reads
     * as a layout mistake rather than a deliberate difference. Padding the shorter
     * one with filler would be a lie about its content, so instead both are
     * measured at their natural height and given the larger value — the pair then
     * stays symmetric no matter which side gains or loses a line later.
     *
     * Only called when the content actually changed. Re-measuring on every
     * one-second tick would thrash layout for no visible gain, and a height pinned
     * by a previous pass has to be released before measuring, or the natural
     * heights below it could never be observed.
     */
    private fun syncCapabilityCardHeights() {
        val left = a11yCardRef
        val right = serverCardRef
        left.post {
            val width = left.width
            if (width <= 0) return@post

            for (card in listOf(left, right)) {
                card.layoutParams = card.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }

            val exact = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val unbounded = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            left.measure(exact, unbounded)
            right.measure(exact, unbounded)

            val target = maxOf(left.measuredHeight, right.measuredHeight)
            if (target <= 0) return@post

            for (card in listOf(left, right)) {
                if (card.layoutParams.height != target) {
                    card.layoutParams = card.layoutParams.apply { height = target }
                }
            }
        }
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        val accessible = AgentAccessibilityService.isConnected
        val serving = ControlServerService.isRunning

        pill.text = if (serving) "服务运行中" else "服务未运行"
        pill.setTextColor(if (serving) Ui.OK else Ui.WARN)
        (pill.parent as? LinearLayout)?.background =
            rounded(if (serving) Ui.OK_SOFT else Ui.WARN_SOFT, 999, this)
        (pillDot.background as? GradientDrawable)?.setColor(if (serving) Ui.OK else Ui.WARN)

        val clients = ControlServerService.connectedClients
        clientLabel.text = if (clients > 0) "已连接 $clients 台电脑" else "等待电脑连接"
        clientLabel.setTextColor(if (clients > 0) Ui.MUTED else Ui.FAINT)

        addressView.text =
            if (serving) "${localIpv4() ?: "无 IP"}:${ControlServerService.PORT}" else "—"

        a11yState.text = if (accessible) "已连接" else "未连接"
        a11yState.setTextColor(if (accessible) Ui.OK else Ui.WARN)
        a11yIcon.background = rounded(if (accessible) Ui.OK_SOFT else Ui.WARN_SOFT, 16, this)

        serverState.text = if (serving) "监听中" else "已停止"
        serverState.setTextColor(if (serving) Ui.OK else Ui.WARN)
        serverIcon.background = rounded(if (serving) Ui.OK_SOFT else Ui.WARN_SOFT, 16, this)

        serverButton.text = if (serving) "停止服务" else "启动服务"
        serverButton.setTextColor(if (serving) Ui.WARN else Color.WHITE)
        serverButton.background = rounded(if (serving) Ui.WARN_SOFT else Ui.BRAND, 12, this)

        statValues[0].text = ControlServerService.operations.toString()
        statValues[1].text = clients.toString()
        statValues[2].text = formatUptime(ControlServerService.uptimeMs)

        val secret = ControlServerService.ensureToken(this)
        val tokenOn = ControlServerService.isTokenEnabled(this)
        tokenValue.text = secret
        tokenState.text = if (tokenOn) "已启用" else "已关闭"
        tokenState.setTextColor(if (tokenOn) Ui.OK else Ui.MUTED)
        tokenButton.text = if (tokenOn) "停用保护" else "启用保护"

        // The permission cards now hold symmetric content, so a single match after
        // the first layout is enough — no per-tick re-measuring.
        if (!cardsAligned) {
            syncCapabilityCardHeights()
            cardsAligned = true
        }

        renderLog()
    }

    private fun renderLog() {
        val entries = ControlServerService.recentOperations()
        logContainer.removeAllViews()

        if (entries.isEmpty()) {
            logContainer.addView(
                text("暂无活动 — 电脑端发起操作后会显示在这里", 12f, Ui.FAINT).apply {
                    setPadding(0, dp(16), 0, dp(16))
                },
            )
            return
        }

        val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        entries.forEachIndexed { index, entry ->
            logContainer.addView(logRow(entry, clock))
            if (index < entries.lastIndex) {
                logContainer.addView(
                    View(this).apply {
                        setBackgroundColor(0xFFEEF0F4.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(1),
                        )
                    },
                )
            }
        }
    }

    private fun logRow(entry: JSONObject, clock: SimpleDateFormat): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))

            val cmd = entry.optString("cmd", "")
            val glyphChar: String
            val tint: Int
            val soft: Int
            when (cmd) {
                "observe", "screenshot" -> {
                    glyphChar = "◉"; tint = Ui.BRAND; soft = Ui.BRAND_SOFT
                }
                "swipe", "tap", "longpress" -> {
                    glyphChar = "↕"; tint = Ui.OK; soft = Ui.OK_SOFT
                }
                "find", "findtext", "ocr", "findcolor", "findimage" -> {
                    glyphChar = "⌕"; tint = 0xFF7C3AED.toInt(); soft = 0xFFF3EBFF.toInt()
                }
                else -> {
                    glyphChar = "•"; tint = Ui.MUTED; soft = 0xFFF2F4F7.toInt()
                }
            }

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = rounded(soft, 11, this@MainActivity)
                    addView(
                        TextView(this@MainActivity).apply {
                            text = glyphChar
                            textSize = 14f
                            setTextColor(tint)
                            gravity = Gravity.CENTER
                        },
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                },
            )

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(11), 0, 0, 0)
                    addView(text(entry.optString("label", cmd), 13f, Ui.INK, bold = true))
                    addView(
                        text(clock.format(Date(entry.optLong("at"))), 11f, Ui.FAINT).apply {
                            setPadding(0, dp(2), 0, 0)
                        },
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                },
            )

            val detail = entry.optString("detail", "")
            if (detail.isNotEmpty()) addView(text(detail, 11.5f, Ui.MUTED))
        }

    private fun formatUptime(ms: Long): String {
        if (ms <= 0L) return "—"
        val total = ms / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun marginTop(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }

    /** First non-loopback IPv4 address — the one the PC should dial. */
    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (t: Throwable) {
        null
    }
}
