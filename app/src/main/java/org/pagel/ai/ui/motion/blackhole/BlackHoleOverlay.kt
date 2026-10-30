package org.pagel.ai.ui.motion.blackhole

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * One run of the effect: the [snapshot] of the box the overlay covers (taken before the data changed), the [pieces]
 * that fly, and where the hole opens ([cx], [cy], px in that box).
 */
@Immutable
class BlackHoleRun(
    val id: Long,
    val snapshot: Bitmap,
    val pieces: List<PieceRect>,
    val cx: Float,
    val cy: Float,
    val timing: BlackHoleTiming,
)

/** The system's animator duration scale: 0 when Boss turned animations off (reduced motion). */
fun animatorScale(context: Context): Float =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

/**
 * One frame of a prepared [scene] at [time] s: the pieces, the ring (AGSL on API 33+ when [shaderRing], else the
 * canvas fallback), the black core on top. The frame-series tests call it with a fixed time.
 */
@Composable
fun BlackHoleCanvas(scene: BlackHoleScene, time: () -> Float, modifier: Modifier = Modifier, shaderRing: Boolean = Build.VERSION.SDK_INT >= 33) {
    val agsl: Any? = remember(shaderRing) { if (shaderRing && Build.VERSION.SDK_INT >= 33) AgslRing.createOrNull() else null }
    Box(modifier.fillMaxSize()) {
        Spacer(Modifier.fillMaxSize().drawBehind { drawIntoCanvas { scene.drawPieces(it.nativeCanvas, time()) } })
        if (agsl != null && Build.VERSION.SDK_INT >= 33) {
            AgslRing.Layer(agsl as android.graphics.RuntimeShader, scene, time)
        } else {
            Spacer(Modifier.fillMaxSize().drawBehind { val t = time(); drawFallbackRing(scene.cx, scene.cy, scene.hole(t), t) })
        }
        Spacer(Modifier.fillMaxSize().drawBehind { drawCore(scene.cx, scene.cy, scene.hole(time())) })
    }
}

/**
 * Plays [run] over the box it was taken from; [onEnd] when it is over. The data already changed (Undo works at any
 * moment): this only draws. The scene is made off the main thread while the plain snapshot holds the place, then one
 * frame clock drives it, each step at most 1/30 s. Two haptics only: the open and the land.
 * Reduced motion, a scene that cannot be made, or two missed frame budgets in a row: the rows fade in 120 ms instead.
 */
@Composable
fun BlackHoleOverlay(run: BlackHoleRun?, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    if (run == null) return
    val view = LocalView.current
    val density = LocalDensity.current
    val radius = BlackHole.RADIUS_DP * density.density
    val cell = BlackHole.CELL_DP * density.density
    val end by rememberUpdatedState(onEnd)
    var scene by remember(run.id) { mutableStateOf<BlackHoleScene?>(null) }
    val time = remember(run.id) { mutableFloatStateOf(0f) }
    val fadeAlpha = remember(run.id) { mutableFloatStateOf(1f) }
    LaunchedEffect(run.id) {
        var t = 0f
        if (chooseMode(animatorScale(view.context)) == HoleMode.Full) {
            val made = runCatching {
                withContext(Dispatchers.Default) { BlackHoleScene.prepare(run.snapshot, run.pieces, run.cx, run.cy, radius, cell, run.timing) }
            }.getOrNull()
            if (made != null) {
                scene = made
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                val watch = BudgetWatch()
                var last = -1L
                var landed = false
                var gaveUp = false
                while (t < run.timing.total && !gaveUp) {
                    val now = withFrameNanos { it }
                    if (last >= 0L) {
                        val raw = (now - last) / 1_000_000_000f
                        gaveUp = watch.frame(raw)
                        t += FrameStep.step(raw)
                    }
                    last = now
                    time.floatValue = t
                    if (!landed && t >= run.timing.swallowEnd) {
                        landed = true
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                }
                if (!gaveUp) {
                    end()
                    return@LaunchedEffect
                }
            }
        }
        // The fade: real time, not an Animatable (with the animator scale at 0 those snap and nothing would show).
        val from = withFrameNanos { it }
        while (true) {
            val ms = (withFrameNanos { it } - from) / 1_000_000f
            fadeAlpha.floatValue = (1f - ms / BlackHole.FADE_MS).coerceIn(0f, 1f)
            if (ms >= BlackHole.FADE_MS) break
        }
        end()
    }
    Box(modifier.graphicsLayer { alpha = fadeAlpha.floatValue }) {
        val s = scene
        if (s == null) {
            // Only the parts that fly hold their place; the rest of the box is live underneath.
            val image = remember(run.id) { run.snapshot.asImageBitmap() }
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    for (p in run.pieces) {
                        val left = p.left.roundToInt().coerceIn(0, image.width - 1)
                        val top = p.top.roundToInt().coerceIn(0, image.height - 1)
                        val w = p.width.roundToInt().coerceIn(1, image.width - left)
                        val h = p.height.roundToInt().coerceIn(1, image.height - top)
                        drawImage(image, srcOffset = IntOffset(left, top), srcSize = IntSize(w, h), dstOffset = IntOffset(left, top), dstSize = IntSize(w, h))
                    }
                },
            )
        } else {
            BlackHoleCanvas(s, { time.floatValue })
        }
    }
}
