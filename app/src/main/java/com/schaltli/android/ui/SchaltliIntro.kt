package com.schaltli.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The brand mark, animated once and then left standing.
 *
 * A port of the designer repo's `brand/intro.js`, and a deliberate second
 * implementation rather than a video or a GIF: the mark is drawn from the same
 * outlines the wordmark is cut from (BrandGlyphs.kt, generated from
 * brand/glyphs.js), so it is sharp at any size on any phone and weighs 5 KB.
 *
 * The choreography, and what it is of: the pill lies on its side below the
 * baseline with a ball inside it. The ball rolls to the far end, its weight
 * tips the pill upright, the pill rises into its place at the end of the word
 * while the letters fade in one after another, and the ball - carrying on
 * after the pill has stopped - knocks against the top. It is the capsule toy
 * the mark was drawn from.
 *
 * The timings are the ones in intro.js, in seconds, and they are copied on
 * purpose: two implementations of one mark that move differently are two
 * marks.
 */
internal object IntroTiming {
    const val HOLD = 0.35f
    const val ROLL = 0.7f
    const val STAND = 0.5f
    const val RISE = 0.7f
    const val REST = 1.1f

    const val T1 = HOLD
    const val T2 = T1 + ROLL
    const val T3 = T2 + STAND
    const val T4 = T3 + RISE
    const val TOTAL = T4 + REST
}

internal data class IntroPose(
    val angle: Float,
    val ballT: Float,
    val cx: Float,
    val cy: Float,
    val letters: Float,
)

private fun clamp01(u: Float): Float = if (u < 0f) 0f else if (u > 1f) 1f else u

/** How long one letter takes to fade in, as a share of the rise. */
internal const val LETTER_FADE = 0.34f

/**
 * When letter [i] of [count] starts. The starts lie on `[0, 1 - LETTER_FADE]`
 * so that the LAST one is finished exactly when the pill has stopped.
 *
 * The rule intro.js had was `i / (count + 2)`, which for the seven letters of
 * this wordmark started the last one at 6/9 - so at the end of the rise it was
 * only 98 % of the way in, and it stayed there for good, through the resting
 * phase and after. Invisible, and still wrong: an animation that is asked to
 * come to rest has to actually arrive. The spacing barely moves (0.111 to
 * 0.110 per letter); only the end does.
 */
internal fun letterStart(i: Int, count: Int): Float =
    if (count < 2) 0f else (i.toFloat() / (count - 1)) * (1f - LETTER_FADE)

private fun smooth(u: Float): Float = u * u * (3f - 2f * u)

/** Slowly out of balance, then it falls, then a short wobble. */
private fun tip(u: Float): Float {
    if (u >= 1f) return 1f
    if (u > 0.88f) {
        val k = (u - 0.88f) / 0.12f
        return 1f + 0.055f * sin(k * Math.PI.toFloat() * 2.2f) * (1f - k)
    }
    return u.pow(2.1f)
}

internal fun poseAt(t: Float): IntroPose {
    val reach = (BrandGlyphs.CAP_H - BrandGlyphs.CAP_W) / 2f
    // The run-up happens below the baseline, which is why the canvas is taller
    // than the viewBox.
    val lieCy = BrandGlyphs.CAP_Y + BrandGlyphs.CAP_H + 34f - BrandGlyphs.CAP_W / 2f
    val standCy = lieCy - reach
    val restingCx = BrandGlyphs.CAP_X + BrandGlyphs.CAP_W / 2f

    if (t < IntroTiming.T1) {
        return IntroPose(0f, -1f, restingCx - reach, lieCy, 0f)
    }

    if (t < IntroTiming.T2) {
        val ball = -1f + 2f * clamp01((t - IntroTiming.T1) / IntroTiming.ROLL).pow(1.8f)
        return IntroPose(0f, ball, restingCx - reach, lieCy, 0f)
    }

    if (t < IntroTiming.T3) {
        val u = clamp01((t - IntroTiming.T2) / IntroTiming.STAND)
        val angle = (Math.PI.toFloat() / 2f) * tip(u)
        // The ball stays where it is; the pill turns around it.
        return IntroPose(
            angle = angle,
            ballT = 1f,
            cx = restingCx - cos(angle) * reach,
            cy = lieCy - sin(angle) * reach,
            letters = 0f,
        )
    }

    val v = clamp01((t - IntroTiming.T3) / IntroTiming.RISE)
    val e = smooth(v)
    // Inertia: the pill brakes at the top and the ball carries on into it, so
    // almost everything happens in the last third.
    var ball = 1f - 2f * v.pow(2.8f)
    if (v > 0.86f) {
        val j = (v - 0.86f) / 0.14f
        ball += 0.07f * sin(j * Math.PI.toFloat() * 2f) * (1f - j)
    }
    return IntroPose(
        angle = Math.PI.toFloat() / 2f,
        ballT = ball,
        cx = restingCx,
        cy = standCy + (BrandGlyphs.CAP_Y + BrandGlyphs.CAP_H / 2f - standCy) * e,
        letters = v,
    )
}

