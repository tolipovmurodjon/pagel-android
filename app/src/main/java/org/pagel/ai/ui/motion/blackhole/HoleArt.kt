package org.pagel.ai.ui.motion.blackhole

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import org.pagel.ai.ui.glass.Glass
import kotlin.math.roundToInt

/** The hole's colours: an ink core, our mint, and a warm gold horizon (never red: Boss's colour rule). */
internal object HoleColors {
    val Core = Color(0xFF03050A)
    val Gold = Color(0xFFF2B54A)
    val Mint = Glass.Aurora1
}

/** The black capsule, on top of everything: the rows dive under its edge. */
internal fun DrawScope.drawCore(cx: Float, cy: Float, s: HoleShape) {
    if (s.alpha <= 0f) return
    drawRoundRect(
        HoleColors.Core, topLeft = Offset(cx - s.width / 2f, cy - s.height / 2f), size = Size(s.width, s.height),
        cornerRadius = CornerRadius(s.height / 2f), alpha = s.alpha,
    )
}

/**
 * API 26-32 (and when AGSL fails to compile): a dark radial shade over two radii and two sweep-gradient arcs turning
 * the opposite ways. No per-pixel work beyond what Canvas does for a gradient.
 */
internal fun DrawScope.drawFallbackRing(cx: Float, cy: Float, s: HoleShape, t: Float) {
    if (s.alpha <= 0f) return
    val c = Offset(cx, cy)
    val r = s.ringRadius
    drawCircle(
        Brush.radialGradient(0f to Color.Black.copy(alpha = 0.24f * s.alpha), 1f to Color.Transparent, center = c, radius = r * 3f),
        radius = r * 3f, center = c,
    )
    if (s.ring <= 0.01f) return
    val sweep = Brush.sweepGradient(
        listOf(HoleColors.Gold.copy(alpha = 0f), HoleColors.Gold, HoleColors.Mint, HoleColors.Gold.copy(alpha = 0f), HoleColors.Gold, HoleColors.Gold.copy(alpha = 0f)),
        center = c,
    )
    rotate(t * 140f, c) { drawCircle(sweep, radius = r * 1.12f, center = c, style = Stroke(width = r * 0.22f), alpha = s.ring * s.alpha) }
    rotate(-t * 90f, c) { drawCircle(sweep, radius = r * 1.34f, center = c, style = Stroke(width = r * 0.08f), alpha = 0.6f * s.ring * s.alpha) }
}

/**
 * API 33+: the ring of light and the shade as one AGSL pass, run only in a box [BlackHole.RING_BOX] radii wide around
 * the hole and at half resolution (an offscreen layer half the size, drawn at 2x): full-screen fragment work is what
 * a Mali-G52 class GPU cannot afford. Fibres turn, 4.5x faster at the horizon; gold at the edge, mint further out.
 */
@RequiresApi(33)
internal object AgslRing {
    private const val SOURCE = """
        uniform float2 center;
        uniform float radius;
        uniform float time;
        uniform float strength;
        uniform float shade;
        half4 main(float2 p) {
            float2 d = p - center;
            float x = length(d) / max(radius, 1.0);
            float ang = atan(d.y, d.x);
            float spin = time * (2.0 + 4.5 / max(x, 1.0));
            float f = 0.5 + 0.5 * sin(ang * 7.0 + spin + 3.0 * sin(ang * 3.0 - time));
            float g = 0.5 + 0.5 * sin(ang * 13.0 - spin * 1.3 + x * 4.0);
            float bx = (x - 1.12) / 0.16;
            float hx = (x - 1.0) / 0.5;
            float band = exp(-bx * bx);
            float halo = exp(-hx * hx) * 0.35;
            float ring = clamp((band * (0.55 + 0.45 * f * g) + halo) * strength, 0.0, 1.0);
            half3 gold = half3(0.949, 0.710, 0.290);
            half3 mint = half3(0.400, 0.867, 0.659);
            half3 col = mix(gold, mint, half(smoothstep(1.0, 1.6, x)));
            float s = shade * 0.24 * (1.0 - smoothstep(1.0, 3.0, x));
            float a = clamp(ring + s, 0.0, 1.0);
            return half4(col * half(ring), half(a));
        }
    """

    /** Null when the shader does not compile here (the canvas fallback draws instead). */
    fun createOrNull(): RuntimeShader? = runCatching { RuntimeShader(SOURCE) }.getOrNull()

    @Composable
    fun Layer(shader: RuntimeShader, scene: BlackHoleScene, time: () -> Float) {
        val side = scene.radius * BlackHole.RING_BOX
        val halfDp = with(LocalDensity.current) { (side / 2f).toDp() }
        Box(
            Modifier
                .offset { IntOffset((scene.cx - side / 2f).roundToInt(), (scene.cy - side / 2f).roundToInt()) }
                .size(halfDp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 2f
                    scaleY = 2f
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawBehind {
                    val t = time()
                    val s = scene.hole(t)
                    if (s.alpha <= 0f) return@drawBehind
                    shader.setFloatUniform("center", size.width / 2f, size.height / 2f)
                    shader.setFloatUniform("radius", s.ringRadius / 2f)
                    shader.setFloatUniform("time", t)
                    shader.setFloatUniform("strength", s.ring * s.alpha)
                    shader.setFloatUniform("shade", s.alpha)
                    drawRect(ShaderBrush(shader))
                },
        )
    }
}
