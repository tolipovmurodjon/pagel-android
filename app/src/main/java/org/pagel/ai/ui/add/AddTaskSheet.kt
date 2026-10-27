package org.pagel.ai.ui.add

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.projects.shortName
import org.pagel.ai.ui.today.dueLabel
import java.time.LocalDateTime

/** Todoist's flag colours, as the web's chips: P1 red, P2 orange, P3 blue, P4 grey (PAGEL's P0-P3). */
private val FLAG = listOf(Color(0xFFD1453B), Color(0xFFEB8909), Color(0xFF246FE0), Glass.Muted)

/** Add task fast (NATIVE-PLAN Phase 3): a sheet over Today; Enter saves and keeps the field open for the next one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskSheet(onClose: () -> Unit, vm: AddTaskViewModel = hiltViewModel()) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Glass.Page) {
        AddTaskContent(projects, saved, now = vm::now, onSave = vm::save)
    }
}

@Composable
fun AddTaskContent(projects: List<ProjectEntity>, saved: List<Saved>, now: () -> LocalDateTime, onSave: (String, String) -> Boolean) {
    var typed by rememberSaveable { mutableStateOf("") }
    var pick by rememberSaveable { mutableStateOf("me") }
    val names = projects.map { it.name }
    val plan = remember(typed, pick, names) { planOf(typed, pick, names, now()) }
    val focus = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() {
        if (onSave(typed, pick)) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            typed = ""
        } else if (plan.tooLong) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }

    Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
        Text("Add a task", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "New task" },
            placeholder = { Text("Call the bank tomorrow 3pm #me p1", color = Glass.Muted) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Glass.Primary, cursorColor = Glass.Primary, focusedTextColor = Glass.Ink, unfocusedTextColor = Glass.Ink),
        )
        Spacer(Modifier.height(10.dp))
        // What Enter will make, read live from the line (the web's chips).
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val today = now().toLocalDate()
            Chip(dueLabel(plan.due, today)?.text ?: plan.due, Glass.Primary)
            Chip("⚑ P${plan.priority + 1}", FLAG[plan.priority.coerceIn(0, 3)])
            plan.every?.let { Chip("↻ ${when (it) { "day" -> "daily"; "weekday" -> "weekdays"; else -> it }}", Glass.Muted) }
        }
        Spacer(Modifier.height(12.dp))
        // The project: tap one, or type #name (then the typed one wins and is shown).
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(projects, key = { it.name }) { p -> ProjectChip(p, selected = p.name == plan.project) { pick = p.name } }
        }
        if (plan.tooLong) {
            Spacer(Modifier.height(8.dp))
            Text("One short line, $MAX_WORDS words at most. Put the detail in steps on pagel.uz.", style = MaterialTheme.typography.bodyMedium, color = Glass.Risk)
        }
        for (s in saved) {
            Spacer(Modifier.height(8.dp))
            Text("✓ ${s.text}  ·  ${s.where}", style = MaterialTheme.typography.bodyMedium, color = Glass.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { submit() },
            enabled = plan.text.isNotBlank() && !plan.tooLong,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Glass.Primary),
        ) { Text("Add task") }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color,
        modifier = Modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun ProjectChip(p: ProjectEntity, selected: Boolean, onPick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Glass.Primary else Glass.Muted.copy(alpha = 0.25f)), shape)
            .background(if (selected) Glass.PrimarySoft else Color.White.copy(alpha = 0.6f), shape)
            .clickable(onClick = onPick)
            .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectTile(p.name, p.color, p.title, size = 20.dp)
        Spacer(Modifier.width(6.dp))
        Text(shortName(p.name, p.title), style = MaterialTheme.typography.bodyMedium, color = Glass.Ink)
    }
}
