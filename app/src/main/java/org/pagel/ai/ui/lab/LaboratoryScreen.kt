package org.pagel.ai.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Space

/** What one card shows: the experiment, its switch, and the quiet line at the bottom (null until it has one). */
data class ExperimentEntry(val experiment: Experiment, val on: Boolean, val note: String?)

/** More → Laboratory: reads each switch and note, writes a switch when Boss flips it. */
@Composable
fun LaboratoryRoute(padding: PaddingValues, onTry: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries = Experiments.all.map { e ->
        val on by Experiments.isOn(context, e.id).collectAsState(initial = e.defaultOn)
        val note by Experiments.note(context, e.id).collectAsState(initial = null)
        ExperimentEntry(e, on, note)
    }
    LaboratoryShelf(entries, padding, onTry = onTry, onSwitch = { id, on -> scope.launch { Experiments.setOn(context, id, on) } })
}

/** The shelf: one glass card per experiment. Stateless, so the screenshot tests can show any state. */
@Composable
fun LaboratoryShelf(entries: List<ExperimentEntry>, padding: PaddingValues, onTry: (String) -> Unit = {}, onSwitch: (String, Boolean) -> Unit = { _, _ -> }) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(bottom = Space.xl)) {
        Column(Modifier.padding(start = Space.textEdge, end = Space.textEdge, top = Space.headerTop)) {
            Text("More", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
            Text("Laboratory", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            Text("Where we test new ideas.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
        }
        Column(Modifier.padding(top = Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            for (entry in entries) ExperimentCard(entry, onTry = { onTry(entry.experiment.id) }, onSwitch = { onSwitch(entry.experiment.id, it) })
        }
    }
}

@Composable
private fun ExperimentCard(entry: ExperimentEntry, onTry: () -> Unit, onSwitch: (Boolean) -> Unit) {
    val e = entry.experiment
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(Space.lg)) {
            Text(e.title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 1)
            Text(e.line, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(top = Space.xs))
            Row(Modifier.fillMaxWidth().padding(top = Space.md), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onTry,
                    colors = ButtonDefaults.buttonColors(containerColor = Glass.Primary),
                    contentPadding = PaddingValues(horizontal = Space.lg),
                ) { Text("Try it", maxLines = 1) }
                Spacer(Modifier.weight(1f))
                Text("Use in the app", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
                Spacer(Modifier.padding(start = Space.sm))
                Switch(
                    checked = entry.on,
                    onCheckedChange = onSwitch,
                    colors = SwitchDefaults.colors(checkedTrackColor = Glass.Primary, checkedThumbColor = Color.White),
                    modifier = Modifier.semantics { contentDescription = "Use ${e.title} in the app" },
                )
            }
            if (entry.note != null) {
                Text(entry.note, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.padding(top = Space.sm))
            }
        }
    }
}
