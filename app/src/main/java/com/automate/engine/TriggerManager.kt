package com.automate.engine

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.automate.data.db.dao.GeofenceLocationDao
import com.automate.data.db.entity.GeofenceLocationEntity
import com.automate.domain.model.Trigger
import com.automate.domain.model.TriggerType
import com.google.android.gms.location.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TriggerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceLocationDao: GeofenceLocationDao,
    private val variableStore: VariableStore,
    private val taskRunner: TaskRunner
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastKnownLocation: Pair<Double, Double>? = null
    private var locationCallback: LocationCallback? = null

    // === Geofence Management ===

    suspend fun enableGeofences() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted")
            return
        }

        val locations = geofenceLocationDao.getAllLocations().first()
        for (location in locations) {
            addGeofence(location)
        }
        Log.i(TAG, "Enabled ${locations.size} geofences")
    }

    fun disableGeofences() {
        try {
            geofencingClient.removeGeofences(geofencePendingIntent)
            Log.i(TAG, "Disabled all geofences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable geofences", e)
        }
    }

    private suspend fun addGeofence(location: GeofenceLocationEntity) {
        if (!hasLocationPermission()) return

        val geofence = Geofence.Builder()
            .setRequestId("geofence_${location.id}")
            .setCircularRegion(
                location.latitude,
                location.longitude,
                location.radiusMeters
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .setNotificationResponsiveness(300_000) // 5 minutes for battery
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        try {
            geofencingClient.addGeofences(request, geofencePendingIntent).await()
            Log.i(TAG, "Geofence added: ${location.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add geofence: ${location.name}", e)
        }
    }

    suspend fun addGeofence(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float = 200f
    ): Long {
        val entity = GeofenceLocationEntity(
            name = name,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters
        )
        val id = geofenceLocationDao.insertLocation(entity)
        addGeofence(entity.copy(id = id))
        return id
    }

    // === Location Tracking ===

    fun startLocationTracking() {
        if (!hasLocationPermission()) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateDistanceMeters(10f)
            .setMinUpdateIntervalMillis(5000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    lastKnownLocation = Pair(location.latitude, location.longitude)
                    scope.launch {
                        checkDistanceFromTimeInLocation(location)
                    }
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        Log.i(TAG, "Location tracking started")
    }

    fun stopLocationTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        Log.i(TAG, "Location tracking stopped")
    }

    private suspend fun checkDistanceFromTimeInLocation(currentLocation: Location) {
        val timeInLocation = variableStore.getTimeInLocation() ?: return
        val exitWatch = variableStore.isExitWatch()

        if (!exitWatch) return

        val timeInLoc = Location("").apply {
            latitude = timeInLocation.first
            longitude = timeInLocation.second
        }

        val distance = currentLocation.distanceTo(timeInLoc)
        Log.d(TAG, "Distance from time-in location: ${distance}m")

        // If user is more than exit_watch_distance away, trigger time-out
        val exitDistance = variableStore.getVariable("exit_watch_distance")?.toFloatOrNull() ?: 50f
        if (distance > exitDistance) {
            Log.i(TAG, "User left office area (${distance}m away), triggering time-out")
            taskRunner.performTimeOut()
        }
    }

    // === Location Permission Check ===

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // === Get Last Known Location ===

    suspend fun getLastKnownLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null

        lastKnownLocation?.let { return it }

        return try {
            val location = fusedLocationClient.lastLocation.await()
            if (location != null) {
                val loc = Pair(location.latitude, location.longitude)
                lastKnownLocation = loc
                loc
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last known location", e)
            null
        }
    }

    // === PendingIntent ===

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, com.automate.geofence.GeofenceBroadcastReceiver::class.java).apply {
            action = "com.automate.GEOFENCE_TRANSITION"
        }
        PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    companion object {
        private const val TAG = "TriggerManager"
    }
}

// Extension function to await Task-based APIs
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
    return suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.cancel(it) }
    }
}
