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
        Log.i(TAG, "Received broadcast: action=${intent.action}, extras=${intent.extras}")

        when (intent.action) {
            // Geofence transitions — Play Services sends with the PendingIntent's action
            "com.automate.GEOFENCE_TRANSITION",
            null -> {
                // Check if this is actually a geofence transition from Play Services
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

            // Morning prompt alarm — send notification
            "MORNING_PROMPT" -> {
                Log.i(TAG, "Morning prompt alarm fired, sending notification")
                taskRunner.sendMorningPrompt()
            }

            // Manual test: trigger time-in flow directly
            "TEST_TIME_IN" -> {
                Log.i(TAG, "Test mode: triggering time-in flow")
                taskRunner.startTimeInFlow()
            }

            // Manual test: trigger time-out flow directly
            "TEST_TIME_OUT" -> {
                Log.i(TAG, "Test mode: triggering time-out flow")
                taskRunner.performTimeOut()
            }

            // Manual test: send morning prompt
            "TEST_MORNING" -> {
                Log.i(TAG, "Test mode: sending morning prompt")
                taskRunner.sendMorningPrompt()
            }

            // Manual test: send time-out prompt
            "TEST_TIMEOUT_PROMPT" -> {
                Log.i(TAG, "Test mode: sending time-out prompt")
                taskRunner.sendTimeOutPrompt()
            }

            // Generic set variable
            "SET_VARIABLE" -> {
                val name = intent.getStringExtra("variable_name") ?: return
                val value = intent.getStringExtra("variable_value") ?: return
                scope.launch {
                    variableStore.setVariable(name, value)
                    Log.i(TAG, "Variable set via broadcast: $name = $value")
                }
            }

            // Quick test setup: set all variables for time-out testing
            "TEST_SETUP_TIMEOUT" -> {
                Log.i(TAG, "Test setup: configuring for time-out testing")
                scope.launch {
                    try {
                        variableStore.setArmed(true)
                        Log.i(TAG, "Set armed=true")
                        variableStore.setTimedInToday(true)
                        Log.i(TAG, "Set timed_in_today=true")
                        variableStore.setGoingToWork(true)
                        Log.i(TAG, "Set going_to_work=true")
                        variableStore.setExitWatch(true)
                        Log.i(TAG, "Set exit_watch=true")
                        variableStore.setVariable("time_in_lat", "19.126812", "STRING")
                        variableStore.setVariable("time_in_lng", "72.838510", "STRING")
                        Log.i(TAG, "Set time_in location")
                        variableStore.setWorkDurationHours(8)
                        Log.i(TAG, "Set work_duration_hours=8")
                        triggerManager.enableGeofences()
                        Log.i(TAG, "Geofences enabled")
                        triggerManager.startLocationTracking()
                        Log.i(TAG, "Location tracking started")
                        Log.i(TAG, "Test setup complete")
                    } catch (e: Exception) {
                        Log.e(TAG, "Test setup failed", e)
                    }
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
