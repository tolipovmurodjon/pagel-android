package org.pagel.ai.ui.glass

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One toast: a short line, good (mint tick) or bad (red), with an optional action such as Undo. */
data class ToastData(
    val id: Long,
    val message: String,
    val good: Boolean = true,
    val action: String? = null,
    val millis: Long = 4_000,
    val onAction: (() -> Unit)? = null,
)

/**
 * The app's one toast line (audit #8): any screen, sheet or the shell's refusal watcher posts here; the shell shows it
 * over the real list (so its glass bends what is under it), and an open sheet shows it on top of itself.
 */
@Singleton
class ToastCenter @Inject constructor() {
    private val _current = MutableStateFlow<ToastData?>(null)
    val current: StateFlow<ToastData?> = _current.asStateFlow()
    private var next = 1L

    /** How many glass sheets are open: the top one shows the toast, the shell's stays hidden under the scrim. */
    var sheets by mutableIntStateOf(0)

    fun show(message: String, good: Boolean = true, action: String? = null, millis: Long = 4_000, onAction: (() -> Unit)? = null) {
        _current.value = ToastData(next++, message, good, action, millis, onAction)
    }

    fun dismiss(id: Long) = _current.update { if (it?.id == id) null else it }
}

val LocalToasts = staticCompositionLocalOf { ToastCenter() }

/**
 * Shows [ToastCenter.current]: slides in from below in 240 ms, out the same way in 160 ms, leaves by itself, and a
 * swipe down sends it away (emilkowalski/skills: exits faster than entries, gestures can dismiss).
 */
@Composable
fun ToastHost(center: ToastCenter, modifier: Modifier = Modifier, backdrop: Backdrop? = null) {
    val toast by center.current.collectAsStateWithLifecycle()
    AnimatedContent(
        targetState = toast,
        modifier = modifier,
        transitionSpec = {
            (slideInVertically(tween(240, easing = EaseOutStrong)) { it } + fadeIn(tween(240)))
                .togetherWith(slideOutVertically(tween(160)) { it } + fadeOut(tween(160)))
        },
        contentKey = { it?.id },
        label = "toast",
    ) { t ->
        if (t == null) Box(Modifier.fillMaxWidth()) else ToastPill(t, backdrop, onGone = { center.dismiss(t.id) })
    }
}

@Composable
private fun ToastPill(t: ToastData, backdrop: Backdrop?, onGone: () -> Unit) {
    LaunchedEffect(t.id) { delay(t.millis); onGone() }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(22.dp)
    val rim = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Glass.Aurora1.copy(alpha = 0.55f)))
    // Before the glass is on (GlassStage) the toast is a Pane, which is flat then too.
    val glass = backdrop != null && GlassStage.on
    val surface = if (glass) Modifier.drawBackdrop(
        backdrop = backdrop!!,
        shape = { RoundedRectangle(22f.dp) },
        effects = { vibrancy(); blur(8f.dp.toPx()); lens(14f.dp.toPx(), 14f.dp.toPx()) },
        shadow = { com.kyant.backdrop.shadow.Shadow(alpha = 0.55f) },
        onDrawSurface = {
            drawRect(Color.White.copy(alpha = 0.64f))
            drawRect(Brush.radialGradient(listOf(Glass.Aurora1.copy(alpha = 0.28f), Color.Transparent), center = Offset.Zero, radius = size.minDimension * 1.4f))
        },
    ).border(1.dp, rim, shape) else Modifier
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (t.good) TabIcons.Done else TabIcons.Ban, contentDescription = null, tint = if (t.good) Glass.Primary else Glass.Risk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(t.message, style = MaterialTheme.typography.bodyMedium, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            t.action?.let { label ->
                val press = remember { MutableInteractionSource() }
                // The pill is drawn 34 dp tall; its touch box is the full 48 dp (checker 2026-10-28 #9).
                Box(
                    Modifier.heightIn(min = TouchMin).pressScale(press).clickable(press, indication = null, role = Role.Button) { t.onAction?.invoke(); onGone() },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier.background(Glass.PrimarySoft, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(TabIcons.Undo, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk)
                    }
                }
            }
        }
    }
    Box(
        Modifier.fillMaxWidth()
            .graphicsLayer { translationY = drag.value; alpha = (1f - drag.value / 300f).coerceIn(0f, 1f) }
            .pointerInput(t.id) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (drag.value > 48.dp.toPx()) onGone() else drag.animateTo(0f, tween(160, easing = EaseOutStrong)) } },
                    onVerticalDrag = { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } },
                )
            }
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (glass) Box(surface) { body() } else Pane(Modifier.fillMaxWidth(), radius = 22.dp) { body() }
    }
}
