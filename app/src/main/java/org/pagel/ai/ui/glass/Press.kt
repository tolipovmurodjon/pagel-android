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
