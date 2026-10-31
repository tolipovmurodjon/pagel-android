package org.pagel.ai.ui.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.pagel.ai.ui.glass.TileColors

/** The habit's lit gradient tile with a white line icon: the project tile's look (brain rules/gradient-project-icons). */
@Composable
fun HabitTile(icon: ImageVector, colour: String, size: Dp, modifier: Modifier = Modifier) {
    val (a, b) = TileColors.stops("", colour)
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(a, b)))
            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(size * 0.3f))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.56f)) }
}
