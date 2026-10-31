package org.pagel.ai.ui.motion.blackhole

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One run of the effect: the [snapshot] of the box the overlay sits in (taken before the data changed) and the
 * [pieces] that fly, in that box's px. The hole does not come from the box: it opens out of the phone's camera.
 */
@Immutable
class BlackHoleRun(
    val id: Long,
    val snapshot: Bitmap,
    val pieces: List<PieceRect>,
    val timing: BlackHoleTiming,
)

/** The system's animator duration scale: 0 when Boss turned animations off (reduced motion). */
fun animatorScale(context: Context): Float =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The camera in window px: the top display cut-out (a punch hole or notch) when the phone has one; else a small
 * capsule on the status bar's lower edge, top centre (the same path, the hole just has no camera to leave).
 * API: WindowInsetsCompat.displayCutout.boundingRects (AndroidX core), read from the root insets.
 */
internal fun cameraCapsule(view: View, density: Float): Capsule {
    val width = view.rootView.width.toFloat()
    val height = view.rootView.height.toFloat()
    val insets = ViewCompat.getRootWindowInsets(view)
    val cut = insets?.displayCutout?.boundingRects.orEmpty().filter { it.height() > 0 && it.top < height * 0.1f }.minByOrNull { it.top }
    if (cut != null) {
        return Capsule(cut.exactCenterX(), cut.exactCenterY(), max(cut.width().toFloat(), 14f * density), max(cut.height().toFloat(), 14f * density))
    }
    val bar = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top?.toFloat() ?: (24f * density)
    return Capsule(width / 2f, bar, 40f * density, 16f * density)
}

/**
 * One frame of a prepared [scene] at [time] s: the pieces, the ring (AGSL on API 33+ when [shaderRing], else the
 * canvas fallback), the black core on top. The frame-series tests call it with a fixed time.
 */
@Composable
fun BlackHoleCanvas(scene: BlackHoleScene, time: () -> Float, modifier: Modifier = Modifier, shaderRing: Boolean = Build.VERSION.SDK_INT >= 33) {
    // The process's one ring shader (BlackHolePrewarm built it when the screen opened; a test builds it here).
    val agsl: Any? = remember(shaderRing) { if (shaderRing && Build.VERSION.SDK_INT >= 33) AgslRing.get() else null }
    Box(modifier.fillMaxSize()) {
        Spacer(Modifier.fillMaxSize().drawBehind { drawIntoCanvas { scene.drawPieces(it.nativeCanvas, time()) } })
        if (agsl != null && Build.VERSION.SDK_INT >= 33) {
            AgslRing.Layer(agsl as android.graphics.RuntimeShader, scene, time)
        } else {
            Spacer(Modifier.fillMaxSize().drawBehind { val t = time(); drawFallbackRing(scene.hole(t), t) })
        }
        Spacer(Modifier.fillMaxSize().drawBehind { drawCore(scene.hole(time())) })
    }
}

/**
 * A full-window layer on top of the app (added to the activity's decor view), so the hole can open out of the camera
 * ABOVE the status bar: the app's own content stops below it and is faded at the top edge. The layer has no pointer
 * input, so touches still reach the app underneath.
 */
@Composable
private fun WindowLayer(content: @Composable () -> Unit) {
    val host = LocalView.current
    val parent = rememberCompositionContext()
    val latest by rememberUpdatedState(content)
    DisposableEffect(host) {
        val decor = host.rootView as? ViewGroup
        val layer = decor?.let {
            ComposeView(host.context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setParentCompositionContext(parent)
                setContent { latest() }
            }
        }
        if (decor != null && layer != null) {
            runCatching { decor.addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        }
        onDispose {
            if (decor != null && layer != null) {
                layer.disposeComposition()
                runCatching { decor.removeView(layer) }
            }
        }
    }
}

/**
 * Plays [run] over the box it was taken from, with the hole opening out of the camera; [onEnd] when it is over. The
 * data already changed (Undo works at any moment): this only draws. The scene is made off the main thread while the
 * plain snapshot holds the place, then one frame clock drives it, each step at most 1/30 s. Two haptics only: the
 * open and the land. The status bar's icons turn light while the hole is black behind them.
 * Reduced motion, a scene that cannot be made, or two missed frame budgets in a row: the rows fade in 120 ms instead.
 */
@Composable
fun BlackHoleOverlay(run: BlackHoleRun?, onEnd: () -> Unit, modifier: Modifier = Modifier, stop: Boolean = false) {
    if (run == null) return
    // [stop] (an Undo mid-run): the effect leaves by the same 120 ms fade, never cut in one frame.
    val stopNow by rememberUpdatedState(stop)
    val view = LocalView.current
    val density = LocalDensity.current
    val cell = BlackHole.CELL_DP * density.density
    val end by rememberUpdatedState(onEnd)
    var scene by remember(run.id) { mutableStateOf<BlackHoleScene?>(null) }
    // Where the snapshot's box sits in the window: the hole's place is worked out in window px, then moved into the box.
    var origin by remember(run.id) { mutableStateOf(Offset.Zero) }
    var iconsOnDark by remember(run.id) { mutableStateOf(false) }
    val time = remember(run.id) { mutableFloatStateOf(0f) }
    val fadeAlpha = remember(run.id) { mutableFloatStateOf(1f) }
    DisposableEffect(iconsOnDark) {
        val window = view.context.findActivity()?.window
        val controller = if (iconsOnDark && window != null) WindowCompat.getInsetsController(window, view) else null
        val wasLight = controller?.isAppearanceLightStatusBars
        if (controller != null) controller.isAppearanceLightStatusBars = false
        onDispose { if (controller != null && wasLight != null) controller.isAppearanceLightStatusBars = wasLight }
    }
    LaunchedEffect(run.id) {
        var t = 0f
        // One frame first: the host below has been laid out, so its window position is known.
        withFrameNanos { it }
        if (!stopNow && chooseMode(animatorScale(view.context)) == HoleMode.Full) {
            val box = origin
            val window = holeGeometry(cameraCapsule(view, density.density), view.rootView.width.toFloat())
            val geometry = window.shifted(-box.x, -box.y)
            val made = runCatching {
                withContext(Dispatchers.Default) { BlackHoleScene.prepare(run.snapshot, run.pieces, geometry, cell, run.timing) }
            }.getOrNull()
            if (made != null) {
                scene = made
                iconsOnDark = true
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                val watch = BudgetWatch()
                var last = -1L
                var landed = false
                var gaveUp = false
                while (t < run.timing.total && !gaveUp && !stopNow) {
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
                        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
                if (!gaveUp && !stopNow) {
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
    // The host only measures where the box is; everything visible is in the window layer.
    Spacer(modifier.onGloballyPositioned { origin = it.positionInWindow() })
    val boxWidth = with(density) { run.snapshot.width.toDp() }
    val boxHeight = with(density) { run.snapshot.height.toDp() }
    WindowLayer {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = fadeAlpha.floatValue }) {
            Box(Modifier.offset { IntOffset(origin.x.roundToInt(), origin.y.roundToInt()) }.size(boxWidth, boxHeight)) {
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
    }
}
