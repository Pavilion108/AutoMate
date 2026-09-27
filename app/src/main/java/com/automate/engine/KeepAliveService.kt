package com.automate.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.automate.AutoMateApp
import com.automate.MainActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*

class KeepAliveService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watchdogJob: Job? = null
    private var rebindAttempts = 0
    private var actionRequiredShown = false
    private var wakeLock: PowerManager.WakeLock? = null

    // Foreground GPS location tracking (true 3-second intervals)
    private var locationManager: LocationManager? = null
    private var gpsListener: LocationListener? = null
    private var networkListener: LocationListener? = null
    private var isLocationTracking = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KeepAliveService created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        acquireWakeLock()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "KeepAliveService started with action: ${intent?.action}")
        when (intent?.action) {
            ACTION_REBIND_ACCESSIBILITY -> attemptRebind()
            ACTION_CHECK_AND_REBIND -> attemptRebind()
            ACTION_START_LOCATION_TRACKING -> startForegroundLocationTracking()
            ACTION_STOP_LOCATION_TRACKING -> stopForegroundLocationTracking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // === Foreground Location Tracking (true 3-second intervals via raw GPS) ===

    private fun startForegroundLocationTracking() {
        if (isLocationTracking) {
            Log.d(TAG, "Foreground location tracking already active")
            return
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        gpsListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.d(TAG, "Foreground GPS: ${location.latitude}, ${location.longitude} (acc: ${location.accuracy}m)")
                val broadcastIntent = Intent(ACTION_LOCATION_UPDATE).apply {
                    putExtra("latitude", location.latitude)
                    putExtra("longitude", location.longitude)
                    putExtra("accuracy", location.accuracy)
                    setPackage(packageName)
                }
                sendBroadcast(broadcastIntent)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        // Raw GPS provider: true 3-second intervals, not batched
        @Suppress("MissingPermission")
        locationManager?.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            3000L, // 3 seconds
            0f,    // no minimum distance
            gpsListener!!
        )

        // Also request network location as backup for indoor
        @Suppress("MissingPermission")
        locationManager?.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            3000L,
            0f,
            gpsListener!!
        )

        isLocationTracking = true
        Log.i(TAG, "Foreground GPS location tracking started (raw GPS, true 3s interval)")
    }

    private fun stopForegroundLocationTracking() {
        gpsListener?.let { locationManager?.removeUpdates(it) }
        networkListener?.let { locationManager?.removeUpdates(it) }
        gpsListener = null
        networkListener = null
        isLocationTracking = false
        Log.i(TAG, "Foreground GPS location tracking stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        stopForegroundLocationTracking()
        releaseWakeLock()
        scope.cancel()
        Log.w(TAG, "KeepAliveService destroyed! Will be restarted by system.")
    }

    // === Smart Watchdog ===

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)

                val accEnabled = isAccessibilityServiceEnabled()
                AccessibilityNotifier.clear(this@KeepAliveService)

                if (!accEnabled) {
                    rebindAttempts++
                    Log.w(TAG, "Accessibility OFF (attempt $rebindAttempts)")

                    // Escalation: try the API route, then the shell route, then stop.
                    // The old code launched the Accessibility Settings activity on every
                    // pass, which stole focus every 10s and looked like a crash loop.
                    when {
                        rebindAttempts <= 3 -> attemptRebind()
                        rebindAttempts <= 8 -> {
                            attemptRebind()
                            tryShellRebind()
                        }
                        else -> {
                            // Genuinely blocked: accessibility cannot be self-granted.
                            // Ask the user once instead of nagging or hijacking the UI.
                            if (!actionRequiredShown) {
                                Log.w(TAG, "Auto-rebind impossible after $rebindAttempts attempts, notifying user")
                                AccessibilityNotifier.postActionRequired(this@KeepAliveService)
                                actionRequiredShown = true
                            }
                            rebindAttempts = 0
                            delay(5 * 60_000L)
                            continue
                        }
                    }

                    // Verify rather than assume: only claim success if the OS agrees.
                    if (isAccessibilityServiceEnabled()) {
                        rebindAttempts = 0
                        actionRequiredShown = false
                        Log.i(TAG, "Accessibility re-enabled")
                    }
                } else {
                    if (rebindAttempts > 0) {
                        Log.i(TAG, "Accessibility recovered after $rebindAttempts attempts")
                    }
                    rebindAttempts = 0
                    actionRequiredShown = false
                }

                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    // === Accessibility Detection ===

    private fun isAccessibilityServiceEnabled(): Boolean {
        return AutoMateAccessibilityService.isEnabled(this)
    }

    // === Re-enable Strategies ===

    private fun attemptRebind() {
        val serviceString = ComponentName(this, AutoMateAccessibilityService::class.java).flattenToShortString()

        // Method 1: Direct Settings.Secure write (needs WRITE_SECURE_SETTINGS)
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
            if (isAccessibilityServiceEnabled()) {
                Log.i(TAG, "Re-enabled via Settings.Secure")
                return
            }
            Log.w(TAG, "Settings.Secure write accepted but had no effect")
        } catch (e: SecurityException) {
            Log.d(TAG, "WRITE_SECURE_SETTINGS not available")
        } catch (e: Exception) {
            Log.w(TAG, "Settings.Secure write failed", e)
        }

        // Method 2: Ask the framework to bind us. Fails while the service is switched
        // off in Settings, which is the normal case — expected, not an error.
        try {
            startService(Intent(this, AutoMateAccessibilityService::class.java))
            Log.d(TAG, "Requested direct service start")
        } catch (e: Exception) {
            Log.d(TAG, "Direct service start failed (expected if disabled)")
        }

        // Deliberately no startActivity() here. Launching Accessibility Settings from a
        // background service is what made AutoMate appear to hijack the screen.
    }

    private fun tryShellRebind() {
        val serviceString = ComponentName(this, AutoMateAccessibilityService::class.java).flattenToShortString()

        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "settings", "put", "secure", "enabled_accessibility_services", serviceString
            ))
            val exitCode = process.waitFor()
            Runtime.getRuntime().exec(arrayOf(
                "settings", "put", "secure", "accessibility_enabled", "1"
            )).waitFor()
            // Exit code alone is not proof; confirm against the OS.
            if (exitCode == 0 && isAccessibilityServiceEnabled()) {
                Log.i(TAG, "Re-enabled via shell command")
            } else {
                Log.w(TAG, "Shell rebind had no effect (exit=$exitCode)")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Shell rebind failed (expected without root)")
        }
    }

    private fun openAccessibilitySettings() {
        try {
            // Open directly to our app's accessibility settings
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)

            // Show a notification guiding the user
            showRebindNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
        }
    }

    private fun showRebindNotification() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 7777, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, AutoMateApp.CHANNEL_KEEP_ALIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("AutoMate needs Accessibility")
            .setContentText("Tap to re-enable Accessibility Service")
            .setPriority(Notification.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(REBIND_NOTIFICATION_ID, notification)
    }

    // === WakeLock ===

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AutoMate::KeepAliveWakeLock"
        ).apply {
            acquire(60 * 60 * 1000L) // 1 hour, will be re-acquired
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // === Notification ===

    private fun buildNotification(): Notification {
        val accEnabled = isAccessibilityServiceEnabled()
        val statusText = if (accEnabled) "Active - Monitoring" else "Accessibility OFF - Reconnecting..."

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // If accessibility is off, make notification high priority with action button
        val builder = Notification.Builder(this, AutoMateApp.CHANNEL_KEEP_ALIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AutoMate")
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)

        if (!accEnabled) {
            // Add action button to open accessibility settings
            val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val settingsPending = PendingIntent.getActivity(
                this, 7778, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    null, "Enable Accessibility", settingsPending
                ).build()
            )
        }

        return builder.build()
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
        private const val REBIND_NOTIFICATION_ID = 9998
        private const val WATCHDOG_INTERVAL_MS = 10_000L // Check every 10 seconds
        const val ACTION_REBIND_ACCESSIBILITY = "REBIND_ACCESSIBILITY"
        const val ACTION_CHECK_AND_REBIND = "CHECK_AND_REBIND"
        const val ACTION_START_LOCATION_TRACKING = "START_LOCATION_TRACKING"
        const val ACTION_STOP_LOCATION_TRACKING = "STOP_LOCATION_TRACKING"
        const val ACTION_LOCATION_UPDATE = "com.automate.LOCATION_UPDATE"

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.startForegroundService(intent)
        }

        /**
         * Shuts the service down completely.
         *
         * Used by the end-of-day teardown: leaving the service alive keeps a foreground
         * notification, a GPS listener and the accessibility watchdog running, which is
         * exactly the all-day battery drain the user asked to avoid.
         */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: ${e.message}")
            }
        }

        fun rebindAccessibility(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_REBIND_ACCESSIBILITY
            }
            context.startService(intent)
        }

        fun checkAndRebind(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_CHECK_AND_REBIND
            }
            context.startService(intent)
        }
    }
}
