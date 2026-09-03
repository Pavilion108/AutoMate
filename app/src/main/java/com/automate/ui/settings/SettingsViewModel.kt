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
    val workHours: Int = 8,
    val morningPromptEnabled: Boolean = true,
    val geofenceRadius: Float = 200f,
    val exitWatchDistance: Float = 150f
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
            val workHours = prefs.getInt("work_hours", 8)
            val morningPrompt = prefs.getBoolean("morning_prompt_enabled", true)
            val geofenceRadius = prefs.getFloat("geofence_radius", 200f)
            val exitWatchDistance = prefs.getFloat("exit_watch_distance", 150f)

            _uiState.value = SettingsUiState(
                workHours = workHours,
                morningPromptEnabled = morningPrompt,
                geofenceRadius = geofenceRadius,
                exitWatchDistance = exitWatchDistance
            )

            // Schedule morning prompt if enabled
            if (morningPromptEnabled) {
                scheduleMorningPrompt()
            }
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
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
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
