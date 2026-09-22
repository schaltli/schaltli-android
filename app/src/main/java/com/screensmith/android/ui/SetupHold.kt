package com.screensmith.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The one way into setup that no project can take away: hold a finger
 * anywhere for five seconds.
 *
 * The rule is the designer's device contract ("Getting back into setup
 * mode"), and it is the same on every ScreenBee device - a touch panel takes
 * one finger held anywhere, a device whose primary input is a knob takes that
 * knob held. It exists because a way back into a device's configuration must
 * not depend on a decision someone made while designing a project: bind
 * nothing, and there is no way in.
 *
 * On this platform setup is only the broker - the phone brings its own
 * network and there is no access point to raise - but the way in is the same,
 * because a user who knows one ScreenBee device should not have to learn
 * another.
 *
 * What replaced: a gear in the top right corner, drawn over the project on
 * every screen. It was a foreign object in someone's design, and it sat
 * silently inside every HIL comparison at about 0.65% of the screen - under
 * the tolerance, so nothing ever said so (2026-09-22).
 *
 * After one second a countdown appears, and from that moment the gesture
 * belongs to setup: the touch is consumed, so a button under the finger does
 * not fire when it is let go. Someone who lets go early wanted neither setup
 * nor the button they were resting on.
 */
private const val COUNTDOWN_AFTER_MS = 1000L
private const val HOLD_SECONDS = 4

/**
 * How far the finger may wander and still count as held.
 *
 * A hold is not a drag. Paging to the next screen is a finger on the glass
 * for as long as it takes to carry the picture across, and a slow one takes
 * longer than a second - without this, swiping would put a countdown up in
 * the middle of it (and, once claimed, would swallow the swipe). 16 units is
 * about a fingertip's own wobble.
 */
private const val HOLD_SLOP = 16f

@Composable
fun SetupHold(
    enabled: Boolean,
    onEnterSetup: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    // Null while nothing is held. Set on the first finger down, cleared when
    // it goes up - which also cancels the countdown, because the effect below
    // is keyed on it.
    var pressStarted by remember { mutableStateOf<Long?>(null) }
    // Seconds left, or null while the gesture has not claimed the touch yet.
    var remaining by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(pressStarted, enabled) {
        if (pressStarted == null || !enabled) {
            remaining = null
            return@LaunchedEffect
        }
        delay(COUNTDOWN_AFTER_MS)
        remaining = HOLD_SECONDS
        while ((remaining ?: 0) > 0) {
            delay(1000)
            remaining = (remaining ?: 1) - 1
        }
        remaining = null
        pressStarted = null
        onEnterSetup()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        // Watched in the Initial pass, and NOT consumed: a tap
                        // on a control has to reach the control. Only once the
                        // countdown is up does this gesture take the touch for
                        // itself.
                        val down = awaitPointerEvent(PointerEventPass.Initial)
                        val first = down.changes.firstOrNull { it.pressed } ?: continue
                        val from = first.position
                        pressStarted = System.currentTimeMillis()
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            // Once the countdown is up the gesture is setup's,
                            // so the touch is taken and nothing under the
                            // finger fires when it is let go.
                            if (remaining != null) event.changes.forEach { it.consume() }
                            val wandered = event.changes.any {
                                val dx = it.position.x - from.x
                                val dy = it.position.y - from.y
                                dx * dx + dy * dy > HOLD_SLOP * HOLD_SLOP
                            }
                            if (wandered && remaining == null) {
                                // A drag, not a hold: hand the gesture back
                                // before the countdown ever shows.
                                pressStarted = null
                                break
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                        pressStarted = null
                    }
                }
            },
        content = content,
    )

    remaining?.let { left ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .background(Color(0xE6000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "Setup in $left\nlet go to cancel",
                    color = Color.White,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
