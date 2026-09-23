package com.schaltli.android

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf

/**
 * The panel going dark after a while without a touch, the way every Schaltli
 * board does ("Turn the display off after" in its setup page).
 *
 * The screen is never really switched off. A phone whose screen is off does not
 * see touches at all - its touch controller sleeps with it - so the only way
 * back would be the power key. Instead the app draws black and turns its own
 * window down to the lowest brightness: on an OLED phone that is dark, on an
 * LCD phone nearly so, and a touch still arrives.
 *
 * The touch that wakes the panel does nothing else. Someone reaching for a dark
 * panel at night must not switch the light by doing it - the same rule the
 * boards follow. The whole gesture that woke it is swallowed, down to the
 * finger lifting.
 *
 * Values arriving over MQTT do not count as activity: a panel showing live
 * readings would otherwise never go dark.
 */
class ScreenSleep(private val activity: Activity) {
    /** Read by the UI, which draws black over everything while it is true. */
    val asleep = mutableStateOf(false)

    private val handler = Handler(Looper.getMainLooper())
    private var seconds = DEFAULT_SECONDS
    private var swallowing = false
    private val fallAsleep = Runnable { sleep() }

    /** 0 keeps the screen on for good. */
    fun setTimeout(seconds: Int) {
        this.seconds = seconds.coerceAtLeast(0)
        if (this.seconds == 0) wake() else restart()
    }

    /**
     * Every touch passes through here first. Returns true when it was spent
     * waking the panel, in which case nothing else may see it.
     */
    fun onTouch(event: MotionEvent): Boolean {
        if (asleep.value || swallowing) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swallowing = true
                    wake()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swallowing = false
            }
            return true
        }
        restart()
        return false
    }

    fun onResume() = wake()

    fun onPause() = handler.removeCallbacks(fallAsleep)

    private fun restart() {
        handler.removeCallbacks(fallAsleep)
        if (seconds > 0) handler.postDelayed(fallAsleep, seconds * 1000L)
    }

    private fun sleep() {
        asleep.value = true
        setBrightness(DARKEST)
    }

    private fun wake() {
        asleep.value = false
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        restart()
    }

    private fun setBrightness(value: Float) {
        val attributes = activity.window.attributes
        attributes.screenBrightness = value
        activity.window.attributes = attributes
    }

    companion object {
        const val DEFAULT_SECONDS = 60

        // The lowest brightness a window can ask for without asking for the
        // screen to be switched off, which some phones would take literally.
        private const val DARKEST = 0.01f
    }
}
