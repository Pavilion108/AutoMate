package com.automate.profiles.whatsapp

import com.automate.domain.model.Action
import com.automate.domain.model.ActionType
import com.automate.domain.model.Task
import com.automate.domain.model.Trigger
import com.automate.domain.model.TriggerType

object WhatsAppProfile {
    const val PACKAGE_NAME = "com.whatsapp"
    const val APP_NAME = "WhatsApp"

    fun createAutoReplyTask(
        contactName: String,
        message: String,
        geofenceId: Long = 0
    ): Task {
        return Task(
            name = "WhatsApp Auto-Reply: $contactName",
            trigger = Trigger(
                type = TriggerType.GEOFENCE_ENTER,
                locationId = geofenceId,
                radiusMeters = 200f
            ),
            actions = createAutoReplyActions(contactName, message)
        )
    }

    fun createScheduledMessageTask(
        contactName: String,
        message: String,
        hour: Int,
        minute: Int,
        daysOfWeek: List<Int> = listOf(1, 2, 3, 4, 5)
    ): Task {
        return Task(
            name = "WhatsApp Scheduled: $contactName",
            trigger = Trigger(
                type = TriggerType.TIME_SCHEDULE,
                hour = hour,
                minute = minute,
                daysOfWeek = daysOfWeek
            ),
            actions = createAutoReplyActions(contactName, message)
        )
    }

    private fun createAutoReplyActions(contactName: String, message: String): List<Action> {
        return listOf(
            // Launch WhatsApp
            Action(
                type = ActionType.LAUNCH_APP,
                packageName = PACKAGE_NAME
            ),
            Action(type = ActionType.WAIT, seconds = 2),

            // Search for contact
            Action(
                type = ActionType.CLICK_ELEMENT,
                target = "Search"
            ),
            Action(type = ActionType.WAIT, seconds = 1),
            Action(
                type = ActionType.TYPE_TEXT,
                target = "Search",
                text = contactName
            ),
            Action(type = ActionType.WAIT, seconds = 2),

            // Click on contact
            Action(
                type = ActionType.CLICK_ELEMENT,
                target = contactName
            ),
            Action(type = ActionType.WAIT, seconds = 1),

            // Type message
            Action(
                type = ActionType.CLICK_ELEMENT,
                target = "Type a message"
            ),
            Action(
                type = ActionType.TYPE_TEXT,
                target = "Type a message",
                text = message
            ),
            Action(type = ActionType.WAIT, seconds = 1),

            // Send message
            Action(
                type = ActionType.CLICK_ELEMENT,
                target = "Send"
            ),
            Action(type = ActionType.WAIT, seconds = 1),

            // Go back to home
            Action(
                type = ActionType.GLOBAL_ACTION,
                globalActionType = "home"
            )
        )
    }
}
