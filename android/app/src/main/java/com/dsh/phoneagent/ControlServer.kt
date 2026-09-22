package com.dsh.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Line-delimited JSON control channel over a plain TCP socket.
 *
 * One request per line, one reply per line, so the PC side needs nothing beyond
 * a socket and a JSON parser. Operations are serialised through [opLock]: two
 * concurrent gesture injections on the same screen produce an interleaved event
 * stream that neither the framework nor the target app can interpret.
 */
class ControlServer(
    private val context: Context,
    private val port: Int,
    private val token: String = "",
) {

    private val running = AtomicBoolean(false)
    private val clients = AtomicInteger(0)
    private val ops = AtomicInteger(0)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "phone-agent").apply { isDaemon = true }
    }

    /** Serialises every device-mutating or screen-reading operation. */
    private val opLock = Any()

    /** Requests admitted but not yet finished, for load shedding. */
    private val inFlight = AtomicInteger(0)

    /** When the last tap-like gesture was issued, for throttling. */
    private val lastTapAt = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var serverSocket: ServerSocket? = null

    val isRunning: Boolean get() = running.get()
    val clientCount: Int get() = clients.get()
    val operationCount: Int get() = ops.get()

    /** Uptime and a short activity trail, both surfaced on the status screen. */
    private val startedAtMs = System.currentTimeMillis()
    private val recent = java.util.Collections.synchronizedList(ArrayList<JSONObject>())

    val uptimeMs: Long get() = System.currentTimeMillis() - startedAtMs

    /** Newest first, capped at [MAX_RECENT]. */
    fun recentOperations(): List<JSONObject> = synchronized(recent) { recent.toList() }

    private fun recordOperation(cmd: String, data: JSONObject) {
        val entry = JSONObject().put("cmd", cmd).put("at", System.currentTimeMillis())
        when (cmd) {
            "observe" -> entry.put("label", "截图 + 界面树")
                .put("detail", "${data.optInt("imageWidth")}x${data.optInt("imageHeight")}")
            "screenshot" -> entry.put("label", "截图")
            "swipe" -> entry.put("label", if (data.optBoolean("human", true)) "滑动 · 拟人" else "滑动 · 机械")
                .put("detail", "${data.optLong("durationMs")} ms")
            "tap" -> entry.put("label", "点击")
            "doubletap" -> entry.put("label", "双击 · 拟人")
            "longpress" -> entry.put("label", "长按 · 拟人")
                .put("detail", "${data.optLong("holdMs")} ms")
            "uitree" -> entry.put("label", "界面树")
                .put("detail", "${data.optInt("nodeCount")} 节点")
            "device" -> entry.put("label", "设备状态")
            "volume" -> entry.put("label", "调节音量")
                .put("detail", "${data.optInt("current")}/${data.optInt("max")}")
            "brightness" -> entry.put("label", "调节亮度")
                .put("detail", "${data.optInt("percent")}%")
            "find" -> entry.put("label", "控件查找").put("detail", "命中 ${data.optInt("count")}")
            "findtext" -> entry.put("label", "文本查找").put("detail", "命中 ${data.optInt("count")}")
            "ocr" -> entry.put("label", "OCR 识别").put("detail", "${data.optInt("blockCount")} 块")
            "sweep" -> entry.put("label", "滚动识别")
                .put("detail", "${data.optInt("scrolls")} 屏 / ${data.optInt("lineCount")} 行")
            "findcolor" -> entry.put("label", "找色").put("detail", "${data.optInt("count")} px")
            "findimage" -> entry.put("label", "找图").put("detail", "命中 ${data.optInt("count")}")
            "text" -> entry.put("label", "输入文本")
            "key" -> entry.put("label", "系统按键")
            "launch" -> entry.put("label", "启动应用").put("detail", data.optString("launched"))
            "wait" -> entry.put("label", "等待")
            "colorat" -> entry.put("label", "单点取色")
                .put("detail", data.optString("color"))
            "wake" -> entry.put("label", "唤醒屏幕")
            "clipboard" -> entry.put("label", "剪贴板")
            "deeplink" -> entry.put("label", "打开链接")
            "apps" -> entry.put("label", "应用列表")
                .put("detail", "${data.optInt("count")} 个")
            "stopapp" -> entry.put("label", "结束应用")
                .put("detail", data.optString("requested"))
            "pinch" -> entry.put("label", "双指缩放")
            "flick" -> entry.put("label", "惯性甩动")
                .put("detail", "${data.optLong("durationMs")} ms")
            "scroll" -> entry.put("label", "滚动容器")
                .put("detail", data.optString("direction"))
            "waitstable" -> entry.put("label", "等待稳定")
                .put("detail", if (data.optBoolean("stable")) "已稳定" else "超时")
            "send" -> entry.put("label", "提交输入")
            "delete" -> entry.put("label", "回删")
                .put("detail", "${data.optInt("deleted")} 字符")
            "clear" -> entry.put("label", "清空输入框")
            else -> entry.put("label", cmd)
        }
        synchronized(recent) {
            recent.add(0, entry)
            while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pool.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                Log.i(TAG, "control server listening on 0.0.0.0:$port")
                while (running.get()) {
                    val socket = try {
                        ss.accept()
                    } catch (t: Throwable) {
                        if (running.get()) Log.w(TAG, "accept failed: ${t.message}")
                        break
                    }
                    clients.incrementAndGet()
                    pool.execute { serve(socket) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "server loop failed", t)
            } finally {
                running.set(false)
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun serve(socket: Socket) {
        try {
            socket.use { s ->
                s.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    writer.write(process(line).toString())
                    writer.write("\n")
                    writer.flush()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "client session ended: ${t.message}")
        } finally {
            clients.decrementAndGet()
        }
    }

    /**
     * Run one command line and return its reply.
     *
     * Exposed so the on-device web console can reuse the exact same path instead of
     * dialling back in over loopback TCP. That detour cost ~200ms per request
     * (measured: 8ms direct vs 217ms through the round trip) for no benefit, and
     * keeping one entry point still means one implementation of every command —
     * validation, error codes and all.
     */
    fun handle(line: String): JSONObject = process(line)

    private fun process(line: String): JSONObject {
        var id = 0
        return try {
            val req = JSONObject(line)
            id = req.optInt("id", 0)
            if (token.isNotEmpty() && req.optString("token") != token) {
                fail(id, "unauthorized", "unauthorized")
            } else {
                // Load shedding. Without this, a caller that fires a burst without
                // waiting for replies queues every one of them behind a serialised
                // op lock, and the last reply arrives long after it stopped being
                // useful. Refusing is more informative than making them wait.
                if (inFlight.get() >= MAX_IN_FLIGHT) {
                    fail(id, "busy: $MAX_IN_FLIGHT operations already in flight; retry shortly")
                } else {
                    inFlight.incrementAndGet()
                    val data = try {
                        synchronized(opLock) { execute(req) }
                    } finally {
                        inFlight.decrementAndGet()
                    }
                    ops.incrementAndGet()
                    recordOperation(req.optString("cmd", "").lowercase(), data)
                    JSONObject().put("id", id).put("ok", true).put("data", data)
                }
            }
        } catch (t: Throwable) {
            // Classify so a caller can react instead of just retrying blindly:
            // "not_ready" means try again in a moment, "bad_request" means the
            // arguments are wrong and retrying identically will fail identically.
            val code = when (t) {
                is IllegalArgumentException -> "bad_request"
                is IllegalStateException -> "not_ready"
                is java.util.concurrent.TimeoutException -> "timeout"
                is java.io.IOException -> "io"
                else -> "error"
            }
            fail(id, t.message ?: t.toString(), code)
        }
    }

    private fun fail(id: Int, message: String, code: String = "error") = JSONObject()
        .put("id", id)
        .put("ok", false)
        .put("error", message)
        .put("code", code)

    // ---------------------------------------------------------------- commands

    private fun execute(req: JSONObject): JSONObject {
        val cmd = req.optString("cmd", "").lowercase()
        val service = AgentAccessibilityService.instance

        when (cmd) {
            "ping" -> return JSONObject()
                .put("pong", true)
                .put("ts", System.currentTimeMillis())
                .put("accessibility", service != null)
                .put("serving", true)
            "info" -> return deviceInfo(service)
            // Full device snapshot needs no accessibility grant, so it stays
            // available even when the service is not yet bound.
            "device" -> return deviceSnapshot()
            "perms" -> return permissions(req)
            "skill" -> return skillDoc(req)
            "adrule" -> return adRule(req)
            "locate" -> return locate(requireService(), req)
            "sequence" -> return sequence(requireService(), req)
            "incidents" -> {
                if (req.optBoolean("clear", false)) IncidentLog.clear()
                return IncidentLog.snapshot()
            }
        }

        if (service == null) {
            throw IllegalStateException(
                "accessibility service is not connected; enable 'DSH Phone Agent' " +
                    "in Settings > Accessibility on the phone",
            )
        }

        return when (cmd) {
            "observe" -> observe(service, req)
            "screenshot" -> screenshot(service, req)
            "uitree" -> annotateTreeQuality(
                UiTree.dump(
                    service.rootNode(),
                    req.optInt("maxDepth", 30),
                    req.optInt("maxNodes", 2000),
                ),
            )
            "find" -> NodeQuery.find(
                service.rootNode(),
                req.optJSONObject("selector") ?: req,
                req.optInt("max", 10),
            )
            "findtext" -> findText(service, req)
            "ocr" -> ocr(service, req)
            "sweep" -> sweep(service, req)
            "findcolor" -> findColor(service, req)
            "findmulti" -> findMultiColor(service, req)
            "findimage" -> findImage(service, req)
            "tap" -> tap(service, req)
            "doubletap" -> doubleTap(service, req)
            "longpress" -> longPress(service, req)
            "swipe" -> swipeSegmented(service, req)
            "swipemeasure" -> swipeMeasure(service, req)
            "volume" -> adjustVolume(req)
            "brightness" -> setBrightness(req)
            "colorat" -> colorAt(service, req)
            "wake" -> wakeScreen()
            "clipboard" -> clipboard(req)
            "deeplink" -> deepLink(req)
            "apps" -> listApps(req)
            "stopapp" -> stopApp(req)
            "pinch" -> pinch(service, req)
            "flick" -> flick(service, req)
            "scroll" -> scrollNode(service, req)
            "waitstable" -> waitStable(service, req)
            "key" -> key(service, req)
            "text" -> text(service, req)
            "send" -> sendIme(service)
            "delete" -> deleteText(service, req)
            "clear" -> clearField(service)
            "launch" -> launch(req)
            "wait" -> {
                // Two modes under one command: a bare sleep when no target is given,
                // and a wait-for-element when one is. Keeping them together means a
                // caller that already knows about `wait` does not have to discover a
                // second verb to stop guessing at timings.
                if (req.has("target")) {
                    waitForTarget(requireService(), req)
                } else {
                    val ms = req.optLong("ms", 500L).coerceIn(0L, 15_000L)
                    Thread.sleep(ms)
                    JSONObject().put("waitedMs", ms).put("appeared", true)
                }
            }
            else -> throw IllegalArgumentException("unknown command: $cmd")
        }
    }

    /**
     * Full-screen display metrics.
     *
     * `resources.displayMetrics` reports the app's usable area, which on a
     * gesture-nav device excludes the navigation bar — 2261 rather than 2400 on
     * the test device. Screenshots and touch injection both work in full-screen
     * pixels, so reporting the app-area height here would silently mislead any
     * caller deriving coordinates from it.
     */
    private fun realScreen(): android.util.DisplayMetrics {
        val metrics = android.util.DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun rotationDegrees(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        return when (wm.defaultDisplay.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** Package owning the active window, or "" when it cannot be read. */
    private fun foregroundPackage(service: AgentAccessibilityService?): String {
        val root = service?.rootNode() ?: return ""
        return root.packageName?.toString() ?: ""
    }

    private fun deviceInfo(service: AgentAccessibilityService?): JSONObject {
        val metrics = realScreen()
        return JSONObject()
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("screenWidth", metrics.widthPixels)
            .put("screenHeight", metrics.heightPixels)
            .put("density", metrics.density.toDouble())
            .put("rotation", rotationDegrees())
            .put("screenOn", DeviceStatus.isScreenOn(context))
            .put("locked", DeviceStatus.isLocked(context))
            .put("foregroundPackage", foregroundPackage(service))
            .put("accessibility", service != null)
            .put("clients", clients.get())
            .put("operations", ops.get())
    }

    /**
     * Apply the caller's `region` and `scale` to a captured frame.
     *
     * Scaling matters because the bytes usually dominate an observe round trip: a
     * 1080x2400 JPEG is ~100 KiB, and halving each edge cuts that roughly
     * fourfold. Responses still report the FULL screen size alongside the scale
     * factor, so a caller can map what it sees back to touch coordinates instead
     * of guessing — touch input always takes full-screen pixels.
     */
    private fun prepareFrame(bitmap: Bitmap, req: JSONObject): Bitmap {
        var frame = bitmap

        val region = req.optJSONArray("region")
        if (region != null && region.length() >= 4) {
            val left = region.getInt(0).coerceIn(0, bitmap.width)
            val top = region.getInt(1).coerceIn(0, bitmap.height)
            val right = region.getInt(2).coerceIn(0, bitmap.width)
            val bottom = region.getInt(3).coerceIn(0, bitmap.height)
            if (right > left && bottom > top) {
                frame = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            }
        }

        val scale = req.optDouble("scale", 1.0)
        if (scale in 0.05..0.99) {
            val scaled = Bitmap.createScaledBitmap(
                frame,
                max(1, (frame.width * scale).toInt()),
                max(1, (frame.height * scale).toInt()),
                true,
            )
            if (scaled !== frame) frame = scaled
        }

        // A hard ceiling on width, independent of `scale`. Callers that only need to
        // read a list do not know the screen size up front, so a proportional scale
        // forces them to guess; "no wider than N" is the thing they actually want.
        val maxWidth = req.optInt("maxWidth", 0)
        if (maxWidth > 0 && frame.width > maxWidth) {
            val ratio = maxWidth.toDouble() / frame.width
            val capped = Bitmap.createScaledBitmap(
                frame,
                maxWidth,
                max(1, (frame.height * ratio).toInt()),
                true,
            )
            if (capped !== frame) frame = capped
        }
        return frame
    }

    /**
     * Screen size + scale the caller must apply to map image pixels back to touch pixels.
     *
     * `regionOrigin` is the other half of that mapping and was missing: a cropped frame
     * sits at an offset in screen space, so a caller dividing by scale alone lands
     * wherever the top-left of the screen is instead of wherever the crop started.
     */
    private fun frameMeta(req: JSONObject, frame: Bitmap): JSONObject {
        val screen = realScreen()
        val region = req.optJSONArray("region")
        val originX = if (region != null && region.length() >= 4) region.getInt(0) else 0
        val originY = if (region != null && region.length() >= 4) region.getInt(1) else 0
        // Derived rather than echoed from `req`: with `maxWidth` in play the effective
        // ratio is not the requested one, and a caller trusting the echo would be off.
        val effectiveX = frame.width.toDouble() / screen.widthPixels
        return JSONObject()
            .put("screenWidth", screen.widthPixels)
            .put("screenHeight", screen.heightPixels)
            .put("scale", req.optDouble("scale", 1.0))
            .put("imageWidth", frame.width)
            .put("imageHeight", frame.height)
            .put("regionOrigin", JSONArray(listOf(originX, originY)))
            .put("imageToScreenX", effectiveX)
            .put("imageToScreenY", frame.height.toDouble() / screen.heightPixels)
            .put("rotation", rotationDegrees())
    }

    private fun observe(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val includeUi = req.optBoolean("includeUi", true)
        val bitmap = service.capture() ?: throw IllegalStateException("screenshot failed")

        val frame = prepareFrame(bitmap, req)
        val (image, format) = encodeImage(
            frame,
            req.optString("format", "jpeg"),
            req.optInt("quality", 82),
        )
        val out = frameMeta(req, frame)
            .put("imageFormat", format)
            .put("image", image)
            .put("foregroundPackage", foregroundPackage(service))
        if (frame !== bitmap) frame.recycle()
        bitmap.recycle()

        if (includeUi) {
            out.put(
                "ui",
                UiTree.dump(
                    service.rootNode(),
                    req.optInt("maxDepth", 30),
                    req.optInt("maxNodes", 2000),
                    req.optBoolean("interactiveOnly", false),
                ),
            )
        }
        return out
    }

    private fun screenshot(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val bitmap = service.capture() ?: throw IllegalStateException("screenshot failed")
        val frame = prepareFrame(bitmap, req)
        val (image, format) = encodeImage(
            frame,
            req.optString("format", "jpeg"),
            req.optInt("quality", 82),
        )
        val out = frameMeta(req, frame)
            .put("imageFormat", format)
            .put("image", image)
        if (frame !== bitmap) frame.recycle()
        bitmap.recycle()
        return out
    }

    /** Pure on-device OCR. Slower than the tree, so use it where the tree is blind. */
    private fun ocr(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val region = req.optJSONArray("region")?.let { arr ->
            if (arr.length() < 4) null
            else Rect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
        }
        val bitmap = service.capture() ?: throw IllegalStateException("screenshot failed")
        return try {
            OcrEngine.recognize(bitmap, region)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Scroll-and-read sweep: OCR every screenful on the way down, in one call.
     *
     * Reading a long list one screen at a time is dominated by round-trip cost, not
     * by the work itself — each step pays for a scroll, a settle, a capture, and a
     * transfer, and the caller then has to interpret an image before it can decide
     * to continue. A forty-item menu becomes forty of those.
     *
     * This collapses it: the phone scrolls, OCRs, and de-duplicates locally, and
     * returns plain text. One call covers many screenfuls, and the caller consumes
     * text instead of images.
     *
     * It also stops on its own. Two consecutive screenfuls that add nothing new mean
     * the list has ended, so `scrolls` is an upper bound rather than a fixed cost —
     * asking for 30 on a 6-screen list still finishes in 7.
     */
    private fun sweep(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val metrics = realScreen()
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val maxScrolls = req.optInt("scrolls", 8).coerceIn(1, 200)
        val distance = req.optInt("distance", screenH / 2).coerceIn(200, screenH - 200)
        val durationMs = req.optLong("durationMs", 500L).coerceIn(60L, 4_000L)
        val settleMs = req.optLong("settleMs", 600L).coerceIn(100L, 5_000L)
        val x = req.optInt("x", screenW / 2).coerceIn(1, screenW - 1)
        val fromY = req.optInt("fromY", screenH * 4 / 5).coerceIn(1, screenH - 1)
        val toY = (fromY - distance).coerceAtLeast(1)

        // One gesture only moves the content about half a screenful — beyond that the
        // list stops following the finger, so a bigger `distance` buys nothing (measured:
        // 1130px -> 2000px gained 1.7% more text). Sliding several times per capture
        // accumulates the movement instead. `perCapture` is capped so the total stays
        // within one screen height; overshooting would skip rows between two captures,
        // and those rows would be lost silently.
        //
        // The cap is a whole screen, not a screen minus a margin: with 1130px steps a
        // 200px margin rounded 2 steps down to 1 and silently disabled the feature.
        val perCapture = req.optInt("perCapture", 2).coerceIn(1, 8)
        val maxPerCapture = (screenH / distance.coerceAtLeast(1)).coerceAtLeast(1)
        val steps = perCapture.coerceAtMost(maxPerCapture)

        val region = req.optJSONArray("region")?.let { arr ->
            if (arr.length() < 4) null
            else Rect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
        }

        // Text -> its first sighting, in reading order.
        //
        // The value carries geometry and the capture index, which is what lets a caller
        // pair a price with the product above it. The old output was a bare list of
        // strings, so on a multi-column layout a caller had no way to tell which ¥9.9
        // belonged to which item — and every pairing it guessed was a coin flip.
        val seen = LinkedHashMap<String, JSONObject>()
        var performed = 0
        var emptyRuns = 0
        var stopReason = "scrolls-exhausted"

        for (i in 0 until maxScrolls) {
            // Captured explicitly rather than with `run { ... break }`: an inline
            // lambda cannot break out of the enclosing loop.
            val bitmap = service.capture()
            if (bitmap == null) {
                stopReason = "screenshot-failed"
                break
            }
            val result = try {
                OcrEngine.recognize(bitmap, region)
            } finally {
                bitmap.recycle()
            }

            // A plain loop rather than forEach: an inline lambda cannot `break`, and
            // stopping early is the whole point of the counter below.
            var fresh = 0
            val ocrLines = result.optJSONArray("ocrLines")
            if (ocrLines != null && ocrLines.length() > 0) {
                for (j in 0 until ocrLines.length()) {
                    val entry = ocrLines.optJSONObject(j) ?: continue
                    val text = entry.optString("text").trim()
                    // Single characters are almost always noise picked off icons or badges.
                    if (text.length < 2 || seen.containsKey(text)) continue
                    seen[text] = JSONObject()
                        .put("text", text)
                        .put("bounds", entry.opt("bounds"))
                        .put("center", entry.opt("center"))
                        .put("confidence", entry.opt("confidence"))
                        .put("capture", i)
                    fresh++
                }
            } else {
                // Fallback for a recogniser that returned no lines: keeps sweep working
                // rather than silently producing nothing.
                for (raw in result.optString("fullText").split('\n')) {
                    val line = raw.trim()
                    if (line.length >= 2 && !seen.containsKey(line)) {
                        seen[line] = JSONObject().put("text", line).put("capture", i)
                        fresh++
                    }
                }
            }

            if (fresh == 0) {
                // Two blank screenfuls in a row means the end of the list. One is not
                // enough, because a slow-loading image can leave a screen temporarily bare.
                if (++emptyRuns >= 2) {
                    stopReason = "reached-end"
                    break
                }
            } else {
                emptyRuns = 0
            }

            if (i == maxScrolls - 1) break
            repeat(steps) {
                // Same travel-plus-brake gesture as `swipe`, so a swept scroll stops
                // where it was aimed instead of coasting past it.
                dispatchSwipeSegmented(
                    service, x.toFloat(), fromY.toFloat(), x.toFloat(), toY.toFloat(),
                    durationMs, defaultBrakeMs(),
                )
                Thread.sleep(120L)
            }
            performed++
            Thread.sleep(settleMs)
        }

        return JSONObject()
            .put("scrolls", performed)
            .put("stepsPerCapture", steps)
            .put("lineCount", seen.size)
            .put("stopReason", stopReason)
            // Legacy plain-text array, unchanged so existing callers keep working.
            .put("lines", JSONArray(seen.keys.toList()))
            .put("ocrLines", JSONArray(seen.values.toList()))
    }

    /**
     * Find text on screen, fusing the accessibility tree with OCR.
     *
     * `source` picks the strategy, and the default is deliberately ordered by cost:
     *
     *  - `node` — tree only. Single-digit milliseconds, and it also carries the
     *    widget id and clickability, which OCR cannot provide.
     *  - `ocr`  — OCR only. Hundreds of milliseconds, but it sees what the tree
     *    cannot: WebViews, canvases, games, text baked into images.
     *  - `auto` — tree first, OCR only when the tree found nothing. Most screens
     *    are native widgets, so this stays fast while still covering the blind spots.
     *
     * With `tappable`, each hit is resolved through a hit test to the clickable
     * widget actually under that point. That conversion matters because the label
     * and the thing you can press are usually different nodes.
     */
    private fun findText(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val needle = req.getString("text")
        val mode = req.optString("mode", "contains").lowercase()
        val source = req.optString("source", "auto").lowercase()
        val tappable = req.optBoolean("tappable", true)
        val max = req.optInt("max", 5).coerceIn(1, 50)
        val root = service.rootNode()

        val selector = JSONObject().apply {
            when (mode) {
                "exact" -> put("text", needle)
                "regex" -> put("textRegex", needle)
                else -> put("textContains", needle)
            }
        }

        var usedSource = "node"
        val matches = org.json.JSONArray()

        fun addNodeMatches(): Int {
            val result = NodeQuery.find(root, selector, max)
            val array = result.optJSONArray("matches") ?: return 0
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i).put("from", "node")
                attachTapTarget(root, item, tappable)
                matches.put(item)
            }
            return array.length()
        }

        fun addOcrMatches(): Int {
            val bitmap = service.capture() ?: throw IllegalStateException("screenshot failed")
            val recognized = try {
                OcrEngine.recognize(bitmap, null)
            } finally {
                bitmap.recycle()
            }
            val blocks = recognized.optJSONArray("blocks") ?: return 0
            var added = 0
            for (b in 0 until blocks.length()) {
                if (matches.length() >= max) break
                val lines = blocks.getJSONObject(b).optJSONArray("lines") ?: continue
                for (l in 0 until lines.length()) {
                    if (matches.length() >= max) break
                    val line = lines.getJSONObject(l)
                    val text = line.optString("text", "")
                    if (!textMatches(text, needle, mode)) continue
                    val item = JSONObject()
                        .put("text", text)
                        .put("from", "ocr")
                        .put("bounds", line.optJSONArray("bounds") ?: org.json.JSONArray())
                        .put("center", line.optJSONArray("center") ?: org.json.JSONArray())
                    attachTapTarget(root, item, tappable)
                    matches.put(item)
                    added++
                }
            }
            return added
        }

        when (source) {
            "node" -> addNodeMatches()
            "ocr" -> {
                usedSource = "ocr"
                addOcrMatches()
            }
            else -> {
                if (addNodeMatches() == 0) {
                    usedSource = "ocr"
                    addOcrMatches()
                }
            }
        }

        return JSONObject()
            .put("count", matches.length())
            .put("source", usedSource)
            .put("query", needle)
            .put("mode", mode)
            .put("matches", matches)
    }

    private fun textMatches(candidate: String, needle: String, mode: String): Boolean = when (mode) {
        "exact" -> candidate == needle
        "regex" -> runCatching { Regex(needle).containsMatchIn(candidate) }.getOrDefault(false)
        else -> candidate.contains(needle)
    }

    /** Resolve a hit's centre to the clickable widget underneath it. */
    private fun attachTapTarget(root: android.view.accessibility.AccessibilityNodeInfo?, item: JSONObject, tappable: Boolean) {
        if (!tappable) return
        val centre = item.optJSONArray("center") ?: return
        if (centre.length() < 2) return
        val target = NodeQuery.hitTest(root, centre.getInt(0), centre.getInt(1), requireClickable = true)
        if (target != null) item.put("tapTarget", target)
    }

    /**
     * Find pixels by colour. Takes a fresh frame, so the coordinates returned are
     * valid for the screenshot the caller is reasoning about.
     */
    private fun findColor(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val color = ImageSearch.parseColor(req.getString("color"))
        val region = req.optJSONArray("region")?.let { arr ->
            if (arr.length() < 4) null
            else Rect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
        }
        val bitmap = service.capture() ?: throw IllegalStateException("screenshot failed")
        return try {
            ImageSearch.findColor(
                bitmap,
                color,
                req.optInt("tolerance", 16),
                region,
                req.optInt("maxClusters", 20),
                req.optInt("clusterSize", 24),
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** Template match; the template arrives as base64 PNG/JPEG. */
    private fun findImage(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val raw = Base64.decode(req.getString("template"), Base64.DEFAULT)
        val template = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw IllegalArgumentException("template is not a decodable image")
        val region = req.optJSONArray("region")?.let { arr ->
            if (arr.length() < 4) null
            else Rect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
        }
        val bitmap = service.capture() ?: throw IllegalStateException("screenshot failed")
        return try {
            ImageSearch.findImage(
                bitmap,
                template,
                req.optDouble("threshold", 0.85),
                region,
                req.optInt("maxResults", 5),
            )
        } finally {
            bitmap.recycle()
            template.recycle()
        }
    }

    /**
     * The full device snapshot: identity, screen, power, audio, network, storage,
     * memory, plus this agent's own service state.
     *
     * Requires no accessibility grant, so a caller can inspect the phone and
     * discover that the service is not bound yet.
     */
    private fun deviceSnapshot(): JSONObject {
        val serviceInfo = JSONObject()
            .put("running", running.get())
            .put("port", port)
            .put("uptimeMs", uptimeMs)
            .put("clients", clients.get())
            .put("operations", ops.get())
            .put("recent", org.json.JSONArray().apply {
                for (entry in recentOperations()) put(entry)
            })
        return DeviceStatus.snapshot(context, serviceInfo)
    }

    /**
     * Adjust a volume stream.
     *
     * Going through AudioManager rather than synthesising key events means it
     * works while the screen is locked and does not hand focus to whatever else
     * is running.
     */
    private fun adjustVolume(req: JSONObject): JSONObject {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (req.optString("stream", "music").lowercase()) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val flags = AudioManager.FLAG_SHOW_UI
        when (req.optString("action", "up").lowercase()) {
            "up" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags)
            "down" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags)
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
            "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
            "set" -> {
                val level = req.getInt("level")
                    .coerceIn(0, audio.getStreamMaxVolume(stream))
                audio.setStreamVolume(stream, level, flags)
            }
            else -> throw IllegalArgumentException("volume action must be up / down / mute / unmute / set")
        }
        return JSONObject()
            .put("current", audio.getStreamVolume(stream))
            .put("max", audio.getStreamMaxVolume(stream))
    }

    /**
     * Set screen brightness.
     *
     * Needs the special WRITE_SETTINGS grant, which cannot be requested as an
     * ordinary runtime permission — the user has to grant it from the app's
     * settings page. `device` reports `brightness.writable` so a caller can check
     * before trying, and the error below names the exact screen to visit.
     */
    private fun setBrightness(req: JSONObject): JSONObject {
        if (!Settings.System.canWrite(context)) {
            throw IllegalStateException(
                "WRITE_SETTINGS not granted; allow 'Modify system settings' for this app " +
                    "in Settings > Apps > DSH Phone Agent",
            )
        }
        // Convert through the device's own scale, not a hard-coded 255: MIUI's
        // ceiling is far higher, so a literal conversion would land the screen at a
        // fraction of the brightness that was actually asked for.
        val max = DeviceStatus.maxBrightness(context)
        val level = if (req.has("percent")) {
            (req.getInt("percent").coerceIn(0, 100) * max) / 100
        } else {
            req.getInt("level").coerceIn(0, max)
        }
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            level,
        )
        return JSONObject()
            .put("level", level)
            .put("max", max)
            .put("percent", level * 100 / max)
    }

    /**
     * Colour of a single screen pixel.
     *
     * The cheap counterpart to `findcolor`: sampling one point costs a screenshot
     * and an array index, while findcolor scans the whole frame. Use it to poll a
     * status indicator, not to locate something.
     */
    private fun colorAt(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val x = req.getInt("x")
        val y = req.getInt("y")
        val bitmap = service.capture() ?: throw IllegalStateException("screenshot failed")
        return try {
            if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) {
                throw IllegalArgumentException("($x,$y) is outside ${bitmap.width}x${bitmap.height}")
            }
            val pixel = bitmap.getPixel(x, y)
            JSONObject()
                .put("x", x)
                .put("y", y)
                .put("color", String.format("#%06X", pixel and 0xFFFFFF))
                .put("r", (pixel shr 16) and 0xFF)
                .put("g", (pixel shr 8) and 0xFF)
                .put("b", pixel and 0xFF)
                .put("alpha", (pixel ushr 24) and 0xFF)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Turn the screen on.
     *
     * Only wakes the panel — it cannot and deliberately does not try to unlock.
     * Gestures dispatched against a dark screen are silently dropped, which is one
     * of the harder failures to diagnose, so a caller should wake before touching.
     */
    private fun wakeScreen(): JSONObject {
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wasOn = power.isInteractive
        if (!wasOn) {
            @Suppress("DEPRECATION")
            val wakelock = power.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "dsh-phone-agent:wake",
            )
            wakelock.acquire(3000L)
            wakelock.release()
        }
        return JSONObject()
            .put("screenOn", power.isInteractive)
            .put("wasOn", wasOn)
            .put("locked", DeviceStatus.isLocked(context))
    }

    /**
     * Read or write the system clipboard.
     *
     * `action:"paste"` also injects ACTION_PASTE into the focused field — the way
     * to get text in when a field refuses ACTION_SET_TEXT because it only accepts
     * real input events.
     */
    private fun clipboard(req: JSONObject): JSONObject {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return when (req.optString("action", "get").lowercase()) {
            "set" -> {
                val text = req.getString("text")
                manager.setPrimaryClip(android.content.ClipData.newPlainText("dsh", text))
                JSONObject().put("written", true).put("length", text.length)
            }
            "paste" -> {
                val service = AgentAccessibilityService.instance
                    ?: throw IllegalStateException("accessibility service is not connected")
                val root = service.rootNode() ?: throw IllegalStateException("no active window")
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: throw IllegalStateException("no focused editable field")
                JSONObject().put("pasted", focused.performAction(AccessibilityNodeInfo.ACTION_PASTE))
            }
            else -> {
                val clip = manager.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context).toString()
                } else {
                    ""
                }
                JSONObject().put("text", text).put("length", text.length)
            }
        }
    }

    /**
     * Open a URI, landing directly on a specific screen.
     *
     * Removes the "launch, then navigate" sequence — and with it the common failure
     * where an app opens on a splash or an update prompt and the caller cannot find
     * its way in.
     */
    private fun deepLink(req: JSONObject): JSONObject {
        val uri = req.getString("uri")
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pkg = req.optString("package", "")
            if (pkg.isNotEmpty()) setPackage(pkg)
        }
        context.startActivity(intent)
        return JSONObject().put("opened", uri)
    }

    /**
     * Installed packages.
     *
     * Reads the full package list rather than querying launcher activities: on
     * Android 11+ an intent query is filtered by package visibility, and plenty of
     * installed things have no launcher entry at all (service-only apps, disabled
     * components). `launchable` is reported per entry so a caller can tell which
     * ones it can actually start, and `totalInstalled` is returned alongside the
     * filtered count so a narrowed query is never mistaken for a small phone.
     */
    private fun listApps(req: JSONObject): JSONObject {
        val pm = context.packageManager
        val query = req.optString("query", "").lowercase()
        val includeSystem = req.optBoolean("includeSystem", false)
        val launchableOnly = req.optBoolean("launchableOnly", false)
        val apps = org.json.JSONArray()

        @Suppress("DEPRECATION")
        val installed = pm.getInstalledPackages(0)
        for (info in installed) {
            val app = info.applicationInfo ?: continue
            val pkg = info.packageName
            val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) continue

            val launchable = pm.getLaunchIntentForPackage(pkg) != null
            if (launchableOnly && !launchable) continue

            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("")
            if (query.isNotEmpty() &&
                !label.lowercase().contains(query) &&
                !pkg.lowercase().contains(query)
            ) {
                continue
            }

            apps.put(
                JSONObject()
                    .put("label", label)
                    .put("package", pkg)
                    .put("system", isSystem)
                    .put("launchable", launchable)
                    .put("enabled", app.enabled)
                    .put("versionName", info.versionName ?: ""),
            )
        }

        return JSONObject()
            .put("count", apps.length())
            .put("totalInstalled", installed.size)
            .put("apps", apps)
    }

    /**
     * Ask the system to reclaim a background app's memory.
     *
     * This is the strongest thing available without root: `killBackgroundProcesses`
     * only affects processes already in the background, so a foreground app is
     * untouched. Reporting that honestly is more useful than pretending it is a
     * force-stop.
     */
    private fun stopApp(req: JSONObject): JSONObject {
        val pkg = req.getString("package")
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.killBackgroundProcesses(pkg)
        val stillRunning = am.runningAppProcesses?.any { it.processName.startsWith(pkg) } == true
        return JSONObject()
            .put("requested", pkg)
            .put("backgroundKilled", true)
            .put("stillRunning", stillRunning)
            .put(
                "note",
                if (stillRunning) {
                    "process is in the foreground; without root a foreground app cannot be stopped"
                } else {
                    "background processes reclaimed"
                },
            )
    }

    /**
     * Two-finger pinch around a point.
     *
     * `endSpread` larger than `startSpread` zooms in, smaller zooms out.
     */
    private fun pinch(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val cx = req.getDouble("x").toFloat()
        val cy = req.getDouble("y").toFloat()
        val startSpread = req.optDouble("startSpread", 200.0).toFloat()
        val endSpread = req.optDouble("endSpread", 600.0).toFloat()
        val duration = req.optLong("durationMs", 400L).coerceIn(100L, 3000L)
        val completed = dispatchWithRetry(service, req, timeoutMs = duration + 6_000L) {
            GestureEngine.pinch(cx, cy, startSpread, endSpread, duration)
        }
        return JSONObject()
            .put("completed", completed)
            .put("startSpread", startSpread)
            .put("endSpread", endSpread)
    }

    /**
     * A flick: short and fast, leaving the surface scrolling under its own momentum.
     *
     * Distinct from `swipe` because apps genuinely respond differently: the same
     * distance delivered slowly drags, delivered fast throws.
     */
    private fun flick(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val x1 = req.getDouble("x1").toFloat()
        val y1 = req.getDouble("y1").toFloat()
        val x2 = req.getDouble("x2").toFloat()
        val y2 = req.getDouble("y2").toFloat()
        val duration = req.optLong("durationMs", 90L).coerceIn(40L, 400L)
        val completed = dispatchWithRetry(service, req, timeoutMs = duration + 6_000L) {
            GestureEngine.flick(x1, y1, x2, y2, duration)
        }
        return JSONObject().put("completed", completed).put("durationMs", duration)
    }

    /**
     * Scroll a container by one viewport, instead of blind-swiping the screen.
     *
     * Anchoring to the container's own bounds keeps the gesture inside it, so it
     * cannot accidentally land on a floating button or the navigation bar.
     */
    private fun scrollNode(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val root = service.rootNode() ?: throw IllegalStateException("no active window")
        val direction = req.optString("direction", "forward").lowercase()
        val target = NodeQuery.findScrollable(root, req.optInt("index", 0))
            ?: throw IllegalStateException("no scrollable container on screen")
        val scrolled = if (direction == "backward") {
            target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        } else {
            target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        return JSONObject()
            .put("scrolled", scrolled)
            .put("direction", direction)
            .put("container", NodeQuery.describeScrollable(target))
    }

    /**
     * Wait until the UI stops changing.
     *
     * More reliable than a fixed sleep: consecutive identical tree fingerprints
     * mean layout and rendering have settled. Reports whether it actually settled,
     * so a caller can tell "ready" apart from "still busy at the deadline".
     */
    private fun waitStable(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val timeout = req.optLong("timeoutMs", 5000L).coerceIn(200L, 30_000L)
        val interval = req.optLong("intervalMs", 180L).coerceIn(60L, 1000L)
        val required = req.optInt("stableFrames", 2).coerceIn(1, 10)

        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeout
        var last = ""
        var stable = 0
        var samples = 0

        while (System.currentTimeMillis() < deadline) {
            val fingerprint = NodeQuery.fingerprint(service.rootNode())
            samples++
            if (fingerprint == last) {
                stable++
                if (stable >= required) {
                    return JSONObject()
                        .put("stable", true)
                        .put("samples", samples)
                        .put("elapsedMs", System.currentTimeMillis() - startedAt)
                        .put("fingerprint", fingerprint)
                }
            } else {
                stable = 0
                last = fingerprint
            }
            Thread.sleep(interval)
        }
        return JSONObject()
            .put("stable", false)
            .put("samples", samples)
            .put("elapsedMs", System.currentTimeMillis() - startedAt)
            .put("note", "UI was still changing at the deadline")
    }

    /**
     * Write text into the focused field.
     *
     * `mode:"replace"` (default) uses ACTION_SET_TEXT: atomic, and it handles any
     * Unicode without an IME. `mode:"append"` reads the current value first, and
     * `mode:"clear"` empties the field.
     *
     * `typing:"natural"` commits one character at a time with a randomised gap.
     * A whole paragraph materialising in a single frame is itself a signal — no
     * human types that way — but it is slower by design and only worth paying for
     * where an app watches input timing rather than content.
     */
    private fun text(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val value = req.getString("text")
        val mode = req.optString("mode", "replace").lowercase()
        val natural = req.optString("typing", "instant").lowercase() == "natural"

        val root = service.rootNode() ?: throw IllegalStateException("no active window")
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw IllegalStateException("no focused editable field")

        if (mode == "clear") {
            return JSONObject().put("cleared", setTextOn(focused, ""))
        }

        val existing = if (mode == "append") focused.text?.toString() ?: "" else ""
        val target = existing + value

        if (!natural) {
            return JSONObject()
                .put("inserted", setTextOn(focused, target))
                .put("length", target.length)
                .put("mode", mode)
                .put("typing", "instant")
        }

        var committed = existing
        var ok = true
        for (ch in value) {
            committed += ch
            if (!setTextOn(focused, committed)) {
                ok = false
                break
            }
            Thread.sleep(45L + java.util.Random().nextLong().mod(130L))
        }
        return JSONObject()
            .put("inserted", ok)
            .put("length", committed.length)
            .put("mode", mode)
            .put("typing", "natural")
    }

    private fun setTextOn(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Submit the focused field — the IME's editor action.
     *
     * Backed by ACTION_IME_ENTER, which asks the input method to perform whatever
     * its action key does: send in a chat box, search in a search field, done in a
     * form. This is deliberately NOT a synthesised Enter keypress — an
     * accessibility service cannot inject key events at all without shell
     * privileges — but it is also exactly what the user's tap on that key does, so
     * apps treat the two identically.
     */
    private fun sendIme(service: AgentAccessibilityService): JSONObject {
        val root = service.rootNode() ?: throw IllegalStateException("no active window")
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw IllegalStateException("no focused editable field")
        // ACTION_IME_ENTER lives on AccessibilityAction (API 30+), not in the
        // legacy ACTION_* constant block.
        val sent = focused.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
        )
        return JSONObject()
            .put("sent", sent)
            .put(
                "note",
                if (sent) {
                    "IME action performed"
                } else {
                    "the field refused an IME action — it may not declare one"
                },
            )
    }

    /**
     * Delete characters from the end of the focused field.
     *
     * There is no accessibility action that sends a backspace — that would need
     * INJECT_EVENTS, which in turn needs shell privileges. So this reads the
     * current value and writes it back without the tail. Content is identical to
     * pressing backspace repeatedly; what differs is that it lands as one atomic
     * write, so a field inspecting keystroke timing could tell the difference.
     * That is a hard limit of the rootless path, not an oversight.
     */
    private fun deleteText(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val count = req.optInt("count", 1).coerceIn(1, 500)
        val root = service.rootNode() ?: throw IllegalStateException("no active window")
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw IllegalStateException("no focused editable field")
        val current = focused.text?.toString() ?: ""
        val removed = minOf(count, current.length)
        val next = if (removed >= current.length) "" else current.substring(0, current.length - removed)
        return JSONObject()
            .put("deleted", removed)
            .put("remaining", next.length)
            .put("applied", setTextOn(focused, next))
    }

    /** Empty the focused field. */
    private fun clearField(service: AgentAccessibilityService): JSONObject {
        val root = service.rootNode() ?: throw IllegalStateException("no active window")
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw IllegalStateException("no focused editable field")
        val had = focused.text?.length ?: 0
        return JSONObject()
            .put("cleared", setTextOn(focused, ""))
            .put("clearedLength", had)
    }

    /**
     * Dispatch a gesture, retrying a cancelled one.
     *
     * A cancelled gesture is usually transient: the screen was mid-animation, or a
     * system window briefly took the touch stream. Retrying is what a human does
     * without thinking about it, so it belongs here as a parameter rather than
     * becoming every caller's problem. Each attempt rebuilds the gesture, so a
     * retry is a fresh trajectory rather than a replay of the identical stroke.
     */
    private fun dispatchWithRetry(
        service: AgentAccessibilityService,
        req: JSONObject,
        timeoutMs: Long = 10_000L,
        build: () -> GestureDescription,
    ): Boolean {
        val attempts = req.optInt("retry", 1).coerceIn(1, 5)
        var completed = false
        for (attempt in 0 until attempts) {
            completed = service.dispatch(build(), timeoutMs)
            if (completed) return true
            if (attempt < attempts - 1) Thread.sleep(120L)
        }
        return completed
    }

    /**
     * Enforce a minimum gap between tap-like gestures.
     *
     * A finger cannot produce taps faster than this, and neither should a caller.
     * Without a floor, a loop that fires a hundred taps queues a hundred full
     * round trips — each one a gesture plus a screenshot — and the caller sees
     * nothing at all until the backlog drains, which reads as a hang.
     *
     * Sleeping here rather than rejecting keeps every individual reply prompt:
     * the caller gets an answer roughly every [DEFAULT_TAP_GAP_MS], which is
     * something it can reason about.
     *
     * Swipes and long presses are deliberately exempt. They are inherently long
     * gestures, and spacing them has no physical basis.
     */
    private fun throttleTap(req: JSONObject) {
        val minGap = req.optLong("minIntervalMs", DEFAULT_TAP_GAP_MS).coerceIn(0L, 5_000L)
        if (minGap <= 0L) return
        val previous = lastTapAt.get()
        if (previous > 0L) {
            val elapsed = System.currentTimeMillis() - previous
            if (elapsed < minGap) Thread.sleep(minGap - elapsed)
        }
        lastTapAt.set(System.currentTimeMillis())
    }

    /**
     * Run the pre-execution safety check when the caller has not opted out.
     *
     * Returns null when disabled. The verdict is advisory by default: it rides along
     * in the reply so a caller can see it landed on nothing, but the gesture still
     * goes out — plenty of legitimate targets (canvas, WebView, game surfaces) have no
     * accessibility node. `abortOnUnsafe` flips that to a hard stop for callers that
     * would rather not act blindly.
     */
    private fun safetyFor(
        service: AgentAccessibilityService,
        req: JSONObject,
        x: Float,
        y: Float,
    ): SafetyNet.Verdict? {
        if (!req.optBoolean("safetyNet", true)) return null
        return runCatching { SafetyNet.check(service, x.toInt(), y.toInt()) }.getOrNull()
    }

    private fun tap(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val x = req.getDouble("x").toFloat()
        val y = req.getDouble("y").toFloat()
        val human = req.optBoolean("human", true)
        val safety = safetyFor(service, req, x, y)

        if (safety != null && !safety.ok && req.optBoolean("abortOnUnsafe", false)) {
            IncidentLog.record("tap", "$x,$y", safety.hint)
            return JSONObject()
                .put("completed", false)
                .put("aborted", true)
                .put("safety", SafetyNet.toJson(safety))
        }

        throttleTap(req)
        val completed = dispatchWithRetry(service, req) {
            if (human) {
                GestureEngine.tap(x, y)
            } else {
                mechanicalLine(x, y, x + 0.01f, y + 0.01f, 60L)
            }
        }

        if (completed) IncidentLog.resolve("tap")
        else IncidentLog.record("tap", "$x,$y", "手势被系统取消")

        return JSONObject()
            .put("completed", completed)
            .put("x", x)
            .put("y", y)
            .put("human", human)
            .apply { safety?.let { put("safety", SafetyNet.toJson(it)) } }
    }

    private fun longPress(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val x = req.getDouble("x").toFloat()
        val y = req.getDouble("y").toFloat()
        val hold = req.optLong("holdMs", 650L).coerceIn(300L, 5_000L)
        val safety = safetyFor(service, req, x, y)
        val completed = dispatchWithRetry(service, req, timeoutMs = hold + 6_000L) {
            GestureEngine.longPress(x, y, hold)
        }
        if (completed) IncidentLog.resolve("longpress")
        else IncidentLog.record("longpress", "$x,$y", "手势被系统取消")
        return JSONObject()
            .put("completed", completed)
            .put("holdMs", hold)
            .apply { safety?.let { put("safety", SafetyNet.toJson(it)) } }
    }

    private fun doubleTap(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val x = req.getDouble("x").toFloat()
        val y = req.getDouble("y").toFloat()
        val safety = safetyFor(service, req, x, y)
        throttleTap(req)
        val completed = dispatchWithRetry(service, req) { GestureEngine.doubleTap(x, y) }
        if (completed) IncidentLog.resolve("doubletap")
        else IncidentLog.record("doubletap", "$x,$y", "手势被系统取消")
        return JSONObject()
            .put("completed", completed)
            .put("x", x)
            .put("y", y)
            .apply { safety?.let { put("safety", SafetyNet.toJson(it)) } }
    }

    private fun swipe(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val x1 = req.getDouble("x1").toFloat()
        val y1 = req.getDouble("y1").toFloat()
        val x2 = req.getDouble("x2").toFloat()
        val y2 = req.getDouble("y2").toFloat()
        val duration = req.optLong("durationMs", 320L).coerceIn(60L, 4_000L)
        val human = req.optBoolean("human", true)

        // Collect the sampled points so a caller can draw what was actually sent.
        // Recomputing the curve from the same formula would not do: the bow and the
        // tremor are randomised per call, so the redrawn curve would differ.
        val trace = if (req.optBoolean("trace", false)) mutableListOf<FloatArray>() else null

        val completed = dispatchWithRetry(service, req, timeoutMs = duration + 8_000L) {
            if (human) {
                GestureEngine.swipe(x1, y1, x2, y2, duration, trace = trace)
            } else {
                mechanicalLine(x1, y1, x2, y2, duration)
            }
        }
        return JSONObject()
            .put("completed", completed)
            .put("human", human)
            .put("durationMs", duration)
            .put("distance", kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toInt())
            .apply { trace?.let { put("path", JSONArray(it.map { p -> JSONArray(listOf(p[0], p[1])) })) } }
    }

    /** The straight, constant-speed stroke `adb shell input swipe` produces. */
    private fun mechanicalLine(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): GestureDescription {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
    }

    private fun key(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val requested = req.optString("key").uppercase()
        val action = when (requested) {
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS", "APPSWITCH" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "POWER" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "LOCK" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "SCREENSHOT" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> throw IllegalArgumentException("unsupported key: $requested")
        }
        val performed = service.performGlobalAction(action)
        return JSONObject()
            .put("performed", performed)
            .put("key", requested)
            .put("label", KEY_LABELS[requested] ?: requested)
    }

    /** Human-readable names, so callers and logs do not have to map the codes. */
    private val KEY_LABELS = mapOf(
        "BACK" to "返回",
        "HOME" to "回到桌面",
        "RECENTS" to "最近任务",
        "APPSWITCH" to "最近任务",
        "NOTIFICATIONS" to "下拉通知栏",
        "QUICK_SETTINGS" to "快捷设置面板",
        "POWER" to "电源菜单",
        "LOCK" to "锁屏",
        "SCREENSHOT" to "截屏",
    )

    /**
     * Start an app, by default from a clean task.
     *
     * A plain launch intent only brings an existing task to the front, so the app
     * reappears exactly where it was left — mid-checkout, on a stale search result,
     * behind a login sheet. Every coordinate a caller then computes is relative to
     * a screen it did not expect, and the whole run goes wrong in a way that looks
     * like a targeting bug rather than a stale screen.
     *
     * `FLAG_ACTIVITY_CLEAR_TASK` is what actually fixes that: it discards the
     * existing task and restarts from the root activity. Killing the background
     * process first is belt-and-braces for apps whose task survives, and HOME
     * beforehand makes the teardown observable rather than racing the launch.
     *
     * Pass `fresh:false` only when deliberately resuming where the app was.
     */
    private fun launch(req: JSONObject): JSONObject {
        val pkg = req.getString("package")
        val fresh = req.optBoolean("fresh", true)

        if (fresh) {
            AgentAccessibilityService.instance?.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
            )
            Thread.sleep(350)
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(pkg)
            }
            Thread.sleep(450)
        }

        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw IllegalArgumentException("no launchable activity for $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (fresh) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)

        // Android 10+ blocks background activity starts, and this process is in the
        // background whenever the user is not looking at our screen — startActivity
        // then returns normally while nothing appears. An AccessibilityService is on
        // the platform's exemption list, so launching through its context is what
        // actually works. The service context is also why this needs no
        // SYSTEM_ALERT_WINDOW grant.
        val starter = AgentAccessibilityService.instance ?: context
        starter.startActivity(intent)

        // Verify rather than assume, and poll rather than sleeping a fixed amount.
        // Android 10+ (and MIUI in particular, via its own "background pop-up"
        // switch) can silently drop a background activity start: startActivity
        // returns normally and nothing appears. Reporting success there is the worst
        // outcome, since the caller then reasons about the wrong screen.
        //
        // A fixed wait is not enough either: mid-transition `rootInActiveWindow` is
        // null, so a single early sample reads as a failure even when the launch
        // succeeds a moment later.
        val deadline = System.currentTimeMillis() +
            req.optLong("verifyMs", 5_000L).coerceIn(500L, 15_000L)
        var foreground = ""
        while (System.currentTimeMillis() < deadline) {
            foreground = foregroundPackage(AgentAccessibilityService.instance)
            if (foreground == pkg || foreground.startsWith("$pkg")) break
            Thread.sleep(200L)
        }
        val started = foreground == pkg || foreground.startsWith("$pkg")

        return JSONObject()
            .put("launched", pkg)
            .put("fresh", fresh)
            .put("via", if (starter === context) "app" else "accessibility")
            .put("foreground", foreground)
            .put("started", started)
            .put(
                "hint",
                if (started) {
                    ""
                } else {
                    "the start was blocked. On MIUI allow '后台弹出界面' for this app " +
                        "(Settings > Apps > DSH Phone Agent > Permissions), or keep this " +
                        "app in the foreground when launching."
                },
            )
    }

    /**
     * Swipe, then report what the screen actually did with it.
     *
     * The finger distance a gesture covers says nothing about how far the content
     * moved: the list may not follow past its limit, may keep coasting after
     * lift-off, or may ignore the stroke entirely. Comparing the screen before and
     * after is the only way to know, and it is what turns "the list moves a little"
     * from an impression into a number.
     */
    private fun swipeMeasure(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val x1 = req.getDouble("x1").toFloat()
        val y1 = req.getDouble("y1").toFloat()
        val x2 = req.getDouble("x2").toFloat()
        val y2 = req.getDouble("y2").toFloat()
        val duration = req.optLong("durationMs", 500L).coerceIn(60L, 4_000L)
        val settleMs = req.optLong("settleMs", 800L).coerceIn(100L, 5_000L)

        val before = service.capture() ?: throw IllegalStateException("screenshot failed")

        val trace = mutableListOf<FloatArray>()
        val completed = try {
            dispatchSwipeSegmented(
                service, x1, y1, x2, y2, duration, defaultBrakeMs(), trace,
            )
        } catch (t: Throwable) {
            before.recycle()
            throw t
        }

        // Let the scroll finish. A fling keeps moving after the stroke ends, so
        // sampling immediately would under-report the distance.
        Thread.sleep(settleMs)
        val after = service.capture() ?: run {
            before.recycle()
            throw IllegalStateException("post-swipe screenshot failed")
        }

        val result = GestureEngine.measureVerticalShift(before, after)
        before.recycle()
        after.recycle()

        val fingerDistance = kotlin.math.hypot(
            (x2 - x1).toDouble(),
            (y2 - y1).toDouble(),
        )
        val shift = result.shift

        return JSONObject()
            .put("completed", completed)
            .put("fingerDistance", fingerDistance.toInt())
            // -1 rather than 0 when nothing matched: 0 is a legitimate answer
            // ("the screen did not move") and must stay distinguishable from
            // "the screen changed too much to measure".
            .put("contentShift", shift ?: -1)
            .put(
                "efficiency",
                if (shift != null && fingerDistance > 0) shift / fingerDistance else 0.0,
            )
            // Kept in the reply so a failed match can be told apart from a genuine
            // zero: a high score means the two screenshots never lined up at all.
            .put("matchScore", kotlin.math.round(result.score * 10) / 10.0)
            .put("matchConfident", result.confident)
            .put("durationMs", duration)
            .put("path", JSONArray(trace.map { p -> JSONArray(listOf(p[0], p[1], p[2])) }))
    }

    /**
     * Swipe as two chained gestures: the travel, then a brief brake.
     *
     * The brake is the fix for a problem that only shows up on short swipes. The
     * framework classifies a gesture as a drag or a fling from the velocity at
     * lift-off, and a single constant-speed stroke cannot be slow at the end without
     * being slow throughout — a 300ms swipe covering 1000px is moving at ~3300px/s
     * the instant the finger leaves, so it keeps coasting past the target. A 1000ms
     * swipe happens to be under the threshold and lands correctly.
     *
     * `continueStroke` is what makes it work: the second description continues the
     * first stroke instead of starting a new touch, so the framework sees one
     * unbroken press that comes to rest before lifting. It must be a separate
     * `GestureDescription` — strokes added to the *same* builder run simultaneously
     * as a multi-finger gesture, which silently discards the whole thing.
     *
     * The brake path is about a pixel long, so the duration is spent standing still.
     */
    private fun swipeSegmented(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val x1 = req.getDouble("x1").toFloat()
        val y1 = req.getDouble("y1").toFloat()
        val x2 = req.getDouble("x2").toFloat()
        val y2 = req.getDouble("y2").toFloat()
        val moveMs = req.optLong("durationMs", 320L).coerceIn(60L, 10_000L)
        // The hand pauses on the target before lifting. Not a constant: a person does
        // not brake for exactly the same time twice, and a fixed pause is one more
        // thing that repeats identically on every gesture. 250-350ms brackets the
        // ~300ms observed by hand.
        val brakeOverride = req.optLong("brakeMs", -1L)
        val brakeMs = if (brakeOverride >= 0L) {
            brakeOverride.coerceIn(0L, 2_000L)
        } else {
            defaultBrakeMs()
        }

        val trace = if (req.optBoolean("trace", false)) mutableListOf<FloatArray>() else null
        val completed = dispatchSwipeSegmented(
            service, x1, y1, x2, y2, moveMs, brakeMs, trace,
        )

        // Offset the traced timestamps: the brake follows the travel, not overlaps it.
        val path = trace?.map { p -> JSONArray(listOf(p[0], p[1], p[2] + moveMs)) }

        return JSONObject()
            .put("completed", completed)
            .put("human", true)
            .put("segmented", true)
            .put("durationMs", moveMs)
            .put("brakeMs", brakeMs)
            .put("totalMs", moveMs + brakeMs)
            .put("distance", kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toInt())
            .apply { path?.let { put("path", JSONArray(it)) } }
    }

    /** Randomised braking pause, matching how a hand does not brake identically twice. */
    private fun defaultBrakeMs(): Long =
        (BRAKE_MIN_MS + kotlin.random.Random.nextInt(BRAKE_SPREAD_MS + 1)).toLong()

    /**
     * Dispatch a swipe as travel + brake, blocking until it finishes.
     *
     * Shared by `swipe`, `swipemeasure` and `sweep` so every gesture the agent makes
     * behaves the same way — a scroll that stops where it was told to is a property
     * of the gesture, not of one command.
     */
    private fun dispatchSwipeSegmented(
        service: AgentAccessibilityService,
        x1: Float, y1: Float, x2: Float, y2: Float,
        moveMs: Long,
        brakeMs: Long,
        trace: MutableList<FloatArray>? = null,
    ): Boolean {
        val movePath = GestureEngine.buildSwipePath(x1, y1, x2, y2, trace = trace)
        val moveStroke = GestureDescription.StrokeDescription(movePath, 0L, moveMs, brakeMs > 0L)

        val latch = java.util.concurrent.CountDownLatch(1)
        val state = booleanArrayOf(false)

        val moveGesture = GestureDescription.Builder().addStroke(moveStroke).build()
        val dispatched = service.dispatchGesture(
            moveGesture,
            object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (brakeMs <= 0L) {
                        state[0] = true
                        latch.countDown()
                        return
                    }
                    val brakePath = GestureEngine.buildBrakePath(x2, y2)
                    val brakeStroke = moveStroke.continueStroke(brakePath, 0L, brakeMs, false)
                    val brakeGesture = GestureDescription.Builder().addStroke(brakeStroke).build()
                    val ok = service.dispatchGesture(
                        brakeGesture,
                        object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(g: GestureDescription?) {
                                state[0] = true
                                latch.countDown()
                            }

                            override fun onCancelled(g: GestureDescription?) {
                                latch.countDown()
                            }
                        },
                        null,
                    )
                    if (!ok) latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )

        if (!dispatched) return false
        val finished = latch.await(
            moveMs + brakeMs + 6_000L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        return finished && state[0]
    }

    /**
     * Multi-point colour search.
     *
     * A single colour rarely identifies anything on a real screen, so this takes an
     * anchor colour plus offsets that must each match their own colour. That
     * combination is what lets a caller describe "this icon" rather than "somewhere
     * this shade appears".
     */
    private fun findMultiColor(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val anchor = ImageSearch.parseColor(req.getString("anchor"))
        val rawPoints = req.optJSONArray("points")
            ?: throw IllegalArgumentException("points must be [[dx,dy,\"#RRGGBB\"], ...]")
        if (rawPoints.length() == 0) throw IllegalArgumentException("points must not be empty")
        if (rawPoints.length() > 48) throw IllegalArgumentException("at most 48 points")

        val points = ArrayList<Triple<Int, Int, Int>>(rawPoints.length())
        for (i in 0 until rawPoints.length()) {
            val p = rawPoints.getJSONArray(i)
            if (p.length() < 3) throw IllegalArgumentException("point $i must be [dx,dy,color]")
            points.add(Triple(p.getInt(0), p.getInt(1), ImageSearch.parseColor(p.getString(2))))
        }

        val region = req.optJSONArray("region")?.let { arr ->
            if (arr.length() < 4) null
            else Rect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
        }
        val bitmap = service.capture() ?: throw IllegalStateException("screenshot failed")
        return try {
            ImageSearch.findMultiColor(
                bitmap, anchor, points,
                req.optInt("tolerance", 16),
                region,
                req.optInt("max", 20).coerceIn(1, 200),
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Capability self-check — one call, every permission that matters.
     *
     * Each item is tested rather than assumed: a permission can be declared, granted
     * and still not do its job (the accessibility service can be "enabled" while not
     * actually bound, `WRITE_SETTINGS` can be withheld, package visibility can be
     * filtered down to almost nothing). Reporting the declaration would be a
     * comforting lie; this reports what actually works.
     */
    private fun permissions(req: JSONObject): JSONObject {
        val items = JSONArray()
        val service = AgentAccessibilityService.instance
        val pm = context.packageManager

        fun add(name: String, key: String, ok: Boolean, detail: String, warn: Boolean = false) {
            items.put(
                JSONObject()
                    .put("name", name)
                    .put("key", key)
                    .put("ok", ok)
                    .put("warn", warn && ok)
                    .put("detail", detail),
            )
        }

        // Accessibility is the gate for most of the rest.
        val a11y = service != null
        add("无障碍服务", "accessibility", a11y, if (a11y) "已连接" else "未启用")

        // Package visibility: without QUERY_ALL_PACKAGES this quietly returns a
        // handful of packages instead of everything installed.
        val pkgCount = runCatching { pm.getInstalledPackages(0).size }.getOrDefault(0)
        add(
            "应用列表", "packages", pkgCount > 50,
            if (pkgCount > 50) "$pkgCount 个包" else "仅 $pkgCount 个(受限)",
            warn = pkgCount in 1..50,
        )

        val canWrite = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)
        add("修改系统设置", "write_settings", canWrite, if (canWrite) "可调亮度" else "亮度只读")

        val notif = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        add("通知", "notifications", notif, if (notif) "可显示" else "被拒绝")

        if (!a11y) {
            add("屏幕截图", "screenshot", false, "需无障碍")
            add("手势注入", "gesture", false, "需无障碍")
            add("读取界面", "uitree", false, "需无障碍")
            add("前台应用", "foreground", false, "需无障碍")
        } else {
            val shot = service?.capture() != null
            add("屏幕截图", "screenshot", shot, if (shot) "正常" else "失败")
            add("手势注入", "gesture", true, "dispatchGesture")
            val root = service?.rootNode()
            add("读取界面", "uitree", root != null, if (root != null) "正常" else "拿不到节点树")
            val self = context.packageName
            val fg = foregroundPackage(service)
            add("前台应用", "foreground", fg.isNotEmpty() || self.isNotEmpty(), fg.ifEmpty { "未知" })
        }

        val clip = runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .hasPrimaryClip()
        }.getOrDefault(false)
        add("剪贴板", "clipboard", true, if (clip) "有内容" else "可读写")

        add("前台服务", "foreground_service", true, "端口 $port")

        // The Chinese model is bundled in the APK, so its presence is a build fact
        // rather than a runtime permission. Reported for completeness.
        add("本地 OCR", "ocr", true, "中文模型已打包")

        val net = runCatching {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                .activeNetwork != null
        }.getOrDefault(false)
        add("网络访问", "network", net, if (net) "已联网" else "未联网")

        val awake = runCatching {
            context.getSystemService(Context.POWER_SERVICE)
                .let { (it as android.os.PowerManager).isInteractive }
        }.getOrDefault(false)
        add("屏幕状态", "screen", awake, if (awake) "亮屏" else "息屏")

        val okCount = (0 until items.length()).count { items.getJSONObject(it).optBoolean("ok") }
        return JSONObject()
            .put("count", items.length())
            .put("okCount", okCount)
            .put("items", items)
    }

    /**
     * The operator guide, served from inside the app.
     *
     * Bundled rather than fetched so it is always present and always matches the
     * build it shipped with. The build copies the repo's SKILL.md into assets, so
     * updating the guide is part of shipping a feature rather than a separate chore —
     * a guide that can drift from the binary is worse than none.
     */
    private fun skillDoc(req: JSONObject): JSONObject {
        val text = runCatching {
            context.assets.open("webui/SKILL.md").use { String(it.readBytes(), Charsets.UTF_8) }
        }.getOrNull()
        return if (text.isNullOrEmpty()) {
            JSONObject().put("text", "").put("source", "missing")
        } else {
            JSONObject().put("text", text).put("source", "assets").put("bytes", text.length)
        }
    }

    /**
     * Author ad-skip rules from the console.
     *
     * `list` returns the existing groups and the enabled packages so the console can
     * offer "add to an existing rule" or "create a new one".
     *
     * `add` appends a selector to a group, creating the group, app and manual
     * subscription as needed. Manual rules live in their own subscription so that
     * refreshing a downloaded one cannot delete them.
     */
    private fun adRule(req: JSONObject): JSONObject {
        val op = req.optString("op", "list")
        val subs = com.dsh.phoneagent.adskip.AdRuleStore.load(context)

        if (op == "list") {
            val groups = JSONArray()
            for (sub in subs) {
                for (app in sub.apps) {
                    for (g in app.groups) {
                        groups.put(
                            JSONObject()
                                .put("subscription", sub.name)
                                .put("subscriptionUrl", sub.url)
                                .put("package", app.id)
                                .put("appName", app.name)
                                .put("key", g.key)
                                .put("name", g.name)
                                .put("action", g.action)
                                .put("enabled", g.enabled)
                                .put("selectors", JSONArray(g.rules))
                                .put("hits", g.hitCount),
                        )
                    }
                }
            }
            val manual = subs.firstOrNull { it.url == com.dsh.phoneagent.adskip.AdRuleStore.MANUAL_URL }
            return JSONObject()
                .put("count", groups.length())
                .put("groups", groups)
                .put("manualCount", manual?.apps?.sumOf { it.groups.size } ?: 0)
        }

        if (op == "add") {
            val pkg = req.optString("package")
            if (pkg.isEmpty()) throw IllegalArgumentException("package is required")
            val selectors = req.optJSONArray("selectors")
                ?: throw IllegalArgumentException("selectors is required")
            if (selectors.length() == 0) throw IllegalArgumentException("selectors must not be empty")
            val selectorList = (0 until selectors.length()).map { selectors.getString(it) }

            val action = req.optString("action", "click")
            val appName = req.optString("appName").ifEmpty { pkg }
            val activityIds = req.optJSONArray("activityIds")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()

            // Validate before storing: an unusable selector would sit in the list
            // looking active while never matching anything.
            val unsupported = selectorList.filterNot {
                com.dsh.phoneagent.adskip.AdSelector.isSupported(it)
            }
            if (unsupported.size == selectorList.size) {
                throw IllegalArgumentException(
                    "选择器不受支持: " +
                        com.dsh.phoneagent.adskip.AdSelector.reasonFor(unsupported.first()),
                )
            }

            val bucket = com.dsh.phoneagent.adskip.AdRuleStore.manualBucket(context)
            val allSubs = com.dsh.phoneagent.adskip.AdRuleStore.load(context)

            val targetSub = allSubs.firstOrNull { it.url == com.dsh.phoneagent.adskip.AdRuleStore.MANUAL_URL }
                ?: bucket
            var app = targetSub.apps.firstOrNull { it.id == pkg }
            if (app == null) {
                app = com.dsh.phoneagent.adskip.AdApp(id = pkg, name = appName)
                targetSub.apps = targetSub.apps + app
            }

            val wantKey = req.optInt("groupKey", -1)
            var group = if (wantKey >= 0) app.groups.firstOrNull { it.key == wantKey } else null
            var created = false
            if (group == null) {
                val nextKey = (app.groups.maxOfOrNull { it.key } ?: -1) + 1
                group = com.dsh.phoneagent.adskip.AdGroup(
                    key = nextKey,
                    name = req.optString("ruleName").ifEmpty { "手动规则 ${nextKey + 1}" },
                    action = action,
                    rules = selectorList,
                    activityIds = activityIds,
                    // Manual rules are on by default: the user just pointed at the thing
                    // they want clicked, so making them opt in again would be strange.
                    enabled = true,
                    actionMaximum = req.optInt("actionMaximum", 0),
                    resetMatch = req.optString("resetMatch", "activity"),
                )
                app.groups = app.groups + group
                created = true
            } else {
                // Append to the existing rule, keeping order stable and skipping
                // duplicates so repeated clicks do not grow the list forever.
                val merged = LinkedHashSet(group.rules)
                merged.addAll(selectorList)
                group.rules = merged.toList()
                if (activityIds.isNotEmpty()) {
                    group.activityIds = (group.activityIds + activityIds).distinct()
                }
                group.enabled = true
            }

            targetSub.lastUpdated = System.currentTimeMillis()
            com.dsh.phoneagent.adskip.AdRuleStore.save(context, allSubs)

            // Reload the live engine so the rule takes effect without a restart.
            AgentAccessibilityService.instance?.adEngine?.let {
                it.reload()
                it.enabled = com.dsh.phoneagent.adskip.AdSkipSettings.isEnabled(context)
            }

            return JSONObject()
                .put("created", created)
                .put("package", pkg)
                .put("groupKey", group.key)
                .put("ruleName", group.name)
                .put("selectorCount", group.rules.size)
                .put("enabled", group.enabled)
        }

        if (op == "remove") {
            val pkg = req.optString("package")
            val key = req.optInt("groupKey", -1)
            val allSubs = com.dsh.phoneagent.adskip.AdRuleStore.load(context)
            var removed = false
            for (s in allSubs) {
                for (a in s.apps) {
                    if (a.id != pkg) continue
                    val before = a.groups.size
                    a.groups = a.groups.filterNot { it.key == key }
                    if (a.groups.size != before) removed = true
                }
            }
            com.dsh.phoneagent.adskip.AdRuleStore.save(context, allSubs)
            AgentAccessibilityService.instance?.adEngine?.reload()
            return JSONObject().put("removed", removed)
        }

        throw IllegalArgumentException("unknown op: $op")
    }

    /**
     * The bound accessibility service, or a clear error.
     *
     * Commands that need to touch the screen cannot run without it, and the failure
     * should say so rather than surfacing later as a null dereference.
     */
    private fun requireService(): AgentAccessibilityService =
        AgentAccessibilityService.instance
            ?: throw IllegalStateException("无障碍服务未连接,请先在 App 里打开")

    /**
     * Say how useful the accessibility tree actually is here.
     *
     * Custom-drawn UIs (Flutter, Compose Canvas, games, and the H5 pages inside many
     * shopping apps) expose a tree full of containers with no text at all. A caller
     * that assumes the tree describes the screen then gets `count: 0` from every
     * selector and concludes the element is missing, when in fact the tree simply
     * never described it.
     *
     * Reporting the ratio turns that dead end into a signpost: low text density means
     * switch to OCR, and the hint says so.
     */
    private fun annotateTreeQuality(tree: JSONObject): JSONObject {
        var withText = 0
        var visible = 0

        fun walk(node: JSONObject?) {
            if (node == null) return
            visible++
            val text = node.optString("text")
            val desc = node.optString("desc")
            if (text.isNotEmpty() || desc.isNotEmpty()) withText++
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) walk(children.optJSONObject(i))
        }
        walk(tree.optJSONObject("root"))

        val rate = if (visible > 0) withText.toDouble() / visible else 0.0
        tree.put("uiTreeTextRate", rate)
            .put("uiTreeVisibleNodes", visible)
            .put("uiTreeTextNodes", withText)

        // Thresholds chosen from what the two ends actually look like: a native screen
        // carries dozens of labelled nodes, a custom-drawn one can expose as few as a
        // dozen containers and nothing else. The count matters as much as the ratio —
        // a tiny tree with no text is the strongest signal there is, and requiring
        // "more than 20 nodes" skipped exactly that case.
        val sparse = withText < 3
        val lowRatio = visible >= 8 && rate < 0.15
        if (sparse || lowRatio) {
            tree.put(
                "hint",
                "控件树里只有 $withText/$visible 个节点带文本 —— " +
                    "这个 App 很可能是自绘界面(Flutter / Canvas / H5)," +
                    "选择器基本找不到东西。改用 ocr / findtext / sweep 这类基于画面的工具。",
            )
        }
        return tree
    }

    /**
     * Poll until something appears (or disappears).
     *
     * `sleep` is the alternative, and it is always wrong in both directions: too short
     * and the next action fires into a page that has not rendered, too long and every
     * step of a long run pays for the worst case. A polling wait costs one check in the
     * common case where the element is already there.
     *
     * Modes:
     *  - `text` (default): accessibility tree plus OCR, the same fusion `findtext` uses
     *  - `node`: tree only, for when OCR's latency is not worth paying
     *  - `gone`: inverted — wait for the target to disappear, which is how a loading
     *    state or a transient dialog is waited out
     *
     * A timeout is a normal outcome, not an error: the reply says `appeared: false` so
     * a caller can decide whether to retry, adapt, or give up.
     */
    private fun waitForTarget(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val target = req.getString("target")
        val mode = req.optString("mode", "text").lowercase()
        val timeoutMs = req.optLong("timeoutMs", 8_000L).coerceIn(200L, 120_000L)
        // 400-600ms is the sweet spot: faster burns battery re-running OCR, slower
        // starts to add its own latency to every step.
        val intervalMs = req.optLong("intervalMs", 500L).coerceIn(150L, 5_000L)
        val started = System.currentTimeMillis()

        while (true) {
            val elapsed = System.currentTimeMillis() - started
            val hit = runCatching { probeTarget(service, target, mode) }.getOrNull()

            val satisfied = if (mode == "gone") hit == null else hit != null
            if (satisfied) {
                return JSONObject()
                    .put("appeared", mode != "gone")
                    .put("gone", mode == "gone")
                    .put("elapsedMs", elapsed)
                    .put("target", target)
                    .put("mode", mode)
                    .apply { hit?.let { put("bounds", it.opt("bounds")); put("center", it.opt("center")) } }
            }

            if (elapsed >= timeoutMs) {
                return JSONObject()
                    .put("appeared", false)
                    .put("gone", false)
                    .put("timedOut", true)
                    .put("elapsedMs", elapsed)
                    .put("target", target)
                    .put("mode", mode)
                    .put("hint", if (mode == "gone") {
                        "等待「$target」消失超时 —— 它仍然在屏幕上"
                    } else {
                        "等待「$target」出现超时 —— 可以加大 timeoutMs,或先截图确认页面上有什么"
                    })
            }

            Thread.sleep(minOf(intervalMs, (timeoutMs - elapsed).coerceAtLeast(50L)))
        }
    }

    /** One probe for [waitForTarget]; returns the geometry when the target is present. */
    private fun probeTarget(
        service: AgentAccessibilityService,
        target: String,
        mode: String,
    ): JSONObject? {
        if (mode == "node") {
            val root = service.rootInActiveWindow ?: return null
            val node = findNodeByText(root, target, 0) ?: return null
            val rect = Rect().also { node.getBoundsInScreen(it) }
            return JSONObject()
                .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                .put("center", JSONArray(listOf(rect.centerX(), rect.centerY())))
        }

        // `text` and `gone` both need the fused view: a target that only OCR can see
        // must still count as present, or a wait would time out on exactly the apps
        // (custom-drawn lists) where it is most useful.
        val fused = findText(
            service,
            JSONObject()
                .put("text", target)
                .put("mode", "contains")
                .put("source", "auto")
                .put("tappable", true),
        )
        if (fused.optInt("count") <= 0) return null
        val first = fused.getJSONArray("matches").getJSONObject(0)
        val center = first.optJSONObject("tapTarget")?.optJSONArray("center")
            ?: first.optJSONArray("center")
        return JSONObject()
            .put("bounds", first.opt("bounds"))
            .put("center", center)
    }

    private fun findNodeByText(
        node: AccessibilityNodeInfo,
        target: String,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > 60) return null
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if ((text != null && text.contains(target)) || (desc != null && desc.contains(target))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByText(child, target, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * Locate something by any available means and say which one worked.
     *
     * The caller supplies a word, not a mechanism. Trying the strategies in order and
     * reporting the winner means the same call keeps working as an app changes from
     * resource ids to Compose to a drawn canvas, instead of failing when the caller's
     * guessed mechanism stops applying.
     */
    private fun locate(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val target = req.getString("target")
        val strategies = req.optJSONArray("strategies")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: Locator.STRATEGIES
        val region = req.optJSONArray("region")?.let { arr ->
            if (arr.length() < 4) null
            else Rect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
        }
        val tolerance = req.optInt("tolerance", 16)

        return Locator.locate(service, target, strategies, region) { strategy ->
            when (strategy) {
                "ocr" -> runCatching {
                    val r = findText(service, JSONObject()
                        .put("text", target)
                        .put("mode", "contains")
                        .put("source", "ocr")
                        .put("tappable", true))
                    if (r.optInt("count") <= 0) null else {
                        val m = r.getJSONArray("matches").getJSONObject(0)
                        val c = m.optJSONObject("tapTarget")?.optJSONArray("center")
                            ?: m.optJSONArray("center")
                        JSONObject()
                            .put("ok", true)
                            .put("node", JSONObject().put("text", m.optString("text")))
                            .put("center", c)
                            .put("bounds", m.optJSONArray("bounds"))
                    }
                }.getOrNull()

                "color" -> runCatching {
                    if (!target.startsWith("#")) null else {
                        val r = findColor(service, JSONObject()
                            .put("color", target)
                            .put("tolerance", tolerance)
                            .put("max", 5))
                        val clusters = r.optJSONArray("clusters")
                        if (clusters == null || clusters.length() == 0) null else {
                            val first = clusters.getJSONObject(0)
                            JSONObject()
                                .put("ok", true)
                                .put("node", JSONObject().put("pixels", first.optInt("pixels")))
                                .put("center", first.optJSONArray("center"))
                                .put("bounds", first.optJSONArray("bounds"))
                        }
                    }
                }.getOrNull()

                else -> null
            }
        }
    }

    /**
     * Execute a list of actions without a round trip between them.
     *
     * Some UI is only briefly real: a toast, a control bar that fades after a second,
     * a dialog that auto-dismisses. Sending one action per network turn loses that
     * race even when every individual step is fast. A sequence runs the steps
     * back to back on the phone, so the gap is microseconds instead of a round trip.
     *
     * Steps are ordinary commands, dispatched through the same path as a top-level
     * call — a sequence is a batching mechanism, not a second command language.
     */
    private fun sequence(service: AgentAccessibilityService, req: JSONObject): JSONObject {
        val steps = req.optJSONArray("steps")
            ?: throw IllegalArgumentException("steps must be an array")
        if (steps.length() == 0) throw IllegalArgumentException("steps must not be empty")
        if (steps.length() > 50) throw IllegalArgumentException("at most 50 steps")
        val stopOnError = req.optBoolean("stopOnError", true)

        val results = JSONArray()
        var executed = 0
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val action = step.optString("action")

            if (action == "wait") {
                val ms = step.optLong("ms", 300L).coerceIn(0L, 30_000L)
                runCatching { Thread.sleep(ms) }
                results.put(JSONObject().put("index", i).put("action", "wait").put("ok", true).put("ms", ms))
                executed++
                continue
            }
            if (action.isEmpty()) {
                results.put(JSONObject().put("index", i).put("ok", false).put("error", "action 为空"))
                if (stopOnError) break
                continue
            }

            val sub = JSONObject(step.toString())
            sub.put("id", "seq-$i")
            // Steps name their command `action`; the dispatcher reads `cmd`. Without
            // this the sub-command arrives with an empty cmd and every step fails with
            // no message — the failure looks like the command was never run.
            sub.put("cmd", action)

            // Call execute() directly rather than re-entering process(). The outer
            // call already holds opLock, and process() would increment inFlight once
            // per step — so a five-step sequence would consume the whole concurrency
            // budget by itself and start rejecting unrelated callers as "busy".
            val reply = runCatching {
                JSONObject().put("ok", true).put("data", execute(sub))
            }.getOrElse { e ->
                JSONObject().put("ok", false).put("error", e.message ?: e.toString())
            }
            executed++
            results.put(
                JSONObject()
                    .put("index", i)
                    .put("action", action)
                    .put("ok", reply.optBoolean("ok"))
                    .put("error", if (reply.optBoolean("ok")) JSONObject.NULL else reply.opt("error"))
                    .put("data", if (reply.optBoolean("ok")) reply.opt("data") else JSONObject.NULL),
            )
            if (!reply.optBoolean("ok") && stopOnError) break
        }

        val okCount = (0 until results.length())
            .count { results.getJSONObject(it).optBoolean("ok") }
        return JSONObject()
            .put("completed", okCount == results.length() && results.length() == steps.length())
            .put("executed", executed)
            .put("total", steps.length())
            .put("okCount", okCount)
            .put("stopOnError", stopOnError)
            .put("results", results)
    }

    private fun encodeImage(bitmap: Bitmap, format: String, quality: Int): Pair<String, String> {
        val isPng = format.equals("png", true)
        val compressFormat = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val buffer = ByteArrayOutputStream()
        bitmap.compress(compressFormat, quality.coerceIn(1, 100), buffer)
        val encoded = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
        return encoded to (if (isPng) "png" else "jpeg")
    }

    companion object {
        private const val TAG = "DSHPhoneAgent"
        private const val MAX_RECENT = 8

        /**
         * Braking pause before lift-off, in milliseconds.
         *
         * The framework decides between drag and fling from the velocity at lift-off,
         * so the gesture needs a stretch of near-zero motion at the end. Randomised
         * within a band rather than fixed, because a perfectly constant pause is a
         * pattern, and this one would otherwise appear in every single swipe.
         */
        private const val BRAKE_MIN_MS = 250
        private const val BRAKE_SPREAD_MS = 100

        /** Requests that may be in flight before new ones are refused outright. */
        private const val MAX_IN_FLIGHT = 4

        /**
         * Minimum gap between tap-like gestures.
         *
         * Roughly the fastest a finger can tap twice; also what keeps a burst from
         * turning into a visible hang.
         */
        private const val DEFAULT_TAP_GAP_MS = 110L
    }
}
