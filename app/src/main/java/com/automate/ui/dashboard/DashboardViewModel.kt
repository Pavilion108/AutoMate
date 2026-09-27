package com.automate.ui.dashboard

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.automate.AutoMateApp
import com.automate.MainActivity
import com.automate.domain.model.GeofenceLocation
import com.automate.domain.model.Task
import com.automate.domain.repository.TaskRepository
import com.automate.engine.AutoMateAccessibilityService
import com.automate.engine.KeepAliveService
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
    val actionSummary: String?,
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

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    // Debounce flags to prevent duplicate execution
    private var isTimeInRunning = false
    private var isTimeOutRunning = false

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
                    isAccessibilityEnabled = AutoMateAccessibilityService.isEnabled(getApplication()),
                    isGeofenceEnabled = locations.isNotEmpty(),
                    isArmed = variables["armed"]?.toBoolean() ?: false,
                    tasks = tasks.map { task ->
                        TaskUiModel(
                            id = task.id,
                            name = task.name,
                            isEnabled = task.isEnabled,
                            triggerDescription = getTriggerDescription(task),
                            actionSummary = getActionSummary(task),
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
            com.automate.domain.model.TriggerType.GEOFENCE_ENTER -> {
                "Triggers when you arrive at the office"
            }
            com.automate.domain.model.TriggerType.GEOFENCE_EXIT -> {
                "Triggers when you leave the office"
            }
            com.automate.domain.model.TriggerType.TIME_SCHEDULE -> {
                val hour = task.trigger.hour
                val min = task.trigger.minute
                val period = if (hour < 12) "AM" else "PM"
                val displayHour = when {
                    hour == 0 -> 12
                    hour > 12 -> hour - 12
                    else -> hour
                }
                "Daily at $displayHour:${String.format("%02d", min)} $period"
            }
            com.automate.domain.model.TriggerType.MANUAL -> "Manual"
            com.automate.domain.model.TriggerType.MORNING_PROMPT_RESPONSE -> "Morning prompt"
            com.automate.domain.model.TriggerType.WORK_HOURS_COMPLETE -> "After work hours"
            com.automate.domain.model.TriggerType.DISTANCE_FROM_LOCATION -> {
                "When you're near the office"
            }
            com.automate.domain.model.TriggerType.APP_LAUNCHED -> "When app opens"
        }
    }

    private fun getActionSummary(task: Task): String? {
        if (task.actions.isEmpty()) return null
        return when (task.trigger.type) {
            com.automate.domain.model.TriggerType.GEOFENCE_ENTER -> "Marks your attendance in Beehive HRMS"
            com.automate.domain.model.TriggerType.GEOFENCE_EXIT -> "Marks your time-out in Beehive HRMS"
            com.automate.domain.model.TriggerType.TIME_SCHEDULE -> "Asks if you're going to work today"
            else -> "Automates your workflow"
        }
    }

    private fun checkAccessibilityService() {
        viewModelScope.launch {
            while (true) {
                val isEnabled = AutoMateAccessibilityService.isEnabled(getApplication())
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
        if (isTimeInRunning) {
            Log.w(TAG, "Time In already running, ignoring duplicate tap")
            return
        }
        // Quick accessibility check before starting
        if (!AutoMateAccessibilityService.isEnabled(getApplication())) {
            Log.w(TAG, "Accessibility not enabled, triggering rebind")
            KeepAliveService.checkAndRebind(getApplication())
            // Show notification
            val context = getApplication()
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity<Intent>(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, AutoMateApp.CHANNEL_TASK_STATUS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Accessibility Service Not Enabled")
                .setContentText("Please enable AutoMate in Settings > Accessibility > AutoMate")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(9997, notification)
            return
        }
        isTimeInRunning = true
        viewModelScope.launch {
            taskRunner.startTimeInFlow()
            isTimeInRunning = false
        }
    }

    fun performTimeOut() {
        if (isTimeOutRunning) {
            Log.w(TAG, "Time Out already running, ignoring duplicate tap")
            return
        }
        // Quick accessibility check before starting
        if (!AutoMateAccessibilityService.isEnabled(getApplication())) {
            Log.w(TAG, "Accessibility not enabled, triggering rebind")
            KeepAliveService.checkAndRebind(getApplication())
            val context = getApplication()
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity<Intent>(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, AutoMateApp.CHANNEL_TASK_STATUS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Accessibility Service Not Enabled")
                .setContentText("Please enable AutoMate in Settings > Accessibility > AutoMate")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(9996, notification)
            return
        }
        isTimeOutRunning = true
        viewModelScope.launch {
            taskRunner.performTimeOut()
            isTimeOutRunning = false
        }
    }

    fun startMetroTicketBooking() {
        taskRunner.startMetroTicketFlow()
    }
}
