package com.dsh.phoneagent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves the debug console from the phone itself.
 *
 * The control port speaks line-delimited JSON over raw TCP, which a browser cannot
 * use at all — it sends HTTP and expects HTTP back. So this is a second, separate
 * listener that speaks HTTP and forwards commands to the control port over the
 * loopback interface.
 *
 * Forwarding over loopback rather than calling the command handlers directly keeps
 * one implementation of every command: the console sees exactly the same behaviour,
 * validation and error codes as any other client.
 */
class WebConsole(
    private val context: Context,
    private val control: ControlServer,
    private val port: Int = DEFAULT_PORT,
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
                serverSocket = socket
                Log.i(TAG, "console on http://<phone-ip>:$port")
                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (t: Throwable) {
                        if (running.get()) Log.w(TAG, "accept failed: ${t.message}")
                        break
                    }
                    pool.execute { serve(client) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "console failed to start: ${t.message}")
            } finally {
                runCatching { serverSocket?.close() }
                serverSocket = null
                running.set(false)
            }
        }, "web-console").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 120_000
            try {
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val out = BufferedOutputStream(s.getOutputStream())

                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return writeText(out, 400, "bad request")
                val method = parts[0]
                val rawPath = parts[1]

                // Read and discard headers, capturing Content-Length so the body can
                // be consumed — skipping this leaves the body unread and corrupts the
                // next read on a keep-alive connection.
                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else {
                    ""
                }

                val path = rawPath.substringBefore('?')
                when {
                    method == "POST" && path == "/rpc" -> handleRpc(out, body)
                    method == "GET" && path == "/shot" -> handleShot(out, rawPath)
                    method == "GET" && path == "/stream" -> handleStream(out, rawPath)
                    path.startsWith("/mcp") -> handleMcp(out, path, rawPath)
                    else -> serveAsset(out, path)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "request failed: ${t.message}")
            }
        }
    }

    /**
     * Run a command through the control server's own entry point.
     *
     * Not a loopback TCP round trip: that cost ~200ms per request (measured 8ms
     * direct vs 217ms via the socket) purely in connection setup and teardown, and
     * bought nothing. Calling in-process still means one implementation of every
     * command — same validation, same error codes.
     */
    private fun handleRpc(out: BufferedOutputStream, body: String) {
        if (body.isBlank()) return writeJson(out, 400, """{"ok":false,"error":"empty body"}""")
        val reply = try {
            control.handle(body.trim()).toString()
        } catch (t: Throwable) {
            null
        }
        if (reply == null) {
            writeJson(out, 502, """{"ok":false,"error":"command failed"}""")
        } else {
            writeJson(out, 200, reply)
        }
    }

    /**
     * Screenshot as raw JPEG bytes rather than base64 inside JSON: a third fewer
     * bytes and no decode step in the browser.
     */
    private fun handleShot(out: BufferedOutputStream, rawPath: String) {
        val query = rawPath.substringAfter('?', "")
        val params = query.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val scale = params["scale"]?.toDoubleOrNull() ?: 0.5
        val quality = params["quality"]?.toIntOrNull() ?: 85

        val command = buildString {
            append("""{"id":9001,"cmd":"screenshot","scale":$scale,"quality":$quality,"format":"jpeg"""")
            params["region"]?.let { append(""","region":[$it]""") }
            params["token"]?.let { append(""","token":"$it"""") }
            append("}")
        }

        val json = runCatching { control.handle(command) }.getOrNull()
        if (json == null || !json.optBoolean("ok")) {
            return writeText(out, 502, json?.optString("error") ?: "screenshot failed")
        }
        val data = json.optJSONObject("data") ?: return writeText(out, 502, "no data")
        val bytes = runCatching {
            android.util.Base64.decode(data.optString("image"), android.util.Base64.DEFAULT)
        }.getOrNull() ?: return writeText(out, 502, "bad image data")

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            // Lets the page learn the phone geometry without a second request.
            append("X-Phone-Width: ${data.optInt("screenWidth")}\r\n")
            append("X-Phone-Height: ${data.optInt("screenHeight")}\r\n")
            append("X-Image-Width: ${data.optInt("imageWidth")}\r\n")
            append("X-Image-Height: ${data.optInt("imageHeight")}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    /**
     * Serve a page asset, preferring a copy on the device's own storage.
     *
     * Editing the console otherwise means rebuilding and reinstalling the APK, which
     * is a slow loop for a file that is pure front-end. Dropping an updated
     * `index.html` into the app's external files directory takes effect on the next
     * refresh — no rebuild, no reinstall, no adb. The bundled copy stays as the
     * fallback so a fresh install works with nothing extra.
     */
    /**
     * Live screen stream, MJPEG over a single long-lived response.
     *
     * The request/response screenshot endpoint rebuilds a connection, captures,
     * encodes to JPEG, base64s it into JSON, and tears down — 400-600ms per frame, so
     * about 0.7 fps. That is fine for "look at the screen", useless for watching it.
     *
     * MJPEG removes everything except the frame itself: one connection stays open and
     * the phone writes a boundary, a length and raw JPEG bytes in a loop. Browsers
     * render `multipart/x-mixed-replace` natively in an `<img>`, so the client needs
     * no decoding, no canvas and no WebSocket — the browser does the work.
     *
     * The frame rate has a hard ceiling that is worth stating plainly: the platform
     * throttles `takeScreenshot`, and this device rejects calls closer than ~180ms
     * apart, so ~5 fps is the maximum through the accessibility channel. Tools that
     * reach 60 fps use MediaProjection with hardware video encoding instead, which
     * costs a user consent dialog on every session.
     */
    /**
     * Reused JPEG output buffer.
     *
     * A fresh ByteArrayOutputStream per frame is small compared to the bitmap, but it
     * still grows to ~96 KB of backing array and is discarded every time; keeping one
     * and resetting it removes the allocation entirely.
     */
    private val jpegBuffer = java.io.ByteArrayOutputStream(96 * 1024)

    /**
     * Encode a frame straight to JPEG bytes, skipping the JSON + base64 hop.
     *
     * The command path is text: a Bitmap becomes JPEG, the JPEG becomes base64, the
     * base64 goes into a JSON string, and the stream then decodes it back to bytes.
     * That costs a third more bytes on the wire (39KB carrying 29KB) and two base64
     * passes per frame, both of them pure overhead for a channel that never needs a
     * text form.
     *
     * The frame is also scaled inside the capture, from the hardware bitmap, so the
     * full-size software copy (10.4 MB) never exists. Only the scaled result is
     * allocated, and the output buffer is reused.
     *
     * Only the stream takes these shortcuts. Everything else keeps going through
     * `control.handle`, so command behaviour stays in one place — the trade is worth
     * it here precisely because this is the one path that runs continuously.
     */
    private fun captureJpegBytes(scale: Double, quality: Int): ByteArray? {
        val service = AgentAccessibilityService.instance ?: return null
        val full = service.capture() ?: return null
        var scaled: android.graphics.Bitmap? = null
        return try {
            val frame = if (scale < 0.999) {
                val w = (full.width * scale).toInt().coerceAtLeast(1)
                val h = (full.height * scale).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createScaledBitmap(full, w, h, true).also { scaled = it }
            } else {
                full
            }
            synchronized(jpegBuffer) {
                jpegBuffer.reset()
                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, jpegBuffer)
                jpegBuffer.toByteArray()
            }
        } catch (t: Throwable) {
            null
        } finally {
            scaled?.recycle()
            full.recycle()
        }
    }

    private fun handleStream(out: BufferedOutputStream, rawPath: String) {
        val params = queryParams(rawPath)
        val scale = params["scale"]?.toDoubleOrNull() ?: 0.35
        val quality = params["quality"]?.toIntOrNull() ?: 70
        val fps = (params["fps"]?.toIntOrNull() ?: 5).coerceIn(1, 10)
        val gapMs = (1000L / fps) - 10L

        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                    "Cache-Control: no-store, no-cache\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.UTF_8),
        )
        out.flush()

        var frames = 0
        while (running.get()) {
            val bytes = captureJpegBytes(scale, quality)
            if (bytes == null) {
                // A transient capture failure is not worth tearing the stream down for;
                // the retry inside capture() has usually already handled it.
                runCatching { Thread.sleep(120) }
                continue
            }

            val header = buildString {
                append("--").append(BOUNDARY).append("\r\n")
                append("Content-Type: image/jpeg\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n\r\n")
            }
            try {
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.write("\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
                frames++
            } catch (t: Throwable) {
                // The client navigated away or closed the tab. Nothing to report.
                Log.i(TAG, "stream closed after $frames frames: ${t.message}")
                return
            }

            if (gapMs > 0) runCatching { Thread.sleep(gapMs) }
        }
    }

    private fun queryParams(rawPath: String): Map<String, String> =
        rawPath.substringAfter('?', "")
            .split('&')
            .mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
            }
            .toMap()

    /**
     * MCP distribution endpoints.
     *
     * The point is that a machine on the same LAN needs nothing but a browser: open
     * this address, copy one command, and it is driving the phone. That convenience is
     * exactly why it is behind a switch — a downloadable, pre-configured bridge lowers
     * the bar for anyone on the network, not just its owner.
     *
     * Closed, every path here answers 403 with the reason, so a client that guessed
     * the URL learns why rather than seeing a 404 that suggests it guessed wrong.
     */
    private fun handleMcp(out: BufferedOutputStream, path: String, rawPath: String) {
        if (!McpSettings.isEnabled(context)) {
            writeJson(
                out, 403,
                JSONObject()
                    .put("ok", false)
                    .put("error", "MCP 未启用")
                    .put("hint", "请在手机的 DSH Phone Agent 里打开「MCP 服务」开关"),
            )
            return
        }

        val host = localAddress()
        when (path) {
            "/mcp", "/mcp/" -> writeHtml(out, mcpHelpPage(host))
            "/mcp/config.json" -> writeJson(out, 200, mcpConfig(host))
            else -> {
                val name = path.removePrefix("/mcp/")
                val assetName = when (name) {
                    "server.mjs" -> "mcp/index.mjs"
                    "test.mjs" -> "mcp/test-mcp.mjs"
                    "README.md" -> "mcp/README.md"
                    else -> null
                }
                if (assetName == null) {
                    writeText(out, 404, "not found: $path")
                    return
                }
                val bytes = runCatching {
                    context.assets.open(assetName).use { it.readBytes() }
                }.getOrNull()
                if (bytes == null) {
                    writeText(out, 404, "asset missing: $assetName")
                    return
                }
                val type = if (name.endsWith(".mjs")) {
                    "text/javascript; charset=utf-8"
                } else {
                    "text/markdown; charset=utf-8"
                }
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ").append(type).append("\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Content-Disposition: attachment; filename=\"").append(name).append("\"\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        }
    }

    /** The address a client on this LAN should use. */
    private fun localAddress(): String {
        val port = WebConsole.DEFAULT_PORT
        val ip = DeviceStatus.localIpAddress()
        return "$ip:$port"
    }

    /** A ready-to-paste MCP client configuration. */
    private fun mcpConfig(host: String): JSONObject {
        val hostOnly = host.substringBefore(":")
        return JSONObject()
            .put("ok", true)
            .put(
                "claude",
                JSONObject().put(
                    "mcpServers",
                    JSONObject().put(
                        "phone",
                        JSONObject()
                            .put("command", "node")
                            .put("args", JSONArray(listOf("index.mjs", "--host", hostOnly))),
                    ),
                ),
            )
            .put(
                "codex",
                "[mcp_servers.phone]\ncommand = \"node\"\n" +
                    "args = [\"index.mjs\", \"--host\", \"$hostOnly\"]",
            )
            .put("controlPort", hostOnly)
            .put("console", "http://$host/")
            .put("download", "http://$host/mcp/server.mjs")
            .put("testScript", "http://$host/mcp/test.mjs")
    }

    /** Self-contained help page: no external assets, works offline. */
    private fun mcpHelpPage(host: String): String {
        val hostOnly = host.substringBefore(":")
        return """
<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH Phone Agent · MCP</title>
<style>
  body{margin:0;padding:24px;background:#0f1115;color:#e6e9ef;
       font:14px/1.7 "Segoe UI","Microsoft YaHei UI",system-ui,sans-serif}
  .wrap{max-width:820px;margin:0 auto}
  h1{font-size:22px;margin:0 0 4px}
  h2{font-size:15px;margin:28px 0 10px;color:#7dd3fc}
  .sub{color:#8b93a1;font-size:13px;margin-bottom:22px}
  pre{background:#181b21;border:1px solid #2b3038;border-radius:8px;
      padding:12px;overflow:auto;font:12px/1.6 ui-monospace,Consolas,monospace}
  code{font-family:ui-monospace,Consolas,monospace;color:#7dd3fc}
  a{color:#3b82f6}
  .card{background:#181b21;border:1px solid #2b3038;border-radius:10px;
        padding:16px;margin:12px 0}
  .pill{display:inline-block;background:#EAF0FF;color:#2563EB;border-radius:8px;
        padding:6px 12px;font-weight:600;font-size:13px;text-decoration:none;
        margin:4px 8px 4px 0}
  ol{padding-left:20px} li{margin:6px 0}
  .muted{color:#8b93a1;font-size:12.5px}
</style></head><body><div class="wrap">

<h1>MCP 接入</h1>
<div class="sub">让 Claude Code / Cursor / Codex / Windsurf 驱动这台手机</div>

<div class="card">
  <b>手机上 MCP 已开启</b> · 控制地址 <code>$hostOnly:7912</code>
  <div style="margin-top:10px">
    <a class="pill" href="/mcp/server.mjs">下载 server.mjs</a>
    <a class="pill" href="/mcp/test.mjs">下载 test.mjs</a>
    <a class="pill" href="/mcp/README.md">下载说明</a>
    <a class="pill" href="/mcp/config.json">配置 JSON</a>
  </div>
</div>

<h2>三步接入</h2>
<ol>
  <li><b>下载并解压</b> server.mjs 到任意目录,例如 <code>D:\phone-mcp\</code></li>
  <li><b>把配置加进 IDE</b>(下面已按当前地址填好)</li>
  <li><b>用自然语言下指令</b></li>
</ol>

<h2>Claude Code / Cursor / Windsurf</h2>
<pre>{
  "mcpServers": {
    "phone": {
      "command": "node",
      "args": ["D:/phone-mcp/server.mjs", "--host", "$hostOnly"]
    }
  }
}</pre>

<h2>Codex</h2>
<pre>[mcp_servers.phone]
command = "node"
args = ["D:/phone-mcp/server.mjs", "--host", "$hostOnly"]</pre>

<h2>先验证再接入</h2>
<pre>node server.mjs --host $hostOnly --selftest</pre>
<div class="muted">能打印出设备型号和权限自检,说明链路是通的。</div>

<h2>可以说什么</h2>
<pre>看一下手机上现在是什么页面
打开设置,找到电池,告诉我当前电量
把那个「跳过」按钮点掉
把这个列表从头到尾读一遍</pre>

<h2>18 个工具</h2>
<div class="muted">
  phone_status · phone_screenshot · phone_uitree · phone_locate · phone_tap ·
  phone_swipe · phone_swipe_measure · phone_text · phone_key · phone_find_text ·
  phone_ocr · phone_sweep · phone_sequence · phone_incidents · phone_launch ·
  phone_apps · phone_find_image · phone_raw
</div>

<div class="card" style="margin-top:24px">
  <b>关于这个开关</b>
  <div class="muted" style="margin-top:6px">
    关闭时本页与所有下载都会返回 403。控制台和已有脚本不受影响 ——
    被关掉的是「把配置好的桥接直接交给局域网里任何一台机器」这件事。
  </div>
</div>

</div></body></html>
""".trimIndent()
    }

    private fun writeHtml(out: BufferedOutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun writeJson(out: BufferedOutputStream, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Forbidden"
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun serveAsset(out: BufferedOutputStream, path: String) {
        val name = when (path) {
            "/", "/index.html" -> "webui/index.html"
            else -> "webui/" + path.trimStart('/')
        }
        val bundledName = name.substringAfter("webui/")
        val override = java.io.File(
            java.io.File(context.getExternalFilesDir(null), "webui"),
            bundledName,
        )

        val bytes = if (override.isFile) {
            runCatching { override.readBytes() }.getOrNull()
        } else {
            null
        } ?: runCatching { context.assets.open(name).use { it.readBytes() } }.getOrNull()
            ?: return writeText(out, 404, "not found")

        val type = when {
            name.endsWith(".html") -> "text/html; charset=utf-8"
            name.endsWith(".js") -> "text/javascript; charset=utf-8"
            name.endsWith(".css") -> "text/css; charset=utf-8"
            else -> "application/octet-stream"
        }
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
            "X-Source: ${if (override.isFile) "device" else "bundled"}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun writeJson(out: BufferedOutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun writeText(out: BufferedOutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status Error\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val TAG = "DSHWebConsole"

        /** 7913: 7912 is taken by the raw control protocol, which is not HTTP. */
        const val DEFAULT_PORT = 7913

        /** Multipart separator for the MJPEG stream. */
        private const val BOUNDARY = "dshphoneframe"
    }
}
