package org.pagel.ai.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** One row of a [GlassMenu]: an icon in its colour, a short label, a mono hint, a check on the chosen one. */
data class MenuItem(val icon: ImageVector, val label: String, val hint: String? = null, val tint: Color = Glass.Ink, val chosen: Boolean = false)

/**
 * A small glass menu anchored to the control that opened it (Apple: anchor to the source). It opens below, or above
 * when the keyboard side has no room, grows from that edge in 160 ms, and closes on a pick or a tap outside.
 * Put it inside the anchor's Box.
 */
@Composable
fun GlassMenu(items: List<MenuItem>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val above = remember { booleanArrayOf(false) }
    val place = remember(gap) { AboveOrBelow(gap) { above[0] = it } }
    Popup(popupPositionProvider = place, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = shown,
            enter = fadeIn(tween(160)) + scaleIn(tween(160, easing = EaseOutStrong), initialScale = 0.94f, transformOrigin = TransformOrigin(0f, if (above[0]) 1f else 0f)),
        ) {
            // A popup is its own window: the page's clouds are not under it, so the pane falls back to bright glass.
            CompositionLocalProvider(LocalPageBackdrop provides null) { MenuContent(items, onPick) }
        }
    }
}

/** The menu's rows, apart from the popup so screenshot tests can draw it. */
@Composable
fun MenuContent(items: List<MenuItem>, onPick: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Pane(Modifier.widthIn(min = 200.dp, max = 280.dp), radius = 18.dp) {
        Column(Modifier.padding(vertical = 6.dp)) {
            items.forEachIndexed { i, item ->
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).pressScale(press)
                        .clickable(press, indication = null, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(i) }
                        .semantics { selected = item.chosen; contentDescription = item.label }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(item.icon, contentDescription = null, tint = item.tint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(item.label, style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                    item.hint?.let { h -> Spacer(Modifier.width(10.dp)); Text(h, style = MaterialTheme.typography.labelSmall, color = Glass.Muted) }
                    Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
                    if (item.chosen) Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private class AboveOrBelow(private val gap: Int, private val onSide: (Boolean) -> Unit) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val fitsBelow = below + popupContentSize.height <= windowSize.height
        onSide(!fitsBelow)
        val y = if (fitsBelow) below else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
