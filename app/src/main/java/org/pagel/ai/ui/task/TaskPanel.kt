package org.pagel.ai.ui.task

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.pagel.ai.ui.glass.DatePick
import org.pagel.ai.ui.glass.DateSheet
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassChip
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.InlineField
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.PriorityColors
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.PrioritySheet
import org.pagel.ai.ui.glass.RepeatSheet
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TouchMin
import org.pagel.ai.ui.glass.pressScale
import java.time.LocalDate

/** What the panel can do; each one answers at once (Room) and saves behind (the outbox). */
data class PanelActions(
    val onTitle: (String) -> Unit = {},
    val onDoneWhen: (String) -> Unit = {},
    val onText: (String) -> Unit = {},
    val onDue: (String?) -> Unit = {},
    val onEvery: (String?) -> Unit = {},
    val onPriority: (Int) -> Unit = {},
    val onStar: (Boolean) -> Unit = {},
    val onSteps: (List<String>, List<Int>) -> Unit = { _, _ -> },
    val onMakeTasks: () -> Unit = {},
    val onComplete: () -> Unit = {},
    /** Null when saved; otherwise why the reason was refused. */
    val onNotDoing: (String, String) -> String? = { _, _ -> null },
    val onDelete: () -> Unit = {},
    val onReopen: () -> Unit = {},
    val onOpenProject: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/** "Won't do": the three real kinds of no, each with an example of a real reason (the web's STOP list). */
private data class Stop(val kind: String, val icon: ImageVector, val label: String, val eg: String)
private val STOP = listOf(
    Stop("waiting", TabIcons.Hourglass, "Blocked", "Waiting on the paid pod run for q08-q13"),
    Stop("someday", TabIcons.Moon, "Not now", "The airframe comes first this month"),
    Stop("drop", TabIcons.Ban, "Not worth it", "Click already sent the new key"),
)

/** The task panel in its glass sheet, opened from any task row (LocalTaskOpener). */
@Composable
fun TaskPanelSheet(taskId: String, onClose: () -> Unit, onOpenProject: (String) -> Unit) {
    val vm: TaskPanelViewModel = hiltViewModel(key = "panel-$taskId")
    LaunchedEffect(taskId) { vm.bind(taskId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Room answers in a few ms: the sheet opens with the task already in it, never with a placeholder that then grows.
    if (ui.loading) return
    GlassSheet(onClose, full = true) {
        when {
            ui.project.isEmpty() -> Text("This task is gone from pagel.uz.", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, modifier = Modifier.padding(24.dp))
            else -> TaskPanelContent(
                ui, vm.dayToday(),
                PanelActions(
                    onTitle = vm::title, onDoneWhen = vm::doneWhen, onText = vm::text, onDue = vm::due, onEvery = vm::every,
                    onPriority = vm::priority, onStar = vm::star, onSteps = vm::steps, onMakeTasks = vm::makeStepsTasks,
                    onComplete = { vm.complete(); onClose() }, onNotDoing = vm::notDoing, onDelete = { vm.delete(); onClose() }, onReopen = vm::reopen,
                    onOpenProject = { onOpenProject(ui.project) }, onClose = onClose,
                ),
            )
        }
    }
}

/**
 * Everything the web's task panel has (today/task-panel.tsx), tailored to a phone: the project and the tools on top,
 * the ring and the title in place, the finish line, won't do with the real reason, steps with a bar, Date / Repeat /
 * Priority / Today as grouped rows, and the full note the AI reads.
 */
@Composable
fun TaskPanelContent(ui: PanelUi, today: LocalDate, a: PanelActions, stopFirst: Boolean = false, picker0: String = "") {
    // A finished task's fields are its record, not a form (Completed → tap): read only, with Reopen.
    if (!ui.open) { DoneContent(ui, today, a); return }
    var stopping by rememberSaveable(ui.id) { mutableStateOf(stopFirst) }
    var picker by rememberSaveable(ui.id) { mutableStateOf(picker0) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = SheetBottom)) {
        Header(ui, stopping, a, onStop = { stopping = !stopping })
        if (ui.held) Held()
        AnimatedVisibility(stopping, enter = expandVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(), exit = shrinkVertically(tween(160)) + fadeOut()) {
            StopForm(onCancel = { stopping = false }) { kind, reason ->
                a.onNotDoing(kind, reason).also { if (it == null) { stopping = false; if (kind == "drop") a.onClose() } }
            }
        }
        TitleBlock(ui, a)
        Spacer(Modifier.height(14.dp))
        Steps(ui, a)
        Spacer(Modifier.height(12.dp))
        Props(ui, today, a, onPick = { picker = it })
        Spacer(Modifier.height(12.dp))
        Note(ui, a)
    }
    if (picker == "date") DateSheet(ui.due, today, onPick = a.onDue, onDismiss = { picker = "" })
    if (picker == "repeat") RepeatSheet(ui.every, onChange = a.onEvery, onDismiss = { picker = "" })
    if (picker == "priority") PrioritySheet(ui.priority, onPick = { if (it != ui.priority) a.onPriority(it) }, onDismiss = { picker = "" })
}

@Composable
private fun Header(ui: PanelUi, stopping: Boolean, a: PanelActions, onStop: () -> Unit, canStop: Boolean = true) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    // At 1.5x font the capsule kept only "PA…" (checker 2026-10-27): it shared the free width with a weighted spacer.
    // Now it owns all of it; the chevron goes first when the words are large.
    val big = LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val press = remember { MutableInteractionSource() }
        Box(Modifier.weight(1f)) { Row(
            Modifier.pressScale(press)
                .background(Color.White.copy(alpha = 0.72f), RoundedCornerShape(18.dp))
                .border(1.dp, SolidColor(Glass.Ink.copy(alpha = 0.08f)), RoundedCornerShape(18.dp))
                .clickable(press, indication = null, role = Role.Button, onClick = a.onOpenProject)
                .semantics { contentDescription = "Open ${ui.projectLabel}" }
                .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProjectTile(ui.project, ui.projectColor, ui.projectLabel, size = 22.dp)
            Spacer(Modifier.width(7.dp))
            Text(ui.projectLabel, style = MaterialTheme.typography.labelLarge, color = Glass.Ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (!big) Icon(TabIcons.ChevronRight, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(14.dp))
        } }
        GlassIconButton(if (copied) TabIcons.Check else TabIcons.Copy, if (copied) "Copied" else "Copy as a Claude Code prompt", on = copied, size = 36.dp) {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("PAGEL task", runPrompt(ui.project, ui.text, ui.priority)))
            copied = true
        }
        if (canStop) GlassIconButton(TabIcons.Ban, "Won't do, with the reason", on = stopping, size = 36.dp, onClick = onStop)
        GlassIconButton(TabIcons.Trash, "Delete task", tint = Glass.Risk, size = 36.dp, onClick = a.onDelete)
        GlassIconButton(TabIcons.Close, "Close", size = 36.dp, onClick = a.onClose)
    }
}

