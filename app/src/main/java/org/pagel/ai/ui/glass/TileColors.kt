package org.pagel.ai.ui.glass

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The ten project tile colours, two OKLCH stops each, light then dark: the same table as the web
 * (dashboard src/app/tile-colors.ts, brain rules/gradient-project-icons), turned into sRGB here.
 */
object TileColors {
    private val OKLCH: Map<String, Pair<Triple<Double, Double, Double>, Triple<Double, Double, Double>>> = mapOf(
        "mint" to (Triple(0.82, 0.13, 165.0) to Triple(0.62, 0.14, 175.0)),
        "blue" to (Triple(0.74, 0.13, 250.0) to Triple(0.55, 0.17, 262.0)),
        "violet" to (Triple(0.72, 0.15, 295.0) to Triple(0.52, 0.2, 285.0)),
        "coral" to (Triple(0.78, 0.14, 35.0) to Triple(0.62, 0.18, 22.0)),
        "amber" to (Triple(0.86, 0.14, 85.0) to Triple(0.7, 0.16, 62.0)),
        "pink" to (Triple(0.78, 0.14, 350.0) to Triple(0.6, 0.19, 340.0)),
        "teal" to (Triple(0.8, 0.1, 200.0) to Triple(0.58, 0.12, 215.0)),
        "sky" to (Triple(0.84, 0.09, 225.0) to Triple(0.64, 0.13, 238.0)),
        "slate" to (Triple(0.7, 0.03, 260.0) to Triple(0.48, 0.04, 265.0)),
        "red" to (Triple(0.74, 0.16, 25.0) to Triple(0.56, 0.2, 28.0)),
    )

    /** The web's defaults when a project has no colour of its own (src/app/project-icon.tsx DEFAULT_COLOR). */
    private val DEFAULT = mapOf(
        "humopedia" to "mint", "ababil" to "red", "pagel" to "violet", "uzcosmos" to "blue", "me" to "coral", "university" to "sky",
        "gamedev" to "pink", "games" to "slate", "samarqand" to "amber", "labs" to "teal", "motion" to "coral", "muhub" to "sky",
        "ovoz" to "violet", "brilliant" to "amber", "autonomy" to "slate",
    )

    /** The ten swatch names, in the web's order. */
    val SWATCH_NAMES: List<String> get() = OKLCH.keys.toList()

    /** A custom hue from the picker: "h<0-359>" (core round; the web's tile-colors.ts tileStops reads the same). */
    fun hueOf(chosen: String?): Int? = chosen?.takeIf { it.length in 2..4 && it[0] == 'h' }?.drop(1)?.toIntOrNull()?.takeIf { it in 0..359 }

    fun nameFor(project: String, chosen: String?): String =
        chosen?.takeIf { it in OKLCH || hueOf(it) != null } ?: DEFAULT[project] ?: "slate"

    /** Light stop then dark stop: a swatch's own pair, or the same recipe at a custom hue (0.8 0.13 h → 0.6 0.16 h+10). */
    fun stops(project: String, chosen: String?): Pair<Color, Color> {
        val name = nameFor(project, chosen)
        hueOf(name)?.let { h -> return oklch(0.8, 0.13, h.toDouble()) to oklch(0.6, 0.16, ((h + 10) % 360).toDouble()) }
        val (a, b) = OKLCH.getValue(name)
        return oklch(a.first, a.second, a.third) to oklch(b.first, b.second, b.third)
    }

    /** OKLCH → sRGB (Björn Ottosson's OKLab matrices), clamped to the sRGB gamut like a browser does for these stops. */
    fun oklch(l: Double, c: Double, hDeg: Double): Color {
        val h = Math.toRadians(hDeg)
        val a = c * cos(h)
        val b = c * sin(h)
        val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val r = 4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1
        val g = -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1
        val bl = -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1
        return Color(gamma(r), gamma(g), gamma(bl))
    }

    private fun gamma(x: Double): Float {
        val v = x.coerceIn(0.0, 1.0)
        return (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055).toFloat().coerceIn(0f, 1f)
    }

}
