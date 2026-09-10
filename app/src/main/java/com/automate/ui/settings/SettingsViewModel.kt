package com.automate.ui.settings

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.automate.engine.VariableStore
import com.automate.geofence.GeofenceBroadcastReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class SettingsUiState(
    val workHours: Float = 8.5f,
    val morningPromptEnabled: Boolean = true,
    val geofenceRadius: Float = 200f,
    val exitWatchDistance: Float = 50f
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val variableStore: VariableStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("automate_prefs", Context.MODE_PRIVATE)
            val workHours = prefs.getFloat("work_hours", 8.5f)
            val morningPrompt = prefs.getBoolean("morning_prompt_enabled", true)
            val geofenceRadius = prefs.getFloat("geofence_radius", 200f)
            val exitWatchDistance = prefs.getFloat("exit_watch_distance", 50f)

            _uiState.value = SettingsUiState(
                workHours = workHours,
                morningPromptEnabled = morningPrompt,
                geofenceRadius = geofenceRadius,
                exitWatchDistance = exitWatchDistance
            )

            // Sync work hours to VariableStore (store as STRING so toFloatOrNull works)
            variableStore.setVariable("work_duration_hours", workHours.toString(), "STRING")

            if (morningPrompt) {
                scheduleMorningPrompt()
            }
        }
    }

    fun setWorkHours(hours: Float) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(workHours = hours)
            val prefs = context.getSharedPreferences("automate_prefs", Context.MODE_PRIVATE)
            prefs.edit().putFloat("work_hours", hours).apply()
            variableStore.setVariable("work_duration_hours", hours.toString(), "STRING")
        }
    }

    fun setGeofenceRadius(radius: Float) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(geofenceRadius = radius)
            val prefs = context.getSharedPreferences("automate_prefs", Context.MODE_PRIVATE)
            prefs.edit().putFloat("geofence_radius", radius).apply()
            variableStore.setVariable("geofence_radius", radius.toInt().toString(), "INTEGER")
        }
    }

    fun setExitWatchDistance(distance: Float) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(exitWatchDistance = distance)
            val prefs = context.getSharedPreferences("automate_prefs", Context.MODE_PRIVATE)
            prefs.edit().putFloat("exit_watch_distance", distance).apply()
            variableStore.setVariable("exit_watch_distance", distance.toInt().toString(), "INTEGER")
        }
    }

    fun toggleMorningPrompt() {
        viewModelScope.launch {
            val newState = !_uiState.value.morningPromptEnabled
            _uiState.value = _uiState.value.copy(morningPromptEnabled = newState)

            val prefs = context.getSharedPreferences("automate_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("morning_prompt_enabled", newState).apply()

            if (newState) {
                scheduleMorningPrompt()
            } else {
                cancelMorningPrompt()
            }
        }
    }

    private fun scheduleMorningPrompt() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = "MORNING_PROMPT"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 8888, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)

            // Skip weekends (Saturday=7, Sunday=1)
            val dayOfWeek = get(Calendar.DAY_OF_WEEK)
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
                // Jump to next Monday
                add(Calendar.DAY_OF_YEAR, if (dayOfWeek == Calendar.SATURDAY) 2 else 1)
            } else if (timeInMillis <= System.currentTimeMillis()) {
                // Already past 7:30 today, schedule for tomorrow (skip weekend)
                add(Calendar.DAY_OF_YEAR, 1)
                val nextDay = get(Calendar.DAY_OF_WEEK)
                if (nextDay == Calendar.SATURDAY) add(Calendar.DAY_OF_YEAR, 2)
                else if (nextDay == Calendar.SUNDAY) add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }

    private fun cancelMorningPrompt() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = "MORNING_PROMPT"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 8888, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
