package com.schaltli.android

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager

/**
 * Schaltli as the phone's home screen - the kiosk that survives a restart.
 *
 * The manifest offers MainActivity as a home app (category HOME). Chosen as
 * the default one in Android's settings, it is what the phone starts after
 * every boot and what the Home key leads back to, with no helper app and no
 * device-owner provisioning - the usual way a wall panel is built from a phone.
 *
 * Pinning (startLockTask) is the other half of the kiosk, and it only stays
 * for a phone where Schaltli is *not* home: Android asks "pin this app?" every
 * time a non-owner app pins itself, which on a home app means after every
 * restart - a tap nobody is there to give in an empty van. Home already brings
 * every way out back to Schaltli.
 */
object HomeApp {
    /** Whether Android would open Schaltli for the Home key right now. */
    fun isDefault(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * Android's own "Default home app" choice - the way back to the phone's
     * usual launcher, and the way in the first time. Unpins first: a pinned
     * app cannot open another app's screen.
     */
    fun openChooser(activity: Activity) {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) activity.stopLockTask()
        try {
            activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            // Some makers leave that screen out; their general settings still
            // have it, under Apps.
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * The swipe-up lock screen a phone without a PIN still shows after every
     * start and every time the screen comes back on - one more thing nobody
     * is there to do in an empty van. Schaltli shows itself above it and asks
     * Android to put it away.
     *
     * Only a lock screen without a secret: with a PIN, pattern or password set
     * the phone stays locked exactly as its owner chose, and nothing here runs.
     * Android itself refuses to dismiss a secure lock screen without the secret
     * anyway; this just does not ask.
     */
    fun skipSwipeLock(activity: Activity) {
        val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isDeviceSecure) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        if (keyguard.isKeyguardLocked) keyguard.requestDismissKeyguard(activity, null)
    }
}
