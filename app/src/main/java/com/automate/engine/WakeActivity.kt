package com.automate.engine

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/**
 * Invisible activity whose only job is to wake the screen and clear a non-secure
 * keyguard, then get out of the way.
 *
 * A geofence fires with the phone in a pocket, and while the screen is off the
 * accessibility service can only see the lock screen, so every lookup returns nothing and
 * the attendance flow fails. There is no accessibility global action for waking the
 * device, and `KeyguardManager.requestDismissKeyguard()` needs an Activity, so this
 * carries the platform flags and finishes immediately without drawing anything.
 *
 * A PIN or pattern lockscreen cannot be dismissed programmatically; that is intentional
 * on Android and no workaround is attempted.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT or
                WindowManager.LayoutParams.FLAG_NO_TITLE
        )

        // No content view: this Activity must never be seen.
        finish()
        overridePendingTransition(0, 0)
    }
}
