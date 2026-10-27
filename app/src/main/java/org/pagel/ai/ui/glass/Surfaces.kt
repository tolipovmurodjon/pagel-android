package org.pagel.ai.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The page behind everything: near-white lit by soft mint light from the corners, as the web's body background
 * (four radial gradients). Drawn once per size change in the draw phase, so it costs nothing while scrolling.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .background(Glass.Page)
            .drawBehind {
                fun glow(x: Float, y: Float, r: Float, c: Color) = drawRect(
                    Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(size.width * x, size.height * y), radius = size.maxDimension * r),
                )
                glow(0.06f, 0.04f, 0.34f, Color(0xFF3DC98C).copy(alpha = 0.20f))
                glow(0.92f, 0.06f, 0.30f, Glass.Aurora2.copy(alpha = 0.45f))
                // The two bottom clouds sit under the glass bar: it always has colour to bend, on every screen
                // (colour theory #5: 3-4 clouds of different hues; the bottom-left one turns to teal).
                glow(0.96f, 0.96f, 0.42f, Glass.Aurora1.copy(alpha = 0.52f))
                glow(0.10f, 0.98f, 0.34f, Color(0xFF2BB5A8).copy(alpha = 0.40f))
            },
        content = content,
    )
}

/** A list pane: tinted, a soft shadow, no blur (glass is for chrome; lists stay ink on near-white for contrast). */
@Composable
fun Pane(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .shadow(18.dp, shape, ambientColor = Glass.Ink.copy(alpha = 0.10f), spotColor = Glass.Ink.copy(alpha = 0.16f))
            .clip(shape)
            .background(Color.White.copy(alpha = 0.78f))
            .background(Glass.PaneTint),
        content = content,
    )
}

/** A project's lit gradient tile with its white line icon (brain rules/gradient-project-icons, colour theory #19). */
@Composable
fun ProjectTile(project: String, color: String?, title: String, size: Dp = 36.dp) {
    val (a, b) = TileColors.stops(project, color)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(a, b)))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(TabIcons.forProject(project), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}
