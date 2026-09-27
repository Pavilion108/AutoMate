package com.automate.engine

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
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
        private const val BEEHIVE_ACTIVITY = "com.tns.NativeScriptActivity"
        private const val PERMISSION_CONTROLLER = "com.android.permissioncontroller"

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
                // CRITICAL: Start GPS tracking FIRST so location is available
                // when enableGeofences checks if user is already inside
                triggerManagerProvider.get().startLocationTracking()
                triggerManagerProvider.get().enableGeofences()
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
        // Clean slate. "am force-stop" via Runtime.exec() needs shell privileges and
        // silently no-ops for a normal app UID, so use the supported API instead.
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(BEEHIVE_PACKAGE)
            delay(500)
        } catch (e: Exception) {
            Log.w(TAG, "killBackgroundProcesses failed: ${e.message}")
        }

        // Launch through the accessibility service, which attaches a transient overlay
        // window so Android's background-activity-start check passes. No
        // performGlobalHome() here: going home first is what guaranteed we were in the
        // background when the launch was rejected.
        //
        // A full-screen intent was tried as a fallback and must not come back: on an
        // unlocked screen Android only raises it as a heads-up notification, and on this
        // MIUI build it left the notification shade stuck open and unusable.
        val launched = launchApp(BEEHIVE_PACKAGE, BEEHIVE_ACTIVITY, service) &&
            // Beehive is a NativeScript app and needs several seconds to cold start.
            waitForForeground(BEEHIVE_PACKAGE, 12_000)

        if (!launched) {
            Log.w(TAG, "Could not bring $BEEHIVE_PACKAGE to the foreground")
            showStatusNotification("Error", "Beehive is not installed or could not be opened.")
            return false
        }
        Log.i(TAG, "Launched Beehive")

        delay(2000)

        for (i in 1..15) {
            val screenText = service.getScreenText()
            val inBeehive = service.isPackageForeground(BEEHIVE_PACKAGE)
            Log.i(TAG, "Beehive detect poll $i (pkg=$inBeehive): ${screenText.take(160)}")

            val isBeehiveVisible = inBeehive && BEEHIVE_INDICATORS.any { indicator ->
                screenText.contains(indicator, ignoreCase = true)
            }
            if (isBeehiveVisible) {
                Log.i(TAG, "Beehive detected on screen after $i polls")
                return true
            }

            // On a cold start Beehive puts up Android runtime permission dialogs, which
            // take the foreground away from it. Grant them so the app can finish loading
            // instead of timing out on a screen that is not Beehive.
            if (dismissPermissionDialogs(service)) {
                Log.i(TAG, "Handled a permission dialog blocking Beehive")
                continue
            }
            delay(1000)
        }

        Log.w(TAG, "Beehive not detected after 15 polls")
        return false
    }

    /**
     * Grants any Android runtime permission dialog that is covering the target app.
     *
     * Beehive asks for location/storage/notification access on a cold start, and those
     * system dialogs steal the foreground, which made the flow believe Beehive never
     * opened. Only the system permission controller is considered, so this can never tap
     * something inside Beehive itself.
     */
    private fun dismissPermissionDialogs(service: AutoMateAccessibilityService): Boolean {
        val controller = try {
            service.windows.any { it.root?.packageName == "com.android.permissioncontroller" }
        } catch (_: Exception) {
            false
        }
        if (!controller) return false

        val labels = listOf(
            "ALLOW", "While using the app", "Only this time", "Allow", "OK", "Continue"
        )
        for (label in labels) {
            val node = service.findActionableNode(label, PERMISSION_CONTROLLER) ?: continue
            if (service.clickNodeRobustly(node)) return true
        }
        return false
    }

    /**
     * Starts [packageName] and reports whether it was dispatched.
     *
     * `Runtime.exec("am start")` is the trap here: from an app UID `am` needs privileges
     * it does not have, the child process exits non-zero, and because the old code never
     * checked the exit code it logged "Launched Beehive" and then polled a screen that
     * never changed. That is exactly the "app closes and nothing opens" symptom.
     */
    private fun launchApp(
        packageName: String,
        activityClass: String? = null,
        service: AutoMateAccessibilityService? = null
    ): Boolean {
        if (!isInstalled(packageName)) {
            Log.e(TAG, "$packageName is not installed")
            return false
        }

        return try {
            val intent = if (activityClass != null) {
                Intent().setComponent(ComponentName(packageName, activityClass))
            } else {
                context.packageManager.getLaunchIntentForPackage(packageName)
            } ?: return false

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            )

            // Preferred path: an AccessibilityService is on the background-activity-start
            // exemption list. Note this can still be refused on MIUI, and a refused
            // startActivity does NOT throw, so the caller must verify focus landed.
            if (service != null) {
                service.launchPackage(packageName, activityClass)
            } else {
                context.startActivity(intent)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startActivity($packageName) failed", e)
            false
        }
    }

    /**
     * Waits briefly for [packageName] to own the focused window.
     *
     * Dispatching an intent is not proof it worked: Android 10+ silently drops background
     * activity starts and `startActivity` returns normally, so without this check the flow
     * logs "Launched Beehive" and then polls whatever app happens to be in front.
     */
    private suspend fun waitForForeground(
        packageName: String,
        timeoutMs: Long
    ): Boolean {
        val steps = (timeoutMs / 500).coerceAtLeast(1).toInt()
        repeat(steps) {
            if (AutoMateAccessibilityService.isPackageForegroundStatic(packageName)) return true
            delay(500)
        }
        Log.w(
            TAG,
            "waitForForeground($packageName) timed out; windows=" +
                AutoMateAccessibilityService.visiblePackages()
        )
        return false
    }

    /** Authoritative install check. getLaunchIntentForPackage can be null under
     *  package-visibility rules even when the app is present. */
    private fun isInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    // === Shared: Click SIGN IN with fallback ===

    private suspend fun clickSignIn(service: AutoMateAccessibilityService): Boolean {
        // Exact match only, and never "Sign In as different user": clicking that switches
        // the employee and is the opposite of what an unattended clock-in should do.
        val signInNode = service.findActionableNode(
            "SIGN IN",
            BEEHIVE_PACKAGE,
            exact = true,
            excludeContaining = listOf("different user")
        ) ?: service.findActionableNode(
            "SIGN IN",
            BEEHIVE_PACKAGE,
            exact = false,
            excludeContaining = listOf("different user")
        )

        if (signInNode == null) {
            Log.w(TAG, "SIGN IN not found")
            return false
        }

        Log.i(TAG, "Found SIGN IN, clicking...")
        var clicked = service.clickNodeRobustly(signInNode)
        delay(1500)

        if (!clicked) {
            Log.w(TAG, "Node click did not register, tapping coordinates")
            val bounds = android.graphics.Rect()
            signInNode.getBoundsInScreen(bounds)
            service.tapAtCoordinates(bounds.centerX(), bounds.centerY())
            clicked = true
            delay(1500)
        }

        val afterText = service.getScreenText()
        if (afterText.contains("SIGN IN", ignoreCase = true) &&
            afterText.contains("Remember Me", ignoreCase = true)
        ) {
            Log.w(TAG, "Still on login screen after click")
            return false
        }
        return true
    }


    // === Smart Time-In Flow ===

    /** Resolves the bound service, waiting briefly for the framework to connect it. */
    private suspend fun awaitBoundService(): AutoMateAccessibilityService? {
        repeat(20) {
            AutoMateAccessibilityService.instance?.let { return it }
            delay(500)
        }
        return AutoMateAccessibilityService.instance
    }

    /**
     * Login screen detection. The dashboard greets the user and shows nav tabs, while the
     * login screen shows the employee id, the password field and a SIGN IN button.
     * "Sign In as different user" must never be treated as the login button, or the flow
     * would sign the user out.
     */
    private fun isLoginScreen(service: AutoMateAccessibilityService): Boolean {
        val text = service.getScreenText()
        if (text.contains("Hi,", ignoreCase = true)) return false
        if (text.contains("MY TEAM", ignoreCase = true)) return false
        val hasSignInButton = service.findActionableNode("SIGN IN", BEEHIVE_PACKAGE) != null
        val hasPasswordField = text.contains("Password", ignoreCase = true) ||
            text.contains("Remember Me", ignoreCase = true)
        return hasSignInButton || hasPasswordField
    }

    private fun isDashboardVisible(service: AutoMateAccessibilityService): Boolean {
        val text = service.getScreenText()
        return text.contains("Hi,", ignoreCase = true) ||
            text.contains("MY TEAM", ignoreCase = true) ||
            text.contains("TIME IN", ignoreCase = true) ||
            text.contains("TIME OUT", ignoreCase = true)
    }

    private suspend fun waitForScreen(
        service: AutoMateAccessibilityService,
        timeoutMs: Long,
        predicate: (String) -> Boolean
    ): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (predicate(service.getScreenText())) return true
            delay(1000)
            waited += 1000
        }
        return predicate(service.getScreenText())
    }

    suspend fun startTimeInFlow(accountId: Long = 0) {
        timeInJob?.cancel()
        timeInJob = scope.launch {
            Log.i(TAG, "Starting smart time-in flow")
            variableStore.setArmed(true)
            variableStore.setTimedInToday(false)

            val context = context
            if (!AutoMateAccessibilityService.isEnabled(context)) {
                showStatusNotification("Error", "Accessibility service not enabled. Open Settings > Accessibility > AutoMate.")
                AccessibilityNotifier.postActionRequired(context)
                return@launch
            }

            val service = awaitBoundService()
            if (service == null) {
                showStatusNotification("Error", "Accessibility service not bound. Try again in a moment.")
                return@launch
            }

            // Launch Beehive exactly once. The old loop relaunched it on every failure
            // (up to 20x), which is what made Beehive keep popping open on the user.
            if (!launchBeehiveAndDetect(service)) {
                showStatusNotification("Time-In Failed", "Could not open Beehive.")
                return@launch
            }

            // Sign in only when genuinely on the login screen.
            if (isLoginScreen(service)) {
                if (!clickSignIn(service)) {
                    showStatusNotification("Time-In Failed", "Could not sign in to Beehive.")
                    return@launch
                }
                val movedOn = waitForScreen(service, 25_000) { text ->
                    isDashboardVisible(service) || !text.contains("SIGN IN", ignoreCase = true)
                }
                if (!movedOn) {
                    Log.w(TAG, "Still on login screen after SIGN IN")
                    showStatusNotification("Time-In Failed", "Sign-in did not complete.")
                    return@launch
                }
            } else {
                Log.i(TAG, "Already signed in - skipping login step")
            }

            // Retry only the tap. A missed click must never restart the app.
            var clicked = false
            for (attempt in 1..3) {
                val target = service.findActionableNode("TIME IN", BEEHIVE_PACKAGE)
                if (target != null) {
                    Log.i(TAG, "Found TIME IN (attempt $attempt), clicking")
                    clicked = service.clickNodeRobustly(target)
                } else {
                    Log.w(TAG, "TIME IN not found (attempt $attempt); screen=${service.getScreenText().take(200)}")
                }
                if (clicked) break
                delay(2000)
            }

            if (!clicked) {
                showStatusNotification("Time-In Failed", "Could not find the TIME IN button.")
                return@launch
            }

            delay(1000)

            if (handleTimeInPopups(service)) {
                Log.i(TAG, "Time-in successful!")
                variableStore.setTimedInToday(true)

                val location = triggerManagerProvider.get().getLastKnownLocation()
                if (location != null) {
                    variableStore.setTimeInLocation(location.first, location.second)
                }

                scheduleTimeOutPrompts()

                showStatusNotification(
                    "Time-In Recorded",
                    "Work hours started. You'll be asked about leaving at 7h."
                )
                actionExecutor.executeAction(Action(
                    type = ActionType.GLOBAL_ACTION,
                    globalActionType = "home"
                ))

                triggerManagerProvider.get().startLocationTracking()
            } else {
                Log.w(TAG, "Time-In popup handling did not confirm success")
                showStatusNotification("Time-In", "Please confirm your attendance in Beehive.")
            }
        }
    }

    // === Time-In Popup Handler ===

    private suspend fun handleTimeInPopups(service: AutoMateAccessibilityService): Boolean {
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

    // === Time-Out Prompt Scheduling (uses work_hours setting) ===

    fun scheduleTimeOutPromptsForManualTimeIn() {
        scope.launch {
            variableStore.setTimedInToday(true)
            variableStore.setArmed(true)
            variableStore.setGoingToWork(true)
            scheduleTimeOutPrompts()
            val workHours = variableStore.getVariable("work_duration_hours")?.toFloatOrNull() ?: 7f
            Log.i(TAG, "Scheduled time-out prompts for manual time-in (${workHours}h)")
            showStatusNotification(
                "Time-Out Scheduled",
                "Will prompt you at ${workHours}h mark. Leaving soon?"
            )
        }
    }

    private fun scheduleTimeOutPrompts() {
        timeOutJob?.cancel()
        timeOutJob = scope.launch {
            val workHours = variableStore.getVariable("work_duration_hours")?.toFloatOrNull()?.toLong() ?: 7L
            val workHoursMs = workHours * 60 * 60 * 1000

            delay(workHoursMs)
            Log.i(TAG, "${workHours}h elapsed, sending first time-out prompt")
            sendTimeOutPrompt(firstPrompt = true)

            val oneAndHalfHours = 1L * 60 * 60 * 1000 + 30 * 60 * 1000
            delay(oneAndHalfHours)

            val stillTimedIn = variableStore.isTimedInToday()
            if (stillTimedIn) {
                Log.i(TAG, "1.5h after ${workHours}h mark, sending second time-out prompt")
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
        val body = if (firstPrompt) "Work hours done. Leaving soon?" else "Still here? Ready to time-out?"

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

    suspend fun performTimeOut() {
        timeOutJob?.cancel()
        timeOutJob = scope.launch {
            Log.i(TAG, "Performing time-out")
            variableStore.setExitWatch(false)

            val context = context
            if (!AutoMateAccessibilityService.isEnabled(context)) {
                showStatusNotification("Error", "Accessibility service not enabled. Open Settings > Accessibility > AutoMate.")
                AccessibilityNotifier.postActionRequired(context)
                return@launch
            }

            val service = awaitBoundService()
            if (service == null) {
                showStatusNotification("Error", "Accessibility service not bound. Try again in a moment.")
                return@launch
            }

            if (!launchBeehiveAndDetect(service)) {
                showStatusNotification("Time-Out Failed", "Could not open Beehive.")
                return@launch
            }

            if (isLoginScreen(service)) {
                Log.i(TAG, "Session expired, signing in for time-out...")
                if (!clickSignIn(service)) {
                    showStatusNotification("Time-Out Failed", "Could not sign in to Beehive.")
                    return@launch
                }
                waitForScreen(service, 25_000) { isDashboardVisible(service) }
            }

            var clicked = false
            for (attempt in 1..3) {
                val target = service.findActionableNode("TIME OUT", BEEHIVE_PACKAGE)
                if (target != null) {
                    Log.i(TAG, "Found TIME OUT (attempt $attempt), clicking")
                    clicked = service.clickNodeRobustly(target)
                } else {
                    Log.w(TAG, "TIME OUT not found (attempt $attempt); screen=${service.getScreenText().take(200)}")
                }
                if (clicked) break
                delay(2000)
            }

            if (!clicked) {
                showStatusNotification("Time-Out Failed", "Could not find the TIME OUT button.")
                return@launch
            }

            delay(1000)

            if (handleTimeOutPopups(service)) {
                Log.i(TAG, "Time-out successful!")
                variableStore.setTimedInToday(false)
                variableStore.setArmed(false)
                showStatusNotification("Time-Out Recorded", "Have a good evening!")
                actionExecutor.executeAction(Action(
                    type = ActionType.GLOBAL_ACTION,
                    globalActionType = "home"
                ))
                triggerManagerProvider.get().disableGeofences()
                triggerManagerProvider.get().stopLocationTracking()
            } else {
                Log.w(TAG, "Time-Out popup handling did not confirm success")
                showStatusNotification("Time-Out", "Please confirm your checkout in Beehive.")
            }
        }
    }

    // === Time-Out Popup Handler ===

    private suspend fun handleTimeOutPopups(service: AutoMateAccessibilityService): Boolean {
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
