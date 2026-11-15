package com.schaltli.android.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Guard: a control that needs a deliberate gesture before it changes (the
 * designer's docs/2026-11-15-guard.md, device contract §2.14).
 *
 * The designer's lib/guard.ts, line for line where pixels or times are
 * concerned: the lock and the double arrow as integer runs (the lock from
 * the round-rectangle spans Adafruit GFX fills), the stripes, and the
 * gesture as a small state machine fed with a clock and finger positions.
 * GuardSpansTest holds the runs to the designer's lib/fixtures/guard-spans.json.
 */
object Guard {
    const val ANIM_MS = 200L
    const val RELOCK_MS = 5000L
    const val TOPUP_MS = 2000L
    const val HINT_MS = 1500L
    const val TAP_SLOP = 8f
    const val WAIT_ALPHA = 0.55f
    const val BOB_PX = 5
    const val BOB_MS = 1000L
    const val STRIPE_PX = 8
    const val LOCK_MIN = 8
    const val HINT = "Slide up to unlock"

    // Fixed, never themed (decision 14).
    const val YELLOW = 0xFFF5C542.toInt()
    const val YELLOW_2 = 0xFFEBB526.toInt()
    const val INK = 0xFF3A2D00.toInt()

    fun stripeIsSecond(x: Int, y: Int): Boolean = Math.floorDiv(x + y, STRIPE_PX) % 2 != 0

    /** Adafruit_GFX::fillRoundRect's rows, [start, end) each: lib/clip.ts roundRectSpans. */
    fun roundRectSpans(wIn: Int, hIn: Int, rIn: Int): List<IntArray> {
        val w = wIn
        val h = hIn
        if (w <= 0 || h <= 0) return emptyList()
        val maxRadius = minOf(w, h) / 2
        val r = rIn.coerceIn(0, maxRadius)
        val lo = IntArray(h) { w }
        val hi = IntArray(h) { -1 }
        fun column(x: Int, y: Int, height: Int) {
            for (row in maxOf(0, y) until minOf(h, y + height)) {
                if (x < lo[row]) lo[row] = x
                if (x > hi[row]) hi[row] = x
            }
        }
        if (w - 2 * r > 0) {
            column(r, 0, h)
            column(w - r - 1, 0, h)
        }
        fun helper(x0: Int, y0: Int, corners: Int, deltaIn: Int) {
            var f = 1 - r
            var ddFx = 1
            var ddFy = -2 * r
            var x = 0
            var y = r
            var px = x
            var py = y
            val delta = deltaIn + 1
            while (x < y) {
                if (f >= 0) {
                    y--
                    ddFy += 2
                    f += ddFy
                }
                x++
                ddFx += 2
                f += ddFx
                if (x < y + 1) {
                    if (corners and 1 != 0) column(x0 + x, y0 - y, 2 * y + delta)
                    if (corners and 2 != 0) column(x0 - x, y0 - y, 2 * y + delta)
                }
                if (y != py) {
                    if (corners and 1 != 0) column(x0 + py, y0 - px, 2 * px + delta)
                    if (corners and 2 != 0) column(x0 - py, y0 - px, 2 * px + delta)
                    py = y
                }
                px = x
            }
        }
        if (r > 0) {
            helper(w - r - 1, r, 1, h - 2 * r - 1)
            helper(r, r, 2, h - 2 * r - 1)
        }
        return List(h) { row -> if (hi[row] < 0) intArrayOf(0, 0) else intArrayOf(lo[row], hi[row] + 1) }
    }

    /** Runs per row, [start, end) pairs. */
    private fun rowsOf(w: Int, h: Int, inside: (Int, Int) -> Boolean): List<List<IntArray>> = List(h) { y ->
        val runs = ArrayList<IntArray>()
        var start = -1
        for (x in 0..w) {
            val on = x < w && inside(x, y)
            if (on && start < 0) start = x
            if (!on && start >= 0) {
                runs.add(intArrayOf(start, x))
                start = -1
            }
        }
        runs
    }

    private fun inSpan(spans: List<IntArray>, x: Int, y: Int): Boolean =
        y >= 0 && y < spans.size && x >= spans[y][0] && x < spans[y][1]

    /** The lock, size x size (guardLockRuns). */
    fun lockRuns(size: Int): List<List<IntArray>> {
        val s = maxOf(1, size)
        val bodyTop = (s * 9) / 20
        val body = roundRectSpans(s, s - bodyTop, maxOf(1, s / 8))
        val a = s / 5
        val t = maxOf(1, s / 7)
        val ow = s - 2 * a
        val outer = roundRectSpans(ow, 2 * bodyTop, ow / 2)
        val iw = ow - 2 * t
        val inner = if (iw > 0) roundRectSpans(iw, maxOf(0, 2 * bodyTop - 2 * t), iw / 2) else emptyList()
        return rowsOf(s, s) { x, y ->
            if (y >= bodyTop) inSpan(body, x, y - bodyTop)
            else if (!inSpan(outer, x - a, y)) false
            else !(y >= t && inSpan(inner, x - a - t, y - t))
        }
    }

    /** The double arrow pointing up, size x size (guardArrowRuns). */
    fun arrowRuns(size: Int): List<List<IntArray>> {
        val s = maxOf(1, size)
        val half = (s - 1) / 2
        val t = maxOf(1, s / 7)
        val chevronRows = (half - 2 + 2 * t) / 2 + 1
        val gap = chevronRows + 1
        val top = maxOf(0, (s - (gap + chevronRows)) / 2)
        fun chevron(x: Int, y: Int): Boolean {
            if (y < 0 || y >= chevronRows) return false
            val d = abs(x - half)
            return d <= half && d >= maxOf(0, 2 * y - 2 * t + 2) && d <= 2 * y + 1
        }
        return rowsOf(s, s) { x, y -> chevron(x, y - top) || chevron(x, y - top - gap) }
    }

    data class Marks(val size: Int, val lockX: Int, val lockY: Int, val arrowsX: Int, val arrowsY: Int)

    /** Where the cover's lock and arrows stand in it (guardCoverMarks). */
    fun coverMarks(x: Int, y: Int, w: Int, h: Int): Marks {
        val size = ((h * 9) / 20).coerceIn(LOCK_MIN, 28).coerceAtLeast(LOCK_MIN)
        val gap = maxOf(3, size / 4)
        val total = 2 * size + gap
        val mx = x + (w - total) / 2
        val my = y + (h - size) / 2
        return Marks(size, mx, my, mx + size + gap, my)
    }

    data class LockBox(val x: Int, val y: Int, val size: Int)

    /** The lock on a Switch's knob (guardKnobLock). */
    fun knobLock(cx: Int, cy: Int, r: Int): LockBox {
        val size = maxOf(LOCK_MIN, (r * 2 * 2) / 5)
        return LockBox(cx - size / 2, cy - size / 2, size)
    }

    fun waitOffset(height: Int): Int = height + 2

    enum class Phase { Pushing, Opening, Open, Dropping }

    /** One cover's state (guardDown/Move/Up/Tick/Changed), immutable. */
    data class Cover(
        val phase: Phase,
        val since: Long,
        val offset: Float = 0f,
        val until: Long = 0L,
        val startX: Float = 0f,
        val startY: Float = 0f,
        val moved: Float = 0f,
        val fromWait: Boolean = false,
    ) {
        val unlocked: Boolean get() = phase == Phase.Opening || phase == Phase.Open

        fun move(now: Long, x: Float, y: Float, travel: Int): Cover {
            if (phase != Phase.Pushing) return this
            val m = maxOf(moved, hypot(x - startX, y - startY))
            val o = maxOf(0f, startY - y)
            return if (o > travel) copy(phase = Phase.Opening, since = now, offset = o, moved = m, until = now + RELOCK_MS)
            else copy(offset = o, moved = m)
        }

        /** The lift: before locking in the cover goes; `second` true for a plain tap (the hint). */
        fun up(now: Long): Pair<Cover, Boolean> =
            if (phase != Phase.Pushing) this to false
            else copy(phase = Phase.Dropping, since = now, fromWait = false) to (moved < TAP_SLOP)

        /** Time passing; null once gone. */
        fun tick(now: Long): Cover? = when {
            phase == Phase.Opening && now - since >= ANIM_MS && now < until -> copy(phase = Phase.Open, since = since + ANIM_MS)
            (phase == Phase.Open || phase == Phase.Opening) && now >= until -> copy(phase = Phase.Dropping, since = now, fromWait = true)
            phase == Phase.Dropping && now - since >= ANIM_MS -> null
            else -> this
        }

        /** A change while it waits: topped up to at least 2 s (decision 13). */
        fun changed(now: Long): Cover = if (!unlocked) this else copy(until = maxOf(until, now + TOPUP_MS))
    }

    fun down(now: Long, x: Float, y: Float) = Cover(Phase.Pushing, now, startX = x, startY = y)

    data class Drawn(val lift: Int, val alpha: Float, val bob: Int)

    private fun easeOutBack(t: Float): Float {
        val s = 1.2f
        val u = t - 1
        return 1 + (s + 1) * u * u * u + s * u * u
    }

    fun drawn(c: Cover, now: Long, height: Int): Drawn {
        val wait = waitOffset(height)
        val bob = (BOB_PX * (1 - cos(2 * Math.PI * (now - c.since) / BOB_MS)) / 2).roundToInt()
        return when (c.phase) {
            Phase.Pushing -> Drawn(minOf(c.offset.roundToInt(), wait), 1f, bob)
            Phase.Opening -> {
                val t = ((now - c.since).toFloat() / ANIM_MS).coerceIn(0f, 1f)
                val from = minOf(c.offset.roundToInt(), wait)
                Drawn((from + (wait - from) * easeOutBack(t)).roundToInt(), 1 - (1 - WAIT_ALPHA) * t, 0)
            }
            Phase.Open -> Drawn(wait, WAIT_ALPHA, 0)
            Phase.Dropping -> {
                val t = ((now - c.since).toFloat() / ANIM_MS).coerceIn(0f, 1f)
                val from = if (c.fromWait) wait else minOf(c.offset.roundToInt(), wait)
                val start = if (c.fromWait) WAIT_ALPHA else 1f
                Drawn((from * (1 - t)).roundToInt(), start * (1 - t), 0)
            }
        }
    }
}
