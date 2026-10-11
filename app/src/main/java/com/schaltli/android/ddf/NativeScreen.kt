package com.schaltli.android.ddf

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.Surface
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.nativeScreenDataStore by preferencesDataStore(name = "native-screen")

/**
 * The screen this phone has when it is the right way up.
 *
 * A Device Description File always describes a device in its *native*
 * orientation, and a project's rotation is applied on top of that - the
 * designer swaps width and height itself for a quarter turn
 * (lib/device-description.ts, resolveRotatedScreenSize). So a phone that
 * announced whatever it was currently showing would be describing an
 * already-turned screen, and the designer would turn it again.
 *
 * It cannot simply be measured on demand, because the app holds the activity
 * in whatever orientation the loaded project asks for: with a landscape
 * project installed, this phone never sees its own portrait configuration
 * again. Nor can portrait be recovered from the landscape numbers - the
 * system bars take a different amount of room along each edge, so the P20
 * this was built against reports 360x679 upright and 679x333 on its side,
 * which is not the same screen either way round.
 *
 * So it is remembered. Whenever the activity is upright the measurement is
 * written down, and that is what gets announced from then on, whichever way
 * the project has since turned the phone.
 */
object NativeScreen {
    private object Keys {
        val WIDTH = intPreferencesKey("native_width_dp")
        val HEIGHT = intPreferencesKey("native_height_dp")
    }

    data class Size(val widthDp: Int, val heightDp: Int)

    /** True when the display is in one of its two upright positions. */
    fun isUpright(rotation: Int): Boolean = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180

    /**
     * True for a device that is wider than tall the right way up - most
     * tablets. Read from what is showing and which way up it is: wide and
     * upright, or tall and on its side.
     */
    fun isNaturalLandscape(widthDp: Int, heightDp: Int, rotation: Int): Boolean =
        (widthDp > heightDp) == isUpright(rotation)

    /**
     * The orientation to hold the activity in for a project's rotation:
     * the display turned by that many degrees from its own native position.
     *
     * Until 2026-10-11 rotation 0 was always portrait, which is native only on
     * a phone. On a tablet that is natively landscape every project came out
     * the other way round: a landscape design turned up portrait and a
     * portrait one landscape (Arno's Sony tablet, Pekaway forum). Android
     * names its orientations by shape, not by turn, so on such a device 0 is
     * landscape and portrait is 270 (Android's default; 90 is upside down).
     */
    fun requestedOrientation(rotation: Int, naturalLandscape: Boolean): Int =
        if (naturalLandscape) when (rotation) {
            90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            270 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else when (rotation) {
            90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

    /**
     * The size to announce, given what the activity is showing right now.
     *
     * Upright: this measurement is the truth, and is remembered. On its side:
     * whatever was remembered, or - on a phone that has never once been
     * upright while this app ran - the current numbers turned back, which is
     * approximate in exactly the way described above and better than
     * describing a screen the wrong way round.
     */
    suspend fun resolve(context: Context, widthDp: Int, heightDp: Int, rotation: Int): Size {
        if (isUpright(rotation)) {
            val size = Size(widthDp, heightDp)
            context.nativeScreenDataStore.edit { prefs ->
                prefs[Keys.WIDTH] = size.widthDp
                prefs[Keys.HEIGHT] = size.heightDp
            }
            return size
        }

        val prefs = context.nativeScreenDataStore.data.first()
        val width = prefs[Keys.WIDTH]
        val height = prefs[Keys.HEIGHT]
        if (width != null && height != null) return Size(width, height)

        // On its side, so native is the other way round - tall for a phone,
        // wide for a tablet. (Until 2026-10-11 always tall.)
        return Size(heightDp, widthDp)
    }
}
