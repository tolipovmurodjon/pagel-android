package org.pagel.ai.ui.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import org.pagel.ai.ui.theme.Cyan
import org.pagel.ai.ui.theme.CyanBright
import kotlin.math.cos
import kotlin.math.sin

/**
 * The focus arc-reactor dial: a 270° gauge with a faint track, a glowing cyan progress arc
 * (layered-stroke bloom — API-30 safe), tick marks, and the score in mono at the center.
 * The single hero metric — the only element that strongly glows (anti-slop: glow = hierarchy).
 */
@Composable
fun FocusDial(score: Int, modifier: Modifier = Modifier) {
    val target = (score / 100f).coerceIn(0f, 1f)
    // Boot-up sweep: start at 0 on first show and animate to the score (arc + number rise together).
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) {
        anim.animateTo(target, animationSpec = tween(1100, easing = FastOutSlowInEasing))
    }
    val animated = anim.value
    val shown = (animated * 100).roundToInt()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            val sw = size.minDimension * 0.06f
            val inset = sw * 1.6f
            val tl = Offset(inset, inset)
            val sz = Size(size.width - inset * 2, size.height - inset * 2)
            val start = 135f
            val full = 270f
            val sweep = full * animated

            // track
            drawArc(Cyan.copy(alpha = 0.12f), start, full, false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
            // glow underlay + crisp progress
            drawArc(Cyan.copy(alpha = 0.18f), start, sweep, false, topLeft = tl, size = sz, style = Stroke(sw * 2.2f, cap = StrokeCap.Round))
            drawArc(CyanBright.copy(alpha = 0.95f), start, sweep, false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
            // inner concentric guide
            drawCircle(Cyan.copy(alpha = 0.10f), radius = r * 0.64f, center = c, style = Stroke(1f))
            // tick marks across the arc
            val ticks = 28
            for (i in 0 until ticks) {
                val deg = start + full * i / (ticks - 1)
                val a = Math.toRadians(deg.toDouble())
                val rOuter = r * 0.90f
                val rInner = r * 0.83f
                val x1 = c.x + (rInner * cos(a)).toFloat()
                val y1 = c.y + (rInner * sin(a)).toFloat()
                val x2 = c.x + (rOuter * cos(a)).toFloat()
                val y2 = c.y + (rOuter * sin(a)).toFloat()
                drawLine(Cyan.copy(alpha = 0.16f), Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.4f)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$shown",
                style = MaterialTheme.typography.displayMedium.copy(fontFamily = org.pagel.ai.ui.theme.MonoFamily),
                color = MaterialTheme.colorScheme.primary,
            )
            Text("FOCUS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
