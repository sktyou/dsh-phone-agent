package com.dsh.phoneagent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.app.KeyguardManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import java.io.File

/**
 * Read-only device facts: what the phone is, and what state it is in.
 *
 * Everything here is deliberately permission-light. The only values that would
 * need a runtime grant (SSID, for instance, which requires location) are omitted
 * rather than silently reporting a wrong answer — a caller that gets `null` knows
 * to stop, whereas a caller that gets a plausible-but-wrong value does not.
 */
object DeviceStatus {

    fun snapshot(context: Context, serviceInfo: JSONObject): JSONObject {
        val out = JSONObject()

        // ---- identity ----
        out.put("model", Build.MODEL)
        out.put("brand", Build.BRAND)
        out.put("manufacturer", Build.MANUFACTURER)
        out.put("device", Build.DEVICE)
        out.put("product", Build.PRODUCT)
        out.put("android", Build.VERSION.RELEASE)
        out.put("sdk", Build.VERSION.SDK_INT)
        out.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")

        // ---- screen ----
        out.put("screen", screen(context))

        // ---- power ----
        out.put("battery", battery(context))
        out.put("screenOn", isScreenOn(context))
        out.put("locked", isLocked(context))

        // ---- audio ----
        out.put("volume", volume(context))
        out.put("brightness", brightness(context))

        // ---- network / storage ----
        out.put("network", network(context))
        out.put("storage", storage())
        out.put("memory", memory(context))

        // ---- the agent itself ----
        out.put("service", serviceInfo)
        out.put("accessibility", AgentAccessibilityService.isConnected)
        return out
    }

    private fun screen(context: Context): JSONObject {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        return JSONObject()
            .put("width", metrics.widthPixels)
            .put("height", metrics.heightPixels)
            .put("density", metrics.density.toDouble())
            .put("densityDpi", metrics.densityDpi)
            .put("rotation", rotation(context))
    }

    private fun rotation(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return when (wm.defaultDisplay.rotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * Battery facts come from the sticky ACTION_BATTERY_CHANGED broadcast, which is
     * the documented way to read them without registering a long-lived receiver.
     */
    private fun battery(context: Context): JSONObject {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return JSONObject()
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return JSONObject()
            .put("percent", percent)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL)
            .put("plugged", plugged(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)))
            .put("temperatureC", if (temperature > 0) temperature / 10.0 else JSONObject.NULL)
            .put("health", health(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)))
    }

    private fun plugged(value: Int): String = when (value) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        else -> "none"
    }

    private fun health(value: Int): String = when (value) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }

    fun isScreenOn(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isInteractive
    }

    fun isLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguard.isKeyguardLocked
    }

    private fun volume(context: Context): JSONObject {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun entry(stream: Int) = JSONObject()
            .put("current", audio.getStreamVolume(stream))
            .put("max", audio.getStreamMaxVolume(stream))
        return JSONObject()
            .put("music", entry(AudioManager.STREAM_MUSIC))
            .put("ring", entry(AudioManager.STREAM_RING))
            .put("alarm", entry(AudioManager.STREAM_ALARM))
            .put("notification", entry(AudioManager.STREAM_NOTIFICATION))
            .put("mode", when (audio.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
    }

    /**
     * Screen brightness.
     *
     * 0-255 is only the AOSP default. OEM builds override
     * `config_screenBrightnessSettingMaximum`, and MIUI uses a far wider scale —
     * hard-coding 255 made a raw level of 406 report as "159%". The real ceiling is
     * read from framework resources, both here and when writing, so a percentage
     * means the same thing on the way in and on the way out.
     */
    private fun brightness(context: Context): JSONObject {
        val auto = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrDefault(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val level = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        val max = maxBrightness(context)
        return JSONObject()
            .put("auto", auto == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            .put("level", level)
            .put("max", max)
            .put("percent", if (level >= 0) (level * 100 / max).coerceIn(0, 100) else -1)
            // Changing brightness needs the special WRITE_SETTINGS grant.
            .put("writable", Settings.System.canWrite(context))
    }

    /** The device's real brightness ceiling, falling back to the AOSP 255. */
    fun maxBrightness(context: Context): Int {
        val id = context.resources.getIdentifier(
            "config_screenBrightnessSettingMaximum",
            "integer",
            "android",
        )
        val value = if (id > 0) {
            runCatching { context.resources.getInteger(id) }.getOrDefault(255)
        } else {
            255
        }
        return if (value > 0) value else 255
    }

    private fun network(context: Context): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        return JSONObject()
            .put("type", transport)
            .put("online", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            .put("metered", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false)
    }

    private fun storage(): JSONObject {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return JSONObject()
            .put("totalBytes", total)
            .put("freeBytes", free)
            .put("usedPercent", if (total > 0) ((total - free) * 100 / total).toInt() else -1)
    }

    private fun memory(context: Context): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return JSONObject()
            .put("totalBytes", info.totalMem)
            .put("availableBytes", info.availMem)
            .put("lowMemory", info.lowMemory)
            .put("usedPercent", if (info.totalMem > 0) {
                ((info.totalMem - info.availMem) * 100 / info.totalMem).toInt()
            } else {
                -1
            })
    }

    /** Rough free-space check used before writing a screenshot to disk. */
    fun freeBytes(): Long = runCatching {
        File(Environment.getDataDirectory().path).usableSpace
    }.getOrDefault(-1L)
}
