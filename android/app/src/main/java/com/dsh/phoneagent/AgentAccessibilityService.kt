package com.dsh.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dsh.phoneagent.adskip.AdSkipSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The only component that may inject input and read the screen.
 *
 * Everything the control server does goes through this service, so the whole
 * capability set stays inside the accessibility grant the device owner made.
 */
class AgentAccessibilityService : AccessibilityService() {

    /**
     * Guards [capture]: `takeScreenshot` refuses a request that overlaps another, and
     * on this device also refuses one that lands too soon after the previous.
     */
    private val captureLock = Any()
    private var lastCaptureAt = 0L

    /** Ad-skip rule evaluation. Created eagerly; it does nothing until enabled. */
    val adEngine by lazy { com.dsh.phoneagent.adskip.AdRuleEngine(applicationContext) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        adEngine.reload()
        adEngine.enabled = AdSkipSettings.isEnabled(this)
    }

    /**
     * Event hook for the ad-skip engine.
     *
     * Kept to a single delegation: everything expensive (package filtering, debounce,
     * tree traversal) lives in the engine, so an event that cannot match costs one
     * map lookup and returns.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        runCatching { adEngine.onEvent(event, this) }
    }

    /** Package of the window in the foreground, or empty when unknown. */
    fun foregroundPackageName(): String = runCatching {
        rootInActiveWindow?.packageName?.toString().orEmpty()
    }.getOrDefault("")

    /** Fully-qualified activity name of the current window, or empty. */
    fun currentActivityName(): String {
        val pkg = foregroundPackageName()
        if (pkg.isEmpty()) return ""
        val events = windows
        for (w in events) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != pkg) continue
            // AccessibilityWindowInfo exposes no activity name directly; the class
            // name of the window's root view is the closest usable signal, and rules
            // match on suffixes, which this satisfies in most cases.
            return root.className?.toString().orEmpty()
        }
        return ""
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun rootNode(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (t: Throwable) {
        null
    }

    /**
     * Dispatch a gesture and wait for the framework's verdict.
     *
     * A returned `false` means the gesture never completed — either the service
     * refused it, or the stroke was cancelled (typically because the screen
     * turned off or another gesture took over).
     */
    fun dispatch(gesture: GestureDescription, timeoutMs: Long = 10_000L): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed = false
                latch.countDown()
            }
        }
        val accepted = try {
            dispatchGesture(gesture, callback, null)
        } catch (t: Throwable) {
            false
        }
        if (!accepted) return false
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return completed
    }

    /**
     * Screenshot through the accessibility channel — no MediaProjection consent dialog.
     *
     * Serialised and retried. `takeScreenshot` rejects a request that arrives while
     * another is in flight, and on this device it also refuses calls that follow too
     * closely behind the previous one. Back-to-back calls therefore produced a mix of
     * successes and `screenshot failed` errors — measured: one call fine, four in a
     * row gave three failures.
     *
     * Guarding here rather than at each of the ten call sites means every command gets
     * the behaviour for free: retries, a minimum spacing, and no two captures racing.
     */
    /**
     * Real display size, including the navigation bar.
     *
     * `resources.displayMetrics` reports the app's own window, which on this device
     * is 2261 tall against a 2400-pixel screen — using it would scale every frame to
     * the wrong aspect and put every screenshot coordinate off by 139 pixels.
     */
    fun screenSize(): IntArray {
        val dm = android.util.DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as? android.view.WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(dm)
        return intArrayOf(
            if (dm.widthPixels > 0) dm.widthPixels else 1080,
            if (dm.heightPixels > 0) dm.heightPixels else 2400,
        )
    }

    fun capture(): Bitmap? = synchronized(captureLock) {
        val since = System.currentTimeMillis() - lastCaptureAt
        if (since < MIN_CAPTURE_GAP_MS) {
            runCatching { Thread.sleep(MIN_CAPTURE_GAP_MS - since) }
        }
        var attempt = 0
        while (attempt < CAPTURE_ATTEMPTS) {
            val shot = captureOnce()
            if (shot != null) {
                lastCaptureAt = System.currentTimeMillis()
                return@synchronized shot
            }
            attempt++
            if (attempt < CAPTURE_ATTEMPTS) {
                runCatching { Thread.sleep(120L * attempt) }
            }
        }
        null
    }

    private fun captureOnce(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        val executor = Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val buffer = screenshot.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            result = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            buffer.close()
                        } catch (t: Throwable) {
                            result = null
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                },
            )
        } catch (t: Throwable) {
            executor.shutdown()
            return null
        }
        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        return result
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null

        /** Minimum spacing between captures; the platform throttles them. */
        private const val MIN_CAPTURE_GAP_MS = 180L

        /** Attempts per capture call before giving up. */
        private const val CAPTURE_ATTEMPTS = 4
    }
}
