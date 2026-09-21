package com.screensmith.android.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.screensmith.android.data.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Names the swipe the user just made, so a screen's own `buttonActions` can
 * say what it does - and, when that turns out to be a move to another screen,
 * carries the picture along under the finger while it is being made.
 *
 * `swipe-left`, `swipe-right`, `swipe-up` and `swipe-down` are four fixed,
 * firmware-invented button ids that the designer offers on every
 * touch-capable device (lib/device-description.ts adds them whenever the DDF
 * supports SoftwareButton, which the Android DDF does). They are bound per
 * screen exactly like a physical button, so once a gesture has a name there
 * is nothing device-specific left - it goes through the same
 * [com.screensmith.android.mqtt.ButtonActionDispatcher] as everything else.
 *
 * The three thresholds for *naming* a gesture are the Waveshare firmware's,
 * and are copied rather than re-tuned because they are about hands, not
 * hardware:
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

/**
 * When a followed gesture counts as "go", also the Waveshare firmware's
 * (`FollowSwipe.h`): a third of the way across, or a flick - fast enough and
 * far enough that stopping short was plainly not the intent.
 *
 * The flick rule exists because distance is exactly what gets lost on a
 * throw. It was added to the firmware for a panel that could sample a fast
 * gesture only two or three times; a phone samples it far better, but the
 * rule costs nothing and a hard flick across a screen means the same thing on
 * both.
 */
private const val FLICK_UNITS_PER_SECOND = 400
private const val FLICK_MIN_UNITS = 30
private const val COMMIT_FRACTION = 3

/**
 * How long to wait for a screen that was asked for before giving up on it and
 * putting the display back to normal. Generous: the alternative to waiting
 * too long is a display frozen mid-transition.
 */
private const val ARRIVAL_TIMEOUT_MS = 1500L

/** Roughly the firmware's glide (120 units a frame at 15fps), as a duration. */
private const val GLIDE_UNITS_PER_SECOND = 1800f
private const val GLIDE_MIN_MS = 80
private const val GLIDE_MAX_MS = 260

/**
 * Draws [screen], and slides it aside under the finger when the swipe being
 * made is bound to another screen.
 *
 * **The picture follows the hand, not the binding.** A finger moving left
 * always pushes the current screen out to the left and brings the next one in
 * from the right - even where the project has bound a leftward swipe to the
 * *previous* screen, which it is free to do. Tying the direction of travel to
 * which screen arrives would have such a project sliding its screens
 * backwards under the finger. This is the firmware's own rule, written down
 * in `FollowSwipe.h` for the same reason.
 *
 * Only horizontal swipes are followed, and only when they lead somewhere: a
 * vertical swipe, and a horizontal one bound to an MQTT message or the screen
 * menu, have no second picture to bring in, so they are named on release as
 * they always were.
 *
 * @param followTargetFor the screen a given swipe would arrive at, or null
 *   when that swipe is not a move to another screen. Resolved by the caller
 *   through `ButtonActionDispatcher.navigationTarget`, so what slides in is
 *   what lands.
 * @param onSwipe named gesture, dispatched exactly as a button press is.
 * @param render draws one screen.
 */
