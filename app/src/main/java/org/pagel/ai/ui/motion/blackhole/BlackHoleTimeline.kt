package org.pagel.ai.ui.motion.blackhole

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/*
 * The black hole delete, our own Kotlin. The numbers (phases, pull, bulge) were learned from riveauxmia's
 * telegram-black-hole-delete (iOS, no licence: read, never copied; D:\Brain\inbox\2026-10-30-telegram-designers.md).
 * Everything in this file is plain maths: no Android, so the unit tests check it on the JVM.
 */

/** The effect's sizes, in one place so Boss can tune them on his A05s. */
object BlackHole {
    /**
     * The hole's radius when fully open, as a share of the screen's width. The source's 90 pt on a 393 pt phone is 0.23;
     * Boss (2026-10-31, watching the demo again): "it becomes LARGE", so 0.45: a hole 0.9 of the screen wide that owns
     * the top half. Its top edge stays at the camera.
     */
    const val RADIUS_FRACTION = 0.45f
    /** The mesh cell: a 72 x 360 dp row is 6 x 30 cells (iOS uses 6 pt; 12 dp is a quarter of the cells). */
    const val CELL_DP = 12f
    /** A list snapshot is kept at 0.75x: it blurs near the hole anyway. */
    const val SNAPSHOT_SCALE = 0.75f
    /** The blurred copy is a quarter of the snapshot; the GPU's bilinear upscale is the blur. */
    const val BLUR_SCALE = 0.25f
    /** Rows heat and fade from 1.6 r down to the horizon at 1.03 r. */
    const val HEAT_FROM = 1.6f
    const val VANISH = 1.03f
    /** The AGSL ring runs only in a box this many radii wide around the hole, at half resolution. */
    const val RING_BOX = 4f
    /** Reduced motion, or the phone could not keep up: the rows fade out in this long instead. */
    const val FADE_MS = 120L
    /** How long the swallow takes to engage once it starts (rows near the hole do not start half faded). */
    const val ENGAGE_S = 0.12f
}

/**
 * One length of the effect. Phases: the hole opens (capsule → circle); the swallow starts at 35 % of the opening (the hole is big now: the rows must not vanish before it has grown over them);
 * the close starts [closeLead] before the last row is gone; a damped outward bulge; a short tail where the glow fades.
 */
data class BlackHoleTiming(
    val open: Float,
    val swallow: Float,
    val closeLead: Float,
    val close: Float,
    val bulge: Float,
    val tail: Float,
    val swallowStartFraction: Float = 0.35f,
) {
    val swallowStart: Float get() = open * swallowStartFraction
    val swallowEnd: Float get() = swallowStart + swallow
    val closeStart: Float get() = swallowEnd - closeLead
    val closeEnd: Float get() = closeStart + close
    val bulgeEnd: Float get() = closeEnd + bulge
    val total: Float get() = bulgeEnd + tail

    companion object {
        /** One task deleted: 1.3 s, short enough to survive a to-do app's many deletes. */
        val Single = BlackHoleTiming(open = 0.5f, swallow = 0.625f, closeLead = 0.06f, close = 0.22f, bulge = 0.26f, tail = 0.08f)
        /** "Clear done" / clear all: the full show, ~1.9 s (the source's 0.8 / 1.15 / 0.3 / 0.34 s). */
        val ClearAll = BlackHoleTiming(open = 0.8f, swallow = 1.0f, closeLead = 0.09f, close = 0.30f, bulge = 0.34f, tail = 0.10f)
    }
}

object HoleEase {
    fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun outQuint(x: Float): Float {
        val u = 1f - x.coerceIn(0f, 1f)
        return 1f - u * u * u * u * u
    }

