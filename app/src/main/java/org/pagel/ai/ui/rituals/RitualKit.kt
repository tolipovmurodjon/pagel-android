package org.pagel.ai.ui.rituals

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TouchMin
import org.pagel.ai.ui.glass.pressScale

/** The rituals' page head, as every native page: a small line, the big title, a muted line under it. */
@Composable
internal fun RitualHead(over: String, title: String, under: String? = null, guideCard: Int? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)) {
        Text(over, style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.weight(1f).semantics { heading() })
            if (guideCard != null) org.pagel.ai.ui.guide.GuideHelpButton(guideCard)
        }
        if (under != null) Text(under, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * The ritual's main button: bright mint with an ink label (Boss's rule: no dull green surface; ink on #66DDA8 is far
 * above 4.5:1), 52 dp tall, a Confirm haptic, pressed in place. Faded while it cannot be pressed.
 */
@Composable
internal fun MintButton(label: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, description: String = label, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.heightIn(min = 52.dp).pressScale(press).graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .background(Brush.horizontalGradient(listOf(Glass.Aurora2, Glass.Aurora1)), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            .clickable(press, indication = null, enabled = enabled, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, tint = Glass.Ink, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** The quiet second choice beside it: white glass, ink words. */
@Composable
internal fun QuietButton(label: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, description: String = label, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier.heightIn(min = 52.dp).pressScale(press).graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .background(Color.White.copy(alpha = 0.72f), RoundedCornerShape(18.dp))
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Glass.Aurora1.copy(alpha = 0.45f))), RoundedCornerShape(18.dp))
            .clickable(press, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, tint = Glass.Ink.copy(alpha = 0.78f), modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = Glass.Ink, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** The bottom pair: the quiet one on the left, the main one on the right, each half the width (wrapping at 1.5×). */
@Composable
internal fun ButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.chipGap), verticalAlignment = Alignment.CenterVertically, content = content)
}

/**
 * Apple's segmented control for one-of choices (Do · Someday · Drop): the chosen part white and raised, a SegmentTick
 * per change. Each part is at least 48 dp tall to touch, 36 dp drawn.
 */
@Composable
internal fun Segmented(options: List<String>, chosen: Int, label: String, modifier: Modifier = Modifier, fill: Boolean = false, onPick: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(modifier.heightIn(min = TouchMin).semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        // [fill]: the full width in equal parts with words that shrink to fit (round C checker #3: "No mercy" was cut).
        Row(Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).background(Glass.Ink.copy(alpha = 0.06f), RoundedCornerShape(12.dp)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, o ->
                val on = i == chosen
                val press = remember { MutableInteractionSource() }
                val lift by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 1f, stiffness = 600f), label = "seg")
                Box(
                    Modifier.then(if (fill) Modifier.weight(1f).padding(horizontal = 2.dp) else Modifier).heightIn(min = 36.dp).pressScale(press)
                        .graphicsLayer { shadowElevation = lift * 3.dp.toPx(); shape = RoundedCornerShape(10.dp); clip = false }
                        .background(Color.White.copy(alpha = lift), RoundedCornerShape(10.dp))
                        .clickable(press, indication = null, role = Role.Tab) { if (!on) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(i) } }
                        .semantics { selected = on; contentDescription = "$label: $o" }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (fill) androidx.compose.foundation.text.BasicText(o, maxLines = 1, softWrap = false,
                        style = MaterialTheme.typography.labelLarge.copy(color = if (on) Glass.Ink else Glass.Muted),
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize))
                    else Text(o, style = MaterialTheme.typography.labelLarge, color = if (on) Glass.Ink else Glass.Muted, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** The review's four steps as numbered dots: done ones bright mint with an ink tick, the current one ringed in ink. */
@Composable
internal fun StepDots(names: List<String>, current: Int, onGo: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, name ->
            val press = remember { MutableInteractionSource() }
            Column(
                Modifier.weight(1f).heightIn(min = TouchMin).pressScale(press)
                    .clickable(press, indication = null, role = Role.Tab) { onGo(i) }
                    .semantics(mergeDescendants = true) { selected = i == current; contentDescription = "Step ${i + 1}: $name" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(28.dp)
                        .background(if (i < current) Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)) else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.8f), Color.White.copy(alpha = 0.8f))), CircleShape)
                        .border(if (i == current) 1.5.dp else 1.dp, if (i == current) Glass.Ink else Glass.Ink.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (i < current) Icon(org.pagel.ai.ui.glass.TabIcons.Check, contentDescription = null, tint = Glass.Ink, modifier = Modifier.size(15.dp))
                    else Text("${i + 1}", style = Glass.NumberText, color = Glass.Ink)
                }
                // One line that shrinks to fit at 1.5x: two lines broke "Someday" into "Someda / y" (CI 40657424080).
                androidx.compose.foundation.text.BasicText(
                    name, style = MaterialTheme.typography.labelSmall.copy(color = if (i == current) Glass.Ink else Glass.Muted, textAlign = TextAlign.Center),
                    maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 4.dp),
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.labelSmall.fontSize, stepSize = 0.5.sp),
                )
            }
        }
    }
}

/** A pane with a heading and its body: one idea per pane, as the web's ritual sections. */
@Composable
internal fun RitualPane(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Pane(modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        // One inset for every pane on a ritual screen (spacing audit #15).
        Column(Modifier.padding(horizontal = Space.paneInset, vertical = Space.lg)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
            content()
        }
    }
}

/** One short muted line (a hint, a state), never a paragraph. */
@Composable
internal fun Hint(text: String, color: Color = Glass.Muted, modifier: Modifier = Modifier, lines: Int = 1) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier, minLines = lines)
}
