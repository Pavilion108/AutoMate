package com.automate.engine

import android.util.Log
import com.automate.data.db.dao.VariableDao
import com.automate.data.db.entity.VariableEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VariableStore @Inject constructor(
    private val variableDao: VariableDao
) {
    fun getAllVariables(): Flow<Map<String, String>> {
        return variableDao.getAllVariables().map { entities ->
            entities.associate { it.name to it.value }
        }
    }

    suspend fun getVariable(name: String): String? {
        return variableDao.getVariableValue(name)
    }

    suspend fun setVariable(name: String, value: String, type: String = "BOOLEAN") {
        variableDao.setVariable(VariableEntity(name, value, type))
        Log.d(TAG, "Variable set: $name = $value")
    }

    suspend fun getBooleanVariable(name: String): Boolean {
        return getVariable(name)?.toBooleanStrictOrNull() ?: false
    }

    suspend fun setBooleanVariable(name: String, value: Boolean) {
        setVariable(name, value.toString(), "BOOLEAN")
    }

    suspend fun getStringVariable(name: String): String {
        return getVariable(name) ?: ""
    }

    suspend fun setTimeVariable(name: String, hours: Int, minutes: Int) {
        setVariable(name, String.format("%02d:%02d", hours, minutes), "TIME")
    }

    suspend fun getTimeVariable(name: String): Pair<Int, Int>? {
        val value = getVariable(name) ?: return null
        val parts = value.split(":")
        if (parts.size == 2) {
            return Pair(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0)
        }
        return null
    }

    suspend fun clearAll() {
        variableDao.clearAll()
    }

    // Predefined variables for the attendance system
    suspend fun isArmed(): Boolean = getBooleanVariable("armed")
    suspend fun setArmed(value: Boolean) = setBooleanVariable("armed", value)

    suspend fun isTimedInToday(): Boolean = getBooleanVariable("timed_in_today")
    suspend fun setTimedInToday(value: Boolean) = setBooleanVariable("timed_in_today", value)

    suspend fun isExitWatch(): Boolean = getBooleanVariable("exit_watch")
    suspend fun setExitWatch(value: Boolean) = setBooleanVariable("exit_watch", value)

    suspend fun isGoingToWork(): Boolean = getBooleanVariable("going_to_work")
    suspend fun setGoingToWork(value: Boolean) = setBooleanVariable("going_to_work", value)

    suspend fun getWorkDurationHours(): Float {
        return getVariable("work_duration_hours")?.toFloatOrNull() ?: 8.5f
    }

    suspend fun setWorkDurationHours(hours: Float) {
        setVariable("work_duration_hours", hours.toString(), "INTEGER")
    }

    suspend fun getTimeInLocation(): Pair<Double, Double>? {
        val lat = getVariable("time_in_lat")?.toDoubleOrNull()
        val lng = getVariable("time_in_lng")?.toDoubleOrNull()
        if (lat != null && lng != null) return Pair(lat, lng)
        return null
    }

    suspend fun setTimeInLocation(lat: Double, lng: Double) {
        setVariable("time_in_lat", lat.toString(), "STRING")
        setVariable("time_in_lng", lng.toString(), "STRING")
    }

    // Predefined variables for metro ticket booking
    suspend fun getMetroBotNumber(): String = getStringVariable("metro_bot_number").ifEmpty { com.automate.profiles.metro.MetroTicketProfile.DEFAULT_BOT_NUMBER }
    suspend fun setMetroBotNumber(number: String) = setVariable("metro_bot_number", number, "STRING")

    suspend fun getMetroSourceStation(): String = getStringVariable("metro_source_station").ifEmpty { com.automate.profiles.metro.MetroTicketProfile.DEFAULT_SOURCE }
    suspend fun setMetroSourceStation(station: String) = setVariable("metro_source_station", station, "STRING")

    suspend fun getMetroDestStation(): String = getStringVariable("metro_dest_station").ifEmpty { com.automate.profiles.metro.MetroTicketProfile.DEFAULT_DESTINATION }
    suspend fun setMetroDestStation(station: String) = setVariable("metro_dest_station", station, "STRING")

    suspend fun getMetroTripType(): String = getStringVariable("metro_trip_type").ifEmpty { com.automate.profiles.metro.MetroTicketProfile.DEFAULT_TRIP_TYPE }
    suspend fun setMetroTripType(type: String) = setVariable("metro_trip_type", type, "STRING")

    suspend fun isMetroBookingActive(): Boolean = getBooleanVariable("metro_booking_active")
    suspend fun setMetroBookingActive(active: Boolean) = setBooleanVariable("metro_booking_active", active)

    companion object {
        private const val TAG = "VariableStore"
    }
}
