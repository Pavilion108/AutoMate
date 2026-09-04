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
import android.util.Log
import com.automate.AutoMateApp
import com.automate.MainActivity
import kotlinx.coroutines.*

class KeepAliveService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watchdogJob: Job? = null

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
                delay(30_000L) // Check every 30 seconds

                // Check if accessibility service is still active
                val accEnabled = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )?.contains("com.automate/com.automate.engine.AutoMateAccessibilityService") == true

                if (!accEnabled) {
                    Log.w(TAG, "Accessibility service lost! Re-enabling...")
                    rebindAccessibility()
                }

                // Update notification with status
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun rebindAccessibility() {
        try {
            android.provider.Settings.Secure.putString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "com.automate/com.automate.engine.AutoMateAccessibilityService"
            )
            android.provider.Settings.Secure.putInt(
                contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            Log.i(TAG, "Accessibility service re-enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-enable accessibility", e)
        }
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
