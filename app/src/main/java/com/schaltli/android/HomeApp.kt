package com.schaltli.android

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

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
}
