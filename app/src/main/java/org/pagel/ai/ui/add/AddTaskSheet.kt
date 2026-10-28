package org.pagel.ai.ui.add

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.ui.glass.DatePick
import org.pagel.ai.ui.glass.DateSheet
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.WordCount
import org.pagel.ai.ui.glass.GlassChip
import org.pagel.ai.ui.glass.GlassMenu
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.PriorityColors
import org.pagel.ai.ui.glass.ProjectSheet
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.RepeatSheet
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.TouchMin
import org.pagel.ai.ui.glass.fadeEnd
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.glass.priorityItems
import org.pagel.ai.ui.projects.shortName
import java.time.LocalDateTime

/**
 * Add task fast (NATIVE-PLAN Phase 1): a glass sheet over Today, or over a project when [project] is set (the task
 * joins that project, not Today). Enter or the mint arrow saves; the field clears and stays open for the next one.
 */
@Composable
fun AddTaskSheet(onClose: () -> Unit, project: String? = null, vm: AddTaskViewModel = hiltViewModel()) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val notSaved by vm.notSaved.collectAsStateWithLifecycle()
    GlassSheet(onClose) {
        // The sheet closes once a task is added (round B: the toast showed and the sheet stayed); a long press on the
        // arrow adds and keeps it open with the project, for a quick second one.
        AddTaskContent(projects, home = project ?: "me", toToday = project == null, now = vm::now, notSaved = notSaved, onAdded = onClose, onSave = vm::save)
        Spacer(Modifier.height(SheetBottom))
    }
}

private val FieldRim = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Glass.Aurora1.copy(alpha = 0.55f)))

