package org.pagel.ai.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The bottom bar's icons, drawn from the same SVG paths as the web: Today and Inbox are PAGEL's own
 * (dashboard src/app/icons.tsx); Habits, Notebook and More are Lucide's repeat, notebook-pen, ellipsis (ISC License,
 * Copyright Lucide Icons and Contributors). 24×24, 2 px round strokes, tinted by Icon().
 */
object TabIcons {
    val Today = icon("today", "M12 7.8a4.2 4.2 0 1 0 0 8.4a4.2 4.2 0 1 0 0-8.4", "M12 2.8v2.1M12 19.1v2.1M2.8 12h2.1M19.1 12h2.1M5.5 5.5l1.5 1.5M17 17l1.5 1.5M5.5 18.5L7 17M17 7l1.5-1.5")
    val Inbox = icon("inbox", "M3.5 13.5 6 5.6A2.5 2.5 0 0 1 8.4 4h7.2A2.5 2.5 0 0 1 18 5.6l2.5 7.9V18a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 18z", "M3.5 13.5h4.6l1.4 2.4h5l1.4-2.4h4.6")
    val Habits = icon("repeat", "m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3")
    val Notebook = icon("notebook-pen", "M13.4 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7.4", "M2 6h4", "M2 10h4", "M2 14h4", "M2 18h4",
        "M21.378 5.626a1 1 0 1 0-3.004-3.004l-5.01 5.012a2 2 0 0 0-.506.854l-.837 2.87a.5.5 0 0 0 .62.62l2.87-.837a2 2 0 0 0 .854-.506z")
    // Lucide's three r=1 circles, written as paths.
    val More = icon("ellipsis", "M11 12a1 1 0 1 0 2 0a1 1 0 1 0-2 0", "M18 12a1 1 0 1 0 2 0a1 1 0 1 0-2 0", "M4 12a1 1 0 1 0 2 0a1 1 0 1 0-2 0")

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}
