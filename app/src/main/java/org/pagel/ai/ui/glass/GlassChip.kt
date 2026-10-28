package org.pagel.ai.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val ChipRim = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Glass.Aurora1.copy(alpha = 0.45f)))

/** The smallest thing a finger may be asked to hit (Material, Apple: 44-48); the drawn shape may be smaller. */
val TouchMin = 48.dp

/**
 * A 40 dp glass capsule in a 48 dp touch box: an icon and a short label (rules/one-icon-over-many-words). Every chip is
 * the same white glass (checker 2026-10-27: two families, and the mint "set" slab had a 1.58:1 edge). Not set = ink
 * icon and words, white-to-mint rim; set = ink words, the icon in its colour, a thin #1C7E69 rim. [tint] colours the
 * icon (a priority flag); [leading] replaces the icon (a project's own gradient tile).
 */
@Composable
fun GlassChip(
    icon: ImageVector,
    label: String,
    set: Boolean,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    /** A one-of choice (won't-do kinds): the chosen chip also carries a ✓. */
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .heightIn(min = TouchMin)
            .pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .background(Color.White.copy(alpha = 0.72f), shape)
                .border(1.dp, if (set) SolidColor(Glass.Primary) else ChipRim, shape)
                .padding(start = if (leading != null) 10.dp else 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) leading() else Icon(icon, contentDescription = null, tint = tint ?: if (set) Glass.Primary else Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (set) Glass.Ink else Glass.Ink.copy(alpha = 0.82f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (checked) { Spacer(Modifier.width(6.dp)); Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(16.dp)) }
        }
    }
}

/**
 * A round glass icon button: a [size] disc drawn in a touch box of at least 48 dp, named for TalkBack. [plain] drops
 * the glass disc for quiet repeated controls (a step's remove) so a list does not fill with circles. [label] = a short
 * word shown on a long press (a tooltip, 1.5 s); [rim] = the disc's outline colour (Delete: red, outlined).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassIconButton(
    icon: ImageVector, description: String, modifier: Modifier = Modifier, tint: Color = Glass.Ink.copy(alpha = 0.78f), on: Boolean = false,
    size: Dp = 40.dp, plain: Boolean = false, label: String? = null, rim: Color? = null, onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    var tip by remember { mutableStateOf(false) }
    LaunchedEffect(tip) { if (tip) { kotlinx.coroutines.delay(1_500); tip = false } }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier
            .size(maxOf(size + 4.dp, TouchMin))
            .pressScale(press)
            .then(
                if (label == null) Modifier.clickable(press, indication = null, role = Role.Button, onClick = onClick)
                else Modifier.combinedClickable(press, indication = null, role = Role.Button, onLongClickLabel = label, onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); tip = true
                }, onClick = onClick),
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val edge = when { on -> SolidColor(Glass.Primary); rim != null -> SolidColor(rim); else -> ChipRim }
        val disc = if (plain) Modifier else Modifier.background(Color.White.copy(alpha = 0.72f), CircleShape).border(if (rim != null) 1.5.dp else 1.dp, edge, CircleShape)
        Box(Modifier.size(size).then(disc), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (on) Glass.Primary else tint, modifier = Modifier.size(if (plain) 16.dp else 19.dp))
        }
        if (tip && label != null) androidx.compose.ui.window.Popup(alignment = Alignment.TopCenter, offset = androidx.compose.ui.unit.IntOffset(0, -96)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Glass.Ink, maxLines = 1,
                modifier = Modifier.background(Color.White.copy(alpha = 0.96f), RoundedCornerShape(10.dp)).border(1.dp, ChipRim, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/**
 * A scrolling row never ends in a hard cut ("Not wor", "16:0"): while more is off screen ([more]), its last [width]
 * fades out. Put it before the scroll modifier so it fades the viewport, not the content.
 */
fun Modifier.fadeEnd(more: () -> Boolean, width: Dp = 24.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (more()) {
                val w = width.toPx()
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                    topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), blendMode = BlendMode.DstIn,
                )
            }
        }

/**
 * Text that is its own editor, like the web's Inline (Todoist's title): it reads as plain text, a tap puts the cursor
 * in, Done or leaving the field saves, an unchanged or blank value saves nothing ([allowBlank] for clearing).
 */
@Composable
fun InlineField(
    value: String,
    placeholder: String,
    label: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    allowBlank: Boolean = false,
    color: Color = Glass.Ink,
    onSave: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun save() {
        val v = text.trim()
        if (v != value.trim() && (v.isNotEmpty() || allowBlank)) onSave(v) else text = value
    }
    LaunchedEffect(value) { if (!focused) text = value }
    BasicTextField(
        value = text,
        onValueChange = { text = if (singleLine) it.replace('\n', ' ') else it },
        modifier = modifier
            .onFocusChanged { f -> if (focused && !f.isFocused) save(); focused = f.isFocused }
            .semantics { contentDescription = label },
        textStyle = style.copy(color = color),
        // Always wraps (a long title stays readable); [singleLine] only means Enter/Done saves instead of a new line.
        singleLine = false,
        cursorBrush = SolidColor(Glass.Primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) Text(placeholder, style = style, color = Glass.Muted)
                inner()
            }
        },
    )
}
