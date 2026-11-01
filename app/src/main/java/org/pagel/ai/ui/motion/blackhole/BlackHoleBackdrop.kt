package org.pagel.ai.ui.motion.blackhole

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The lens bends the PAGE, and the page copied at the tap still shows the rows that are about to fly. Where the pieces
 * were, each column is filled from the pixel just above their block to the pixel just below it (the page there is a
 * smooth aurora), so the lens never bends a second, stationary copy of the rows.
 */
internal object BlackHoleBackdrop {
    /** [bitmap] is the window at half size; [pieces] are in the snapshot box, whose origin is ([shiftX], [shiftY]) window px. */
    fun paintOutPieces(bitmap: Bitmap, pieces: List<PieceRect>, shiftX: Float, shiftY: Float) {
        if (pieces.isEmpty() || !bitmap.isMutable) return
        val left = ((pieces.minOf { it.left } + shiftX) / 2f).roundToInt().coerceIn(0, bitmap.width - 1)
        val right = ((pieces.maxOf { it.right } + shiftX) / 2f).roundToInt().coerceIn(left + 1, bitmap.width)
        val top = ((pieces.minOf { it.top } + shiftY) / 2f).roundToInt().coerceIn(1, bitmap.height - 2)
        val bottom = ((pieces.maxOf { it.bottom } + shiftY) / 2f).roundToInt().coerceIn(top + 1, bitmap.height - 2)
        val width = right - left
        val above = IntArray(width)
        val below = IntArray(width)
        bitmap.getPixels(above, 0, width, left, top - 1, width, 1)
        bitmap.getPixels(below, 0, width, left, min(bottom + 1, bitmap.height - 1), width, 1)
        val line = IntArray(width)
        val span = max(1, bottom - top)
        for (y in top..bottom) {
            val f = (y - top).toFloat() / span
            for (x in 0 until width) line[x] = mix(above[x], below[x], f)
            bitmap.setPixels(line, 0, width, left, y, width, 1)
        }
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = (((a ushr shift) and 0xFF) * (1f - f) + ((b ushr shift) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
