package com.example.hs_disconnect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File

class VpnBlockService : VpnService() {
    private val handler = Handler(Looper.getMainLooper())
    private var tunInterface: ParcelFileDescriptor? = null
    private var socksProxy: LocalSocks5Proxy? = null
    private var overlay: TextView? = null
    private var windowManager: WindowManager? = null
    private var blockGeneration = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val info = packageManager.getPackageInfo(packageName, 0)
        Log.i(TAG, "service created version=${info.versionName} (${info.longVersionCode})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "command action=${intent?.action} startId=$startId tun=${tunInterface != null}")
        when (intent?.action) {
            ACTION_STOP -> stopEverything()
            ACTION_REFRESH -> if (isRunning() && tunInterface == null) startVpn() else showOverlay()
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (tunInterface != null) {
            Log.d(TAG, "start ignored: VPN already established")
            return
        }
        Log.i(TAG, "starting VPN and local SOCKS proxy")
        startForeground(NOTIFICATION_ID, notification(false))
        runCatching {
            socksProxy = LocalSocks5Proxy(SOCKS_PORT).also { it.start() }
            tunInterface = Builder()
                .setSession("HS Disconnect")
                .setMtu(MTU)
                .addAddress("198.18.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName)
                .establish() ?: error("Android refused to establish the VPN")
            Log.i(TAG, "TUN established fd=${tunInterface!!.fd} mtu=$MTU")

            val config = writeTProxyConfig()
            TProxyBridge.start(config.absolutePath, tunInterface!!.fd)
            socksProxy?.setBlocked(false)
            TProxyBridge.setBlocked(false)
            verifyTunnelStarted(0)
        }.onFailure {
            Log.e(TAG, "VPN startup failed", it)
            stopEverything()
        }
    }

    private fun verifyTunnelStarted(attempt: Int) {
        handler.postDelayed({
            if (tunInterface == null) return@postDelayed
            if (TProxyBridge.isRunning()) {
                Log.i(TAG, "native tunnel ready after ${attempt + 1} checks")
                prefs().edit()
                    .putBoolean(KEY_RUNNING, true)
                    .putBoolean(KEY_BLOCKING, false)
                    .apply()
                showOverlay()
            } else if (attempt < 20) {
                verifyTunnelStarted(attempt + 1)
            } else {
                Log.e(TAG, "native tunnel failed to become ready")
                stopEverything()
            }
        }, 100)
    }

    private fun writeTProxyConfig(): File {
        return File(cacheDir, "tproxy.conf").apply {
            writeText(
                """
                tunnel:
                  mtu: $MTU
                socks5:
                  address: '127.0.0.1'
                  port: $SOCKS_PORT
                  udp: 'udp'
                misc:
                  task-stack-size: 24576
                  connect-timeout: 10000
                  tcp-read-write-timeout: 300000
                  udp-read-write-timeout: 60000
                  log-level: warn
                """.trimIndent()
            )
        }
    }

    private fun triggerBlock() {
        val generation = ++blockGeneration
        val durationMs = prefs().getInt(KEY_DURATION_MS, 3000).coerceIn(1, 5000).toLong()
        Log.w(TAG, "reconnect trigger generation=$generation durationMs=$durationMs")
        socksProxy?.setBlocked(true)
        prefs().edit().putBoolean(KEY_BLOCKING, true).apply()
        updateOverlay(true)
        notificationManager().notify(NOTIFICATION_ID, notification(true))

        handler.postDelayed({ if (generation == blockGeneration) endBlock() }, durationMs)
    }

    private fun endBlock() {
        blockGeneration++
        Log.i(TAG, "reconnect block ended generation=$blockGeneration")
        socksProxy?.setBlocked(false)
        prefs().edit().putBoolean(KEY_BLOCKING, false).apply()
        updateOverlay(false)
        notificationManager().notify(NOTIFICATION_ID, notification(false))
    }

    private fun showOverlay() {
        removeOverlay()
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = prefs().getInt(KEY_SIZE, 64).coerceIn(44, 120)
        val view = TextView(this).apply {
            text = "拔"
            textSize = size * 0.34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            elevation = 12f
        }
        val params = WindowManager.LayoutParams(
            dp(size), dp(size),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs().getInt(KEY_X, 24)
            y = prefs().getInt(KEY_Y, 240)
        }
        attachDragAndClick(view, params)
        windowManager?.addView(view, params)
        overlay = view
        updateOverlay(prefs().getBoolean(KEY_BLOCKING, false))
    }

    private fun attachDragAndClick(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    moved = moved || kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs().edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    if (!moved) triggerBlock()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateOverlay(blocking: Boolean) {
        overlay?.apply {
            text = if (blocking) "停" else "断"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (blocking) Color.rgb(190, 45, 55) else Color.rgb(28, 90, 116))
                setStroke(dp(2), Color.argb(150, 255, 255, 255))
            }
        }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
    }

    private fun notification(blocking: Boolean): android.app.Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, VpnBlockService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(if (blocking) "正在关闭并挂起网络会话" else "VPN 始终在线")
            .setContentText(if (blocking) "现有连接已关闭，新连接将在计时结束后放行" else "点击悬浮按钮可触发游戏断线重连")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN 断流服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun stopEverything() {
        Log.i(TAG, "stopping VPN service")
        handler.removeCallbacksAndMessages(null)
        blockGeneration++
        runCatching { TProxyBridge.setBlocked(false) }
        runCatching { TProxyBridge.stop() }
        runCatching { tunInterface?.close() }
        tunInterface = null
        socksProxy?.close()
        socksProxy = null
        removeOverlay()
        prefs().edit().putBoolean(KEY_RUNNING, false).putBoolean(KEY_BLOCKING, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopEverything()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun isRunning() = prefs().getBoolean(KEY_RUNNING, false)
    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun notificationManager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_START = "hs.action.START"
        const val ACTION_STOP = "hs.action.STOP"
        const val ACTION_REFRESH = "hs.action.REFRESH"
        const val PREFS = "hs_disconnect"
        const val KEY_RUNNING = "running"
        const val KEY_BLOCKING = "blocking"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_SIZE = "size"
        const val KEY_X = "overlay_x"
        const val KEY_Y = "overlay_y"
        private const val SOCKS_PORT = 10808
        private const val MTU = 1500
        private const val CHANNEL_ID = "hs_disconnect_service"
        private const val NOTIFICATION_ID = 4102
        private const val TAG = "HSDisconnect"
    }
}
