package com.automate.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.automate.AutoMateApp
import com.automate.MainActivity

/**
 * Accessibility cannot be self-granted on stock Android, so the only correct behaviour
 * is to detect the state accurately and ask the user once, clearly, with a link to the
 * exact settings page. The previous watchdog loop logged "re-enabled" regardless of
 * outcome and KeepAliveService kept yanking the Accessibility Settings activity to the
 * foreground every 10 seconds, which made the app look broken.
 */
object AccessibilityNotifier {

    private const val TAG = "AccessibilityNotifier"
    private const val CHANNEL_ID = "accessibility_action_required"
    private const val NOTIFICATION_ID = 8891

    private fun channel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Accessibility Action Required",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when AutoMate needs the Accessibility Service switched on"
                enableVibration(true)
            }
        )
    }

    /** Settings page for this specific app, so the user only has to flip one toggle. */
    private fun settingsIntent(context: Context): Intent {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return intent
    }

    fun postActionRequired(context: Context) {
        try {
            channel(context)

            val settings = PendingIntent.getActivity(
                context, 0, settingsIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val openApp = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("AutoMate needs Accessibility")
                .setContentText(
                    "Tap to switch on AutoMate so time-in and time-out can run."
                )
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "AutoMate cannot enable its own Accessibility Service. " +
                            "Open Accessibility settings, choose AutoMate, and turn it on. " +
                            "Until then, attendance automation stays idle."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setContentIntent(settings)
                .addAction(0, "Open AutoMate", openApp)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()

            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)

            Log.i(TAG, "Posted action-required notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post accessibility notification", e)
        }
    }

    fun clear(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }
}
