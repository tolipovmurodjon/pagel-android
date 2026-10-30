package org.pagel.ai.ui.motion.blackhole

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Everything one run needs, made off the main thread before frame 1 (budget: under 30 ms): the snapshot at 0.75x and a
 * quarter-size blurred copy as two bitmap shaders, the mesh, and the flow table. A frame is then [HoleMesh.update] +
 * two drawVertices calls.
 */
class BlackHoleScene private constructor(
    val timing: BlackHoleTiming,
    val cx: Float,
    val cy: Float,
    val radius: Float,
    private val mesh: HoleMesh,
    private val flow: FlowTable,
    private val sharpPaint: Paint,
    private val softPaint: Paint,
) {
    val vertexCount: Int get() = mesh.vertexCount

    fun hole(t: Float): HoleShape = holeAt(t, timing, radius)

    /** Draws the pieces at [t] s into the run. */
    fun drawPieces(canvas: Canvas, t: Float) {
        mesh.update(t - timing.swallowStart, flow, radius)
        val count = mesh.vertexCount * 2
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, count, mesh.pos, 0, mesh.tex, 0, mesh.sharp, 0, mesh.indices, 0, mesh.indices.size, sharpPaint)
        // The blurred copy only once something is near the horizon (before that every soft alpha is 0).
        if (t > timing.swallowStart) {
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, count, mesh.pos, 0, mesh.tex, 0, mesh.soft, 0, mesh.indices, 0, mesh.indices.size, softPaint)
        }
    }

    companion object {
        /**
         * [snapshot] covers the overlay's whole box at 1x (its pixel (x, y) is the screen point (x, y) of the box);
         * [pieces] are the parts that fly; the hole sits at ([cx], [cy]) with radius [radius] px; [cell] is the mesh
         * cell in px.
         */
        fun prepare(snapshot: Bitmap, pieces: List<PieceRect>, cx: Float, cy: Float, radius: Float, cell: Float, timing: BlackHoleTiming): BlackHoleScene {
            // A GraphicsLayer snapshot is a HARDWARE bitmap: copy it once so it can be scaled.
            val readable = if (snapshot.config == Bitmap.Config.HARDWARE) snapshot.copy(Bitmap.Config.ARGB_8888, false) else snapshot
            val s = BlackHole.SNAPSHOT_SCALE
            val small = Bitmap.createScaledBitmap(readable, max(1, (readable.width * s).roundToInt()), max(1, (readable.height * s).roundToInt()), true)
            // Two halvings, each filtered: a softer blur than one jump to a quarter.
            val half = Bitmap.createScaledBitmap(small, max(1, small.width / 2), max(1, small.height / 2), true)
            val blurred = Bitmap.createScaledBitmap(half, max(1, half.width / 2), max(1, half.height / 2), true)
            if (half !== small && half !== blurred) half.recycle()
            if (readable !== snapshot && readable !== small) readable.recycle()
            val mesh = HoleMesh(pieces, cx, cy, cell)
            val flow = FlowTable.build(mesh.maxStart, radius, timing.swallow)
            mesh.bind(flow)
            return BlackHoleScene(
                timing, cx, cy, radius, mesh, flow,
                shaderPaint(small, readable.width.toFloat() / small.width, readable.height.toFloat() / small.height),
                shaderPaint(blurred, readable.width.toFloat() / blurred.width, readable.height.toFloat() / blurred.height),
            )
        }

        /** A paint whose bitmap shader samples in screen px (the local matrix undoes the downscale). */
        private fun shaderPaint(bitmap: Bitmap, sx: Float, sy: Float): Paint {
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(Matrix().apply { setScale(sx, sy) })
            return Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        }
    }
}
