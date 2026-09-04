package com.automate.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.automate.data.db.dao.GeofenceLocationDao
import com.automate.engine.KeepAliveService
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i(TAG, "Device booted, starting services")
            KeepAliveService.start(context)
            scope.launch {
                geofenceRegistrar.reRegisterAllGeofences()
            }
        }
    }

    companion object {
        private const val TAG = "GeofenceBootReceiver"
    }
}
