package com.automate

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AutoMateApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        initDefaultPrefs()
    }

    private fun initDefaultPrefs() {
        val prefs = getSharedPreferences("automate_prefs", MODE_PRIVATE)
        if (!prefs.contains("initialized")) {
            prefs.edit()
                .putInt("work_hours", 9)
                .putBoolean("morning_prompt_enabled", true)
                .putFloat("geofence_radius", 200f)
                .putFloat("exit_watch_distance", 150f)
                .putBoolean("initialized", true)
                .apply()
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val morningPrompt = NotificationChannel(
            CHANNEL_MORNING_PROMPT,
            "Morning Check-in Prompt",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Daily prompt asking if you're going to work"
            enableVibration(true)
        }

        val taskStatus = NotificationChannel(
            CHANNEL_TASK_STATUS,
            "Task Status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications about automation task status"
        }

        val locationAlert = NotificationChannel(
            CHANNEL_LOCATION_ALERT,
            "Location Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts about geofence events and location errors"
            enableVibration(true)
        }

        manager.createNotificationChannels(listOf(morningPrompt, taskStatus, locationAlert))
    }

    companion object {
        const val CHANNEL_MORNING_PROMPT = "morning_prompt"
        const val CHANNEL_TASK_STATUS = "task_status"
        const val CHANNEL_LOCATION_ALERT = "location_alert"
    }
}
