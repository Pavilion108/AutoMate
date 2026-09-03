package com.automate.ui.geofencemanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.automate.domain.model.GeofenceLocation
import com.automate.domain.repository.TaskRepository
import com.automate.engine.TriggerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GeofenceUiState(
    val locations: List<GeofenceLocation> = emptyList()
)

@HiltViewModel
class GeofenceManagerViewModel @Inject constructor(
    private val repository: TaskRepository,
    private val triggerManager: TriggerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(GeofenceUiState())
    val uiState: StateFlow<GeofenceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllLocations().collect { locations ->
                _uiState.value = GeofenceUiState(locations = locations)
            }
        }
    }

    fun addLocation(name: String, latitude: Double, longitude: Double, radiusMeters: Float) {
        viewModelScope.launch {
            val id = triggerManager.addGeofence(name, latitude, longitude, radiusMeters)
        }
    }

    fun deleteLocation(id: Long) {
        viewModelScope.launch {
            val location = repository.getLocationById(id) ?: return@launch
            repository.deleteLocation(location)
            triggerManager.disableGeofences()
            triggerManager.enableGeofences()
        }
    }
}
