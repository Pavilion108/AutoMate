package com.automate.geofence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.automate.data.db.dao.GeofenceLocationDao
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeofenceRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceLocationDao: GeofenceLocationDao
) {
    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)

    suspend fun reRegisterAllGeofences() {
        try {
            // Remove existing geofences
            geofencingClient.removeGeofences(geofencePendingIntent)

            // Re-register all from database
            val locations = geofenceLocationDao.getAllLocations().first()
            if (locations.isEmpty()) {
                Log.i(TAG, "No geofences to register")
                return
            }

            val geofences = locations.map { location ->
                Geofence.Builder()
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
                    .setNotificationResponsiveness(300_000)
                    .build()
            }

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(geofences)
                .build()

            geofencingClient.addGeofences(request, geofencePendingIntent)
                .addOnSuccessListener { Log.i(TAG, "Re-registered ${geofences.size} geofences") }
                .addOnFailureListener { Log.e(TAG, "Failed to re-register geofences", it) }

        } catch (e: Exception) {
            Log.e(TAG, "Error re-registering geofences", e)
        }
    }

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = "com.automate.GEOFENCE_TRANSITION"
        }
        PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    companion object {
        private const val TAG = "GeofenceRegistrar"
    }
}
