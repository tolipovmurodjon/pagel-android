package org.pagel.ai.ui.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import org.pagel.ai.data.habits.Part
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.pressScale

/**
 * A new habit in two taps from Habits (Boss's note 8): "+" → type the name → Add (or the keyboard's Done). It starts
 * today, every day, in the part of the day on screen; days, reminder, colour and stacking live behind the habit's ⋮.
 */
@Composable
internal fun NewHabitSheet(part: Part, onAdd: (String, Part) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var at by rememberSaveable { mutableStateOf(part) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = name.isNotBlank()
    fun add() { if (ok) { onAdd(name.trim(), at); onDismiss() } }
    GlassSheet(onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Text("New habit", style = MaterialTheme.typography.titleLarge, color = Glass.Ink, modifier = Modifier.padding(horizontal = Space.textEdge).semantics { heading() })
            OutlinedTextField(
                name, { name = it.take(80) }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter).focusRequester(focus),
                label = { Text("Name") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.chipGap), verticalArrangement = Arrangement.spacedBy(Space.chipGap)) {
                for (p in listOf(Part.MORNING, Part.AFTERNOON, Part.EVENING, Part.ANYTIME)) Chip("${p.icon} ${p.label}", on = p == at) { at = p }
            }
            val press = remember { MutableInteractionSource() }
            Text(
                "Add habit", style = MaterialTheme.typography.labelLarge, color = if (ok) Glass.PrimaryInk else Glass.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter).heightIn(min = Space.touch).pressScale(press)
                    .background(if (ok) Glass.PrimarySoft else Color.White.copy(alpha = 0.4f), CircleShape)
                    .clickable(press, indication = null, enabled = ok, role = Role.Button, onClick = ::add).padding(vertical = Space.md),
            )
            Spacer(Modifier.height(SheetBottom))
        }
    }
}
