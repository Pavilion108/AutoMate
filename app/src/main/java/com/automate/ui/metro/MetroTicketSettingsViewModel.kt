package com.automate.ui.metro

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.automate.engine.VariableStore
import com.automate.profiles.metro.MetroTicketProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MetroTicketUiState(
    val botNumber: String = MetroTicketProfile.DEFAULT_BOT_NUMBER,
    val sourceStation: String = MetroTicketProfile.DEFAULT_SOURCE,
    val destinationStation: String = MetroTicketProfile.DEFAULT_DESTINATION,
    val tripType: String = MetroTicketProfile.DEFAULT_TRIP_TYPE,
    val initialMessage: String = MetroTicketProfile.DEFAULT_INITIAL_MESSAGE,
    val isBookingActive: Boolean = false
)

@HiltViewModel
class MetroTicketSettingsViewModel @Inject constructor(
    application: Application,
    private val variableStore: VariableStore
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MetroTicketUiState())
    val uiState: StateFlow<MetroTicketUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val botNumber = variableStore.getStringVariable("metro_bot_number").ifEmpty { MetroTicketProfile.DEFAULT_BOT_NUMBER }
            val source = variableStore.getStringVariable("metro_source_station").ifEmpty { MetroTicketProfile.DEFAULT_SOURCE }
            val dest = variableStore.getStringVariable("metro_dest_station").ifEmpty { MetroTicketProfile.DEFAULT_DESTINATION }
            val tripType = variableStore.getStringVariable("metro_trip_type").ifEmpty { MetroTicketProfile.DEFAULT_TRIP_TYPE }
            val message = variableStore.getStringVariable("metro_initial_message").ifEmpty { MetroTicketProfile.DEFAULT_INITIAL_MESSAGE }
            val isActive = variableStore.getBooleanVariable("metro_booking_active")

            _uiState.value = MetroTicketUiState(
                botNumber = botNumber,
                sourceStation = source,
                destinationStation = dest,
                tripType = tripType,
                initialMessage = message,
                isBookingActive = isActive
            )
        }
    }

    fun setBotNumber(number: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(botNumber = number)
            variableStore.setVariable("metro_bot_number", number, "STRING")
        }
    }

    fun setSourceStation(station: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(sourceStation = station)
            variableStore.setVariable("metro_source_station", station, "STRING")
        }
    }

    fun setDestinationStation(station: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(destinationStation = station)
            variableStore.setVariable("metro_dest_station", station, "STRING")
        }
    }

    fun setTripType(type: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(tripType = type)
            variableStore.setVariable("metro_trip_type", type, "STRING")
        }
    }

    fun setInitialMessage(message: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(initialMessage = message)
            variableStore.setVariable("metro_initial_message", message, "STRING")
        }
    }
}
