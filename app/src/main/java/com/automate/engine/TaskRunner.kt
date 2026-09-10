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
    private val triggerManagerProvider: dagger.Lazy<TriggerManager>,
    private val metroTicketFlow: com.automate.profiles.metro.MetroTicketFlow
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var notificationId = 2000
    private var timeInJob: Job? = null
    private var timeOutJob: Job? = null
    private var popupHandlerJob: Job? = null

    companion object {
        private const val TAG = "TaskRunner"
        private const val BEEHIVE_PACKAGE = "com.app.beehivehrms"

        // Beehive detection keywords — any of these on screen means Beehive is loaded
        private val BEEHIVE_INDICATORS = listOf(
            "E0099", "Remember Me", "Forgot Password", "App Ver",
            "SIGN IN", "Password", "Employee", "Login",
            "beehive", "Beehive", "HRMS"
        )
    }

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

        val yesIntent = Intent(context, com.automate.geofence.GeofenceBroadcastReceiver::class.java).apply {
            action = "MORNING_RESPONSE"
            putExtra("going_to_work", true)
        }
        val yesPending = PendingIntent.getBroadcast(
            context, 101, yesIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
                triggerManagerProvider.get().startLocationTracking()
                showStatusNotification("Going to work", "Monitoring your location for check-in")

                // Schedule metro prompt after configurable delay (default 1 hour)
                scheduleMetroPrompt()
            } else {
                Log.i(TAG, "User is staying home - disabling everything")
                variableStore.setArmed(false)
                variableStore.setTimedInToday(false)
                variableStore.setExitWatch(false)
                triggerManagerProvider.get().disableGeofences()
                triggerManagerProvider.get().stopLocationTracking()
                showStatusNotification("Staying home", "AutoMate is off for today")
            }
        }
    }

    // === Metro Prompt (ask after morning "Yes") ===

    private fun scheduleMetroPrompt() {
        scope.launch {
            val delayMinutes = variableStore.getStringVariable("metro_prompt_delay_minutes").toIntOrNull() ?: 60
            Log.i(TAG, "Scheduling metro prompt in $delayMinutes minutes")
            delay(delayMinutes * 60 * 1000L)

            // Only ask if user is still going to work
            val goingToWork = variableStore.getBooleanVariable("going_to_work")
            if (!goingToWork) return@launch

            sendMetroPrompt()
        }
    }

    fun sendMetroPrompt() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "METRO_PROMPT"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 300, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val yesIntent = Intent(context, com.automate.geofence.GeofenceBroadcastReceiver::class.java).apply {
            action = "METRO_PROMPT_RESPONSE"
            putExtra("need_metro", true)
        }
        val yesPending = PendingIntent.getBroadcast(
            context, 301, yesIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val noIntent = Intent(context, com.automate.geofence.GeofenceBroadcastReceiver::class.java).apply {
            action = "METRO_PROMPT_RESPONSE"
            putExtra("need_metro", false)
        }
        val noPending = PendingIntent.getBroadcast(
            context, 302, noIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, AutoMateApp.CHANNEL_MORNING_PROMPT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Metro Ticket")
            .setContentText("Need a metro ticket today?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_send, "Yes, book ticket", yesPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "No, skip", noPending)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId++, notification)
        Log.i(TAG, "Metro prompt sent")
    }

    fun handleMetroPromptResponse(needMetro: Boolean) {
        scope.launch {
            if (needMetro) {
                Log.i(TAG, "User needs metro ticket - starting booking flow")
                showStatusNotification("Metro Ticket", "Starting booking flow...")
                metroTicketFlow.start()
            } else {
                Log.i(TAG, "User skipped metro ticket")
                showStatusNotification("Skipped", "No metro ticket today")
            }
        }
    }

    // === Shared: Launch Beehive and wait for it ===

    private suspend fun launchBeehiveAndDetect(service: AutoMateAccessibilityService): Boolean {
        // Force-stop first for clean state
        try {
            Runtime.getRuntime().exec(arrayOf("am", "force-stop", BEEHIVE_PACKAGE)).waitFor()
        } catch (_: Exception) {}
        delay(500)

        // Go home to clear foreground
        service.performGlobalHome()
        delay(500)

        // Launch Beehive
        try {
            Runtime.getRuntime().exec(arrayOf(
                "am", "start", "-n", "$BEEHIVE_PACKAGE/com.tns.NativeScriptActivity"
            )).waitFor()
            Log.i(TAG, "Launched Beehive")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Beehive", e)
            return false
        }

        // Wait only 1 second — fast action, smart detection loop takes over
        delay(1000)

        // Poll for Beehive content (up to 10 attempts, 1s each = max 10s worst case)
        for (i in 1..10) {
            val screenText = service.getScreenText()
            Log.i(TAG, "Beehive detect poll $i: ${screenText.take(200)}")

            val isBeehiveVisible = BEEHIVE_INDICATORS.any { indicator ->
                screenText.contains(indicator, ignoreCase = true)
            }
            if (isBeehiveVisible) {
                Log.i(TAG, "Beehive detected on screen after $i polls")
                return true
            }
            delay(1000)
        }

        Log.w(TAG, "Beehive not detected after 10 polls")
        return false
    }

    // === Shared: Click SIGN IN with fallback ===

    private suspend fun clickSignIn(service: AutoMateAccessibilityService): Boolean {
        val signInNode = service.findNodeByTextInApp("SIGN IN", BEEHIVE_PACKAGE)
            ?: service.findNodeByText("SIGN IN")

        if (signInNode != null) {
            Log.i(TAG, "Found SIGN IN, clicking...")
            service.performClick(signInNode)
            delay(1000)

            // Check if page changed
            val afterText = service.getScreenText()
            if (afterText.contains("SIGN IN") && afterText.contains("Remember Me")) {
                // Still on login — try coordinate click
                Log.w(TAG, "Still on login, trying coordinate click")
                val bounds = android.graphics.Rect()
                signInNode.getBoundsInScreen(bounds)
                service.tapAtCoordinates(bounds.centerX(), bounds.centerY())
                delay(1000)
            }
            return true
        }

        Log.w(TAG, "SIGN IN not found")
        return false
    }

    // === Shared: Find and click a target button with navigation fallback ===

    private suspend fun findAndClickTarget(
        service: AutoMateAccessibilityService,
        target: String,
        vararg fallbackNavTexts: String
    ): Boolean {
        // Direct search
        var node = service.findNodeByTextInApp(target, BEEHIVE_PACKAGE)
            ?: service.findNodeByText(target)
        if (node != null) {
            Log.i(TAG, "Found $target, clicking...")
            service.performClick(node)
            return true
        }

        // Try navigation fallback
        for (navText in fallbackNavTexts) {
            val navNode = service.findNodeByTextInApp(navText, BEEHIVE_PACKAGE)
                ?: service.findNodeByText(navText)
            if (navNode != null) {
                Log.i(TAG, "Found nav: $navText, clicking to reach $target...")
                service.performClick(navNode)
                delay(1000)

                node = service.findNodeByTextInApp(target, BEEHIVE_PACKAGE)
                    ?: service.findNodeByText(target)
                if (node != null) {
                    Log.i(TAG, "Found $target after navigation")
                    service.performClick(node)
                    return true
                }
                break
            }
        }

        return false
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
            val maxAttempts = 20

            while (attempts < maxAttempts && isActive) {
                attempts++
                Log.i(TAG, "Time-in attempt $attempts/$maxAttempts")

                // Step 1-4: Launch Beehive and wait for it
                val beehiveReady = launchBeehiveAndDetect(service)
                if (!beehiveReady) {
                    Log.w(TAG, "Beehive not ready, retrying...")
                    delay(1000)
                    continue
                }

                // Step 5: Click SIGN IN
                val signedIn = clickSignIn(service)
                if (!signedIn) {
                    Log.w(TAG, "SIGN IN failed, retrying...")
                    delay(1000)
                    continue
                }

                delay(1000) // Wait for page transition

                // Check if we're past login
                val afterLoginText = service.getScreenText()
                Log.i(TAG, "After login: ${afterLoginText.take(300)}")

                // Step 6: Find and click TIME IN (with nav fallbacks)
                val clicked = findAndClickTarget(
                    service, "TIME IN",
                    "Attendance", "Mark Attendance", "Check In", "Dashboard", "HOME"
                )

                if (clicked) {
                    delay(1000)

                    // Step 7: Handle popups
                    val success = handleTimeInPopups()
                    if (success) {
                        Log.i(TAG, "Time-in successful!")
                        variableStore.setTimedInToday(true)

                        // Force-fetch fresh GPS location
                        val location = triggerManagerProvider.get().getLastKnownLocation()
                        if (location != null) {
                            variableStore.setTimeInLocation(location.first, location.second)
                        }

                        // Schedule time-out: 7hr prompt first, 8.5hr prompt again
                        scheduleTimeOutPrompts()

                        showStatusNotification(
                            "Time-In Recorded",
                            "Work hours started. You'll be asked about leaving at 7h."
                        )
                        actionExecutor.executeAction(Action(
                            type = ActionType.GLOBAL_ACTION,
                            globalActionType = "home"
                        ))

                        // Start aggressive location tracking for exit watch
                        triggerManagerProvider.get().startLocationTracking()
                        return@launch
                    }
                }

                Log.w(TAG, "Time-in attempt $attempts failed, retrying...")
                delay(1000)
            }

            if (attempts >= maxAttempts) {
                showStatusNotification("Time-In Failed", "Could not complete time-in after $maxAttempts attempts")
            }
        }
    }

    // === Time-In Popup Handler ===

    private suspend fun handleTimeInPopups(): Boolean {
        val service = AutoMateAccessibilityService.instance ?: return false
        var attempts = 0

        while (attempts < 30) {
            attempts++
            val screenText = service.getScreenText()

            // SUCCESS: Only trigger on compound indicators
            // Must have "recorded" OR ("success" AND NOT just the TIME IN button alone)
            val hasRecorded = screenText.contains("recorded", ignoreCase = true)
            val hasTimeInSuccess = screenText.contains("Time In", ignoreCase = true) &&
                    (screenText.contains("success", ignoreCase = true) ||
                     screenText.contains("recorded", ignoreCase = true))
            val hasGenericSuccess = screenText.contains("Success", ignoreCase = true) ||
                    screenText.contains("Recorded", ignoreCase = true)

            if (hasRecorded || hasTimeInSuccess || hasGenericSuccess) {
                Log.i(TAG, "Time-In success detected at attempt $attempts")
                val okButton = service.findNodeByText("OK") ?: service.findNodeByText("Ok")
                if (okButton != null) {
                    service.performClick(okButton)
                    delay(500)
                }
                return true
            }

            // LOCATION ERROR: dismiss and force refresh GPS
            val locationError = screenText.contains("Location", ignoreCase = true) &&
                    (screenText.contains("Error", ignoreCase = true) ||
                     screenText.contains("error", ignoreCase = true) ||
                     screenText.contains("fail", ignoreCase = true) ||
                     screenText.contains("unable", ignoreCase = true))

            if (locationError) {
                Log.w(TAG, "Location error detected, dismissing and refreshing GPS")
                val dismissButton = service.findNodeByText("OK") ?: service.findNodeByText("CLOSE")
                if (dismissButton != null) {
                    service.performClick(dismissButton)
                    delay(500)
                }
                // Force immediate GPS fix
                triggerManagerProvider.get().forceLocationUpdate()
                delay(3000) // Wait 3s for GPS fix
                continue
            }

            // UPDATE/PERMISSION popups
            val updatePopup = screenText.contains("Update", ignoreCase = true) ||
                    screenText.contains("Permission", ignoreCase = true) ||
                    screenText.contains("Allow", ignoreCase = true)

            if (updatePopup) {
                Log.i(TAG, "Update/permission popup detected, dismissing")
                val dismissButton = service.findNodeByText("OK")
                    ?: service.findNodeByText("ALLOW")
                    ?: service.findNodeByText("Allow")
                if (dismissButton != null) {
                    service.performClick(dismissButton)
                    delay(500)
                }
                continue
            }

            // GENERIC POPUP: try common button texts
            val popupTexts = listOf("OK", "CLOSE", "Close", "Cancel", "Dismiss", "Got it", "GOT IT")
            for (text in popupTexts) {
                val node = service.findNodeByText(text)
                if (node != null && node.isClickable) {
                    Log.i(TAG, "Dismissing generic popup: $text")
                    service.performClick(node)
                    delay(500)
                    break
                }
            }

            delay(1000)
        }

        return false
    }

    // === Time-Out Prompt Scheduling (7h ask, 8.5h ask again) ===

    private fun scheduleTimeOutPrompts() {
        timeOutJob?.cancel()
        timeOutJob = scope.launch {
            // First prompt at 7 hours — ask if about to leave
            val sevenHours = 7L * 60 * 60 * 1000
            delay(sevenHours)

            Log.i(TAG, "7 hours elapsed, sending first time-out prompt")
            sendTimeOutPrompt(firstPrompt = true)

            // Second prompt at 8.5 hours total (1.5h after first prompt)
            val oneAndHalfHours = 1L * 60 * 60 * 1000 + 30 * 60 * 1000
            delay(oneAndHalfHours)

            // If still timed in (user said "watch me" at 7h), send second prompt
            val stillTimedIn = variableStore.isTimedInToday()
            if (stillTimedIn) {
                Log.i(TAG, "8.5 hours elapsed, sending second time-out prompt")
                sendTimeOutPrompt(firstPrompt = false)
            }
        }
    }

    fun sendTimeOutPrompt(firstPrompt: Boolean = false) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "TIME_OUT_PROMPT"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 200, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val watchIntent = Intent(context, com.automate.geofence.GeofenceBroadcastReceiver::class.java).apply {
            action = "TIME_OUT_RESPONSE"
            putExtra("watch_location", true)
        }
        val watchPending = PendingIntent.getBroadcast(
            context, 201, watchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val leaveIntent = Intent(context, com.automate.geofence.GeofenceBroadcastReceiver::class.java).apply {
            action = "TIME_OUT_RESPONSE"
            putExtra("watch_location", false)
        }
        val leavePending = PendingIntent.getBroadcast(
            context, 202, leaveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (firstPrompt) "About to leave?" else "Time to check out!"
        val body = if (firstPrompt) "7h done. Leaving soon?" else "8.5h done. Ready to time-out?"

        val notification = NotificationCompat.Builder(context, AutoMateApp.CHANNEL_MORNING_PROMPT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
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
                triggerManagerProvider.get().startLocationTracking()
                showStatusNotification("Watching Location", "Will time-out when you leave the office")
            } else {
                Log.i(TAG, "User is leaving now - performing time-out")
                performTimeOut()
            }
        }
    }

    // === Smart Time-Out Flow (mirrors time-in with login) ===

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
            val maxAttempts = 20

            while (attempts < maxAttempts && isActive) {
                attempts++
                Log.i(TAG, "Time-out attempt $attempts/$maxAttempts")

                // Step 1-4: Launch Beehive and detect (same as time-in)
                val beehiveReady = launchBeehiveAndDetect(service)
                if (!beehiveReady) {
                    Log.w(TAG, "Beehive not ready for time-out, retrying...")
                    delay(1000)
                    continue
                }

                // Step 5: Click SIGN IN if needed (session may have expired)
                val screenText = service.getScreenText()
                val needsLogin = screenText.contains("SIGN IN", ignoreCase = true) &&
                        screenText.contains("Password", ignoreCase = true)

                if (needsLogin) {
                    Log.i(TAG, "Login required for time-out, signing in...")
                    clickSignIn(service)
                    delay(1000)
                }

                // Step 6: Find and click TIME OUT
                val clicked = findAndClickTarget(
                    service, "TIME OUT",
                    "Attendance", "Mark Attendance", "Dashboard", "HOME"
                )

                if (clicked) {
                    delay(1000)

                    // Step 7: Handle popups
                    val success = handleTimeOutPopups()
                    if (success) {
                        Log.i(TAG, "Time-out successful!")
                        variableStore.setTimedInToday(false)
                        variableStore.setArmed(false)

                        showStatusNotification("Time-Out Recorded", "Have a good evening!")

                        actionExecutor.executeAction(Action(
                            type = ActionType.GLOBAL_ACTION,
                            globalActionType = "home"
                        ))

                        // Disable geofences and stop tracking
                        triggerManagerProvider.get().disableGeofences()
                        triggerManagerProvider.get().stopLocationTracking()
                        return@launch
                    }
                }

                Log.w(TAG, "Time-out attempt $attempts failed, retrying...")
                delay(1000)
            }

            if (attempts >= maxAttempts) {
                showStatusNotification("Time-Out Failed", "Could not complete time-out after $maxAttempts attempts")
            }
        }
    }

    // === Time-Out Popup Handler ===

    private suspend fun handleTimeOutPopups(): Boolean {
        val service = AutoMateAccessibilityService.instance ?: return false
        var attempts = 0

        while (attempts < 30) {
            attempts++
            val screenText = service.getScreenText()

            // SUCCESS detection
            val hasRecorded = screenText.contains("recorded", ignoreCase = true)
            val hasTimeOutSuccess = screenText.contains("Time Out", ignoreCase = true) &&
                    (screenText.contains("success", ignoreCase = true) ||
                     screenText.contains("recorded", ignoreCase = true))
            val hasGenericSuccess = screenText.contains("Success", ignoreCase = true) ||
                    screenText.contains("Recorded", ignoreCase = true)

            if (hasRecorded || hasTimeOutSuccess || hasGenericSuccess) {
                Log.i(TAG, "Time-Out success detected at attempt $attempts")
                val okButton = service.findNodeByText("OK") ?: service.findNodeByText("Ok")
                if (okButton != null) {
                    service.performClick(okButton)
                    delay(500)
                }
                return true
            }

            // LOCATION ERROR handling (same as time-in)
            val locationError = screenText.contains("Location", ignoreCase = true) &&
                    (screenText.contains("Error", ignoreCase = true) ||
                     screenText.contains("error", ignoreCase = true) ||
                     screenText.contains("fail", ignoreCase = true) ||
                     screenText.contains("unable", ignoreCase = true))

            if (locationError) {
                Log.w(TAG, "Time-out: location error, dismissing and refreshing")
                val dismissButton = service.findNodeByText("OK") ?: service.findNodeByText("CLOSE")
                if (dismissButton != null) {
                    service.performClick(dismissButton)
                    delay(500)
                }
                triggerManagerProvider.get().forceLocationUpdate()
                delay(3000)
                continue
            }

            // UPDATE/PERMISSION popups
            val updatePopup = screenText.contains("Update", ignoreCase = true) ||
                    screenText.contains("Permission", ignoreCase = true) ||
                    screenText.contains("Allow", ignoreCase = true)

            if (updatePopup) {
                Log.i(TAG, "Time-out: update/permission popup, dismissing")
                val dismissButton = service.findNodeByText("OK")
                    ?: service.findNodeByText("ALLOW")
                    ?: service.findNodeByText("Allow")
                if (dismissButton != null) {
                    service.performClick(dismissButton)
                    delay(500)
                }
                continue
            }

            // GENERIC POPUP
            val popupTexts = listOf("OK", "CLOSE", "Close", "Cancel", "Dismiss", "Got it", "GOT IT")
            for (text in popupTexts) {
                val node = service.findNodeByText(text)
                if (node != null && node.isClickable) {
                    Log.i(TAG, "Time-out: dismissing popup: $text")
                    service.performClick(node)
                    delay(500)
                    break
                }
            }

            delay(1000)
        }

        return false
    }

    // === Generic Task Execution ===

    suspend fun runTask(task: Task): Boolean {
        Log.i(TAG, "Running task: ${task.name}")

        for (constraint in task.constraints) {
            if (!checkConstraint(constraint)) {
                Log.w(TAG, "Constraint not met: ${constraint.variableName}")
                return false
            }
        }

        val result = actionExecutor.executeActions(task.actions)
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

    // === Metro Ticket Flow ===

    fun startMetroTicketFlow() {
        metroTicketFlow.start()
    }

    fun cancelMetroTicketFlow() {
        metroTicketFlow.cancel()
    }

    fun cancelAllJobs() {
        timeInJob?.cancel()
        timeOutJob?.cancel()
        popupHandlerJob?.cancel()
    }
}
