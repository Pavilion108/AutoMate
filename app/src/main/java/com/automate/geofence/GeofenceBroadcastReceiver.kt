package com.automate.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.automate.engine.TaskRunner
import com.automate.engine.VariableStore
import com.automate.engine.TriggerManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    @Inject lateinit var taskRunner: TaskRunner
    @Inject lateinit var variableStore: VariableStore
    @Inject lateinit var triggerManager: TriggerManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received broadcast: ${intent.action}")

        when (intent.action) {
            // Geofence transitions
            "com.automate.GEOFENCE_TRANSITION" -> {
                val geofencingEvent = GeofencingEvent.fromIntent(intent)
                if (geofencingEvent != null) {
                    handleGeofenceTransition(geofencingEvent)
                }
            }

            // Morning prompt response
            "MORNING_RESPONSE" -> {
                val goingToWork = intent.getBooleanExtra("going_to_work", false)
                taskRunner.handleMorningResponse(goingToWork)
            }

            // Time-out response
            "TIME_OUT_RESPONSE" -> {
                val watchLocation = intent.getBooleanExtra("watch_location", false)
                taskRunner.handleTimeOutResponse(watchLocation)
            }

            // Time-out trigger (from alarm)
            "TIME_OUT_TRIGGER" -> {
                taskRunner.performTimeOut()
            }

            // Refresh location
            "com.automate.REFRESH_LOCATION" -> {
                scope.launch {
                    triggerManager.startLocationTracking()
                }
            }
        }
    }

    private fun handleGeofenceTransition(event: GeofencingEvent) {
        if (event.hasError()) {
            Log.e(TAG, "Geofencing error: ${event.errorCode}")
            return
        }

        val transition = event.geofenceTransition
        val triggeringGeofences = event.triggeringGeofences ?: return

        scope.launch {
            when (transition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> {
                    Log.i(TAG, "Entered geofence area")
                    handleGeofenceEnter(triggeringGeofences)
                }

                Geofence.GEOFENCE_TRANSITION_EXIT -> {
                    Log.i(TAG, "Exited geofence area")
                    handleGeofenceExit(triggeringGeofences)
                }
            }
        }
    }

    private suspend fun handleGeofenceEnter(geofences: List<Geofence>) {
        val armed = variableStore.isArmed()
        val timedInToday = variableStore.isTimedInToday()

        if (armed && !timedInToday) {
            Log.i(TAG, "Armed and not timed in - starting time-in flow")
            taskRunner.startTimeInFlow()
        }
    }

    private suspend fun handleGeofenceExit(geofences: List<Geofence>) {
        val exitWatch = variableStore.isExitWatch()
        val timedInToday = variableStore.isTimedInToday()

        if (exitWatch && timedInToday) {
            Log.i(TAG, "Exit watch active and timed in - starting time-out flow")
            taskRunner.performTimeOut()
        }
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
    }
}
