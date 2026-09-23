package com.schaltli.android.ddf

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
 * `screensmith-android-<timestamp>` - the product's name at the time - new on
 * every launch: harmless for a subscriber, but a retained announcement under
 * it would leave one more corpse on the broker per app start.
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
     * What the picker in the designer shows, in descending order of how much
     * it sounds like a phone:
     *
     * 1. The vendor's own marketing name. Not in the public API - there is
     *    none - but most vendors put it in a system property, and it is the
     *    only place the words on the box appear. A Huawei P20 Pro reports
     *    `ro.config.marketing_name = "HUAWEI P20 Pro"`; Xiaomi and others use
     *    `ro.product.marketname`.
     * 2. The name the owner gave the device, if they gave it one. Often just
     *    the model again, which is why it is only used when it differs.
     * 3. Maker and model together - "Huawei CLT-L29". Always available, and
     *    at least says who made it.
     *
     * `Build.MODEL` alone was step three until 2026-09-21, and read as a part
     * number in a list beside "Waveshare Knob-Touch LCD 1.8".
     */
    fun deviceName(context: Context? = null): String {
        for (key in listOf("ro.product.marketname", "ro.config.marketing_name")) {
            val marketing = systemProperty(key)
            if (!marketing.isNullOrBlank()) return marketing
        }

        val model = Build.MODEL?.trim().orEmpty()
        if (context != null) {
            val given = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()
            if (!given.isNullOrBlank() && !given.equals(model, ignoreCase = true)) return given
        }

        val maker = Build.MANUFACTURER?.trim().orEmpty()
        return when {
            maker.isNotEmpty() && model.isNotEmpty() -> "${tidyMaker(maker)} $model"
            model.isNotEmpty() -> model
            else -> "Android Phone"
        }
    }

    /** "HUAWEI" is shouted on the property; nobody writes it that way. */
    private fun tidyMaker(maker: String): String =
        if (maker == maker.uppercase()) {
            maker.lowercase().replaceFirstChar { it.uppercase() }
        } else {
            maker
        }

    /**
     * A system property, read the way anything outside the SDK has to be:
     * `android.os.SystemProperties` is hidden and blocked on modern Android,
     * so this asks the same `getprop` a shell would. One process at startup,
     * and a failure is simply "no such name" - the caller has two more
     * answers below it.
     */
    private fun systemProperty(key: String): String? = try {
        Runtime.getRuntime().exec(arrayOf("getprop", key)).inputStream
            .bufferedReader()
            .use { it.readLine() }
            ?.trim()
            ?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    /** The MQTT client id, which is also the topic segment under `schaltli/`. */
    fun clientId(context: Context): String = deviceId(context)
}
