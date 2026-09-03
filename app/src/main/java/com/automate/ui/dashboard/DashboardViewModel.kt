package com.automate.ui.dashboard

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.automate.domain.model.GeofenceLocation
import com.automate.domain.model.Task
import com.automate.domain.repository.TaskRepository
import com.automate.engine.AutoMateAccessibilityService
import com.automate.engine.TaskRunner
import com.automate.engine.VariableStore
import com.automate.engine.TriggerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TaskUiModel(
    val id: Long,
    val name: String,
    val isEnabled: Boolean,
    val triggerDescription: String,
    val lastRunDescription: String?
)

data class LocationUiModel(
    val id: Long,
    val name: String,
    val radiusMeters: Float,
    val isActive: Boolean
)

data class DashboardUiState(
    val isAccessibilityEnabled: Boolean = false,
    val isGeofenceEnabled: Boolean = false,
    val isArmed: Boolean = false,
    val tasks: List<TaskUiModel> = emptyList(),
    val locations: List<LocationUiModel> = emptyList()
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    application: Application,
    private val repository: TaskRepository,
    private val taskRunner: TaskRunner,
    private val variableStore: VariableStore,
    private val triggerManager: TriggerManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadData()
        checkAccessibilityService()
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                repository.getAllTasks(),
                repository.getAllLocations(),
                variableStore.getAllVariables()
            ) { tasks, locations, variables ->
                DashboardUiState(
                    isAccessibilityEnabled = AutoMateAccessibilityService.instance != null,
                    isGeofenceEnabled = locations.isNotEmpty(),
                    isArmed = variables["armed"]?.toBoolean() ?: false,
                    tasks = tasks.map { task ->
                        TaskUiModel(
                            id = task.id,
                            name = task.name,
                            isEnabled = task.isEnabled,
                            triggerDescription = getTriggerDescription(task),
                            lastRunDescription = task.lastRunAt?.let {
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(it))
                            }
                        )
                    },
                    locations = locations.map { loc ->
                        LocationUiModel(
                            id = loc.id,
                            name = loc.name,
                            radiusMeters = loc.radiusMeters,
                            isActive = true
                        )
                    }
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    private fun getTriggerDescription(task: Task): String {
        return when (task.trigger.type) {
            com.automate.domain.model.TriggerType.GEOFENCE_ENTER -> "On entering ${task.trigger.radiusMeters.toInt()}m area"
            com.automate.domain.model.TriggerType.GEOFENCE_EXIT -> "On leaving ${task.trigger.radiusMeters.toInt()}m area"
            com.automate.domain.model.TriggerType.TIME_SCHEDULE -> "At ${task.trigger.hour}:${String.format("%02d", task.trigger.minute)}"
            com.automate.domain.model.TriggerType.MANUAL -> "Manual trigger"
            com.automate.domain.model.TriggerType.MORNING_PROMPT_RESPONSE -> "Morning prompt"
            com.automate.domain.model.TriggerType.WORK_HOURS_COMPLETE -> "After work hours"
            com.automate.domain.model.TriggerType.DISTANCE_FROM_LOCATION -> "Distance: ${task.trigger.distanceMeters.toInt()}m"
            com.automate.domain.model.TriggerType.APP_LAUNCHED -> "When ${task.trigger.packageName} launches"
        }
    }

    private fun checkAccessibilityService() {
        viewModelScope.launch {
            while (true) {
                val isEnabled = AutoMateAccessibilityService.instance != null
                _uiState.update { it.copy(isAccessibilityEnabled = isEnabled) }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun toggleTask(taskId: Long) {
        viewModelScope.launch {
            val task = repository.getTaskById(taskId) ?: return@launch
            repository.updateTask(task.copy(isEnabled = !task.isEnabled))
        }
    }

    fun startTimeIn() {
        taskRunner.startTimeInFlow()
    }

    fun performTimeOut() {
        taskRunner.performTimeOut()
    }
}
