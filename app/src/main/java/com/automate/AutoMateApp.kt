package com.automate

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.automate.engine.AccessibilityWatchdogWorker
import com.automate.engine.KeepAliveService
import com.automate.engine.TriggerManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class AutoMateApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        initDefaultPrefs()
        requestBatteryOptimization()
        KeepAliveService.start(this)
        AccessibilityWatchdogWorker.enqueue(this)
        restoreLocationAutomation()
    }

    /**
     * Geofences and the foreground GPS tracker used to be registered only as a side
     * effect of adding a location or of the 9:40 morning prompt. Because the prompt is
     * an exact alarm that Android 13+ can drop, a normal app launch ended up with
     * zero registered geofences — the reason locations were "not detected".
     * Re-registering on every start makes the pipeline self-healing.
     */
    private fun restoreLocationAutomation() {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext, AutomationEntryPoint::class.java
            )
            val triggerManager = entryPoint.triggerManager()
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    triggerManager.startLocationTracking()
                    triggerManager.enableGeofences()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore location automation", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not resolve TriggerManager for startup restore", e)
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AutomationEntryPoint {
        fun triggerManager(): TriggerManager
    }

    private fun requestBatteryOptimization() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // Can't automatically request, but we store the intent for later use
                // The app will prompt user on first launch
                val prefs = getSharedPreferences("automate_prefs", MODE_PRIVATE)
                prefs.edit().putBoolean("needs_battery_optimization", true).apply()
            }
        } catch (e: Exception) {
            // Ignore — not critical
        }
    }

    private fun initDefaultPrefs() {
        val prefs = getSharedPreferences("automate_prefs", MODE_PRIVATE)
        if (!prefs.contains("initialized")) {
            prefs.edit()
                .putInt("work_hours", 9)
                .putBoolean("morning_prompt_enabled", true)
                .putFloat("geofence_radius", 200f)
                .putFloat("exit_watch_distance", 150f)
                .putBoolean("initialized", true)
                .apply()
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val morningPrompt = NotificationChannel(
            CHANNEL_MORNING_PROMPT,
            "Morning Check-in Prompt",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Daily prompt asking if you're going to work"
            enableVibration(true)
        }

        val taskStatus = NotificationChannel(
            CHANNEL_TASK_STATUS,
            "Task Status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications about automation task status"
        }

        val locationAlert = NotificationChannel(
            CHANNEL_LOCATION_ALERT,
            "Location Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts about geofence events and location errors"
            enableVibration(true)
        }

        val keepAlive = NotificationChannel(
            CHANNEL_KEEP_ALIVE,
            "Keep Alive",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps AutoMate running in background"
            setShowBadge(false)
        }

        // Background activity launches are refused on Android 10+ ("Abort background
        // activity starts"), and MIUI enforces it even with SYSTEM_ALERT_WINDOW granted.
        // A high-importance channel carrying a full-screen intent is the supported way to
        // bring Beehive to the front from the background, so it must exist up front.
        val launch = NotificationChannel(
            CHANNEL_APP_LAUNCH,
            "App Launch",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Used by AutoMate to open other apps for attendance"
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            setSound(null, null)
        }

        manager.createNotificationChannels(
            listOf(morningPrompt, taskStatus, locationAlert, keepAlive, launch)
        )
    }

    companion object {
        private const val TAG = "AutoMateApp"
        const val CHANNEL_MORNING_PROMPT = "morning_prompt"
        const val CHANNEL_TASK_STATUS = "task_status"
        const val CHANNEL_LOCATION_ALERT = "location_alert"
        const val CHANNEL_KEEP_ALIVE = "keep_alive"
        const val CHANNEL_APP_LAUNCH = "app_launch"
    }
}
