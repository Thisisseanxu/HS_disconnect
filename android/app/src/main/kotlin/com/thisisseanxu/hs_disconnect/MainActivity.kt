package com.thisisseanxu.hs_disconnect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "com.thisisseanxu.hs_disconnect/control"
    private val vpnRequestCode = 7001
    private val notificationRequestCode = 7002
    private var pendingResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "start" -> requestVpnAndStart(result)
                    "stop" -> {
                        startService(Intent(this, VpnBlockService::class.java).apply {
                            action = VpnBlockService.ACTION_STOP
                        })
                        result.success(true)
                    }
                    "setLocale" -> {
                        val tag = call.argument<String>("tag") ?: ""
                        val prefs = getSharedPreferences(VpnBlockService.PREFS, MODE_PRIVATE)
                        prefs.edit().putString(VpnBlockService.KEY_LANG_TAG, tag).apply()
                        if (prefs.getBoolean(VpnBlockService.KEY_RUNNING, false)) {
                            startService(Intent(this, VpnBlockService::class.java).apply {
                                action = VpnBlockService.ACTION_REFRESH
                            })
                        }
                        result.success(true)
                    }
                    "updateSettings" -> {
                        val duration = call.argument<Int>("durationMs") ?: 3000
                        val size = call.argument<Int>("overlaySize") ?: 64
                        getSharedPreferences(VpnBlockService.PREFS, MODE_PRIVATE).edit()
                            .putInt(VpnBlockService.KEY_DURATION_MS, duration.coerceIn(1, 5000))
                            .putInt(VpnBlockService.KEY_SIZE, size.coerceIn(40, 120))
                            .apply()
                        startService(Intent(this, VpnBlockService::class.java).apply {
                            action = VpnBlockService.ACTION_REFRESH
                        })
                        result.success(true)
                    }
                    "getState" -> {
                        val prefs = getSharedPreferences(VpnBlockService.PREFS, MODE_PRIVATE)
                        result.success(
                            mapOf(
                                "running" to prefs.getBoolean(VpnBlockService.KEY_RUNNING, false),
                                "blocking" to prefs.getBoolean(VpnBlockService.KEY_BLOCKING, false),
                                "durationMs" to prefs.getInt(VpnBlockService.KEY_DURATION_MS, 3000),
                                "overlaySize" to prefs.getInt(VpnBlockService.KEY_SIZE, 64),
                                "overlayGranted" to Settings.canDrawOverlays(this),
                                "batteryOptimizationIgnored" to isIgnoringBatteryOptimizations(),
                                "notificationsGranted" to notificationsEnabled()
                            )
                        )
                    }
                    "requestNotifications" -> {
                        requestNotifications()
                        result.success(true)
                    }
                    "requestOverlay" -> {
                        if (!Settings.canDrawOverlays(this)) {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                        result.success(true)
                    }
                    "requestBatteryOptimization" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            runCatching {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:$packageName")
                                    )
                                )
                            }.onFailure {
                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun requestVpnAndStart(result: MethodChannel.Result) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            startBlockService()
            result.success(true)
        } else {
            pendingResult = result
            startActivityForResult(prepareIntent, vpnRequestCode)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode) {
            val granted = resultCode == RESULT_OK
            if (granted) startBlockService()
            pendingResult?.success(granted)
            pendingResult = null
        }
    }

    private fun requestNotifications() {
        if (notificationsEnabled()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            val permissionDenied =
                ContextCompat.checkSelfPermission(this, permission) !=
                    PackageManager.PERMISSION_GRANTED
            val preferences = getPreferences(MODE_PRIVATE)
            val previouslyRequested =
                preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
            val canRequestAgain =
                !previouslyRequested ||
                    ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

            if (permissionDenied && canRequestAgain) {
                preferences.edit()
                    .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
                    .apply()
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(permission),
                    notificationRequestCode
                )
                return
            }
        }

        openNotificationSettings()
    }

    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        runCatching { startActivity(intent) }.onFailure {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun startBlockService() {
        val intent = Intent(this, VpnBlockService::class.java).apply {
            action = VpnBlockService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val running = getSharedPreferences(VpnBlockService.PREFS, MODE_PRIVATE)
            .getBoolean(VpnBlockService.KEY_RUNNING, false)
        if (running) {
            startService(Intent(this, VpnBlockService::class.java).apply {
                action = VpnBlockService.ACTION_REFRESH
            })
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val manager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        return manager.isIgnoringBatteryOptimizations(packageName)
    }

    companion object {
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED =
            "notification_permission_requested"
    }
}
