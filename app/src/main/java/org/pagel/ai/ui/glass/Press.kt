package org.pagel.ai.ui.glass

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/** Apple's strong ease-out (emilkowalski/skills emil-design-eng): fast start, soft landing; never ease-in on UI. */
val EaseOutStrong = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * The press every pressable shares (emilkowalski/skills animate-expo, Apple HIG buttons): it shrinks to 0.97 on
 * finger-down in 120 ms, before the tap commits, and springs back on release. Read in the draw phase only.
 * Pass the same [interaction] to the clickable so the press is seen.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120, easing = EaseOutStrong), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/**
 * Press on touch-down for rows in a scrolling list (audit #6): clickable's pressed state waits ~100 ms to tell a tap
 * from a scroll, so a row felt dead under the finger. This shrinks it the moment the finger lands; a scroll lets go.
 */
@Composable
fun Modifier.pressOnTouch(): Modifier {
    val s = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            scope.launch { s.animateTo(0.97f, tween(120, easing = EaseOutStrong)) }
            waitForUpOrCancellation()
            scope.launch { s.animateTo(1f, tween(160, easing = EaseOutStrong)) }
        }
    }.graphicsLayer { scaleX = s.value; scaleY = s.value }
}
