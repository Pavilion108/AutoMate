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
import com.automate.MainActivity

/**
 * Accessibility cannot be self-granted on stock Android, so the only correct behaviour is
 * to detect the state accurately and ask the user once, clearly, with a link to the exact
 * settings page.
 *
 * MIUI kills this app's process repeatedly, and every kill used to re-post the same
 * "enable Accessibility" notification, which looked like a nagging loop even though the
 * user had already enabled it. The notification is now rate-limited to once per ten
 * minutes so a dying service cannot spam the user.
 */
object AccessibilityNotifier {

    private const val TAG = "AccessibilityNotifier"
    private const val CHANNEL_ID = "accessibility_action_required"
    private const val NOTIFICATION_ID = 8891
    private const val MIN_REPOST_MS = 10 * 60 * 1000L

    /** Monotonic clock of the last notification, so a dying-service loop cannot spam. */
    @Volatile
    private var lastPostedElapsedMs = android.os.SystemClock.elapsedRealtime()

    fun postActionRequired(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPostedElapsedMs < MIN_REPOST_MS) {
            Log.i(TAG, "Action-required notification suppressed (re-requested too soon)")
            return
        }
        lastPostedElapsedMs = now

        try {
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

            /** Settings page for this specific app, so the user only has to flip one toggle. */
            val settings = PendingIntent.getActivity(
                context, 0,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
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
                .setContentText("Tap to switch on AutoMate so time-in and time-out can run.")
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

            manager.notify(NOTIFICATION_ID, notification)
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