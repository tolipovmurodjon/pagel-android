package org.pagel.ai.ui.habits

import org.pagel.ai.ui.glass.HabitIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.pagel.ai.data.habits.Part
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.RepeatSheet
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale

/**
 * A new habit (Boss's note 8): "+" → type the name → Add. It is the task panel's own pattern in small: the name big
 * and in place beside the tile it will wear, then Time, Repeat and Reminder as rows that show their value (defaults
 * Anytime, Daily, Off) and open only when tapped; one Add button. Icon, colour, after and the rest are the panel's.
 */
@Composable
internal fun NewHabitSheet(part: Part, onAdd: (String, Part, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) { NewHabitContent(part, onAdd, onDismiss) }
}

@Composable
internal fun NewHabitContent(part: Part, onAdd: (String, Part, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var at by rememberSaveable { mutableStateOf(part) }
    var every by rememberSaveable { mutableStateOf("day") }
    var remind by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf("") }
    var repeating by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = name.isNotBlank()
    fun add() { if (ok) { onAdd(name.trim(), at, every, remind); onDismiss() } }
    // imePadding: the sheet rides above the keyboard, Add always in view.
    Column(Modifier.fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Row(Modifier.padding(horizontal = Space.textEdge), verticalAlignment = Alignment.CenterVertically) {
                HabitTile(HabitIcons.iconFor(name, null), HabitIcons.defaultColour(name, null), 40.dp)
                Spacer(Modifier.width(Space.md))
                BasicTextField(
                    name, { name = it.replace('\n', ' ').take(80) },
                    Modifier.weight(1f).heightIn(min = Space.touch).focusRequester(focus).semantics { contentDescription = "Habit name"; heading() },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = Glass.Ink), singleLine = true, cursorBrush = SolidColor(Glass.Primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (name.isEmpty()) Text("Name the habit", style = MaterialTheme.typography.headlineSmall, color = Glass.Muted); inner() } },
                )
            }
            Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                Column(Modifier.padding(vertical = Space.xs)) {
                    HabitRow(TabIcons.Clock, "Time", at.label, set = at != Part.ANYTIME, open = open == "time", onClick = { open = if (open == "time") "" else "time" }) {
                        Column(Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.md)) {
                            PartCapsule(at) { at = it; if (it == Part.ANYTIME) remind = false }
                        }
                    }
                    RowLine()
                    HabitRow(TabIcons.Habits, "Repeat", Repeat.summary(every), set = true, open = false, onClick = { repeating = true })
                    RowLine()
                    HabitRow(
                        if (remind) TabIcons.BellRing else TabIcons.Bell, "Reminder", if (remind) at.label else "Off", set = remind, open = false,
                        // A reminder rings at a time, so it gives an Anytime habit the morning.
                        onClick = { remind = !remind; if (remind && at == Part.ANYTIME) at = Part.MORNING },
                        trailing = { ReminderSwitch(remind) { on -> remind = on; if (on && at == Part.ANYTIME) at = Part.MORNING } },
                    )
                }
            }
            val press = remember { MutableInteractionSource() }
            // Solid mint with white words when it can be pressed; flat grey with a hint when it cannot (never a pale pill that looks
            // disabled while enabled, Boss 2026-10-31).
            Text(
                if (ok) "Add habit" else "Type a name", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (ok) Color.White else Glass.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter).heightIn(min = Space.row).pressScale(press)
                    .background(if (ok) Glass.Primary else Glass.Ink.copy(alpha = 0.08f), CircleShape)
                    .clickable(press, indication = null, enabled = ok, role = Role.Button, onClick = ::add).padding(vertical = Space.md)
                    .semantics { contentDescription = if (ok) "Add habit" else "Add habit, type a name first" },
            )
            Spacer(Modifier.height(SheetBottom))
    }
    if (repeating) RepeatSheet(every, onChange = { v -> if (v != null) every = v }, onDismiss = { repeating = false })
}
