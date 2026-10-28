package org.pagel.ai.ui.compass

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TouchMin
import org.pagel.ai.ui.glass.pressScale

/** A field's rim: white light at the top-left into mint, as the Add sheet's field. */
internal val FieldRim = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Glass.Aurora1.copy(alpha = 0.55f)))

/** The path's line: the mint thin line (#1C7E69) of colour theory, never a slab. */
internal val PathLine = Glass.Primary.copy(alpha = 0.55f)

/** Width of the path's rail on the left of every node. */
internal val RailWidth = 32.dp

/**
 * One stop on the vertical path: the rail on the left (a line in from the stop above, the stop's mark, a line on to
 * the stop below) and the stop's card on the right. The purpose stop's mark is a star, like a north star.
 */
@Composable
internal fun PathStop(first: Boolean, last: Boolean, mark: StopMark, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().drawBehind {
            val x = RailWidth.toPx() / 2f
            val markY = MarkCentre.toPx()
            val w = 1.5.dp.toPx()
            if (!first) drawLine(PathLine, Offset(x, 0f), Offset(x, markY), w)
            if (!last) drawLine(PathLine, Offset(x, markY), Offset(x, size.height), w)
        },
    ) {
        Box(Modifier.width(RailWidth).heightIn(min = 52.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.offset(y = MarkCentre - mark.size / 2)) { Mark(mark) }
        }
        Box(Modifier.weight(1f).padding(bottom = Space.md)) { content() }
    }
}

enum class StopMark(val size: Dp) { STAR(24.dp), FULL(12.dp), RING(12.dp) }

/** Where a stop's mark sits on the rail: level with the first line of its card. */
private val MarkCentre = 26.dp

@Composable
private fun Mark(mark: StopMark) {
    when (mark) {
        StopMark.STAR -> Box(
            Modifier.size(mark.size).background(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(TabIcons.StarFilled, contentDescription = null, tint = Glass.PrimaryInk, modifier = Modifier.size(14.dp)) }
        StopMark.FULL -> Box(Modifier.size(mark.size).background(Glass.Primary, CircleShape))
        StopMark.RING -> Box(Modifier.size(mark.size).background(Color.White, CircleShape).border(1.5.dp, PathLine, CircleShape))
    }
}

/** A text field that stays on the phone: white glass, ink words, the label as its accessible name. */
@Composable
internal fun SealedField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    hint: String,
    modifier: Modifier = Modifier,
    lines: Int = 1,
    maxChars: Int = 280,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Column(modifier.fillMaxWidth()) {
        // The field itself carries the label as its name; the visible label is not a second node to read or tap.
        Text(label, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, modifier = Modifier.padding(start = Space.xs, bottom = Space.xs).clearAndSetSemantics {})
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(maxChars)) },
            singleLine = lines == 1,
            minLines = lines,
            maxLines = if (lines == 1) 1 else 8,
            textStyle = style.copy(color = Glass.Ink),
            cursorBrush = SolidColor(Glass.Primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (lines == 1) ImeAction.Next else ImeAction.Default),
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchMin)
                .background(Color.White.copy(alpha = 0.84f), RoundedCornerShape(16.dp))
                .border(1.dp, FieldRim, RoundedCornerShape(16.dp))
                .padding(horizontal = Space.lg, vertical = Space.md)
                .semantics { contentDescription = label },
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = style, color = Glass.Muted, maxLines = lines); inner() } },
        )
    }
}

/** Apple's switch, drawn only: the row around it is the target. */
@Composable
internal fun CompassSwitch(on: Boolean) {
    val x by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "knob")
    Box(
        Modifier.size(width = 51.dp, height = 31.dp)
            .background(if (on) Brush.horizontalGradient(listOf(Glass.Aurora1, Glass.Primary)) else SolidColor(Glass.Ink.copy(alpha = 0.16f)), CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        // A 27 dp knob with a 2 dp inset, sliding 20 dp.
        Box(Modifier.graphicsLayer { translationX = 2.dp.toPx() + x * 20.dp.toPx() }.size(27.dp).background(Color.White, CircleShape))
    }
}

/** A settings row: a lit icon tile, words, and on the right a switch or a value. The whole row is the target. */
@Composable
internal fun SettingRow(icon: ImageVector, label: String, state: String, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit) {
    val press = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
            .clickable(press, indication = null, role = Role.Switch) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = state }
            .padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).background(Brush.linearGradient(listOf(Glass.Aurora1, Glass.Primary)), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.width(Space.md))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** A small mint capsule with ink words (the label on mint pill rule: #0B5C45 on the soft mint). */
@Composable
internal fun MintPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = Glass.PrimaryInk, maxLines = 1,
        modifier = modifier.background(Glass.PrimarySoft, CircleShape).padding(horizontal = Space.sm, vertical = Space.xs),
    )
}

/** A dashed-looking empty slot that invites writing: a white glass card with a + and one line. */
@Composable
internal fun EmptySlot(text: String, description: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
            .background(Color.White.copy(alpha = 0.42f), RoundedCornerShape(18.dp))
            .border(1.dp, PathLine.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.iconText),
    ) {
        Icon(TabIcons.Plus, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink.copy(alpha = 0.78f))
    }
}
