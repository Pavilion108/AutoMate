package com.automate.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.automate.AutoMateApp
import com.automate.MainActivity
import kotlinx.coroutines.*

class KeepAliveService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watchdogJob: Job? = null
    private var rebindAttempts = 0

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KeepAliveService created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "KeepAliveService started with action: ${intent?.action}")
        when (intent?.action) {
            ACTION_REBIND_ACCESSIBILITY -> rebindAccessibility()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        scope.cancel()
        Log.w(TAG, "KeepAliveService destroyed! Will be restarted by system.")
    }

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            while (isActive) {
                delay(15_000L) // Check every 15 seconds

                val accEnabled = isAccessibilityEnabled()

                if (!accEnabled) {
                    rebindAttempts++
                    Log.w(TAG, "Accessibility service OFF (attempt $rebindAttempts). Re-enabling...")
                    rebindAccessibility()
                } else {
                    if (rebindAttempts > 0) {
                        Log.i(TAG, "Accessibility service recovered after $rebindAttempts attempts")
                    }
                    rebindAttempts = 0
                }

                // Update notification
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabled.contains("com.automate/com.automate.engine.AutoMateAccessibilityService")
    }

    private fun rebindAccessibility() {
        val serviceString = "com.automate/com.automate.engine.AutoMateAccessibilityService"

        // Method 1: Try Settings.Secure (requires WRITE_SECURE_SETTINGS)
        try {
            Settings.Secure.putString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                serviceString
            )
            Settings.Secure.putInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            Log.i(TAG, "Accessibility re-enabled via Settings.Secure")
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted, trying shell fallback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-enable via Settings.Secure", e)
        }

        // Method 2: Try shell command (works if app has shell access)
        try {
            Runtime.getRuntime().exec(arrayOf(
                "settings", "put", "secure", "enabled_accessibility_services", serviceString
            )).waitFor()
            Runtime.getRuntime().exec(arrayOf(
                "settings", "put", "secure", "accessibility_enabled", "1"
            )).waitFor()
            Log.i(TAG, "Accessibility re-enabled via shell command")
            return
        } catch (e: Exception) {
            Log.e(TAG, "Shell fallback failed", e)
        }

        // Method 3: Send broadcast to self to try re-enabling
        try {
            val intent = Intent(this, AutoMateAccessibilityService::class.java)
            startService(intent)
            Log.i(TAG, "Tried direct service start")
        } catch (e: Exception) {
            Log.e(TAG, "Direct service start failed", e)
        }

        Log.e(TAG, "All rebind methods failed. User must manually re-enable accessibility.")
    }

    private fun buildNotification(): Notification {
        val accEnabled = AutoMateAccessibilityService.instance != null
        val statusText = if (accEnabled) "Active - Monitoring" else "Reconnecting..."

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, AutoMateApp.CHANNEL_KEEP_ALIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AutoMate Active")
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            AutoMateApp.CHANNEL_KEEP_ALIVE,
            "Keep Alive",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps AutoMate running in background"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIFICATION_ID = 9999
        const val ACTION_REBIND_ACCESSIBILITY = "REBIND_ACCESSIBILITY"

        fun start(service: android.content.Context) {
            val intent = Intent(service, KeepAliveService::class.java)
            service.startForegroundService(intent)
        }

        fun rebindAccessibility(context: android.content.Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_REBIND_ACCESSIBILITY
            }
            context.startService(intent)
        }
    }
}
