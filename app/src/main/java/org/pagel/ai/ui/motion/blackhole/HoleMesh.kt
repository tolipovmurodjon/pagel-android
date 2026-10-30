package org.pagel.ai.ui.motion.blackhole

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** One part of the snapshot that flies, in px: where it sits on screen (and in the snapshot) at t = 0. */
data class PieceRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Every piece as a grid of cells (two triangles each), built once before frame 1. Per frame [update] does one
 * bilinear table read per point and writes the positions and the two colour sets in place: no allocation.
 * [tex] is each point's place at rest, which is also where it samples the snapshot.
 */
class HoleMesh(pieces: List<PieceRect>, private val cx: Float, private val cy: Float, cell: Float) {
    val vertexCount: Int
    val tex: FloatArray
    val pos: FloatArray
    /** The sharp snapshot's colours (alpha, and the gold heat multiplied in). */
    val sharp: IntArray
    /** The blurred copy's colours: it takes over as a point heats. */
    val soft: IntArray
    val indices: ShortArray
    val maxStart: Float
    private val ux: FloatArray
    private val uy: FloatArray
    private val start: FloatArray
    private val row: IntArray
    private val rowWeight: FloatArray

    init {
        // drawVertices takes short indices: a bigger cell if a huge snapshot would pass 32k points.
        var c = cell
        while (count(pieces, c).first > MAX_VERTICES) c *= 1.25f
        val (verts, idx) = count(pieces, c)
        vertexCount = verts
        tex = FloatArray(verts * 2)
        pos = FloatArray(verts * 2)
        sharp = IntArray(verts)
        soft = IntArray(verts)
        indices = ShortArray(idx)
        ux = FloatArray(verts)
        uy = FloatArray(verts)
        start = FloatArray(verts)
        row = IntArray(verts)
        rowWeight = FloatArray(verts)
        var v = 0
        var k = 0
        var far = 0f
        for (p in pieces) {
            val cols = cellsAcross(p.width, c)
            val rows = cellsAcross(p.height, c)
            val base = v
            for (r in 0..rows) for (q in 0..cols) {
                val x = p.left + p.width * q / cols
                val y = p.top + p.height * r / rows
                tex[2 * v] = x
                tex[2 * v + 1] = y
                pos[2 * v] = x
                pos[2 * v + 1] = y
                val dx = x - cx
                val dy = y - cy
                val d = sqrt(dx * dx + dy * dy)
                start[v] = d
                if (d > 1e-3f) { ux[v] = dx / d; uy[v] = dy / d }
                far = max(far, d)
                sharp[v] = WHITE
                v++
            }
            for (r in 0 until rows) for (q in 0 until cols) {
                val a = base + r * (cols + 1) + q
                val b = a + 1
                val d = a + cols + 1
                val e = d + 1
                indices[k++] = a.toShort(); indices[k++] = b.toShort(); indices[k++] = d.toShort()
                indices[k++] = b.toShort(); indices[k++] = e.toShort(); indices[k++] = d.toShort()
            }
        }
        maxStart = far
    }

    /** Fixes each point's start row in [flow] (its start distance never changes). Call once, before frame 1. */
    fun bind(flow: FlowTable) {
        for (v in 0 until vertexCount) {
            val x = (start[v] / flow.maxStart).coerceIn(0f, 1f) * (flow.n - 1)
            val i = x.toInt().coerceAtMost(flow.n - 2)
            row[v] = i
            rowWeight[v] = x - i
        }
    }

    /**
     * Puts every point where it is [tau] s into the swallow (negative: not started, all at rest). Heat runs from
     * [BlackHole.HEAT_FROM] · [radius] to the horizon: the colour warms to gold, the blurred copy takes over, alpha goes.
     */
    fun update(tau: Float, flow: FlowTable, radius: Float) {
        val engaged = if (tau <= 0f) 0f else HoleEase.smoothstep(tau / BlackHole.ENGAGE_S)
        val heatFrom = BlackHole.HEAT_FROM * radius
        val span = max(1f, heatFrom - flow.vanish)
        for (v in 0 until vertexCount) {
            val d = if (tau <= 0f) start[v] else flow.read(row[v], rowWeight[v], tau)
            pos[2 * v] = cx + ux[v] * d
            pos[2 * v + 1] = cy + uy[v] * d
            val far = HoleEase.smoothstep((d - flow.vanish) / span)
            val heat = engaged * (1f - far)
            val alpha = 1f - heat
            val g = (255 - 41 * heat).toInt()
            val b = (255 - 125 * heat).toInt()
            sharp[v] = argb(alpha * (1f - 0.85f * heat), 255, g, b)
            soft[v] = argb(alpha * 0.85f * heat, 255, g, b)
        }
    }

    companion object {
        const val MAX_VERTICES = 32_000
        private const val WHITE = -0x1

        private fun cellsAcross(length: Float, cell: Float) = max(1, ceil(length / cell).toInt())

        private fun count(pieces: List<PieceRect>, cell: Float): Pair<Int, Int> {
            var verts = 0
            var idx = 0
            for (p in pieces) {
                val cols = cellsAcross(p.width, cell)
                val rows = cellsAcross(p.height, cell)
                verts += (cols + 1) * (rows + 1)
                idx += cols * rows * 6
            }
            return verts to idx
        }

        fun argb(alpha: Float, r: Int, g: Int, b: Int): Int =
            ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (r shl 16) or (g shl 8) or b
    }
}
