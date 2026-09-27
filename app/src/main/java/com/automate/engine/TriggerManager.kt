package com.automate.engine

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
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
    private var isTracking = false

    // === Geofence Management ===

    suspend fun enableGeofences() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission missing (foreground=${hasForegroundLocationPermission()}, background=${hasBackgroundLocationPermission()}) — geofences NOT registered")
            return
        }

        val locations = geofenceLocationDao.getAllLocations().first()
        if (locations.isEmpty()) {
            Log.w(TAG, "No saved locations — nothing to register")
            return
        }

        // Clear stale registrations first so renames/deletes and the 100-item
        // per-app geofence limit cannot leave orphans behind.
        try {
            geofencingClient.removeGeofences(geofencePendingIntent).await()
            Log.i(TAG, "Cleared previous geofence registrations")
        } catch (e: Exception) {
            Log.w(TAG, "No previous geofences to clear: ${e.message}")
        }

        var added = 0
        for (location in locations) {
            if (addGeofence(location)) added++
        }
        Log.i(TAG, "Registered $added/${locations.size} geofences")

        // Play Services INITIAL_TRIGGER_ENTER is unreliable when geofences are added
        // while the user is already inside, so verify locally too.
        if (added > 0) checkIfAlreadyInsideGeofence(locations)
    }

    private suspend fun checkIfAlreadyInsideGeofence(locations: List<GeofenceLocationEntity>) {
        val currentLocation = getLastKnownLocation() ?: run {
            Log.w(TAG, "Cannot check geofence — no location available")
            forceLocationUpdate()
            delay(3000)
            getLastKnownLocation() ?: run {
                Log.w(TAG, "Still no location after forced fix")
                return
            }
        }

        if (variableStore.isTimedInToday()) return

        Log.i(TAG, "Checking geofence: current=${currentLocation.first},${currentLocation.second}, armed=${variableStore.isArmed()}, timedIn=${variableStore.isTimedInToday()}")

        for (location in locations) {
            val geofenceLoc = Location("").apply {
                latitude = location.latitude
                longitude = location.longitude
            }
            val current = Location("").apply {
                latitude = currentLocation.first
                longitude = currentLocation.second
            }
            val distance = current.distanceTo(geofenceLoc)
            Log.i(TAG, "Distance to '${location.name}': ${distance}m (radius: ${location.radiusMeters}m)")

            if (distance <= location.radiusMeters) {
                Log.i(TAG, "User is INSIDE '${location.name}' — triggering time-in")
                if (!variableStore.isArmed()) {
                    variableStore.setArmed(true)
                    variableStore.setGoingToWork(true)
                    variableStore.setTimeInLocation(location.latitude, location.longitude)
                }
                taskRunner.startTimeInFlow()
                return
            }
        }

        Log.i(TAG, "User is outside all geofences — waiting for ENTER transition")
    }

    fun disableGeofences() {
        try {
            geofencingClient.removeGeofences(geofencePendingIntent)
            Log.i(TAG, "Disabled all geofences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable geofences", e)
        }
    }

    private suspend fun addGeofence(location: GeofenceLocationEntity): Boolean {
        if (!hasLocationPermission()) return false

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
            .setNotificationResponsiveness(10_000) // 10 seconds — fast detection
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        return try {
            geofencingClient.addGeofences(request, geofencePendingIntent).await()
            Log.i(TAG, "Geofence added: '${location.name}' r=${location.radiusMeters}m @ ${location.latitude},${location.longitude}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add geofence '${location.name}'", e)
            false
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

    // === Location Tracking (Aggressive: 3-second intervals via Foreground Service) ===

    private var locationBroadcastReceiver: android.content.BroadcastReceiver? = null

    fun startLocationTracking() {
        if (!hasLocationPermission()) return
        if (isTracking) {
            Log.d(TAG, "Location tracking already active")
            return
        }

        // Register receiver for foreground service location updates
        locationBroadcastReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                val lat = intent.getDoubleExtra("latitude", 0.0)
                val lng = intent.getDoubleExtra("longitude", 0.0)
                val accuracy = intent.getFloatExtra("accuracy", 0f)
                val location = Location("").apply {
                    latitude = lat
                    longitude = lng
                    this.accuracy = accuracy
                }
                lastKnownLocation = Pair(lat, lng)
                scope.launch {
                    checkGeofenceEntry(location)
                    checkDistanceFromTimeInLocation(location)
                }
            }
        }
        val filter = android.content.IntentFilter(KeepAliveService.ACTION_LOCATION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(locationBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(locationBroadcastReceiver, filter)
        }

        // Start foreground location tracking via service (unthrottled 3s intervals)
        val intent = android.content.Intent(context, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_START_LOCATION_TRACKING
        }
        context.startForegroundService(intent)

        isTracking = true
        Log.i(TAG, "Foreground location tracking delegated (3s interval)")
    }

    fun stopLocationTracking() {
        // Stop foreground service location tracking
        val intent = android.content.Intent(context, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_STOP_LOCATION_TRACKING
        }
        context.startService(intent)

        // Unregister receiver
        locationBroadcastReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        locationBroadcastReceiver = null
        isTracking = false
        Log.i(TAG, "Location tracking stopped")
    }

    // === Force Immediate GPS Fix ===

    fun forceLocationUpdate() {
        if (!hasLocationPermission()) return

        Log.i(TAG, "Forcing immediate GPS location update")

        // Strategy 1: Request a single high-accuracy location immediately
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0)
            .setMaxUpdates(1) // Only one update, then stop
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    lastKnownLocation = Pair(location.latitude, location.longitude)
                    Log.i(TAG, "Forced location update: ${location.latitude}, ${location.longitude}")
                }
                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        // Also trigger a network-based location as backup
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    lastKnownLocation = Pair(location.latitude, location.longitude)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "lastLocation fallback failed", e)
        }
    }

    // === Aggressive GPS-based Geofence Entry Detection (3-second checks) ===

    private var lastGeofenceEntryCheck = 0L
    private val GEOFENCE_ENTRY_COOLDOWN_MS = 15_000L

    private suspend fun checkGeofenceEntry(currentLocation: Location) {
        if (variableStore.isTimedInToday()) return

        // Bad accuracy would fire a time-in while the user is still walking to the office.
        if (currentLocation.hasAccuracy() && currentLocation.accuracy > 100f) {
            Log.d(TAG, "Entry check skipped: accuracy ${currentLocation.accuracy}m")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastGeofenceEntryCheck < GEOFENCE_ENTRY_COOLDOWN_MS) return
        lastGeofenceEntryCheck = now

        val locations = try {
            geofenceLocationDao.getAllLocations().first()
        } catch (e: Exception) {
            return
        }

        for (geofenceLoc in locations) {
            val target = Location("").apply {
                latitude = geofenceLoc.latitude
                longitude = geofenceLoc.longitude
            }
            val distance = currentLocation.distanceTo(target)
            if (distance <= geofenceLoc.radiusMeters) {
                Log.i(TAG, "GPS ENTRY detected: ${distance}m from '${geofenceLoc.name}' (radius: ${geofenceLoc.radiusMeters}m)")

                // Self-arming: the 9:40 morning prompt is an exact alarm, which Android 13+
                // can silently drop, leaving armed=false forever and blocking every trigger.
                // A confirmed arrival at a saved office is a stronger signal than a timer.
                if (!variableStore.isArmed()) {
                    Log.i(TAG, "Auto-arming: user arrived at '${geofenceLoc.name}' but armed=false")
                    variableStore.setArmed(true)
                    variableStore.setGoingToWork(true)
                    // Remember where they clocked in so distance checks use the right office.
                    variableStore.setTimeInLocation(geofenceLoc.latitude, geofenceLoc.longitude)
                }

                taskRunner.startTimeInFlow()
                return
            }
        }
    }

    // === Distance-based Exit Detection (measures from CLIENT LOCATION, not time-in point) ===

    private suspend fun checkDistanceFromTimeInLocation(currentLocation: Location) {
        val exitWatch = variableStore.isExitWatch()
        if (!exitWatch) return

        if (!variableStore.isTimedInToday()) return

        val locations = try {
            geofenceLocationDao.getAllLocations().first()
        } catch (e: Exception) {
            return
        }
        if (locations.isEmpty()) return

        // Multi-location support: measure against the NEAREST saved location, not
        // locations.first(). Using the first entry made every secondary office look
        // like a ~19km "exit" and fired an immediate time-out.
        val nearest = locations.minBy { loc ->
            val target = Location("").apply {
                latitude = loc.latitude
                longitude = loc.longitude
            }
            currentLocation.distanceTo(target)
        }

        val target = Location("").apply {
            latitude = nearest.latitude
            longitude = nearest.longitude
        }

        val distance = currentLocation.distanceTo(target)
        Log.d(TAG, "Distance from nearest client '${nearest.name}': ${distance}m (accuracy: ${currentLocation.accuracy}m)")

        // Poor accuracy would cause both false time-ins and false time-outs.
        if (currentLocation.accuracy > 100f) {
            Log.d(TAG, "GPS accuracy too low (${currentLocation.accuracy}m), skipping exit check")
            return
        }

        // Never fire a time-out while still inside the office geofence radius,
        // otherwise time-in (200m) and time-out (120m default) can fight each other.
        val exitDistance = variableStore.getVariable("exit_watch_distance")?.toFloatOrNull()
            ?: (nearest.radiusMeters + 150f)
        val effectiveExit = maxOf(exitDistance, nearest.radiusMeters + 50f)
        if (effectiveExit != exitDistance) {
            Log.i(TAG, "exit_watch_distance raised to ${effectiveExit}m to clear '${nearest.name}' radius ${nearest.radiusMeters}m")
        }

        if (distance > effectiveExit) {
            Log.i(TAG, "User left ${nearest.name} (${distance}m, threshold ${effectiveExit}m), triggering time-out")
            taskRunner.performTimeOut()
        }
    }

    // === Location Permission Check ===

    fun hasLocationPermission(): Boolean {
        return hasForegroundLocationPermission() && hasBackgroundLocationPermission()
    }

    fun hasForegroundLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    /** Geofence accuracy needs FINE. Approximate-only location is not good enough. */
    fun hasPreciseLocationPermission(): Boolean {
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
                // Force a fresh location if last known is null
                forceLocationUpdate()
                delay(2000)
                lastKnownLocation
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
