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
        return AutoMateAccessibilityService.isEnabled(applicationContext) ||
            AutoMateAccessibilityService.isBound
    }

    private fun attemptReenable() {
        val serviceComponent = ComponentName(
            applicationContext,
            AutoMateAccessibilityService::class.java
        ).flattenToShortString()

        // WRITE_SECURE_SETTINGS is signature|privileged, so this essentially never
        // succeeds for a normal install. It is kept only for rooted/privileged setups.
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
            if (AutoMateAccessibilityService.isEnabled(applicationContext)) {
                Log.i(TAG, "Accessibility re-enabled via Settings.Secure")
                return
            }
            Log.w(TAG, "Settings.Secure write was accepted but did not take effect")
        } catch (e: SecurityException) {
            Log.d(TAG, "WRITE_SECURE_SETTINGS not available in worker")
        } catch (e: Exception) {
            Log.w(TAG, "Settings.Secure write failed in worker", e)
        }

        // Shell fallback. The old code logged success unconditionally, which hid the
        // fact that `settings put` fails from an app UID. Check the exit code and, more
        // importantly, re-read the setting to confirm the OS actually accepted it.
        try {
            runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("settings", "put", "secure", "enabled_accessibility_services", serviceComponent))
                    .waitFor()
                Runtime.getRuntime()
                    .exec(arrayOf("settings", "put", "secure", "accessibility_enabled", "1"))
                    .waitFor()
            }.onFailure { Log.d(TAG, "Shell rebind threw: ${it.message}") }

            if (AutoMateAccessibilityService.isEnabled(applicationContext)) {
                Log.i(TAG, "Accessibility re-enabled via shell in worker")
                return
            }
            Log.w(TAG, "Shell rebind did not take effect — manual enable required")
        } catch (e: Exception) {
            Log.d(TAG, "Shell rebind failed in worker")
        }

        // Cannot self-grant accessibility on stock Android. Tell the user once,
        // with a notification, instead of silently failing or stealing focus.
        AccessibilityNotifier.postActionRequired(applicationContext)
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
