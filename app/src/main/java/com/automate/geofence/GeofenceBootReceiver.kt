package com.automate.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.automate.data.db.dao.GeofenceLocationDao
import com.automate.engine.AccessibilityWatchdogWorker
import com.automate.engine.KeepAliveService
import com.automate.engine.VariableStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GeofenceBootReceiver : BroadcastReceiver() {

    @Inject lateinit var geofenceLocationDao: GeofenceLocationDao
    @Inject lateinit var geofenceRegistrar: GeofenceRegistrar
    @Inject lateinit var variableStore: VariableStore

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i(TAG, "Device booted, starting services")

            scope.launch {
                // Boot must not start anything on a non-office day. The armed flag is the
                // only thing that decides whether AutoMate is allowed to run today, so it
                // is read before any service, worker or geofence is created.
                val armed = variableStore.isArmed()
                if (!armed) {
                    Log.i(TAG, "Boot: not armed, staying idle")
                    return@launch
                }

                Log.i(TAG, "Boot: armed, restoring location automation")
                KeepAliveService.start(context)
                AccessibilityWatchdogWorker.enqueue(context)
                geofenceRegistrar.reRegisterAllGeofences()
            }
        }
    }

    companion object {
        private const val TAG = "GeofenceBootReceiver"
    }
}
