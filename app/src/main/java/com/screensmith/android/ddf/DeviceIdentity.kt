package com.screensmith.android.ddf

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Who this phone says it is, to the broker and to the designer.
 *
 * The id has to be the same every time the app starts: it is the key the
 * designer files a DDF under, and it is the retained `hello` topic's own
 * segment. The MQTT client identifier used to be
 * `screensmith-android-<timestamp>`, new on every launch - harmless for a
 * subscriber, but a retained announcement under it would leave one more
 * corpse on the broker per app start.
 *
 * `ANDROID_ID` is stable for as long as the app is installed and needs no
 * storage of its own. Reinstalling the app produces a new one, and the
 * designer then sees a new device - which is the truth as far as it can tell.
 */
object DeviceIdentity {
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val suffix = (raw ?: "").filter { it.isLetterOrDigit() }.takeLast(8).ifEmpty { "unknown" }
        return "android-$suffix"
    }

    /**
     * What the picker in the designer shows. `Build.MODEL` is "Pixel 7" or
     * "SM-A546B" - more than anyone would type, and it needs no settings
     * field until there are two phones to tell apart.
     */
    fun deviceName(): String = Build.MODEL?.trim()?.ifEmpty { null } ?: "Android Phone"

    /** The MQTT client id, which is also the topic segment under `screenbee/`. */
    fun clientId(context: Context): String = deviceId(context)
}
