package com.automate.ui.taskeditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.automate.domain.model.*
import com.automate.domain.repository.TaskRepository
import com.automate.engine.VariableStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TaskEditorUiState(
    val task: Task? = null,
    val isSaving: Boolean = false
)

@HiltViewModel
class TaskEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TaskRepository,
    private val variableStore: VariableStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskEditorUiState())
    val uiState: StateFlow<TaskEditorUiState> = _uiState.asStateFlow()

    private val taskId: Long = savedStateHandle.get<String>("taskId")?.toLongOrNull() ?: -1L

    init {
        if (taskId != -1L) {
            loadTask(taskId)
        }
    }

    fun loadTask(id: Long) {
        viewModelScope.launch {
            val task = repository.getTaskById(id)
            _uiState.value = _uiState.value.copy(task = task)
        }
    }

    fun saveTask(name: String, triggerType: String, workHours: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)

            val trigger = when (triggerType) {
                "GEOFENCE_ENTER" -> Trigger(
                    type = TriggerType.GEOFENCE_ENTER,
                    radiusMeters = 200f
                )
                "GEOFENCE_EXIT" -> Trigger(
                    type = TriggerType.GEOFENCE_EXIT,
                    radiusMeters = 150f
                )
                "TIME_SCHEDULE" -> Trigger(
                    type = TriggerType.TIME_SCHEDULE,
                    hour = 7,
                    minute = 30,
                    daysOfWeek = listOf(1, 2, 3, 4, 5)
                )
                "MANUAL" -> Trigger(type = TriggerType.MANUAL)
                else -> Trigger(type = TriggerType.MANUAL)
            }

            val actions = when (triggerType) {
                "GEOFENCE_ENTER" -> createTimeInActions()
                "GEOFENCE_EXIT" -> createTimeOutActions(workHours)
                else -> emptyList()
            }

            val task = Task(
                id = if (taskId != -1L) taskId else 0,
                name = name,
                trigger = trigger,
                actions = actions
            )

            if (taskId != -1L) {
                repository.updateTask(task)
            } else {
                repository.insertTask(task)
            }

            // Save work hours
            variableStore.setWorkDurationHours(workHours.toFloat())

            _uiState.value = _uiState.value.copy(isSaving = false)
        }
    }

    private fun createTimeInActions(): List<Action> {
        return listOf(
            Action(type = ActionType.LAUNCH_APP, packageName = "com.app.beehivehrms"),
            Action(type = ActionType.WAIT, seconds = 3),
            Action(type = ActionType.CLICK_ELEMENT, target = "SIGN IN"),
            Action(type = ActionType.WAIT, seconds = 2),
            Action(type = ActionType.CLICK_ELEMENT, target = "TIME IN"),
            Action(type = ActionType.WAIT, seconds = 2),
            Action(type = ActionType.POPUP_HANDLER, maxRetries = 30, retryDelayMs = 1000,
                popupDismissTexts = listOf("OK", "CLOSE", "Allow"),
                successIndicator = "Time In recorded"),
            Action(type = ActionType.GLOBAL_ACTION, globalActionType = "home")
        )
    }

    private fun createTimeOutActions(workHours: Int): List<Action> {
        return listOf(
            Action(type = ActionType.SCHEDULE_TIME_OUT, seconds = workHours * 60),
            Action(type = ActionType.SET_VARIABLE, variableName = "exit_watch", variableValue = "true"),
            Action(type = ActionType.SHOW_NOTIFICATION, title = "Time-Out Watch", text = "Monitoring location for time-out")
        )
    }
}
