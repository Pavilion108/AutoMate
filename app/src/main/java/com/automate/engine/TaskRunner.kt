package com.automate.engine

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.automate.AutoMateApp
import com.automate.MainActivity
import com.automate.domain.model.Action
import com.automate.domain.model.ActionType
import com.automate.domain.model.Constraint
import com.automate.domain.model.ConstraintOperator
import com.automate.domain.model.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actionExecutor: ActionExecutor,
    private val variableStore: VariableStore,
    private val triggerManagerProvider: dagger.Lazy<TriggerManager>
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var notificationId = 2000
    private var timeInJob: Job? = null
    private var timeOutJob: Job? = null
    private var popupHandlerJob: Job? = null

    // === Smart Morning Prompt ===

    fun sendMorningPrompt() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "MORNING_PROMPT"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Yes, going to work" action
        val yesIntent = Intent(context, com.automate.geofence.GeofenceBroadcastReceiver::class.java).apply {
            action = "MORNING_RESPONSE"
            putExtra("going_to_work", true)
        }
        val yesPending = PendingIntent.getBroadcast(
            context, 101, yesIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "No, staying home" action
        val noIntent = Intent(context, com.automate.geofence.GeofenceBroadcastReceiver::class.java).apply {
            action = "MORNING_RESPONSE"
            putExtra("going_to_work", false)
        }
        val noPending = PendingIntent.getBroadcast(
            context, 102, noIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, AutoMateApp.CHANNEL_MORNING_PROMPT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AutoMate")
            .setContentText("Going to work today?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_send, "Yes, going!", yesPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "No, staying home", noPending)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId++, notification)
        Log.i(TAG, "Morning prompt sent")
    }

    fun handleMorningResponse(goingToWork: Boolean) {
        scope.launch {
            variableStore.setGoingToWork(goingToWork)

            if (goingToWork) {
                Log.i(TAG, "User is going to work - enabling geofence monitoring")
                variableStore.setArmed(true)
                triggerManagerProvider.get().enableGeofences()
                showStatusNotification("Going to work", "Monitoring your location for check-in")
            } else {
                Log.i(TAG, "User is staying home - disabling everything")
                variableStore.setArmed(false)
                variableStore.setTimedInToday(false)
                variableStore.setExitWatch(false)
                triggerManagerProvider.get().disableGeofences()
                showStatusNotification("Staying home", "AutoMate is off for today")
            }
        }
    }

    // === Smart Time-In Flow ===

    fun startTimeInFlow(accountId: Long = 0) {
        timeInJob?.cancel()
        timeInJob = scope.launch {
            Log.i(TAG, "Starting smart time-in flow")
            variableStore.setArmed(true)
            variableStore.setTimedInToday(false)

            val service = AutoMateAccessibilityService.instance
            if (service == null) {
                showStatusNotification("Error", "Accessibility service not running")
                return@launch
            }

            var attempts = 0
            val maxAttempts = 60 // Try for 5 minutes (60 * 5 seconds)

            val beehivePackage = "com.app.beehivehrms"

            // Go home FIRST so AutoMate is not in foreground
            Log.i(TAG, "Going home to clear screen...")
            service.performGlobalHome()
            delay(2000)

            while (attempts < maxAttempts && isActive) {
                attempts++
                Log.i(TAG, "Time-in attempt $attempts/$maxAttempts")

                // Dismiss any blocking dialogs
                val screenText0 = service.getScreenText()
                if (screenText0.contains("Uninstall", ignoreCase = true) ||
                    screenText0.contains("force stop", ignoreCase = true)) {
                    Log.i(TAG, "Dismissing blocking dialog...")
                    service.performGlobalBack()
                    delay(1000)
                }

                // Kill Beehive and relaunch fresh
                Runtime.getRuntime().exec(arrayOf("am", "force-stop", beehivePackage)).waitFor()
                delay(1000)

                // Launch Beehive via shell
                try {
                    Runtime.getRuntime().exec(arrayOf(
                        "am", "start", "-n", "$beehivePackage/com.tns.NativeScriptActivity"
                    )).waitFor()
                    Log.i(TAG, "Launched Beehive")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch Beehive", e)
                }
                delay(8000)

                // Log what's on screen
                var screenText = service.getScreenText()
                Log.i(TAG, "Screen text: ${screenText.take(500)}")

                // Check if Beehive login content is visible (NOT AutoMate dashboard)
                val isBeehiveVisible = screenText.contains("E0099", ignoreCase = true) ||
                        screenText.contains("Remember Me", ignoreCase = true) ||
                        screenText.contains("Forgot Password", ignoreCase = true) ||
                        screenText.contains("App Ver.", ignoreCase = true)

                if (!isBeehiveVisible) {
                    Log.w(TAG, "Beehive not visible on screen, retrying...")
                    delay(3000)
                    continue
                }

                Log.i(TAG, "Beehive content detected on screen")

                // Step 3: On login screen — click SIGN IN (credentials pre-filled)
                val signInNode = service.findNodeByTextInApp("SIGN IN", beehivePackage)
                    ?: service.findNodeByText("SIGN IN") // Fallback: search all windows
                if (signInNode != null) {
                    Log.i(TAG, "Found SIGN IN in Beehive, clicking...")
                    service.performClick(signInNode)
                    delay(5000) // Wait for login + page transition

                    // Check if page changed
                    var afterLoginText = service.getScreenText()
                    Log.i(TAG, "After SIGN IN click, screen: ${afterLoginText.take(300)}")

                    // If still on login, try coordinates click
                    if (afterLoginText.contains("SIGN IN") && afterLoginText.contains("Remember Me")) {
                        Log.w(TAG, "Still on login page. Trying coordinates click...")
                        val bounds = android.graphics.Rect()
                        signInNode.getBoundsInScreen(bounds)
                        service.tapAtCoordinates(bounds.centerX(), bounds.centerY())
                        delay(5000)
                        afterLoginText = service.getScreenText()
                        Log.i(TAG, "After coord click, screen: ${afterLoginText.take(300)}")
                    }

                    // Now look for TIME IN
                    val timeInNode = service.findNodeByTextInApp("TIME IN", beehivePackage)
                        ?: service.findNodeByText("TIME IN")
                    if (timeInNode != null) {
                        Log.i(TAG, "Found TIME IN in Beehive, clicking...")
                        service.performClick(timeInNode)
                        delay(2000)

                        val success = handleTimeInPopups()
                        if (success) {
                            Log.i(TAG, "Time-in successful!")
                            variableStore.setTimedInToday(true)
                            val location = triggerManagerProvider.get().getLastKnownLocation()
                            if (location != null) {
                                variableStore.setTimeInLocation(location.first, location.second)
                            }
                            val workHours = variableStore.getWorkDurationHours()
                            scheduleTimeOutPrompt(workHours)
                            showStatusNotification("Time-In Recorded", "Work hours started. Duration: ${workHours}h")
                            actionExecutor.executeAction(Action(
                                type = ActionType.GLOBAL_ACTION,
                                globalActionType = "home"
                            ))
                            return@launch
                        }
                    } else {
                        Log.w(TAG, "TIME IN not found after login. Looking for navigation...")
                        // Look for other nav elements
                        val navTexts = listOf("Attendance", "Mark Attendance", "Check In", "Dashboard", "HOME")
                        for (navText in navTexts) {
                            val navNode = service.findNodeByTextInApp(navText, beehivePackage)
                                ?: service.findNodeByText(navText)
                            if (navNode != null) {
                                Log.i(TAG, "Found nav: $navText, clicking...")
                                service.performClick(navNode)
                                delay(3000)
                                afterLoginText = service.getScreenText()
                                Log.i(TAG, "After nav click, screen: ${afterLoginText.take(300)}")
                                // Check for TIME IN now
                                val timeInNow = service.findNodeByTextInApp("TIME IN", beehivePackage)
                                    ?: service.findNodeByText("TIME IN")
                                if (timeInNow != null) {
                                    Log.i(TAG, "Found TIME IN after navigation!")
                                    service.performClick(timeInNow)
                                    delay(2000)
                                    val success = handleTimeInPopups()
                                    if (success) {
                                        Log.i(TAG, "Time-in successful after navigation!")
                                        variableStore.setTimedInToday(true)
                                        val location = triggerManagerProvider.get().getLastKnownLocation()
                                        if (location != null) {
                                            variableStore.setTimeInLocation(location.first, location.second)
                                        }
                                        val workHours = variableStore.getWorkDurationHours()
                                        scheduleTimeOutPrompt(workHours)
                                        showStatusNotification("Time-In Recorded", "Work hours started. Duration: ${workHours}h")
                                        actionExecutor.executeAction(Action(
                                            type = ActionType.GLOBAL_ACTION,
                                            globalActionType = "home"
                                        ))
                                        return@launch
                                    }
                                }
                                break
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "SIGN IN not found in Beehive")
                }

                // If we get here, something went wrong - refresh location and retry
                Log.w(TAG, "Time-in attempt $attempts failed, refreshing and retrying...")
                actionExecutor.executeAction(Action(type = ActionType.REFRESH_LOCATION))
                delay(5000) // Wait before retry
            }

            if (attempts >= maxAttempts) {
                showStatusNotification("Time-In Failed", "Could not complete time-in after $maxAttempts attempts")
            }
        }
    }

    private suspend fun handleTimeInPopups(): Boolean {
        val service = AutoMateAccessibilityService.instance ?: return false
        var success = false
        var attempts = 0

        while (attempts < 30 && !success) {
            attempts++

            val screenText = service.getScreenText()

            // Check for success indicators
            if (screenText.contains("Time In", ignoreCase = true) ||
                screenText.contains("Success", ignoreCase = true) ||
                screenText.contains("Recorded", ignoreCase = true) ||
                screenText.contains("Time In recorded", ignoreCase = true)) {

                // Click OK to confirm
                val okButton = service.findNodeByText("OK")
                if (okButton != null) {
                    service.performClick(okButton)
                    delay(500)
                }
                success = true
                break
            }

            // Handle location error popups - close them and refresh
            val locationError = screenText.contains("Location", ignoreCase = true) &&
                    (screenText.contains("Error", ignoreCase = true) ||
                     screenText.contains("error", ignoreCase = true) ||
                     screenText.contains("fail", ignoreCase = true))

            if (locationError) {
                Log.w(TAG, "Location error detected, closing and refreshing")
                // Close the error popup
                val dismissButton = service.findNodeByText("OK") ?: service.findNodeByText("CLOSE")
                if (dismissButton != null) {
                    service.performClick(dismissButton)
                    delay(500)
                }
                // Refresh location
                actionExecutor.executeAction(Action(type = ActionType.REFRESH_LOCATION))
                delay(1000) // Wait 1 second for location refresh
                continue
            }

            // Handle update/permission popups
            val updatePopup = screenText.contains("Update", ignoreCase = true) ||
                    screenText.contains("Permission", ignoreCase = true) ||
                    screenText.contains("Allow", ignoreCase = true)

            if (updatePopup) {
                Log.i(TAG, "Update/permission popup detected, dismissing")
                val dismissButton = service.findNodeByText("OK") ?: service.findNodeByText("ALLOW")
                if (dismissButton != null) {
                    service.performClick(dismissButton)
                    delay(500)
                }
                continue
            }

            // Handle any other popups
            val popupTexts = listOf("OK", "CLOSE", "Cancel", "Dismiss", "Got it")
            for (text in popupTexts) {
                val node = service.findNodeByText(text)
                if (node != null && node.isClickable) {
                    Log.i(TAG, "Dismissing popup: $text")
                    service.performClick(node)
                    delay(500)
                    break
                }
            }

            delay(1000)
        }

        return success
    }

    // === Smart Time-Out Flow ===

    private fun scheduleTimeOutPrompt(workHours: Float) {
        timeOutJob?.cancel()
        timeOutJob = scope.launch {
            // Wait for work hours to complete
            delay((workHours * 60 * 60 * 1000).toLong())

            Log.i(TAG, "Work hours complete, prompting for time-out")
            sendTimeOutPrompt()
        }
    }

    fun sendTimeOutPrompt() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "TIME_OUT_PROMPT"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 200, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Watch my location" action
        val watchIntent = Intent(context, com.automate.geofence.GeofenceBroadcastReceiver::class.java).apply {
            action = "TIME_OUT_RESPONSE"
            putExtra("watch_location", true)
        }
        val watchPending = PendingIntent.getBroadcast(
            context, 201, watchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "I'm leaving now" action
        val leaveIntent = Intent(context, com.automate.geofence.GeofenceBroadcastReceiver::class.java).apply {
            action = "TIME_OUT_RESPONSE"
            putExtra("watch_location", false)
        }
        val leavePending = PendingIntent.getBroadcast(
            context, 202, leaveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, AutoMateApp.CHANNEL_MORNING_PROMPT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Work hours complete!")
            .setContentText("Want me to keep watching your location?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_mylocation, "Yes, watch me", watchPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "No, I'm leaving", leavePending)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId++, notification)
    }

    fun handleTimeOutResponse(watchLocation: Boolean) {
        scope.launch {
            if (watchLocation) {
                Log.i(TAG, "User wants location watching - enabling exit watch")
                variableStore.setExitWatch(true)
                showStatusNotification("Watching Location", "Will time-out when you leave the office")
            } else {
                Log.i(TAG, "User is leaving now - performing time-out")
                performTimeOut()
            }
        }
    }

    fun performTimeOut() {
        timeOutJob?.cancel()
        timeOutJob = scope.launch {
            Log.i(TAG, "Performing time-out")
            variableStore.setExitWatch(false)

            val service = AutoMateAccessibilityService.instance
            if (service == null) {
                showStatusNotification("Error", "Accessibility service not running")
                return@launch
            }

            var attempts = 0
            val maxAttempts = 30

            while (attempts < maxAttempts && isActive) {
                attempts++
                Log.i(TAG, "Time-out attempt $attempts/$maxAttempts")

                // Step 1: Launch Beehive HRMS
                actionExecutor.executeAction(Action(
                    type = ActionType.LAUNCH_APP,
                    packageName = "com.app.beehivehrms"
                ))
                delay(3000)

                // Step 2: Try to click TIME OUT
                val timeOutNode = service.findNodeByText("TIME OUT")
                if (timeOutNode != null) {
                    service.performClick(timeOutNode)
                    delay(2000)

                    // Step 3: Handle popups
                    val success = handleTimeOutPopups()
                    if (success) {
                        Log.i(TAG, "Time-out successful!")
                        variableStore.setTimedInToday(false)
                        variableStore.setArmed(false)

                        showStatusNotification("Time-Out Recorded", "Have a good evening!")

                        // Close the app
                        actionExecutor.executeAction(Action(
                            type = ActionType.GLOBAL_ACTION,
                            globalActionType = "home"
                        ))

                        // Disable geofences for today
                        triggerManagerProvider.get().disableGeofences()
                        return@launch
                    }
                }

                Log.w(TAG, "Time-out attempt $attempts failed, retrying...")
                delay(5000)
            }

            if (attempts >= maxAttempts) {
                showStatusNotification("Time-Out Failed", "Could not complete time-out after $maxAttempts attempts")
            }
        }
    }

    private suspend fun handleTimeOutPopups(): Boolean {
        val service = AutoMateAccessibilityService.instance ?: return false
        var success = false
        var attempts = 0

        while (attempts < 30 && !success) {
            attempts++
            val screenText = service.getScreenText()

            if (screenText.contains("Time Out", ignoreCase = true) ||
                screenText.contains("Success", ignoreCase = true) ||
                screenText.contains("Recorded", ignoreCase = true)) {
                val okButton = service.findNodeByText("OK")
                if (okButton != null) {
                    service.performClick(okButton)
                    delay(500)
                }
                success = true
                break
            }

            // Handle popups same as time-in
            val popupTexts = listOf("OK", "CLOSE", "Cancel", "Dismiss", "Got it")
            for (text in popupTexts) {
                val node = service.findNodeByText(text)
                if (node != null && node.isClickable) {
                    service.performClick(node)
                    delay(500)
                    break
                }
            }

            delay(1000)
        }

        return success
    }

    // === Generic Task Execution ===

    suspend fun runTask(task: Task): Boolean {
        Log.i(TAG, "Running task: ${task.name}")

        // Check constraints
        for (constraint in task.constraints) {
            if (!checkConstraint(constraint)) {
                Log.w(TAG, "Constraint not met: ${constraint.variableName}")
                return false
            }
        }

        // Execute actions
        val result = actionExecutor.executeActions(task.actions)

        // Update last run
        // repository.updateLastRun(task.id, if (result) "success" else "failed")

        return result
    }

    private suspend fun checkConstraint(constraint: Constraint): Boolean {
        val value = variableStore.getVariable(constraint.variableName) ?: return false
        return when (constraint.operator) {
            ConstraintOperator.EQUALS -> value == constraint.value
            ConstraintOperator.NOT_EQUALS -> value != constraint.value
            ConstraintOperator.GREATER_THAN -> (value.toDoubleOrNull() ?: 0.0) > (constraint.value.toDoubleOrNull() ?: 0.0)
            ConstraintOperator.LESS_THAN -> (value.toDoubleOrNull() ?: 0.0) < (constraint.value.toDoubleOrNull() ?: 0.0)
            ConstraintOperator.CONTAINS -> value.contains(constraint.value, ignoreCase = true)
        }
    }

    private fun showStatusNotification(title: String, text: String) {
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
    }

    fun cancelAllJobs() {
        timeInJob?.cancel()
        timeOutJob?.cancel()
        popupHandlerJob?.cancel()
    }

    private fun getForegroundApp(): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities"))
            val result = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val match = Regex("mResumedActivity=.*?u0 (\\S+?)\\b").find(result)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "TaskRunner"
    }
}
