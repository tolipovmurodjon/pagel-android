package org.pagel.ai.ui.motion.blackhole

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pagel.ai.ui.glass.Glass
import kotlin.math.roundToInt

/** The hole's colours: an ink core, our mint, and a warm gold horizon (never red: Boss's colour rule). */
internal object HoleColors {
    val Core = Color(0xFF03050A)
    val Gold = Color(0xFFF2B54A)
    val Mint = Glass.Aurora1
}

/** The black capsule, on top of everything: the rows dive under its edge. */
internal fun DrawScope.drawCore(s: HoleShape) {
    if (s.alpha <= 0f) return
    drawRoundRect(
        HoleColors.Core, topLeft = Offset(s.cx - s.width / 2f, s.cy - s.height / 2f), size = Size(s.width, s.height),
        cornerRadius = CornerRadius(s.height / 2f), alpha = s.alpha,
    )
}

/**
 * The shade's strength: soft on the light page (a smudge at 0.24, checker 2026-10-30), and it leaves with the ring, so
 * the closed capsule does not sit in a grey cloud.
 */
internal fun shadeOf(s: HoleShape): Float = s.alpha * (0.08f + 0.92f * s.ring)

/**
 * API 26-32 (and when AGSL fails to compile): a soft radial shade and two sweep-gradient arcs turning the opposite
 * ways. Every brush and stroke is made once at unit size around (0, 0); a frame only translates, scales and rotates.
 */
private object FallbackArt {
    val shade = Brush.radialGradient(0f to Color.Black.copy(alpha = 0.12f), 0.5f to Color.Black.copy(alpha = 0.05f), 1f to Color.Transparent, center = Offset.Zero, radius = 1f)
    val sweep = Brush.sweepGradient(
        listOf(HoleColors.Gold.copy(alpha = 0f), HoleColors.Gold, HoleColors.Mint, HoleColors.Gold.copy(alpha = 0f), HoleColors.Gold, HoleColors.Gold.copy(alpha = 0f)),
        center = Offset.Zero,
    )
    val wide = Stroke(width = 0.22f)
    val thin = Stroke(width = 0.08f)
}

internal fun DrawScope.drawFallbackRing(s: HoleShape, t: Float) {
    if (s.alpha <= 0f) return
    val r = s.ringRadius
    translate(s.cx, s.cy) {
        scale(r * 3.5f, Offset.Zero) { drawCircle(FallbackArt.shade, radius = 1f, center = Offset.Zero, alpha = shadeOf(s)) }
        if (s.ring > 0.01f) scale(r, Offset.Zero) {
            rotate(t * 140f, Offset.Zero) { drawCircle(FallbackArt.sweep, radius = 1.12f, center = Offset.Zero, style = FallbackArt.wide, alpha = s.ring * s.alpha) }
            rotate(-t * 90f, Offset.Zero) { drawCircle(FallbackArt.sweep, radius = 1.34f, center = Offset.Zero, style = FallbackArt.thin, alpha = 0.6f * s.ring * s.alpha) }
        }
    }
}

/**
 * API 33+: the ring of light and the shade as one AGSL pass, run only in a box [BlackHole.RING_BOX] radii wide around
 * the hole and at half resolution (an offscreen layer half the size, drawn at 2x): full-screen fragment work is what
 * a Mali-G52 class GPU cannot afford. Fibres are periodic value noise along the circle (48 and 96 cells), pulled into
 * a spiral by the radius and turning 4.5x faster at the horizon; gold at the edge, mint further out.
 * One shader per process ([get]), built off the main thread and drawn once by [BlackHolePrewarm] before any run.
 */
@RequiresApi(33)
internal object AgslRing {
    private const val SOURCE = """
        uniform float2 center;
        uniform float radius;
        uniform float time;
        uniform float strength;
        uniform float shade;
        float hash(float n) { return fract(sin(n) * 43758.5453); }
        float fibre(float a, float cells) {
            float x = a * cells;
            float i = floor(x);
            float f = fract(x);
            float u = f * f * (3.0 - 2.0 * f);
            return mix(hash(mod(i, cells)), hash(mod(i + 1.0, cells)), u);
        }
        half4 main(float2 p) {
            float2 d = p - center;
            float x = length(d) / max(radius, 1.0);
            float a = atan(d.y, d.x) / 6.2831853 + 0.5;
            float spin = time * (0.32 + 0.72 / max(x, 1.0));
            float twist = 0.06 * x;
            float f1 = fibre(fract(a + spin + twist), 48.0);
            float f2 = fibre(fract(a - spin * 1.4 + twist * 1.7), 96.0);
            float fib = 0.55 * f1 + 0.45 * f2;
            float bx = (x - 1.07) / 0.12;
            float hx = (x - 1.0) / 0.42;
            float band = exp(-bx * bx);
            float halo = exp(-hx * hx) * 0.26;
            float ring = clamp((band * (0.35 + 0.65 * fib * fib * 1.6) + halo) * strength, 0.0, 1.0);
            half3 gold = half3(0.949, 0.710, 0.290);
            half3 mint = half3(0.400, 0.867, 0.659);
            half3 hot = half3(1.0, 0.94, 0.78);
            half3 col = mix(hot, gold, half(smoothstep(1.05, 1.5, x)));
            col = mix(col, mint, half(smoothstep(1.4, 2.2, x)));
            float fall = 1.0 - smoothstep(0.9, 2.0, x);
            float s = shade * 0.12 * fall * fall;
            // Faint spiral arms in the shade: the space around the hole looks drawn in (implied, not a real lens).
            float arm = 0.5 + 0.5 * sin(6.0 * (a + spin * 0.5) * 6.2831853 * 0.5 - 7.0 * x);
            float streak = shade * 0.07 * arm * fall * smoothstep(1.1, 1.3, x);
            float alpha = clamp(ring + s + streak, 0.0, 1.0);
            return half4(col * half(ring + streak), half(alpha));
        }
    """

