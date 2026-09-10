package com.automate.engine

import android.content.Context
import android.content.ComponentName
import android.provider.Settings
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class AccessibilityWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i(TAG, "AccessibilityWatchdogWorker running")

        val enabled = isAccessibilityServiceEnabled()
        if (!enabled) {
            Log.w(TAG, "Accessibility service is OFF, attempting to re-enable")
            attemptReenable()
        } else {
            Log.d(TAG, "Accessibility service is ON")
        }

        return Result.success()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceComponent = ComponentName(
            applicationContext,
            AutoMateAccessibilityService::class.java
        ).flattenToShortString()

        val enabledServices = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        if (enabledServices.contains(serviceComponent)) return true

        // Also check if instance is alive
        if (AutoMateAccessibilityService.instance != null) return true

        return false
    }

    private fun attemptReenable() {
        val serviceComponent = ComponentName(
            applicationContext,
            AutoMateAccessibilityService::class.java
        ).flattenToShortString()

        // Try Settings.Secure write
        try {
            Settings.Secure.putString(
                applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                serviceComponent
            )
            Settings.Secure.putInt(
                applicationContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            Log.i(TAG, "Accessibility re-enabled via WorkManager")
            return
        } catch (e: SecurityException) {
            Log.d(TAG, "WRITE_SECURE_SETTINGS not available in worker")
        } catch (e: Exception) {
            Log.w(TAG, "Settings.Secure write failed in worker", e)
        }

        // Try shell command
        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "settings", "put", "secure", "enabled_accessibility_services", serviceComponent
            ))
            process.waitFor()
            Runtime.getRuntime().exec(arrayOf(
                "settings", "put", "secure", "accessibility_enabled", "1"
            )).waitFor()
            Log.i(TAG, "Accessibility re-enabled via shell in worker")
        } catch (e: Exception) {
            Log.d(TAG, "Shell rebind failed in worker")
        }

        // Notify the KeepAliveService to handle user-facing rebind
        KeepAliveService.checkAndRebind(applicationContext)
    }

    companion object {
        private const val TAG = "AccessibilityWatchdog"
        private const val WORK_NAME = "accessibility_watchdog"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false) // Run even on low battery
                .build()

            val work = PeriodicWorkRequestBuilder<AccessibilityWatchdogWorker>(
                15, TimeUnit.MINUTES // Check every 15 minutes
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    1, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                work
            )
            Log.i(TAG, "Accessibility watchdog work enqueued")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
