package org.pagel.ai.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.TileColors
import org.pagel.ai.ui.upcoming.blockColor
import org.pagel.ai.ui.upcoming.calendarSafe

/** Boss 2026-10-31: Namoz and habit blocks read near red on the Day view. No calendar block colour may be red. */
class CalendarColorsTest {
    /** HSV hue in degrees and saturation of [c]. */
    private fun hueSat(c: Color): Pair<Float, Float> {
        val max = maxOf(c.red, c.green, c.blue)
        val d = max - minOf(c.red, c.green, c.blue)
        if (d == 0f) return 0f to 0f
        var hue = when (max) {
            c.red -> 60f * ((c.green - c.blue) / d)
            c.green -> 60f * (2f + (c.blue - c.red) / d)
            else -> 60f * (4f + (c.red - c.green) / d)
        }
        if (hue < 0f) hue += 360f
        return hue to d / max
    }

    private fun isRed(c: Color): Boolean {
        val (hue, sat) = hueSat(c)
        return (hue >= 345f || hue <= 25f) && sat > 0.25f
    }

    @Test fun `no swatch and no custom hue paints a red calendar block`() {
        val colours = TileColors.SWATCH_NAMES.map { TileColors.stops("x", it).first } +
            (0..359 step 5).map { TileColors.stops("x", "h$it").first }
        for (c in colours) assertTrue("block colour $c reads red", !isRed(calendarSafe(c)))
    }

    @Test fun `a habit or prayer block is mint whatever its project colour`() {
        val red = calendarSafe(TileColors.stops("ababil", null).first)
        assertEquals(Glass.Primary, blockColor(habit = true, project = red))
        assertEquals(red, blockColor(habit = false, project = red))
        assertTrue(!isRed(blockColor(habit = true, project = red)))
    }

    @Test fun `the mint block mark keeps 3 to 1 against white`() {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        val c = Glass.Primary
        val l = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
        assertTrue("mint on white is ${1.05 / (l + 0.05)}:1", 1.05 / (l + 0.05) >= 3.0)
    }
}
