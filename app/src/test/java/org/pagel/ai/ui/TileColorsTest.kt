package org.pagel.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pagel.ai.ui.glass.TileColors
import kotlin.math.atan2
import kotlin.math.hypot

class TileColorsTest {
    private fun rgb(c: androidx.compose.ui.graphics.Color) = Triple((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    @Test fun `oklch of pure red comes back as sRGB red`() {
        val (l, a, b) = Triple(0.627955, 0.224863, 0.125846)
        val red = TileColors.oklch(l, hypot(a, b), Math.toDegrees(atan2(b, a)))
        val (r, g, bl) = rgb(red)
        assertEquals(255, r, 1); assertEquals(0, g, 1); assertEquals(0, bl, 1)
    }

    @Test fun `the mint tile matches the browser's stops`() {
        val (a, b) = TileColors.stops("humopedia", null)
        val (r1, g1, b1) = rgb(a)
        assertEquals(101, r1, 2); assertEquals(223, g1, 2); assertEquals(175, b1, 2)
        val (r2, g2, b2) = rgb(b)
        assertEquals(0, r2, 2); assertEquals(160, g2, 2); assertEquals(129, b2, 2)
    }

    @Test fun `a chosen colour wins, an unknown one falls back to the project's default, then slate`() {
        assertEquals("blue", TileColors.nameFor("humopedia", "blue"))
        assertEquals("mint", TileColors.nameFor("humopedia", "neon"))
        assertEquals("slate", TileColors.nameFor("new-project", null))
    }

    private fun assertEquals(want: Int, got: Int, slack: Int) =
        org.junit.Assert.assertTrue("want $want±$slack, got $got", kotlin.math.abs(want - got) <= slack)
}
