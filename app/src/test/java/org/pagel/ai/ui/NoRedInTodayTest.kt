package org.pagel.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Boss 2026-10-31: never red. No colour literal in Today, Upcoming or the task panel is red (HSV hue 345-25 deg, saturation over 0.25). */
class NoRedInTodayTest {
    private val literal = Regex("0x[Ff][Ff]([0-9A-Fa-f]{6})")

    private fun isRed(rgb: Int): Boolean {
        val r = (rgb shr 16 and 255) / 255f; val g = (rgb shr 8 and 255) / 255f; val b = (rgb and 255) / 255f
        val max = maxOf(r, g, b); val d = max - minOf(r, g, b)
        if (d == 0f || d / max <= 0.25f) return false
        var hue = when (max) { r -> 60f * ((g - b) / d); g -> 60f * (2f + (b - r) / d); else -> 60f * (4f + (r - g) / d) }
        if (hue < 0f) hue += 360f
        return hue >= 345f || hue <= 25f
    }

    @Test fun `no red colour literal in today, upcoming or the task panel`() {
        val found = listOf("today", "upcoming", "task").flatMap { dir ->
            val d = listOf("src/main/java/org/pagel/ai/ui/$dir", "app/src/main/java/org/pagel/ai/ui/$dir").map(::File).first { it.isDirectory }
            d.listFiles { f -> f.extension == "kt" }!!.flatMap { f ->
                f.readLines().flatMapIndexed { i, line ->
                    literal.findAll(line).filter { isRed(it.groupValues[1].toInt(16)) }.map { "${f.name}:${i + 1} ${it.value}" }.toList()
                }
            }
        }
        assertEquals("red colours left in: $found", emptyList<String>(), found)
    }
}
