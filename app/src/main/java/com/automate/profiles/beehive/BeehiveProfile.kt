package com.automate.profiles.beehive

import com.automate.domain.model.Action
import com.automate.domain.model.ActionType
import com.automate.domain.model.Task
import com.automate.domain.model.Trigger
import com.automate.domain.model.TriggerType

object BeehiveProfile {
    const val PACKAGE_NAME = "com.app.beehivehrms"
    const val APP_NAME = "Beehive HRMS"

    // === Pre-built Tasks ===

    fun createTimeInTask(geofenceId: Long = 0): Task {
        return Task(
            name = "Beehive Auto Time-In",
            trigger = Trigger(
                type = TriggerType.GEOFENCE_ENTER,
                locationId = geofenceId,
                radiusMeters = 200f
            ),
            constraints = listOf(
                com.automate.domain.model.Constraint("armed", com.automate.domain.model.ConstraintOperator.EQUALS, "true"),
                com.automate.domain.model.Constraint("timed_in_today", com.automate.domain.model.ConstraintOperator.NOT_EQUALS, "true")
            ),
            actions = createTimeInActions()
        )
    }

    fun createTimeOutTask(geofenceId: Long = 0): Task {
        return Task(
            name = "Beehive Auto Time-Out",
            trigger = Trigger(
                type = TriggerType.DISTANCE_FROM_LOCATION,
                locationId = geofenceId,
                distanceMeters = 150f
            ),
            constraints = listOf(
                com.automate.domain.model.Constraint("exit_watch", com.automate.domain.model.ConstraintOperator.EQUALS, "true"),
                com.automate.domain.model.Constraint("timed_in_today", com.automate.domain.model.ConstraintOperator.EQUALS, "true")
            ),
            actions = createTimeOutActions()
        )
    }

    fun createArmingTask(): Task {
        return Task(
            name = "Beehive Arm Check-in",
            trigger = Trigger(
                type = TriggerType.TIME_SCHEDULE,
                hour = 7,
                minute = 30,
                daysOfWeek = listOf(1, 2, 3, 4, 5) // Mon-Fri
            ),
            actions = createArmingActions()
        )
    }

    // === Action Sequences ===

    fun createTimeInActions(): List<Action> {
        return listOf(
            // Step 1: Launch Beehive HRMS
            Action(
                type = ActionType.LAUNCH_APP,
                packageName = PACKAGE_NAME
            ),
            Action(type = ActionType.WAIT, seconds = 3),

            // Step 2: Click SIGN IN if visible
            Action(
                type = ActionType.CLICK_ELEMENT,
                target = "SIGN IN",
                retryOnFailure = false
            ),
            Action(type = ActionType.WAIT, seconds = 2),

            // Step 3: Click TIME IN
            Action(
                type = ActionType.CLICK_ELEMENT,
                target = "TIME IN",
                retryOnFailure = false
            ),
            Action(type = ActionType.WAIT, seconds = 2),

            // Step 4: Smart popup handling - keep trying until success
            Action(
                type = ActionType.POPUP_HANDLER,
                maxRetries = 60,
                retryDelayMs = 2000,
                popupDismissTexts = listOf("OK", "CLOSE", "Allow", "ALLOW", "Got it", "DISMISS"),
                successIndicator = "Time In recorded"
            ),

            // Step 5: Close the app
            Action(
                type = ActionType.GLOBAL_ACTION,
                globalActionType = "home"
            )
        )
    }

    fun createTimeOutActions(): List<Action> {
        return listOf(
            // Step 1: Launch Beehive HRMS
            Action(
                type = ActionType.LAUNCH_APP,
                packageName = PACKAGE_NAME
            ),
            Action(type = ActionType.WAIT, seconds = 3),

            // Step 2: Click TIME OUT
            Action(
                type = ActionType.CLICK_ELEMENT,
                target = "TIME OUT",
                retryOnFailure = false
            ),
            Action(type = ActionType.WAIT, seconds = 2),

            // Step 3: Smart popup handling
            Action(
                type = ActionType.POPUP_HANDLER,
                maxRetries = 30,
                retryDelayMs = 2000,
                popupDismissTexts = listOf("OK", "CLOSE", "Allow", "ALLOW", "Got it", "DISMISS"),
                successIndicator = "Time Out recorded"
            ),

            // Step 4: Close the app
            Action(
                type = ActionType.GLOBAL_ACTION,
                globalActionType = "home"
            ),

            // Step 5: Reset variables
            Action(
                type = ActionType.SET_VARIABLE,
                variableName = "timed_in_today",
                variableValue = "false"
            ),
            Action(
                type = ActionType.SET_VARIABLE,
                variableName = "exit_watch",
                variableValue = "false"
            ),
            Action(
                type = ActionType.SET_VARIABLE,
                variableName = "armed",
                variableValue = "false"
            ),

            // Step 6: Show notification
            Action(
                type = ActionType.SHOW_NOTIFICATION,
                title = "Time-Out Recorded",
                message = "Have a good evening!"
            )
        )
    }

    fun createArmingActions(): List<Action> {
        return listOf(
            // Show morning prompt notification
            Action(
                type = ActionType.SHOW_NOTIFICATION,
                title = "AutoMate",
                message = "Going to work today?"
            ),

            // Wait for user response via notification action buttons
            // The response is handled by GeofenceBroadcastReceiver
        )
    }

    // === Helper: Dismiss location error and retry ===

    fun createLocationErrorRecovery(): List<Action> {
        return listOf(
            // Close any error popup
            Action(
                type = ActionType.CLICK_ELEMENT,
                target = "OK",
                retryOnFailure = false
            ),
            Action(type = ActionType.WAIT, seconds = 1),

            // Refresh location
            Action(type = ActionType.REFRESH_LOCATION),
            Action(type = ActionType.WAIT, seconds = 2)
        )
    }
}
