package com.automate.domain.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class Task(
    val id: Long = 0,
    val name: String,
    val isEnabled: Boolean = true,
    val accountId: Long = 0,
    val trigger: Trigger,
    val constraints: List<Constraint> = emptyList(),
    val actions: List<Action> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null,
    val lastRunResult: String? = null
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): Task = Gson().fromJson(json, Task::class.java)
    }
}

enum class TriggerType {
    GEOFENCE_ENTER,
    GEOFENCE_EXIT,
    TIME_SCHEDULE,
    APP_LAUNCHED,
    MANUAL,
    MORNING_PROMPT_RESPONSE,
    WORK_HOURS_COMPLETE,
    DISTANCE_FROM_LOCATION
}

data class Trigger(
    val type: TriggerType,
    val locationId: Long = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Float = 200f,
    val hour: Int = 0,
    val minute: Int = 0,
    val daysOfWeek: List<Int> = emptyList(),
    val packageName: String = "",
    val distanceMeters: Float = 150f
)

enum class ConstraintOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    LESS_THAN,
    CONTAINS
}

data class Constraint(
    val variableName: String,
    val operator: ConstraintOperator,
    val value: String
)

enum class ActionType {
    LAUNCH_APP,
    CLICK_ELEMENT,
    CLICK_COORDINATES,
    TYPE_TEXT,
    WAIT,
    SET_VARIABLE,
    CHECK_VARIABLE,
    KILL_APP,
    SHOW_NOTIFICATION,
    SHOW_DIALOG,
    SWIPE,
    GLOBAL_ACTION,
    REFRESH_LOCATION,
    POPUP_HANDLER,
    SCHEDULE_TIME_OUT
}

data class Action(
    val type: ActionType,
    val target: String = "",
    val targetId: String = "",
    val text: String = "",
    val seconds: Int = 0,
    val variableName: String = "",
    val variableValue: String = "",
    val packageName: String = "",
    val title: String = "",
    val message: String = "",
    val positiveButton: String = "Yes",
    val negativeButton: String = "No",
    val coordinates: Pair<Int, Int>? = null,
    val globalActionType: String = "",
    val swipeStart: Pair<Int, Int>? = null,
    val swipeEnd: Pair<Int, Int>? = null,
    val swipeDurationMs: Long = 500,
    val retryOnFailure: Boolean = false,
    val maxRetries: Int = 10,
    val retryDelayMs: Long = 1000,
    val successIndicator: String = "",
    val popupDismissTexts: List<String> = emptyList()
)
