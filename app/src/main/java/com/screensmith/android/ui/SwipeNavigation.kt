package com.screensmith.android.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.composed
import kotlin.math.abs

/**
 * Names the swipe the user just made, so a screen's own `buttonActions` can
 * say what it does.
 *
 * `swipe-left`, `swipe-right`, `swipe-up` and `swipe-down` are four fixed,
 * firmware-invented button ids that the designer offers on every
 * touch-capable device (lib/device-description.ts adds them whenever the DDF
 * supports SoftwareButton, which the Android DDF does). They are bound per
 * screen exactly like a physical button, so once a gesture has a name there
 * is nothing device-specific left - it goes through the same
 * [com.screensmith.android.mqtt.ButtonActionDispatcher] as everything else.
 *
 * The three thresholds are the Waveshare firmware's, and are copied rather
 * than re-tuned because they are about hands, not hardware:
 *
 *   - 60 units of travel, well past a jittery tap.
 *   - A 2:1 axis ratio rather than an absolute drift cap. The firmware ran
 *     an absolute 70px cap and it contradicted itself on real gestures
 *     (recorded 2026-08-22): a 309px swipe up with 90px of drift was
 *     rejected as diagonal while a visibly wonkier 127px swipe with 51px of
 *     drift was accepted, purely because the shorter one drifted less in
 *     absolute terms. A finger pivoting from the wrist drifts in proportion
 *     to how far it travels, so the rule has to be proportional too.
 *   - 900ms, past which a slow drag is not a swipe.
 *
 * Nothing here consumes a pointer event. A tap that a SoftwareButton or a
 * Switch handles travels no distance, so it can never clear the first
 * threshold, and the two gesture detectors coexist without either having to
 * know about the other.
 */
private const val SWIPE_MIN_DISTANCE = 60
private const val SWIPE_AXIS_RATIO = 2
private const val SWIPE_MAX_MS = 900L

fun Modifier.swipeNavigation(onSwipe: (buttonId: String) -> Unit): Modifier = composed {
    val density = LocalDensity.current.density
    pointerInput(onSwipe, density) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startMs = System.currentTimeMillis()
            val startPosition = down.position
            var lastPosition = startPosition

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                lastPosition = change.position
                if (!change.pressed) break
            }

            if (System.currentTimeMillis() - startMs > SWIPE_MAX_MS) return@awaitEachGesture

            // Project units, not device pixels: the thresholds describe a
            // gesture on a 360-unit-wide design, and a phone with twice the
            // density must not need twice the finger travel.
            val dx = (lastPosition.x - startPosition.x) / density
            val dy = (lastPosition.y - startPosition.y) / density

            val buttonId = when {
                abs(dx) > abs(dy) ->
                    if (abs(dx) >= abs(dy) * SWIPE_AXIS_RATIO && abs(dx) >= SWIPE_MIN_DISTANCE) {
                        if (dx > 0) "swipe-right" else "swipe-left"
                    } else {
                        null
                    }
                else ->
                    if (abs(dy) >= abs(dx) * SWIPE_AXIS_RATIO && abs(dy) >= SWIPE_MIN_DISTANCE) {
                        if (dy > 0) "swipe-down" else "swipe-up"
                    } else {
                        null
                    }
            }

            if (buttonId != null) onSwipe(buttonId)
        }
    }
}
