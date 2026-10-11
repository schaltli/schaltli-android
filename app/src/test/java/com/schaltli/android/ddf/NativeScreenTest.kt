package com.schaltli.android.ddf

import android.content.pm.ActivityInfo
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A project's rotation turns the display from its native position, on a
// natively landscape tablet as on a phone (Arno's Sony tablet, 2026-10-11:
// landscape designs came out portrait and portrait ones landscape).
class NativeScreenTest {
    @Test
    fun aPhoneIsNativelyPortraitWhicheverWayItShows() {
        assertFalse(NativeScreen.isNaturalLandscape(360, 679, Surface.ROTATION_0))
        assertFalse(NativeScreen.isNaturalLandscape(679, 333, Surface.ROTATION_90))
        assertFalse(NativeScreen.isNaturalLandscape(679, 333, Surface.ROTATION_270))
    }

    @Test
    fun aTabletIsNativelyLandscapeWhicheverWayItShows() {
        assertTrue(NativeScreen.isNaturalLandscape(1280, 752, Surface.ROTATION_0))
        assertTrue(NativeScreen.isNaturalLandscape(800, 1232, Surface.ROTATION_270))
        assertTrue(NativeScreen.isNaturalLandscape(800, 1232, Surface.ROTATION_90))
    }

    @Test
    fun onAPhoneRotationZeroIsPortrait() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, NativeScreen.requestedOrientation(0, false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, NativeScreen.requestedOrientation(90, false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, NativeScreen.requestedOrientation(180, false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, NativeScreen.requestedOrientation(270, false))
    }

    @Test
    fun onATabletRotationZeroIsLandscapeAndAQuarterTurnPortrait() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, NativeScreen.requestedOrientation(0, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, NativeScreen.requestedOrientation(90, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, NativeScreen.requestedOrientation(180, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, NativeScreen.requestedOrientation(270, true))
    }
}
