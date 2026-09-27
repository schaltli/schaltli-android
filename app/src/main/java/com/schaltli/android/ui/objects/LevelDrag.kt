package com.schaltli.android.ui.objects

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Follows one finger on a settable level from touch to lift: every new value
 * it points at is handed on as it comes ([onValue] with final = false), and
 * the last one again when it lifts (final = true).
 *
 * A level took only taps until 2026-09-27, so a dimmer could not be dragged
 * on the phone while the lamp and the boards follow a finger - and the
 * feedback of the light turning up as the finger moves is the point of a
 * dimmer. What reaches the broker, and how often, is the caller's: it
 * publishes every 100 ms and always on the lift, as the boards do.
 *
 * [valueAt] turns a point into the value it stands for, or null where it
 * stands for none (the centre of a ring). The gesture is the level's from
 * the first touch on - consumed, so nothing behind it pages or scrolls.
 */
suspend fun PointerInputScope.trackLevelFinger(
    valueAt: (Offset) -> String?,
    onValue: (value: String, final: Boolean) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        var last = valueAt(down.position)
        last?.let { onValue(it, false) }
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            val value = valueAt(change.position)
            if (value != null && value != last) {
                last = value
                onValue(value, false)
            }
        }
        last?.let { onValue(it, true) }
    }
}