    @Volatile private var shader: RuntimeShader? = null
    @Volatile private var brush: ShaderBrush? = null
    @Volatile private var failed = false

    /** The one ring shader; null when AGSL does not compile here (the canvas fallback draws instead). */
    fun get(): RuntimeShader? {
        shader?.let { return it }
        if (failed) return null
        synchronized(this) {
            shader?.let { return it }
            val made = runCatching { RuntimeShader(SOURCE) }.getOrNull()
            if (made == null) failed = true else { shader = made; brush = ShaderBrush(made) }
            return made
        }
    }

    private fun brushOf(s: RuntimeShader): ShaderBrush = brush ?: ShaderBrush(s).also { brush = it }

    private fun DrawScope.drawRing(s: RuntimeShader, cx: Float, cy: Float, radius: Float, t: Float, strength: Float, shade: Float) {
        s.setFloatUniform("center", cx, cy)
        s.setFloatUniform("radius", radius)
        s.setFloatUniform("time", t)
        s.setFloatUniform("strength", strength)
        s.setFloatUniform("shade", shade)
        drawRect(brushOf(s))
    }

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
                    // The box is centred on the circle's end place; the ring follows the capsule's own centre.
                    drawRing(shader, size.width / 2f + (s.cx - scene.cx) / 2f, size.height / 2f + (s.cy - scene.cy) / 2f, s.ringRadius / 2f, t, s.ring * s.alpha, shadeOf(s))
                },
        )
    }

    /** One draw that changes no pixel (strength and shade 0), so the GPU compiles the pipeline now, not at frame 1. */
    @Composable
    fun WarmUp(shader: RuntimeShader) {
        Box(Modifier.size(1.dp).drawBehind { drawRing(shader, 0f, 0f, 1f, 0f, 0f, 0f) })
    }
}

/**
 * API 33+: the real lens. The whole window was copied once at the start (half size); inside 1.6 radii of the hole each
 * pixel shows what lay farther out along its ray, so the page is drawn toward the hole, twisted a little as it nears
 * the horizon. Outside 1.6 radii the sample is the pixel's own place, so the edge blends into the live page. Same
 * half-resolution box trick as the ring. The page behind the rows, glass and header all bend; the rows fly by mesh.
 */
@RequiresApi(33)
internal object AgslLens {
    private const val SOURCE = """
        uniform shader backdrop;
        uniform float2 center;
        uniform float radius;
        uniform float2 shift;
        uniform float amount;
        half4 main(float2 p) {
            float2 d = p - center;
            float x = length(d) / max(radius, 1.0);
            float k = amount * pow(1.0 - smoothstep(0.9, 1.6, x), 2.0);
            float ang = 0.7 * k;
            float cs = cos(ang);
            float sn = sin(ang);
            float2 q = float2(d.x * cs - d.y * sn, d.x * sn + d.y * cs) * (1.0 + 0.5 * k);
            return backdrop.eval((center + q) * 2.0 + shift);
        }
    """

    @Volatile private var shader: RuntimeShader? = null
    @Volatile private var brush: ShaderBrush? = null
    @Volatile private var failed = false

    /** The one lens shader; null when AGSL does not compile here (the shade alone is drawn). */
    fun get(): RuntimeShader? {
        shader?.let { return it }
        if (failed) return null
        synchronized(this) {
            shader?.let { return it }
            val made = runCatching { RuntimeShader(SOURCE) }.getOrNull()
            if (made == null) failed = true else { shader = made; brush = ShaderBrush(made) }
            return made
        }
    }

    @Composable
    fun Layer(shader: RuntimeShader, scene: BlackHoleScene, time: () -> Float) {
        val backdrop = scene.lensBackdrop ?: return
        val side = scene.radius * BlackHole.LENS_BOX
        val left = scene.cx - side / 2f
        val top = scene.cy - side / 2f
        val halfDp = with(LocalDensity.current) { (side / 2f).toDp() }
        val ink = brush ?: ShaderBrush(shader).also { brush = it }
        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(halfDp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 2f
                    scaleY = 2f
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawBehind {
                    val s = scene.hole(time())
                    val amount = s.ring * s.alpha
                    if (amount <= 0.01f) return@drawBehind
                    shader.setInputShader("backdrop", backdrop)
                    shader.setFloatUniform("center", size.width / 2f + (s.cx - scene.cx) / 2f, size.height / 2f + (s.cy - scene.cy) / 2f)
                    shader.setFloatUniform("radius", s.ringRadius / 2f)
                    shader.setFloatUniform("shift", scene.shiftX + left, scene.shiftY + top)
                    shader.setFloatUniform("amount", amount)
                    drawRect(ink)
                },
        )
    }
}

/**
 * Put on a screen that can play the hole (Completed, the Lab): builds the ring shader off the main thread when the
 * screen opens and draws it once, invisibly, so the first run opens without a compile hitch. Below API 33: nothing.
 */
@Composable
fun BlackHolePrewarm() {
    if (Build.VERSION.SDK_INT < 33) return
    var ready by remember { mutableStateOf<RuntimeShader?>(null) }
    LaunchedEffect(Unit) { ready = withContext(Dispatchers.Default) { AgslLens.get(); AgslRing.get() } }
    ready?.let { AgslRing.WarmUp(it) }
}