/**
 * The web's add-task.tsx, for the thumb: a 52 dp capsule field with a 44 dp mint send inside it, then chips that open
 * the pickers (date with a calendar, priority with flags, repeat, project with search). The typed line is read live
 * ("Call the bank tomorrow 3pm #me p1"); a pick beats what was typed, a typed #project beats the picked one.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AddTaskContent(
    projects: List<ProjectEntity>,
    home: String = "me",
    toToday: Boolean = true,
    now: () -> LocalDateTime,
    initial: String = "",
    initialPicks: Picks = Picks(),
    autoFocus: Boolean = true,
    notSaved: List<NotSaved> = emptyList(),
    /** After a task is added (not on a long press of the arrow): the sheet closes. */
    onAdded: () -> Unit = {},
    onSave: (String, String, Picks, Boolean) -> Boolean,
) {
    var typed by rememberSaveable { mutableStateOf(initial) }
    // pagel.uz refused an add: its words come back into the field (if Boss has not started the next line), once.
    var restored by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(notSaved.lastOrNull()?.key) {
        val g = notSaved.lastOrNull() ?: return@LaunchedEffect
        if (g.key != restored) { restored = g.key; if (typed.isBlank()) typed = g.text }
    }
    var pick by rememberSaveable { mutableStateOf(home) }
    var dueSet by rememberSaveable { mutableStateOf(initialPicks.dueSet) }
    var due by rememberSaveable { mutableStateOf(initialPicks.due) }
    var everySet by rememberSaveable { mutableStateOf(initialPicks.everySet) }
    var every by rememberSaveable { mutableStateOf(initialPicks.every) }
    var priority by rememberSaveable { mutableStateOf(initialPicks.priority) }
    var sheet by rememberSaveable { mutableStateOf("") }
    var flags by remember { mutableStateOf(false) }
    val picks = Picks(dueSet, due, everySet, every, priority)
    val names = projects.map { it.name }
    val clock = now()
    val plan = remember(typed, pick, names, picks, toToday) { planOf(typed, pick, names, clock, picks, toToday) }
    val today = clock.toLocalDate()
    val focus = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    val ready = plan.text.isNotBlank() && !plan.tooLong

    fun submit(keepOpen: Boolean = false) {
        if (onSave(typed, pick, picks, toToday)) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            typed = ""; dueSet = false; due = null; everySet = false; every = null; priority = null
            if (!keepOpen) onAdded()
        } else if (plan.tooLong) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }

    Column(Modifier.fillMaxWidth()) {
        SheetTitle("New task", if (toToday) null else shortName(plan.project, projects.firstOrNull { it.name == plan.project }?.title ?: plan.project))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp)
                .background(Color.White.copy(alpha = 0.84f), RoundedCornerShape(26.dp))
                .border(1.dp, if (plan.tooLong) SolidColor(Glass.Risk) else FieldRim, RoundedCornerShape(26.dp))
                .padding(start = 18.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A long line ends in a soft fade, never a hard cut (checker 2026-10-28), except while typing, when the end
            // is where the cursor is.
            var fieldFocused by remember { mutableStateOf(false) }
            var textRight by remember { mutableStateOf(0f) }
            var fieldWidth by remember { mutableStateOf(0) }
            BasicTextField(
                value = typed,
                onValueChange = { typed = it.replace('\n', ' ') },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink),
                cursorBrush = SolidColor(Glass.Primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                onTextLayout = { textRight = if (it.lineCount > 0) it.getLineRight(0) else 0f },
                modifier = Modifier.weight(1f).onSizeChanged { fieldWidth = it.width }
                    .fadeEnd({ textRight > fieldWidth + 1f && !fieldFocused }, 32.dp).focusRequester(focus)
                    .onFocusChanged { fieldFocused = it.isFocused }.semantics { contentDescription = "New task" },
                decorationBox = { inner -> Box { if (typed.isEmpty()) Text("Call the bank tomorrow 3pm #me p1", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, maxLines = 1); inner() } },
            )
            Spacer(Modifier.width(8.dp))
            // Not ready = white glass with a muted arrow; ready = solid mint (checker 2026-10-27: the pale teal disc had a
            // 1.58:1 arrow). A 44 dp disc in a 48 dp touch box.
            val press = remember { MutableInteractionSource() }
            val fill by animateColorAsState(if (ready) Glass.Primary else Color.White.copy(alpha = 0.72f), label = "send")
            Box(
                Modifier.size(TouchMin).pressScale(press)
                    .combinedClickable(press, indication = null, enabled = ready, role = Role.Button, onLongClickLabel = "Add and keep open", onLongClick = { submit(keepOpen = true) }) { submit() }
                    .semantics { contentDescription = "Add task" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(44.dp).background(fill, CircleShape).then(if (ready) Modifier else Modifier.border(1.dp, FieldRim, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) { Icon(TabIcons.ArrowUp, contentDescription = null, tint = if (ready) Color.White else Glass.Muted, modifier = Modifier.size(22.dp)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        // What Enter will make, as chips that are also the pickers.
        val chipsScroll = rememberScrollState()
        Row(Modifier.fadeEnd({ chipsScroll.canScrollForward }, 48.dp).horizontalScroll(chipsScroll).padding(start = 16.dp, end = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip(TabIcons.Calendar, DatePick.dueText(plan.due, today).let { if (it == "Date" && !toToday) "Date" else it }, set = plan.due != null, description = "Date") { sheet = "date" }
            Box {
                GlassChip(if (priority != null) TabIcons.FlagFilled else TabIcons.Flag, "P${plan.priority + 1}", set = plan.priority != 2 || priority != null,
                    tint = PriorityColors[plan.priority.coerceIn(0, 3)], description = "Priority") { flags = true }
                if (flags) GlassMenu(priorityItems(plan.priority), onPick = { priority = it; flags = false }, onDismiss = { flags = false })
            }
            GlassChip(TabIcons.Habits, plan.every?.let(Repeat::label) ?: "Repeat", set = plan.every != null, description = "Repeat") { sheet = "repeat" }
            val p = projects.firstOrNull { it.name == plan.project }
            GlassChip(
                TabIcons.forProject(plan.project), shortName(plan.project, p?.title ?: plan.project), set = true, description = "Project",
                leading = { ProjectTile(plan.project, p?.color, p?.title ?: plan.project, size = 20.dp) },
            ) { sheet = "project" }
        }
        // slot: one fixed message line under the chips (spacing audit #11): the newest refused add, or the word
        // counter. The sheet sits on the keyboard, so a growing message moved the field while he typed.
        Box(Modifier.fillMaxWidth().height(Space.xxxl).padding(horizontal = Space.textEdge), contentAlignment = Alignment.CenterStart) {
            val g = notSaved.lastOrNull()
            if (g != null && !plan.tooLong) NotSavedRow(g)
            else WordCount(org.pagel.ai.ui.inbox.words(plan.text), MAX_WORDS, Modifier.align(Alignment.CenterEnd))
        }
    }
    when (sheet) {
        "date" -> DateSheet(plan.due, today, onPick = { dueSet = true; due = it }, onDismiss = { sheet = "" })
        "repeat" -> RepeatSheet(plan.every, onChange = { everySet = true; every = it }, onDismiss = { sheet = "" })
        "project" -> ProjectSheet(projects, plan.project, onPick = { pick = it }, onDismiss = { sheet = "" })
    }
}

/** The web's ghost row for a refused add: the line, struck through, and "Not saved" in red; its words are back above. */
@Composable
private fun NotSavedRow(g: NotSaved) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TabIcons.Ban, contentDescription = null, tint = Glass.Risk, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(g.text, style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.LineThrough), color = Glass.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("Not saved", style = MaterialTheme.typography.labelSmall, color = Glass.Risk, maxLines = 1, softWrap = false)
    }
}
