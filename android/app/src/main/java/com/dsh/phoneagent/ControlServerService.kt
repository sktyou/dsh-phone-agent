package com.dsh.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the control server alive while the phone is idle.
 *
 * A foreground service is required here: a plain background service would be
 * frozen by Doze within minutes, which would present itself to the PC side as
 * an unexplained connection drop rather than a policy stop.
 */
class ControlServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val token = effectiveToken(this)
        val controlServer = ControlServer(applicationContext, PORT, token).also { it.start() }
        server = controlServer
        Log.i(TAG, "control server listening on $PORT (auth=${if (token.isEmpty()) "off" else "on"})")

        // The browser console needs HTTP, which the control port cannot speak, so it
        // gets its own listener. It calls into the control server in-process rather
        // than dialling back over loopback, which measured ~200ms slower per request.
        // Started here rather than from the Activity so it survives the app going to
        // the background — the whole point is to drive the phone from a PC while
        // nobody is looking at the phone screen.
        webConsole = WebConsole(applicationContext, controlServer, WebConsole.DEFAULT_PORT)
            .also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        webConsole?.stop()
        webConsole = null
        server?.stop()
        server = null
        instance = null
        Log.i(TAG, "control server stopped")
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // The old channel was IMPORTANCE_LOW, which by definition suppresses the
        // status-bar icon — "low" means the user asked not to be shown. A
        // foreground service the user needs to notice cannot be silent *and*
        // invisible, so this is DEFAULT with sound and vibration explicitly off:
        // the icon appears, nothing makes noise.
        //
        // Channel importance is immutable once created, so the id carries a version
        // suffix and the old one is removed rather than edited.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "控制服务", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "保持手机控制端口在线"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ControlServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val address = "${localIpv4() ?: "无 IP"}:$PORT"
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("控制服务运行中")
            .setContentText(address)
            // A single-colour silhouette is mandatory here: the system tints
            // notification small icons by alpha and discards colour, so the
            // launcher icon would arrive as a solid white square.
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setColor(0xFF2563EB.toInt())
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止服务", stop).build())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Without this a foreground-service notification may be deferred,
            // which is exactly the window in which the user cannot tell the
            // service is alive. Below S there is no such deferral, and
            // Builder.setPriority is deprecated in favour of channel importance.
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** First non-loopback IPv4 — the address the PC dials. */
    private fun localIpv4(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (t: Throwable) {
        null
    }

    companion object {
        /** Chosen away from common ports (5555/7912-free) to avoid collisions. */
        const val PORT = 7912
        const val ACTION_STOP = "com.dsh.phoneagent.STOP"

        /** v2: DEFAULT importance with sound/vibration off, so the icon is visible. */
        private const val CHANNEL_ID = "dsh_phone_agent_v2"

        /** The original LOW-importance channel, which suppressed the status icon. */
        private const val LEGACY_CHANNEL_ID = "dsh_phone_agent"

        private const val NOTIFICATION_ID = 7912
        private const val TAG = "DSHPhoneAgent"

        @Volatile
        var instance: ControlServerService? = null

        /** HTTP console, separate from the raw control protocol. */
        @Volatile
        private var webConsole: WebConsole? = null
            private set

        @Volatile
        var server: ControlServer? = null
            private set

        val isRunning: Boolean
            get() = instance != null && server?.isRunning == true

        val connectedClients: Int
            get() = server?.clientCount ?: 0

        val operations: Int
            get() = server?.operationCount ?: 0

        val uptimeMs: Long
            get() = server?.uptimeMs ?: 0L

        fun recentOperations(): List<org.json.JSONObject> =
            server?.recentOperations() ?: emptyList()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ControlServerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ControlServerService::class.java))
        }

        const val PREFS = "dsh-phone-agent"
        const val KEY_TOKEN = "token"
        const val KEY_TOKEN_ENABLED = "token_enabled"

        /**
         * The stored secret, whether or not it is currently enforced.
         *
         * Kept separate from [isTokenEnabled] so the value survives being switched
         * off and back on: turning protection off must not throw the secret away,
         * or every toggle would force the PC side to be reconfigured.
         */
        fun readToken(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, "")
                ?.trim()
                ?: ""

        fun writeToken(context: Context, token: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TOKEN, token)
                .apply()
        }

        fun isTokenEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TOKEN_ENABLED, false)

        fun setTokenEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_TOKEN_ENABLED, enabled)
                .apply()
        }

        /**
         * The secret the server actually enforces.
         *
         * Empty means "no authentication" — which is what the socket layer expects,
         * so the enable switch and the presence of a value collapse into one answer
         * here rather than being re-derived at every call site.
         */
        fun effectiveToken(context: Context): String =
            if (isTokenEnabled(context)) readToken(context) else ""

        /**
         * Generate a secret if none is stored yet.
         *
         * Called on first launch so the token section has something to show and the
         * user can turn protection on without a separate "generate" step first. It
         * deliberately does NOT enable enforcement — appearing in the UI is not
         * consent to lock the port.
         */
        fun ensureToken(context: Context): String {
            val existing = readToken(context)
            if (existing.isNotEmpty()) return existing
            val fresh = newToken()
            writeToken(context, fresh)
            return fresh
        }

        /** 16 hex-ish characters: long enough to be unguessable, short enough to type. */
        fun newToken(): String =
            java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
