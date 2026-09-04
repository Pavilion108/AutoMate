package com.automate.engine

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.automate.AutoMateApp
import com.automate.MainActivity
import com.automate.domain.model.Action
import com.automate.domain.model.ActionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val variableStore: VariableStore
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var notificationId = 1000

    suspend fun executeActions(actions: List<Action>): Boolean {
        for ((index, action) in actions.withIndex()) {
            Log.i(TAG, "Executing action ${index + 1}/${actions.size}: ${action.type}")
            val result = executeAction(action)
            if (!result && !action.retryOnFailure) {
                Log.w(TAG, "Action failed: ${action.type}")
                return false
            }
        }
        return true
    }

    suspend fun executeAction(action: Action): Boolean {
        return withContext(Dispatchers.IO) {
            when (action.type) {
                ActionType.LAUNCH_APP -> launchApp(action.packageName)
                ActionType.KILL_APP -> killApp(action.packageName)
                ActionType.SHOW_NOTIFICATION -> showNotification(action.title, action.message)
                ActionType.SHOW_DIALOG -> showDialog(action.title, action.message, action.positiveButton, action.negativeButton)
                ActionType.SET_VARIABLE -> {
                    variableStore.setVariable(action.variableName, action.variableValue)
                    true
                }
                ActionType.CHECK_VARIABLE -> {
                    val currentValue = variableStore.getVariable(action.variableName)
                    currentValue == action.variableValue
                }
                ActionType.REFRESH_LOCATION -> refreshLocation()
                ActionType.POPUP_HANDLER -> handlePopups(action)
                ActionType.SCHEDULE_TIME_OUT -> scheduleTimeOut(action.seconds)
                ActionType.WAIT -> {
                    delay(action.seconds * 1000L)
                    true
                }
                ActionType.CLICK_ELEMENT,
                ActionType.CLICK_COORDINATES,
                ActionType.TYPE_TEXT,
                ActionType.SWIPE,
                ActionType.GLOBAL_ACTION -> {
                    // Delegate to accessibility service
                    val service = AutoMateAccessibilityService.instance
                    if (service != null) {
                        service.executeAction(action)
                    } else {
                        Log.e(TAG, "Accessibility service not running")
                        false
                    }
                }
            }
        }
    }

    private fun launchApp(packageName: String): Boolean {
        return try {
            // Strategy 1: Try standard launch intent
            var intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                Log.i(TAG, "Launched app via intent: $packageName")
                return true
            }

            // Strategy 2: MIUI fallback — use known activity components
            val knownActivities = mapOf(
                "com.app.beehivehrms" to "com.tns.NativeScriptActivity"
            )
            val activityClass = knownActivities[packageName]
            if (activityClass != null) {
                intent = Intent().apply {
                    setClassName(packageName, activityClass)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
                Log.i(TAG, "Launched app via fallback activity: $packageName/$activityClass")
                return true
            }

            // Strategy 3: Use shell am start via Runtime
            val process = Runtime.getRuntime().exec(arrayOf("am", "start", "-n", "$packageName/$(knownActivities[packageName] ?: "")"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "Launched app via shell: $packageName")
                return true
            }

            Log.w(TAG, "App not found: $packageName")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: $packageName", e)
            false
        }
    }

    private fun killApp(packageName: String): Boolean {
        return try {
            val service = AutoMateAccessibilityService.instance
            if (service != null) {
                // Use accessibility service to kill app via recents
                service.performGlobalRecents()
                Thread.sleep(500)

                // Find the app in recents and swipe it away
                val root = service.rootInActiveWindow
                if (root != null) {
                    // Try to find and close the app
                    val nodes = root.findAccessibilityNodeInfosByText(packageName)
                    if (nodes.isNotEmpty()) {
                        // Swipe up to dismiss
                        service.performSwipe(360, 800, 360, 200, 300)
                        Thread.sleep(300)
                        service.performGlobalBack()
                    }
                }
                true
            } else {
                // Fallback: use force-stop via shell (requires root or special permissions)
                Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName))
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill app: $packageName", e)
            false
        }
    }

    private fun showNotification(title: String, text: String): Boolean {
        return try {
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, AutoMateApp.CHANNEL_TASK_STATUS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notificationId++, notification)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification", e)
            false
        }
    }

    private fun showDialog(title: String, message: String, positive: String, negative: String): Boolean {
        // For now, return true - dialog handling will be implemented in UI layer
        return true
    }

    private fun refreshLocation(): Boolean {
        // Trigger a location refresh
        return try {
            val intent = Intent("com.automate.REFRESH_LOCATION")
            context.sendBroadcast(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun handlePopups(action: Action): Boolean {
        val service = AutoMateAccessibilityService.instance ?: return false
        val dismissTexts = action.popupDismissTexts.ifEmpty {
            listOf("OK", "Ok", "ok", "CLOSE", "Close", "ALLOW", "Allow",
                   "GOT IT", "Got it", "DISMISS", "Dismiss", "YES", "NO")
        }

        var attempts = 0
        val maxAttempts = action.maxRetries.coerceAtLeast(20)

        while (attempts < maxAttempts) {
            attempts++

            // Check for popups
            val root = service.rootInActiveWindow
            if (root == null) {
                delay(action.retryDelayMs)
                continue
            }

            var popupFound = false
            for (text in dismissTexts) {
                val node = service.findNodeByText(text)
                if (node != null) {
                    Log.i(TAG, "Dismissing popup with text: $text")
                    service.performClick(node)
                    popupFound = true
                    delay(500) // Wait for popup to close
                    break
                }
            }

            if (!popupFound) {
                // No popup found, check if success indicator is present
                if (action.successIndicator.isNotEmpty()) {
                    val successNode = service.findNodeByText(action.successIndicator)
                    if (successNode != null) {
                        Log.i(TAG, "Success indicator found: ${action.successIndicator}")
                        service.performClick(successNode)
                        return true
                    }
                }
                // Check screen text for success
                val screenText = service.getScreenText()
                if (screenText.contains("Time In", ignoreCase = true) ||
                    screenText.contains("Success", ignoreCase = true) ||
                    screenText.contains("Recorded", ignoreCase = true)) {
                    // Find and click OK to confirm
                    val okButton = service.findNodeByText("OK")
                    if (okButton != null) {
                        service.performClick(okButton)
                    }
                    return true
                }
            }

            delay(action.retryDelayMs)
        }

        return false
    }

    private fun scheduleTimeOut(delayMinutes: Int): Boolean {
        // Schedule a time-out notification after delayMinutes
        return try {
            val intent = Intent(context, com.automate.geofence.GeofenceBroadcastReceiver::class.java).apply {
                action = "TIME_OUT_TRIGGER"
                putExtra("delay_minutes", delayMinutes)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 9999, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + (delayMinutes * 60 * 1000L),
                pendingIntent
            )
            Log.i(TAG, "Time-out scheduled in $delayMinutes minutes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule time-out", e)
            false
        }
    }

    companion object {
        private const val TAG = "ActionExecutor"
    }
}