/** An edit an older pagel.uz cannot store yet: kept on the phone, sent again once the server learns it. */
@Composable
private fun Held() {
    Row(Modifier.padding(start = 22.dp, bottom = 8.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(TabIcons.CloudOff, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text("Waits for the pagel.uz update", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopForm(onCancel: () -> Unit, onSave: (String, String) -> String?) {
    var why by rememberSaveable { mutableStateOf("waiting") }
    var reason by rememberSaveable { mutableStateOf("") }
    var bad by remember { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current
    Pane(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Why not?", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
            Spacer(Modifier.height(12.dp))
            // All three kinds in view, wrapping if the words are large; never a row cut at "Not wor" (checker 2026-10-27).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in STOP) GlassChip(s.icon, s.label, set = why == s.kind, checked = why == s.kind, modifier = Modifier.semantics { selected = why == s.kind }) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); why = s.kind; bad = ""
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 76.dp)
                    .background(Color.White.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
                    .border(1.dp, SolidColor(if (bad.isNotEmpty()) Glass.Risk else Glass.Ink.copy(alpha = 0.10f)), RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                BasicTextField(
                    value = reason, onValueChange = { reason = it; bad = "" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink), cursorBrush = SolidColor(Glass.Primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "The real reason" },
                    decorationBox = { inner -> Box { if (reason.isEmpty()) Text(STOP.first { it.kind == why }.eg, style = MaterialTheme.typography.bodyLarge, color = Glass.Muted); inner() } },
                )
            }
            Spacer(Modifier.height(8.dp))
            // One line: what happens to the task; the error takes its place when the reason is refused.
            if (bad.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(TabIcons.Ban, contentDescription = null, tint = Glass.Risk, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(bad, style = MaterialTheme.typography.bodyMedium, color = Glass.Risk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            else Text(
                when (why) { "drop" -> "Leaves the plan."; "someday" -> "Goes to P4, off Today."; else -> "Stays in the plan, off Today." },
                style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                val cancel = remember { MutableInteractionSource() }
                Text("Cancel", style = MaterialTheme.typography.labelLarge, color = Glass.Muted,
                    modifier = Modifier.pressScale(cancel).clickable(cancel, indication = null, role = Role.Button, onClick = onCancel).padding(horizontal = 14.dp, vertical = 12.dp))
                val save = remember { MutableInteractionSource() }
                Text("Save reason", style = MaterialTheme.typography.labelLarge, color = Color.White,
                    modifier = Modifier.pressScale(save).background(Glass.Primary, RoundedCornerShape(22.dp))
                        .clickable(save, indication = null, role = Role.Button) {
                            val r = onSave(why, reason.trim())
                            if (r == null) haptics.performHapticFeedback(HapticFeedbackType.Confirm) else { haptics.performHapticFeedback(HapticFeedbackType.Reject); bad = r }
                        }
                        .padding(horizontal = 18.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun TitleBlock(ui: PanelUi, a: PanelActions) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.Top) {
        Ring(ui.priority, done = !ui.open, title = ui.title) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); a.onComplete() }
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            InlineField(ui.title, "Name the next action", "Title", MaterialTheme.typography.headlineSmall, Modifier.fillMaxWidth(), onSave = a.onTitle)
            Spacer(Modifier.height(6.dp))
            InlineField(ui.doneWhen, "+ Add a finish line: Done when …", "Finish line", MaterialTheme.typography.bodyMedium, Modifier.fillMaxWidth(),
                allowBlank = true, color = Glass.PrimaryInk, onSave = a.onDoneWhen)
        }
    }
}

/** The panel's ring: 24 dp drawn in the priority's colour, 48 dp to touch; a tap finishes the task. */
@Composable
private fun Ring(priority: Int, done: Boolean, title: String, onTick: () -> Unit) {
    val flag = PriorityColors.getOrNull(priority)?.takeIf { priority < 3 }
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.size(48.dp).pressScale(press)
            .clickable(press, indication = null, enabled = !done, role = Role.Checkbox, onClick = onTick)
            .semantics { contentDescription = "Complete: $title" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val r = size.minDimension / 2
            val line = 1.6.dp.toPx()
            val c = flag ?: Color(0xFF9AA2BB)
            if (done) drawCircle(Color(0xFF058527), r) else { drawCircle(c.copy(alpha = 0.08f), r); drawCircle(c, r - line / 2, style = Stroke(line)) }
            val u = size.minDimension / 20f
            if (done) {
                drawLine(Color.White, Offset(5.5f * u, 10.5f * u), Offset(8.5f * u, 13.5f * u), 2.2f * u, StrokeCap.Round)
                drawLine(Color.White, Offset(8.5f * u, 13.5f * u), Offset(14.5f * u, 6.5f * u), 2.2f * u, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun Steps(ui: PanelUi, a: PanelActions) {
    val haptics = LocalHapticFeedback.current
    val texts = ui.steps.map { it.text }
    val ticked = ui.steps.mapIndexedNotNull { i, s -> if (s.done) i else null }
    var adding by rememberSaveable(ui.id) { mutableStateOf("") }
    var made by rememberSaveable(ui.id) { mutableStateOf(false) }
    fun edit(i: Int, s: String) {
        if (s.isNotEmpty()) a.onSteps(texts.mapIndexed { n, x -> if (n == i) s else x }, ticked)
        else a.onSteps(texts.filterIndexed { n, _ -> n != i }, ticked.filter { it != i }.map { if (it > i) it - 1 else it })
    }
    fun add() { val s = adding.trim(); if (s.isNotEmpty()) { a.onSteps(texts + s, ticked); adding = "" } }

    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Steps", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                if (texts.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text("${ticked.size}/${texts.size}", style = Glass.NumberText, color = Glass.PrimaryInk) }
            }
            if (texts.isNotEmpty()) {
                val frac = ticked.size.toFloat() / texts.size
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(4.dp).background(Glass.Ink.copy(alpha = 0.07f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(frac).height(4.dp).background(Glass.Primary, RoundedCornerShape(2.dp)))
                }
            }
            ui.steps.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val press = remember { MutableInteractionSource() }
                    Box(
                        Modifier.size(48.dp).pressScale(press)
                            .clickable(press, indication = null, role = Role.Checkbox) {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                a.onSteps(texts, if (s.done) ticked - i else (ticked + i).sorted())
                            }
                            .semantics { contentDescription = if (s.done) "Undo: ${s.text}" else "Done: ${s.text}"; stateDescription = if (s.done) "Done" else "Open" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(22.dp).then(if (s.done) Modifier.background(Glass.Primary, CircleShape) else Modifier.border(1.5.dp, Glass.Ink.copy(alpha = 0.30f), CircleShape)),
                            contentAlignment = Alignment.Center,
                        ) { if (s.done) Icon(TabIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    }
                    InlineField(
                        s.text, "Step", "Step ${i + 1}",
                        MaterialTheme.typography.bodyLarge.copy(textDecoration = if (s.done) TextDecoration.LineThrough else null),
                        Modifier.weight(1f), allowBlank = true, color = if (s.done) Glass.Muted else Glass.Ink,
                    ) { edit(i, it) }
                    GlassIconButton(TabIcons.Close, "Remove step: ${s.text}", tint = Glass.Muted, size = 40.dp, plain = true) { edit(i, "") }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 13.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(TabIcons.Plus, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(13.dp))
                BasicTextField(
                    value = adding, onValueChange = { adding = it.replace('\n', ' ') }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink), cursorBrush = SolidColor(Glass.Primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Add a step" },
                    decorationBox = { inner -> Box { if (adding.isEmpty()) Text("Add a step", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted); inner() } },
                )
            }
            if (texts.isNotEmpty() && !made) {
                GlassChip(TabIcons.ListPlus, "To tasks", set = false, description = "Make these steps tasks in ${ui.projectLabel}", modifier = Modifier.padding(start = 12.dp)) {
                    a.onMakeTasks(); made = true
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Props(ui: PanelUi, today: LocalDate, a: PanelActions, onPick: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            PropRow(TabIcons.Calendar, "Date", DatePick.dueText(ui.due, today).takeIf { ui.due != null } ?: "None", set = ui.due != null) { onPick("date") }
            Line()
            PropRow(TabIcons.Habits, "Repeat", ui.every?.let(Repeat::label) ?: "Never", set = ui.every != null) { onPick("repeat") }
            Line()
            // One row family, one selection mark (checker 2026-10-28 #8): Priority opens its own sheet, as Date and Repeat do.
            PropRow(TabIcons.Flag, "Priority", "P${ui.priority + 1}", set = true, valueColor = PriorityColors.getOrNull(ui.priority)) { onPick("priority") }
            Line()
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(TabIcons.Star, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(12.dp))
                Text("Today", style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
                // A date decides by itself (Boss 2026-10-27: "why a Today toggle when I already set the date?"); the switch
                // is only for a task with no date, as on the web.
                val day = ui.due?.take(10)
                when {
                    // Short: the date or the repeat decides (checker: "Shows on its date" was a sentence).
                    ui.every != null -> Why("By repeat")
                    day != null && day <= today.toString() -> Why("By date")
                    day != null -> Why("From " + DatePick.dueText(day, today).let { if (it == "Tomorrow") "tomorrow" else it })
                    else -> GlassSwitch(ui.star, if (ui.star) "On today" else "Not today") { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); a.onStar(!ui.star) }
                }
            }
        }
    }
}

@Composable
private fun Why(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun Line() = HorizontalDivider(Modifier.padding(start = 47.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))

@Composable
private fun PropRow(icon: ImageVector, label: String, value: String, set: Boolean, valueColor: Color? = null, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" }
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 1, softWrap = false)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        if (valueColor != null) { Icon(TabIcons.FlagFilled, contentDescription = null, tint = valueColor, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)) }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (set) Glass.PrimaryInk else Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        Icon(TabIcons.ChevronRight, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp))
    }
}

/** An Apple-style switch in mint: the knob slides in 160 ms; the state is said in words beside it. */
@Composable
private fun GlassSwitch(on: Boolean, label: String, onToggle: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val knob by animateDpAsState(if (on) 20.dp else 2.dp, tween(160), label = "knob")
    Row(
        Modifier.heightIn(min = TouchMin).pressScale(press).clickable(press, indication = null, role = Role.Switch, onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = "Today"; stateDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) Glass.PrimaryInk else Glass.Muted)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(width = 46.dp, height = 28.dp).background(if (on) Glass.Primary else Glass.Ink.copy(alpha = 0.16f), RoundedCornerShape(14.dp))) {
            Box(Modifier.offset(x = knob, y = 2.dp).size(24.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun Note(ui: PanelUi, a: PanelActions) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Note", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
            Spacer(Modifier.height(8.dp))
            InlineField(ui.text, "Write the task", "Full note", MaterialTheme.typography.bodyMedium, Modifier.fillMaxWidth(), onSave = a.onText)
        }
    }
}

/**
 * A done task's panel: what it was and when it was finished, read only. Reopen (pagel.uz's undone) is the one action;
 * every field becomes editable again once it is open.
 */
@Composable
private fun DoneContent(ui: PanelUi, today: LocalDate, a: PanelActions) {
    val day = ui.doneAt?.let(org.pagel.ai.data.upcoming::doneDayOf)
    val time = ui.doneAt?.let(org.pagel.ai.data.upcoming::doneTimeOf).orEmpty()
    val word = day?.let { org.pagel.ai.data.upcoming.doneDayLabel(it, today) }?.let { if (it == "Today" || it == "Yesterday") it.lowercase() else it }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = SheetBottom)) {
        Header(ui, stopping = false, a, onStop = {}, canStop = false)
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.Top) {
            Ring(ui.priority, done = true, title = ui.title) {}
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Text(ui.title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink)
                if (ui.doneWhen.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Done when ${ui.doneWhen}", style = MaterialTheme.typography.bodyMedium, color = Glass.PrimaryInk)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(TabIcons.Done, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(12.dp))
                Text(listOfNotNull("Done", word, time.ifEmpty { null }).joinToString(" "), style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier.heightIn(min = TouchMin).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = a.onReopen)
                        .semantics(mergeDescendants = true) { contentDescription = "Reopen" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.background(Glass.PrimarySoft, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(TabIcons.Undo, contentDescription = null, tint = Glass.PrimaryInk, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reopen", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk)
                    }
                }
            }
        }
        if (ui.steps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    for (s in ui.steps) Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (s.done) Icon(TabIcons.Done, contentDescription = "Done", tint = Glass.Primary, modifier = Modifier.size(16.dp))
                        else Box(Modifier.size(16.dp).border(1.5.dp, Glass.Ink.copy(alpha = 0.30f), CircleShape).semantics { contentDescription = "Not done" })
                        Spacer(Modifier.width(10.dp))
                        Text(s.text, style = MaterialTheme.typography.bodyMedium.copy(textDecoration = if (s.done) TextDecoration.LineThrough else null), color = if (s.done) Glass.Muted else Glass.Ink)
                    }
                }
            }
        }
        if (ui.text.isNotBlank() && ui.text != ui.title) {
            Spacer(Modifier.height(12.dp))
            Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Note", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                    Spacer(Modifier.height(8.dp))
                    Text(ui.text, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
                }
            }
        }
    }
}