/**
 * Plays once and stops. [reducedMotion] jumps straight to the end - the same
 * courtesy intro.js pays to `prefers-reduced-motion`, and the same picture.
 */
@Composable
fun SchaltliIntro(
    modifier: Modifier = Modifier,
    ink: Color = Color(0xFF111111),
    knock: Color = Color.White,
    reducedMotion: Boolean = false,
) {
    val letterPaths: List<Path> = remember {
        BrandGlyphs.LETTERS.map { PathParser().parsePathString(it).toPath() }
    }
    var elapsed by remember { mutableFloatStateOf(if (reducedMotion) IntroTiming.TOTAL else 0f) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (elapsed < IntroTiming.TOTAL) {
            val now = withFrameNanos { it }
            elapsed = min((now - start) / 1_000_000_000f, IntroTiming.TOTAL)
        }
        // Nothing after the loop: it has played, and it stays as it landed.
    }

    // The canvas is the viewBox plus the room the run-up needs underneath.
    val artWidth = BrandGlyphs.WIDTH
    val artHeight = BrandGlyphs.HEIGHT + 44f

    Canvas(modifier = modifier) {
        val factor = min(size.width / artWidth, size.height / artHeight)
        val left = (size.width - artWidth * factor) / 2f
        val top = (size.height - artHeight * factor) / 2f
        translate(left, top) {
            scale(factor, factor, Offset.Zero) {
                // The baseline into its place.
                translate(-BrandGlyphs.MIN_X, -BrandGlyphs.MIN_Y) {
                    drawIntro(poseAt(elapsed), letterPaths, ink, knock)
                }
            }
        }
    }
}

private fun DrawScope.drawIntro(pose: IntroPose, letters: List<Path>, ink: Color, knock: Color) {
    // The letters, one after another. Each starts a little later than the last
    // and slides up as it arrives.
    for (i in letters.indices) {
        val start = letterStart(i, letters.size)
        val a = clamp01((pose.letters - start) / LETTER_FADE)
        if (a <= 0f) continue
        translate(0f, (1f - a) * 10f) {
            drawPath(letters[i], ink, alpha = a)
        }
    }

    val reach = (BrandGlyphs.CAP_H - BrandGlyphs.CAP_W) / 2f

    // The pill.
    translate(pose.cx, pose.cy) {
        rotateRad(pose.angle, Offset.Zero) {
            drawRoundRect(
                color = ink,
                topLeft = Offset(-BrandGlyphs.CAP_H / 2f, -BrandGlyphs.CAP_W / 2f),
                size = androidx.compose.ui.geometry.Size(BrandGlyphs.CAP_H, BrandGlyphs.CAP_W),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(BrandGlyphs.CAP_W / 2f),
            )
        }
    }

    // The ball, knocked out of the pill's own colour.
    drawCircle(
        color = knock,
        radius = BrandGlyphs.BALL_R,
        center = Offset(
            pose.cx + cos(pose.angle) * pose.ballT * reach,
            pose.cy + sin(pose.angle) * pose.ballT * reach,
        ),
    )
}
