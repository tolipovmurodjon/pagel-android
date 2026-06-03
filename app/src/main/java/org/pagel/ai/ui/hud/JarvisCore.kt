package org.pagel.ai.ui.hud

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.pagel.ai.ui.theme.Cyan
import org.pagel.ai.ui.theme.CyanBright
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class Particle(
    val angle: Float,
    val radius: Float,   // fraction of R
    val speed: Float,
    val phase: Float,
    val twinkle: Float,
    val size: Float,
    val alpha: Float,
)

/**
 * The JARVIS energy-core: a dense field of fine cyan particles orbiting on black — a breathing,
 * twinkling vortex ring with a soft inner disc. Pure Canvas, one infinite transition drives the
 * whole field (no per-particle animatables). No geometric guide rings — it's all energy, like the
 * reference. Monochrome cyan; selective glow (only the brightest particles bloom) keeps it crisp.
 */
@Composable
fun JarvisCore(
    modifier: Modifier = Modifier,
    particleCount: Int = 700,
) {
    val tau = (2.0 * PI).toFloat()
    val particles = remember(particleCount) {
        val r = Random(7) // fixed seed → stable, designed-looking field
        List(particleCount) {
            val u = r.nextFloat()
            val band = when {
                u < 0.76f -> 0.60f + r.nextFloat() * 0.30f               // main ring band
                u < 0.90f -> 0.42f + r.nextFloat() * 0.17f               // inner wisps (depth)
                else -> 0.90f + r.nextFloat() * 0.18f                    // outer strays (tendrils)
            } + (r.nextFloat() - 0.5f) * 0.03f
            Particle(
                angle = r.nextFloat() * tau,
                radius = band,
                speed = 0.08f + r.nextFloat() * 0.85f,                    // differential = swirl
                phase = r.nextFloat() * tau,
                twinkle = 0.5f + r.nextFloat() * 2.4f,
                size = 0.35f + r.nextFloat() * 1.5f,                      // fine grain
                alpha = 0.10f + r.nextFloat() * 0.72f,                    // many faint = depth
            )
        }
    }

    val anim = rememberInfiniteTransition(label = "core")
    val time by anim.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
        label = "time",
    )
    val breathe by anim.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    Canvas(modifier) {
        val c = center
        val rad = size.minDimension / 2f
        val dot = rad / 200f

        // soft inner core disc — breathes
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Cyan.copy(alpha = 0.14f + 0.10f * breathe), Color.Transparent),
                center = c, radius = rad * 0.5f,
            ),
            radius = rad * 0.5f, center = c,
        )

        // the fine orbiting particle field
        particles.forEach { p ->
            val ang = p.angle + time * tau * p.speed
            val rr = (p.radius + sin(time * tau * 0.5f + p.phase) * 0.03f) * rad * (0.97f + 0.03f * breathe)
            val x = c.x + rr * cos(ang)
            val y = c.y + rr * sin(ang)
            val tw = 0.40f + 0.60f * (0.5f + 0.5f * sin(time * tau * p.twinkle + p.phase))
            val a = (p.alpha * tw).coerceIn(0f, 1f)
            val s = p.size * dot
            if (a > 0.5f) drawCircle(Cyan.copy(alpha = a * 0.16f), radius = s * 2.6f, center = Offset(x, y)) // selective bloom
            drawCircle(CyanBright.copy(alpha = a), radius = s, center = Offset(x, y))                         // crisp core
        }

        // a single faint scan spark orbiting the ring
        val scan = time * tau
        val sr = rad * 0.9f
        val sx = c.x + sr * cos(scan)
        val sy = c.y + sr * sin(scan)
        drawCircle(Cyan.copy(alpha = 0.45f), radius = dot * 1.8f, center = Offset(sx, sy))
        drawCircle(Cyan.copy(alpha = 0.10f), radius = dot * 7f, center = Offset(sx, sy))
    }
}