@Composable
fun FollowingScreens(
    screen: Screen,
    followTargetFor: (buttonId: String) -> Screen?,
    onSwipe: (buttonId: String) -> Unit,
    modifier: Modifier = Modifier,
    render: @Composable (Screen) -> Unit,
) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    var offsetPx by remember { mutableFloatStateOf(0f) }
    var incoming by remember { mutableStateOf<Screen?>(null) }
    // +1 when the incoming screen waits on the right, which is what a finger
    // moving left produces.
    var incomingSide by remember { mutableIntStateOf(1) }
    var settling by remember { mutableStateOf(false) }
    // Set between asking for the move and the moved-to screen turning up.
    var awaitingArrival by remember { mutableStateOf(false) }

    // The transition is not over when the movement stops. It is over when the
    // screen that was asked for is the one being handed in.
    //
    // Clearing the offset and the second picture the moment the move was
    // asked for looked right and was not: the offset is read in a layout
    // lambda and lands on the very next frame, while the new screen has to
    // come back down through the caller and be composed - which includes
    // decoding its background. Measured on a P20 on 2026-09-21, that gap was
    // 148ms, and for all of it the *outgoing* screen sat back in the middle
    // of the display. A flash of the picture you just swiped away.
    //
    // It only showed on a gesture let go near the threshold, where the glide
    // still has most of the screen to cover; a swipe carried nearly all the
    // way leaves so little glide that the eye misses it. Reported from the
    // hand, which is the instrument that catches this sort of thing.
    LaunchedEffect(screen, awaitingArrival) {
        if (!awaitingArrival) return@LaunchedEffect
        if (screen.id == incoming?.id) {
            offsetPx = 0f
            incoming = null
            awaitingArrival = false
            settling = false
            return@LaunchedEffect
        }
        // It should always arrive - the screen slid in is the screen resolved
        // for the binding, by the same function that dispatches it - but a
        // transition that waits for ever is a frozen display, so there is a
        // way out.
        delay(ARRIVAL_TIMEOUT_MS)
        if (awaitingArrival) {
            offsetPx = 0f
            incoming = null
            awaitingArrival = false
            settling = false
        }
    }

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(screen, density, widthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startMs = System.currentTimeMillis()
                        val start = down.position
                        var last = start

                        // Set once this gesture has been recognised as a
                        // horizontal move to somewhere. From then on it owns
                        // the gesture, and release is decided on distance.
                        var followedId: String? = null

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            last = change.position

                            if (!settling) {
                                val dx = (last.x - start.x) / density
                                val dy = (last.y - start.y) / density
                                if (followedId == null && isHorizontalSwipe(dx, dy)) {
                                    val id = if (dx > 0) "swipe-right" else "swipe-left"
                                    val target = followTargetFor(id)
                                    if (target != null) {
                                        followedId = id
                                        incoming = target
                                        incomingSide = if (dx > 0) -1 else 1
                                    }
                                }
                                if (followedId != null) {
                                    // Clamped to one screen, and to the side
                                    // the gesture set off towards: dragging
                                    // back past where it began would
                                    // otherwise uncover nothing at all on the
                                    // other side.
                                    val travel = last.x - start.x
                                    offsetPx = if (incomingSide > 0) {
                                        travel.coerceIn(-widthPx, 0f)
                                    } else {
                                        travel.coerceIn(0f, widthPx)
                                    }
                                }
                            }

                            if (!change.pressed) break
                        }

                        val elapsed = System.currentTimeMillis() - startMs
                        val dx = (last.x - start.x) / density
                        val dy = (last.y - start.y) / density

                        val followed = followedId
                        if (followed != null) {
                            // No time limit on a followed gesture, unlike a
                            // named one: a slow, deliberate drag most of the
                            // way across is the clearest "go" there is, and
                            // the firmware judges these on distance alone too.
                            val travelled = abs(offsetPx) / density
                            val speed = if (elapsed > 0) travelled * 1000f / elapsed else 0f
                            val flick = speed >= FLICK_UNITS_PER_SECOND && travelled >= FLICK_MIN_UNITS
                            val commit = travelled >= (widthPx / density) / COMMIT_FRACTION || flick

                            settling = true
                            scope.launch {
                                val end = if (commit) -incomingSide * widthPx else 0f
                                glide(from = offsetPx, to = end, density = density) { offsetPx = it }
                                if (commit) {
                                    onSwipe(followed)
                                    // Everything stays where the glide left
                                    // it - the incoming screen centred, the
                                    // outgoing one off the edge - until the
                                    // new screen actually arrives. See
                                    // awaitingArrival.
                                    awaitingArrival = true
                                } else {
                                    // Nothing was asked for, so there is
                                    // nothing to wait for.
                                    offsetPx = 0f
                                    incoming = null
                                    settling = false
                                }
                            }
                            return@awaitEachGesture
                        }

                        if (elapsed > SWIPE_MAX_MS) return@awaitEachGesture
                        nameSwipe(dx, dy)?.let(onSwipe)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.offset { IntOffset(offsetPx.roundToInt(), 0) },
                contentAlignment = Alignment.Center,
            ) {
                render(screen)
            }
        }

        incoming?.let { next ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((offsetPx + incomingSide * widthPx).roundToInt(), 0) },
                contentAlignment = Alignment.Center,
            ) {
                render(next)
            }
        }
    }
}

/** Far enough, and straight enough, to be a sideways swipe rather than a drift. */
private fun isHorizontalSwipe(dx: Float, dy: Float): Boolean =
    abs(dx) >= SWIPE_MIN_DISTANCE && abs(dx) >= abs(dy) * SWIPE_AXIS_RATIO

/** The button id for a finished gesture, or null if it was not a swipe at all. */
internal fun nameSwipe(dx: Float, dy: Float): String? = when {
    abs(dx) > abs(dy) ->
        if (isHorizontalSwipe(dx, dy)) {
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

/**
 * Finishes the movement rather than jumping it. The firmware steps 120 units
 * a frame; this asks for the same rate as a duration, so the last stretch
 * takes about as long whatever is left of it.
 */
private suspend fun glide(from: Float, to: Float, density: Float, onValue: (Float) -> Unit) {
    val distanceUnits = abs(to - from) / density
    val ms = (distanceUnits / GLIDE_UNITS_PER_SECOND * 1000f).roundToInt()
        .coerceIn(GLIDE_MIN_MS, GLIDE_MAX_MS)
    animate(initialValue = from, targetValue = to, animationSpec = tween(durationMillis = ms)) { value, _ ->
        onValue(value)
    }
}
