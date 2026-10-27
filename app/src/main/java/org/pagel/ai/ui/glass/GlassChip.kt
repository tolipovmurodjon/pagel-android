package org.pagel.ai.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
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

/**
 * A 40 dp glass capsule: an icon and a short label (rules/one-icon-over-many-words). Set = mint (soft mint, deep mint
 * words); not set = white glass with the white-to-mint rim. [tint] colours the icon, e.g. a priority flag.
 */
@Composable
fun GlassChip(
    icon: ImageVector,
    label: String,
    set: Boolean,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    description: String? = null,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .pressScale(press)
            .background(if (set) Glass.PrimarySoft else Color.White.copy(alpha = 0.72f), shape)
            .border(1.dp, if (set) SolidColor(Glass.Primary.copy(alpha = 0.35f)) else ChipRim, shape)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: if (set) Glass.Primary else Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (set) Glass.PrimaryInk else Glass.Ink.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round glass icon button, 40 dp drawn inside a 44 dp touch target, named for TalkBack. */
@Composable
fun GlassIconButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, tint: Color = Glass.Ink.copy(alpha = 0.78f), on: Boolean = false, size: Dp = 40.dp, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size + 4.dp)
            .pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).background(if (on) Glass.PrimarySoft else Color.White.copy(alpha = 0.72f), CircleShape).border(1.dp, if (on) SolidColor(Glass.Primary.copy(alpha = 0.35f)) else ChipRim, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = if (on) Glass.Primary else tint, modifier = Modifier.size(19.dp)) }
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
