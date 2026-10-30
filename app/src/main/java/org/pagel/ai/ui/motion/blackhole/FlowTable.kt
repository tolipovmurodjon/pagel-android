package org.pagel.ai.ui.motion.blackhole

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * The swallow, solved once before frame 1 so the frame loop does no maths per point but one bilinear read.
 *
 * A point at distance D from the hole's centre falls along its ray with speed v = V · a(τ) · (1 + P·e^(−D/k)),
 * P = [PULL], k = [REACH] · r: near the hole it moves up to 4x faster, so a row stretches ("spaghetti"). a(τ) eases in
 * over [RAMP] of the swallow and then keeps accelerating. The equation separates: with
 * F(D) = k · ln((e^(D/k) + P) / (1 + P)) it reads F(D(τ)) = F(D₀) − V · W(τ), W = ∫a, and F has a closed inverse.
 * V is chosen so the farthest point reaches the horizon ([BlackHole.VANISH] · r) exactly at the swallow's end.
 *
 * The table holds D for [N] start distances x [N] times; row i is start distance maxStart · i / (N − 1).
 */
class FlowTable private constructor(
    val maxStart: Float,
    val vanish: Float,
    val swallow: Float,
    val n: Int,
    private val table: FloatArray,
) {
    /** The distance at [tau] s into the swallow of a point that started [start] px away. */
    fun distance(start: Float, tau: Float): Float {
        val x = (start / maxStart).coerceIn(0f, 1f) * (n - 1)
        val i = x.toInt().coerceAtMost(n - 2)
        return read(i, x - i, tau)
    }

    /** The start distance's row [i] and its weight [fx] (fixed per point, so the mesh keeps them) at [tau]. */
    fun read(i: Int, fx: Float, tau: Float): Float {
        val y = (tau / swallow).coerceIn(0f, 1f) * (n - 1)
        val j = y.toInt().coerceAtMost(n - 2)
        val fy = y - j
        val a = table[i * n + j]
        val b = table[i * n + j + 1]
        val c = table[(i + 1) * n + j]
        val d = table[(i + 1) * n + j + 1]
        val top = a + (b - a) * fy
        val bottom = c + (d - c) * fy
        return top + (bottom - top) * fx
    }

    /** The raw table value: start row [i], time column [j]. */
    fun at(i: Int, j: Int): Float = table[i * n + j]

    companion object {
        const val N = 64
        const val PULL = 3.0
        const val REACH = 0.8
        const val RAMP = 0.3

        /** a(τ): eases in over [RAMP] of the swallow, then speeds up gently (the source's late acceleration). */
        fun accel(tau: Double, swallow: Double): Double {
            val s = HoleEase.smoothstep((tau / (RAMP * swallow)).toFloat()).toDouble()
            val u = tau / swallow
            return s * (1.0 + 1.5 * u * u)
        }

        /** W(τ) = ∫₀^τ a at the table's [n] times (trapezoids, 16 per column). */
        fun push(swallow: Double, n: Int = N): DoubleArray {
            val w = DoubleArray(n)
            val sub = 16
            val dt = swallow / (n - 1) / sub
            var acc = 0.0
            for (j in 1 until n) {
                for (k in 0 until sub) {
                    val t0 = ((j - 1) * sub + k) * dt
                    acc += 0.5 * (accel(t0, swallow) + accel(t0 + dt, swallow)) * dt
                }
                w[j] = acc
            }
            return w
        }

        /** F(D), written so a large D/k never overflows. */
        fun potential(d: Double, k: Double): Double = d + k * ln(1.0 + PULL * exp(-d / k)) - k * ln(1.0 + PULL)

        /** F⁻¹: the distance whose potential is [f]; 0 once it has sunk past the centre. */
        fun inverse(f: Double, k: Double): Double {
            if (f <= 0.0) return 0.0
            return max(0.0, f + k * ln(1.0 + PULL) + k * ln(1.0 - PULL / (1.0 + PULL) * exp(-f / k)))
        }

        /** Builds the table for points up to [maxStart] px from a hole of radius [radius] px, swallowing in [swallow] s. */
        fun build(maxStart: Float, radius: Float, swallow: Float, n: Int = N): FlowTable {
            val vanish = BlackHole.VANISH * radius
            val far = max(maxStart, vanish + 1f).toDouble()
            val k = REACH * radius
            val w = push(swallow.toDouble(), n)
            val speed = (potential(far, k) - potential(vanish.toDouble(), k)) / w[n - 1]
            val table = FloatArray(n * n)
            for (i in 0 until n) {
                val f0 = potential(far * i / (n - 1), k)
                for (j in 0 until n) table[i * n + j] = inverse(f0 - speed * w[j], k).toFloat()
            }
            return FlowTable(far.toFloat(), vanish, swallow, n, table)
        }
    }
}