    fun outCubic(x: Float): Float {
        val u = 1f - x.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    /** No jerk at the start, a long tail: the opening's curve. */
    fun soft(x: Float): Float = outQuint(smoothstep(x)) * 0.6f + smoothstep(smoothstep(x)) * 0.4f

    /** The damped bulge after the close: 0.35 · sin(πu) · e^(−2.2u), outward only. */
    fun bulge(u: Float): Float = max(0f, 0.35f * sin(PI.toFloat() * u.coerceIn(0f, 1f)) * exp(-2.2f * u.coerceIn(0f, 1f)))
}

/** The camera cut-out (or its stand-in under the status bar) as a capsule, in px of one coordinate space. */
data class Capsule(val cx: Float, val cy: Float, val width: Float, val height: Float) {
    val left: Float get() = cx - width / 2f
    val right: Float get() = cx + width / 2f
    val top: Float get() = cy - height / 2f
    val bottom: Float get() = cy + height / 2f
}

/** Where the hole comes from ([capsule]) and where it ends up: a circle of [radius] at ([cx], [cy]). */
data class HoleGeometry(val capsule: Capsule, val radius: Float, val cx: Float, val cy: Float) {
    /** The same geometry in another coordinate space whose origin is ([dx], [dy]) away. */
    fun shifted(dx: Float, dy: Float) = HoleGeometry(capsule.copy(cx = capsule.cx + dx, cy = capsule.cy + dy), radius, cx + dx, cy + dy)
}

/** The hole for a screen [screenWidth] px wide whose camera is [capsule]: out of the camera, big, a little lower. */
fun holeGeometry(capsule: Capsule, screenWidth: Float): HoleGeometry {
    val r = BlackHole.RADIUS_FRACTION * screenWidth
    return HoleGeometry(capsule, r, capsule.cx.coerceIn(0.6f * r, screenWidth - 0.6f * r), capsule.top + r)
}

/** The hole at one moment: a capsule [width] x [height] px centred ([cx], [cy]), its opacity, and the ring's brightness (0..1). */
data class HoleShape(val width: Float, val height: Float, val cx: Float, val cy: Float, val alpha: Float, val ring: Float) {
    /** The radius the ring circles at: the capsule's mean half size. */
    val ringRadius: Float get() = (width + height) / 4f
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * The hole at time [t] s: the camera's capsule grows into the big circle (and its centre slides down to it), and on the
 * close it shrinks back INTO the camera, then a small outward bulge and the fade. The shape always contains the camera.
 */
fun holeAt(t: Float, timing: BlackHoleTiming, g: HoleGeometry): HoleShape {
    val o = HoleEase.soft(t / timing.open)
    val c = if (t <= timing.closeStart) 0f else HoleEase.outCubic((t - timing.closeStart) / timing.close)
    val k = o * (1f - c)
    val cap = g.capsule
    var w = lerp(cap.width, 2f * g.radius, k)
    var h = lerp(cap.height, 2f * g.radius, k)
    if (t > timing.closeEnd && timing.bulge > 0f) {
        val b = HoleEase.bulge((t - timing.closeEnd) / timing.bulge)
        w += 2f * g.radius * 0.3f * b
        h += 2f * g.radius * 0.3f * b
    }
    val fadeIn = HoleEase.smoothstep(t / (timing.open * 0.25f))
    val fadeOut = if (timing.tail > 0f) 1f - HoleEase.smoothstep((t - timing.bulgeEnd) / timing.tail) else if (t >= timing.bulgeEnd) 0f else 1f
    return HoleShape(w, h, lerp(cap.cx, g.cx, k), lerp(cap.cy, g.cy, k), fadeIn * fadeOut, k)
}

/** The frame clock's step: a dropped frame continues (at most 1/30 s), it never jumps. */
object FrameStep {
    const val MAX = 1f / 30f
    fun step(rawSeconds: Float): Float = rawSeconds.coerceIn(0f, MAX)
}

/** Full effect, or the 120 ms fade (reduced motion: the system's animator scale is 0). */
enum class HoleMode { Full, Fade }

fun chooseMode(animatorScale: Float): HoleMode = if (animatorScale == 0f) HoleMode.Fade else HoleMode.Full

/**
 * Two missed frame budgets in a row and the effect gives up for the fade (never stutter). The first [warmup] frames
 * do not count: they carry the shader compile.
 */
class BudgetWatch(private val warmup: Int = 2, private val missSeconds: Float = 0.025f) {
    private var frames = 0
    private var misses = 0

    /** One frame took [rawSeconds]; true = give up now. */
    fun frame(rawSeconds: Float): Boolean {
        frames++
        if (frames <= warmup) return false
        misses = if (rawSeconds > missSeconds) misses + 1 else 0
        return misses >= 2
    }
}

/**
 * When the soft ticks of the swallow fire, in seconds into the run: they start slow and come faster, so the pull feels
 * like it speeds up (Boss, 2026-10-31: "a rising soft haptic during the swallow"). At most [max] of them; the gap
 * shrinks by a fifth each time, from 0.2 s down to 0.06 s.
 */
fun swallowTicks(timing: BlackHoleTiming, max: Int = 9): List<Float> {
    val out = ArrayList<Float>()
    var t = timing.swallowStart + 0.12f
    var gap = 0.2f
    while (t < timing.closeStart - 0.05f && out.size < max) {
        out += t
        t += gap
        gap = kotlin.math.max(0.06f, gap * 0.8f)
    }
    return out
}
