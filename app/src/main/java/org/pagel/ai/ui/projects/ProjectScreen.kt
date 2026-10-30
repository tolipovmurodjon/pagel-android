package org.pagel.ai.ui.projects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.projects.ProjectGoal
import org.pagel.ai.data.projects.ProjectRepository
import org.pagel.ai.data.projects.goalsOf
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.ui.glass.DatePick
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.InlineField
import org.pagel.ai.ui.glass.LocalToasts
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.PriorityColors
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TileColors
import org.pagel.ai.ui.glass.pressOnTouch
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.today.headline
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One task row on a project page. [sort] places a drag between two rows; [milestone] links it to a goal's milestone. */
data class ProjectRow(val id: String, val title: String, val priority: Int, val due: String?, val every: String?, val sort: Double = 0.0, val milestone: String? = null)

/** One heading (a phase) of the plan: its title, whether it is NOW, its open tasks and the ones done lately, in order. */
data class PhaseGroup(val title: String, val now: Boolean, val rows: List<ProjectRow>, val done: List<ProjectRow> = emptyList())

private fun TaskEntity.row() = ProjectRow(id, title?.takeIf(String::isNotBlank) ?: headline(text), priority, due, every, sort, milestone)

/**
 * Tasks grouped by phase, NOW first, then in plan order (the rows come ordered by PagelDao.projectTasks). An older
 * server sends no phase order, so its phases keep the order they first appear in. A task just added on the phone has
 * no phase until pagel.uz files it: it waits at the end of the NOW phase (where the server puts it).
 */
fun projectGroups(tasks: List<TaskEntity>): List<PhaseGroup> {
    val groups = LinkedHashMap<String, MutableList<TaskEntity>>()
    val unfiled = tasks.filter { it.phase.isEmpty() }
    for (t in tasks) if (t.phase.isNotEmpty()) groups.getOrPut("${t.phaseNow}|${t.phaseSort}|${t.phase}") { mutableListOf() } += t
    val out = groups.values.map { list ->
        val (open, done) = list.partition { it.status == "open" }
        PhaseGroup(list.first().phase, list.first().phaseNow, open.map { it.row() }, done.map { it.row() })
    }.sortedByDescending { it.now }.toMutableList()
    if (unfiled.isNotEmpty()) {
        val (open, done) = unfiled.partition { it.status == "open" }
        val i = out.indexOfFirst { it.now }
        if (i >= 0) out[i] = out[i].copy(rows = out[i].rows + open.map { it.row() }, done = out[i].done + done.map { it.row() })
        else out.add(0, PhaseGroup("Tasks", true, open.map { it.row() }, done.map { it.row() }))
    }
    return out
}

/**
 * A project page's state. [open] counts every phase, [now] the NOW phase only (the web's "2 now · 14 open in all");
 * [next] is the one thing to do now (the NOW phase's first open task) and [step] its first open step.
 */
data class ProjectUi(
    val loading: Boolean = true, val name: String = "", val title: String = "", val color: String? = null, val open: Int = 0, val now: Int = 0,
    val groups: List<PhaseGroup> = emptyList(), val icon: String? = null, val done: Int = 0, val step: String? = null,
    val finishedAt: String? = null, val shipped: String? = null, val goals: List<ProjectGoal> = emptyList(),
    /** Other projects a task can be dragged onto (the "Move to" strip). */
    val others: List<ProjectEntity> = emptyList(),
    /** Days from its first finished task to today: the finish moment's "over N days". */
    val days: Int? = null,
    /** Open habits in this project: kept out of [groups], [open] and the bar; shown as one quiet line. */
    val habits: Int = 0,
) {
    val next: ProjectRow? get() = groups.firstOrNull { it.now }?.rows?.firstOrNull() ?: groups.firstOrNull { it.rows.isNotEmpty() }?.rows?.firstOrNull()
    val total: Int get() = open + done
    /** Everything is done and the project is not finished yet: the finish line is one tap away. */
    val canFinish: Boolean get() = !loading && open == 0 && done > 0 && finishedAt == null
}

/** What a project page can do; the screen holds no data logic of its own. */
data class ProjectActions(
    val onOpenTask: (String) -> Unit = {},
    val onTick: (String) -> Unit = {},
    /** An inline add (Enter adds another); null → the Add sheet. */
    val onAddText: ((String) -> Unit)? = null,
    /** Many tasks from one paste (one per line, in order); null hides "Paste a list". */
    val onAddMany: ((List<String>) -> Unit)? = null,
    val onAdd: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onSettings: () -> Unit = {},
    val onSort: (id: String, sort: Double) -> Unit = { _, _ -> },
    val onMoveTo: (id: String, project: String) -> Unit = { _, _ -> },
    val onFinish: () -> Unit = {},
    val onReopen: () -> Unit = {},
    val onGoals: (List<ProjectGoal>) -> Unit = {},
    val onLink: (taskId: String, milestone: String?) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val today: TodayRepository,
    val projects: ProjectRepository,
    session: Session,
) : ViewModel() {
    private val name = MutableStateFlow<String?>(null)

    val ui: StateFlow<ProjectUi> = name.filterNotNull().flatMapLatest { n ->
        combine(tasks.projectTasks(n), session.projects()) { all0, all -> all0 to all }.flatMapLatest { (all0, all) ->
            // Habits never end, so they stay out of the doing list, the bar and Finish (Boss: "Now · 13" of daily habits,
            // a 0/13 bar that can never fill). The page shows one quiet "N habits live in Habits" line instead.
            val habits = all0.count { it.every != null && it.status == "open" }
            val list = all0.filter { it.every == null }
            val groups = projectGroups(list)
            val next = groups.firstOrNull { it.now }?.rows?.firstOrNull() ?: groups.firstOrNull { it.rows.isNotEmpty() }?.rows?.firstOrNull()
            (if (next != null) tasks.steps(next.id) else flowOf(emptyList())).let { steps ->
                combine(steps, flowOf(Unit)) { s, _ ->
                    val p = all.firstOrNull { it.name == n }
                    ProjectUi(
                        loading = false, name = n, title = p?.let { shortName(it.name, it.title) } ?: shortName(n, n), color = p?.color, icon = p?.icon,
                        open = list.count { it.status == "open" }, now = groups.filter { it.now }.sumOf { it.rows.size }, groups = groups,
                        done = list.count { it.status == "done" }, step = s.firstOrNull { !it.done }?.let { it.title?.takeIf(String::isNotBlank) ?: it.text },
                        finishedAt = p?.finishedAt, shipped = p?.shipped, goals = p?.goals?.let(::goalsOf).orEmpty(),
                        others = all.filter { it.name != n && !it.archived && it.finishedAt == null },
                        days = projects.daysTaken(list), habits = habits,
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUi())

    fun bind(project: String) { name.value = project }
    fun tick(id: String) { viewModelScope.launch { today.tick(id, done = true) } }
    fun add(text: String) { val n = name.value ?: return; viewModelScope.launch { today.add(text, n, 2, null, null, onToday = false) } }
    /** A pasted list: the tasks are added one by one, in order, so pagel.uz files them in that order; one Undo takes all back. */
    fun addMany(lines: List<String>, undo: (suspend () -> Unit) -> Unit) {
        val n = name.value ?: return
        viewModelScope.launch {
            val ids = lines.map { today.add(it, n, 2, null, null, onToday = false) }
            undo { ids.forEach { tasks.delete(it) } }
        }
    }
    fun rename(title: String) { val n = name.value ?: return; viewModelScope.launch { projects.rename(n, title) } }
    fun sort(id: String, sort: Double) { viewModelScope.launch { tasks.sort(id, sort) } }
    fun moveTo(id: String, project: String, undo: (suspend () -> Unit) -> Unit) {
        viewModelScope.launch {
            val from = name.value ?: return@launch
            val op = tasks.move(id, project) ?: return@launch
            undo { if (!tasks.takeBack(id, op)) tasks.move(id, from) }
        }
    }
    fun finish(shipped: String, undo: (suspend () -> Unit) -> Unit) {
        val n = name.value ?: return
        viewModelScope.launch { projects.finish(n, shipped); undo { projects.reopen(n) } }
    }
    fun reopen() { val n = name.value ?: return; viewModelScope.launch { projects.reopen(n) } }
    fun goals(g: List<ProjectGoal>) { val n = name.value ?: return; viewModelScope.launch { projects.goals(n, g) } }
    fun link(id: String, m: String?) { viewModelScope.launch { tasks.milestone(id, m) } }
    fun run(block: suspend () -> Unit) { viewModelScope.launch { block() } }
}

@Composable
fun ProjectRoute(
    project: String, contentPadding: PaddingValues, reselect: Int, onOpenTask: (String) -> Unit, onAdd: (String) -> Unit = {},
    onGone: () -> Unit = {}, onOpenProject: (String) -> Unit = {}, vm: ProjectViewModel = hiltViewModel(),
) {
    LaunchedEffect(project) { vm.bind(project) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val toasts = LocalToasts.current
    var settings by rememberSaveable { mutableStateOf(false) }
    var finishing by rememberSaveable { mutableStateOf(false) }
    fun undoToast(message: String, undo: suspend () -> Unit) = toasts.show(message, action = "Undo", millis = 5_000) { vm.run(undo) }
    ProjectScreen(
        ui, contentPadding, reselect,
        actions = ProjectActions(
            onOpenTask = onOpenTask, onTick = vm::tick, onAddText = vm::add, onAddMany = { lines -> vm.addMany(lines) { undo -> undoToast(if (lines.size == 1) "1 task added" else "${lines.size} tasks added", undo) } }, onAdd = { onAdd(project) }, onRename = vm::rename,
            onSettings = { settings = true }, onSort = vm::sort,
            onMoveTo = { id, to -> vm.moveTo(id, to) { undo -> undoToast("Moved to ${ui.others.firstOrNull { it.name == to }?.let { shortName(it.name, it.title) } ?: to}", undo) } },
            onFinish = { finishing = true }, onReopen = vm::reopen, onGoals = vm::goals, onLink = vm::link,
        ),
        goal = { org.pagel.ai.ui.compass.ServesGoalRow(project) },
    )
    if (finishing) FinishProjectSheet(ui, onDismiss = { finishing = false }) { shipped ->
        finishing = false
        vm.finish(shipped) { undo -> undoToast("Project finished", undo) }
    }
    if (settings) ProjectSettingsRoute(project, onDismiss = { settings = false }, onGone = { settings = false; onGone() }, onOpenProject = onOpenProject)
}

private val TASHKENT = ZoneId.of("Asia/Tashkent")

/**
 * The project page as a DOING screen (core round, Boss 2026-10-29: "I open one, do the job, and close the project
 * faster"): the next action on top with its tiny first step and a "Start · 10 min" timer, a progress bar to the finish
 * line, the tasks in order (long-press drag to reorder, or onto another project in the "Move to" strip), an inline add,
 * later headings folded, the project's 1-year goals, and "Finish project" once everything is done.
 */
@Composable
fun ProjectScreen(
    ui: ProjectUi, contentPadding: PaddingValues, reselect: Int = 0, today: LocalDate = LocalDate.now(TASHKENT),
    actions: ProjectActions = ProjectActions(), goal: @Composable (() -> Unit)? = null,
) {
    val list = rememberLazyListState()
    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    val nowGroup = ui.groups.firstOrNull { it.now }
    val later = ui.groups.filter { it !== nowGroup && it.rows.isNotEmpty() }
    val finished = ui.groups.filter { it !== nowGroup && it.rows.isEmpty() && it.done.isNotEmpty() }
    val next = ui.next
    val nowRows = (nowGroup?.rows ?: emptyList()).filter { it.id != next?.id }
    // The "Move to" strip: shown while a task is lifted; its tiles' x-ranges in root pixels decide the drop.
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Unspecified) }
    var stripTop by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val tiles = remember { mutableStateMapOf<String, ClosedFloatingPointRange<Float>>() }
    val target = if (dragging != null && pointer.isSpecified && pointer.y >= stripTop) tiles.entries.firstOrNull { pointer.x in it.value }?.key else null
    // Read at drop time: the gesture keeps the onDrop it got at lift (pointerInput keyed by id), so a captured
    // `target` was always null there and every drop became a reorder (CI 40945211358 sent sort, never project).
    fun hit(): String? = if (dragging != null && pointer.isSpecified && pointer.y >= stripTop) tiles.entries.firstOrNull { pointer.x in it.value }?.key else null
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(Space.md)) {
            item(key = "head", contentType = "head") { Head(ui, actions) }
            if (!ui.loading && ui.total > 0) item(key = "progress", contentType = "progress") { ProgressLine(ui) }
            if (goal != null && ui.name.isNotEmpty()) item(key = "goal", contentType = "goal") { goal() }
            when {
                ui.loading -> item(key = "loading", contentType = "note") { Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) { SkeletonRows(4) } }
                ui.finishedAt != null -> item(key = "finished", contentType = "card") { FinishedCard(ui, actions.onReopen) }
                ui.canFinish -> item(key = "finish", contentType = "card") { FinishCard(actions.onFinish) }
                next != null -> item(key = "next", contentType = "card") {
                    NextCard(next, ui.step, today, actions,
                        onLift = { id, at -> dragging = id; pointer = at }, onDragAt = { pointer = it },
                        onDrop = { id -> val t = hit(); dragging = null; pointer = Offset.Unspecified; if (t != null) { actions.onMoveTo(id, t); true } else false })
                }
                else -> item(key = "first", contentType = "card") { FirstStepCard(ui.title, actions) }
            }
            if (!ui.loading && ui.finishedAt == null && (nowRows.isNotEmpty() || next != null)) item(key = "now", contentType = "phase") {
                NowList(
                    nowGroup?.title ?: "Tasks", nowRows, ui.title, today, actions,
                    onLift = { id, at -> dragging = id; pointer = at }, onDragAt = { pointer = it },
                    onDrop = { id -> val t = hit(); dragging = null; pointer = Offset.Unspecified; if (t != null) { actions.onMoveTo(id, t); true } else false },
                    done = nowGroup?.done.orEmpty(),
                )
            }
            if (later.isNotEmpty()) item(key = "later", contentType = "fold") { FoldPane("Later · ${later.size} ${if (later.size == 1) "heading" else "headings"}", later, today, actions) }
            if (finished.isNotEmpty()) item(key = "done", contentType = "fold") { FoldPane("Done · ${finished.size} ${if (finished.size == 1) "heading" else "headings"}", finished, today, actions) }
            if (!ui.loading && ui.name.isNotEmpty()) item(key = "goals", contentType = "goals") { GoalsPane(ui, today, actions) }
        }
        if (dragging != null && ui.others.isNotEmpty()) MoveStrip(
            ui.others, target, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + Space.sm),
            onTop = { stripTop = it }, onTile = { n, r -> tiles[n] = r },
        )
    }
}

@Composable
private fun Head(ui: ProjectUi, actions: ProjectActions) {
    Row(Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.sm, top = Space.md), verticalAlignment = Alignment.CenterVertically) {
        if (ui.name.isNotEmpty()) {
            val press = remember { MutableInteractionSource() }
            Box(Modifier.pressScale(press).clickable(press, indication = null, role = Role.Button, onClickLabel = "Project settings", onClick = actions.onSettings)) {
                ProjectTile(ui.name, ui.color, ui.title, size = 40.dp, icon = ui.icon)
            }
            Spacer(Modifier.width(Space.md))
        }
        Column(Modifier.weight(1f)) {
            InlineField(ui.title.ifEmpty { "Project" }, "Project name", "Project name", MaterialTheme.typography.headlineLarge.copy(color = Glass.Ink),
                modifier = Modifier.semantics { heading() }, onSave = actions.onRename)
            if (!ui.loading) Text(when {
                ui.total == 0 && ui.habits > 0 -> "🔁 ${ui.habits} habit${if (ui.habits == 1) "" else "s"} · in Habits"
                ui.total == 0 -> "No plan yet"
                ui.now == ui.open || ui.now == 0 -> "${ui.open} open" + if (ui.habits > 0) " · 🔁 ${ui.habits} in Habits" else ""
                else -> "${ui.now} now · ${ui.open} open" + if (ui.habits > 0) " · 🔁 ${ui.habits}" else ""
            }, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
        }
        GlassIconButton(TabIcons.More, "Project settings", onClick = actions.onSettings)
    }
}

/** A progress bar that runs to the finish line (a flag at its end): done of all, the number as words beside it. */
@Composable
private fun ProgressLine(ui: ProjectUi) {
    val f = if (ui.total == 0) 0f else ui.done.toFloat() / ui.total
    val shown by animateFloatAsState(f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow), label = "progress")
    val (a, b) = TileColors.stops(ui.name, ui.color)
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.xl).semantics(mergeDescendants = true) { contentDescription = "${ui.done} of ${ui.total} done" },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Glass.Ink.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(shown).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(a, b))))
        }
        Spacer(Modifier.width(Space.sm))
        Icon(TabIcons.Flag, contentDescription = null, tint = if (f >= 1f) Glass.Primary else Glass.Muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.xs))
        Text("${ui.done}/${ui.total}", style = Glass.NumberText, color = Glass.Muted)
    }
}

/** The one thing to do now: big title, its tiny first step, a tick, and "Start · 10 min" (a quiet countdown). */
@Composable
private fun NextCard(
    r: ProjectRow, step: String?, today: LocalDate, actions: ProjectActions,
    // Long-press lifts the card like any row, so the task you are most likely to grab can go to another project too.
    onLift: (String, Offset) -> Unit = { _, _ -> }, onDragAt: (Offset) -> Unit = {}, onDrop: (String) -> Boolean = { false },
) {
    var cardTop by remember { mutableStateOf(Offset.Zero) }
    var cardDy by remember { mutableFloatStateOf(0f) }
    var lifted by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    var endsAt by rememberSaveable(r.id) { mutableLongStateOf(0L) }
    var left by remember { mutableIntStateOf(0) }
    LaunchedEffect(endsAt) {
        while (endsAt > 0L) {
            left = ((endsAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
            if (left == 0) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); endsAt = 0L; break }
            delay(1_000)
        }
    }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)
        .onPlaced { cardTop = it.positionInRoot() }
        .graphicsLayer { translationY = cardDy; if (lifted) { scaleX = 1.03f; scaleY = 1.03f } }
        .pointerInputDrag(
            key = r.id,
            onStart = { at -> lifted = true; cardDy = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLift(r.id, cardTop + at) },
            onDrag = { delta, at -> cardDy += delta; onDragAt(cardTop + at + Offset(0f, cardDy)) },
            onEnd = { lifted = false; cardDy = 0f; onDrop(r.id) },
        )
        .semantics { stateDescription = if (lifted) "Lifted" else "" }) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Text("Next step", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(r, big = true) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); actions.onTick(r.id) }
                val press = remember { MutableInteractionSource() }
                Text(r.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = Glass.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable(press, indication = null, role = Role.Button, onClickLabel = "Open") { actions.onOpenTask(r.id) })
            }
            // The tiny first step: the thing to do right now (a task with no steps starts with itself).
            Text("Start: ${step ?: r.title}", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Space.touch, top = Space.xs))
            // The date sits on its own line: beside the Start button it wrapped as "09:00-10 / :00" (Boss, 0.3.33 CI shot).
            r.due?.let {
                Text(DatePick.dueText(it, today), style = MaterialTheme.typography.labelMedium, color = Glass.Muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Space.touch, top = Space.xs))
            }
            Row(Modifier.padding(start = Space.touch, top = Space.md), verticalAlignment = Alignment.CenterVertically) {
                val running = endsAt > 0L
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier.heightIn(min = Space.touch).clip(RoundedCornerShape(24.dp)).background(if (running) Glass.PrimarySoft else Glass.Primary)
                        .pressScale(press).clickable(press, indication = null, role = Role.Button) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            endsAt = if (running) 0L else System.currentTimeMillis() + 10 * 60_000L
                        }
                        .semantics { contentDescription = if (running) "Stop the timer" else "Start, 10 minutes" }
                        .padding(horizontal = Space.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(TabIcons.Clock, contentDescription = null, tint = if (running) Glass.PrimaryInk else Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.sm))
                    Text(if (running) "%d:%02d".format(left / 60, left % 60) else "Start · 10 min", style = MaterialTheme.typography.labelLarge,
                        color = if (running) Glass.PrimaryInk else Color.White)
                }
            }
        }
    }
}

/** No next action: the first thing on the page is adding one (Boss: "Add the next step" first). */
@Composable
private fun FirstStepCard(title: String, actions: ProjectActions) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Text("Add the next step", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
            Text("One small thing you can start in 10 minutes.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
            Spacer(Modifier.height(Space.sm))
            AddLine(title, actions, autoFocus = false)
        }
    }
}

@Composable
private fun FinishCard(onFinish: () -> Unit) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Text("Everything is done 🎉", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = Glass.Ink)
            Text("Close it and say what you shipped.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
            Spacer(Modifier.height(Space.md))
            BigButton("Finish project", TabIcons.Flag, onFinish)
        }
    }
}

@Composable
private fun FinishedCard(ui: ProjectUi, onReopen: () -> Unit) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Text("Finished · ${ui.finishedAt?.take(10).orEmpty()}", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk)
            Text(ui.shipped ?: "Done.", style = MaterialTheme.typography.titleLarge, color = Glass.Ink)
            Spacer(Modifier.height(Space.sm))
            val press = remember { MutableInteractionSource() }
            Text("Reopen", style = MaterialTheme.typography.labelLarge, color = Glass.Primary,
                modifier = Modifier.heightIn(min = Space.touch).clickable(press, indication = null, role = Role.Button, onClick = onReopen).padding(vertical = Space.md))
        }
    }
}

@Composable
internal fun BigButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(Glass.Primary).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.sm))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

/** Where a lifted row lands: the slot whose centre the row's own centre passed ([heights] in px, in list order). */
internal fun dropIndex(heights: List<Int>, from: Int, dy: Float): Int {
    if (heights.isEmpty()) return from
    var to = from
    var moved = dy
    if (dy > 0) while (to < heights.lastIndex && moved > heights[to + 1] / 2f) { moved -= heights[to + 1]; to++ }
    else while (to > 0 && -moved > heights[to - 1] / 2f) { moved += heights[to - 1]; to-- }
    return to
}

/** A sort between the new neighbours of a row moved from [from] to [to] (the rest keep theirs: one PATCH per drag). */
internal fun sortAfterMove(sorts: List<Double>, from: Int, to: Int): Double {
    if (from == to) return sorts[from]
    val rest = sorts.toMutableList().apply { removeAt(from) }
    val before = rest.getOrNull(to - 1)
    val after = rest.getOrNull(to)
    return when {
        before == null && after == null -> sorts[from]
        before == null -> after!! - 1.0
        after == null -> before + 1.0
        else -> (before + after) / 2.0
    }
}

/** The NOW heading's tasks after the next one, in order: long-press lifts a row (glass shadow, a tick per slot). */
@Composable
private fun NowList(
    title: String, rows: List<ProjectRow>, project: String, today: LocalDate, actions: ProjectActions,
    onLift: (String, Offset) -> Unit, onDragAt: (Offset) -> Unit, onDrop: (String) -> Boolean, done: List<ProjectRow>,
) {
    val haptics = LocalHapticFeedback.current
    val heights = remember { mutableStateMapOf<String, Int>() }
    var lifted by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    var slot by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Row(Modifier.padding(start = Space.rowLead, bottom = Space.rowLead), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(Space.sm))
            Text("${rows.size}", style = Glass.NumberText, color = Glass.Muted)
        }
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = Space.xs)) {
                rows.forEachIndexed { i, r -> androidx.compose.runtime.key(r.id) {
                    val isLifted = lifted == r.id
                    val from = rows.indexOfFirst { it.id == lifted }
                    // Rows between the lifted one and its slot step aside by the lifted row's height.
                    val shift = if (lifted != null && !isLifted && from >= 0 && slot >= 0) {
                        val h = (heights[lifted!!] ?: 0).toFloat()
                        when { i in (from + 1)..slot -> -h; i in slot until from -> h; else -> 0f }
                    } else 0f
                    var top by remember { mutableStateOf(Offset.Zero) }
                    Box(
                        Modifier.fillMaxWidth()
                            .onSizeChanged { heights[r.id] = it.height }
                            .onPlaced { top = it.positionInRoot() }
                            .graphicsLayer {
                                translationY = if (isLifted) dy else shift
                                if (isLifted) { scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 16.dp.toPx(); shape = RoundedCornerShape(16.dp); clip = true }
                            }
                            .background(if (isLifted) Color.White.copy(alpha = 0.92f) else Color.Transparent)
                            .pointerInputDrag(
                                key = r.id,
                                onStart = { at -> lifted = r.id; dy = 0f; slot = i; haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLift(r.id, top + at) },
                                onDrag = { delta, at ->
                                    dy += delta
                                    // `at` is local to the lifted (translated) row and `top` is its resting place, so the
                                    // finger on screen is top + dy + at. Without dy it never reached the Move to strip
                                    // and every drop became a reorder (CI 40936570317 sent sort, never project).
                                    onDragAt(top + at + Offset(0f, dy))
                                    val list = rows.map { heights[it.id] ?: 0 }
                                    val s = dropIndex(list, i, dy)
                                    if (s != slot) { slot = s; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                                },
                                onEnd = {
                                    val to = slot
                                    val id = lifted
                                    lifted = null; dy = 0f; slot = -1
                                    if (id != null && !onDrop(id) && to >= 0 && to != i) actions.onSort(id, sortAfterMove(rows.map { it.sort }, i, to))
                                },
                            )
                            .semantics { stateDescription = if (isLifted) "Lifted" else "" },
                    ) { TaskLine(r, today, onOpen = { actions.onOpenTask(r.id) }, onTick = { actions.onTick(r.id) }) }
                    RowLine()
                } }
                AddLine(project, actions, autoFocus = false)
                if (done.isNotEmpty()) { RowLine(); DoneFold(done, "done-now-$title", actions.onOpenTask) }
            }
        }
    }
}

private fun Modifier.pointerInputDrag(key: Any, onStart: (Offset) -> Unit, onDrag: (Float, Offset) -> Unit, onEnd: () -> Unit): Modifier =
    this.pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = onStart,
            onDrag = { change, amount -> change.consume(); onDrag(amount.y, change.position) },
            onDragEnd = onEnd,
            onDragCancel = onEnd,
        )
    }

/** "+ Add task" typed in place: Enter adds it and keeps the field open for the next one; no field → the Add sheet. */
@Composable
private fun AddLine(project: String, actions: ProjectActions, autoFocus: Boolean) {
    val add = actions.onAddText
    if (add == null) { AddRow(project, actions.onAdd); return }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = Space.xs, end = Space.lg), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Space.touch), contentAlignment = Alignment.Center) { Icon(TabIcons.Plus, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(20.dp)) }
        // Enter adds it and empties the field; the keyboard stays up for the next one (Things' "Enter adds another").
        var text by rememberSaveable { mutableStateOf("") }
        val style = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink)
        androidx.compose.foundation.text.BasicTextField(
            value = text, onValueChange = { text = it.replace('\n', ' ') },
            modifier = Modifier.weight(1f).semantics { contentDescription = "Add a task to $project" },
            textStyle = style, singleLine = true, cursorBrush = androidx.compose.ui.graphics.SolidColor(Glass.Primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (text.isNotBlank()) { add(text.trim()); text = "" } }),
            decorationBox = { inner -> Box { if (text.isEmpty()) Text("Add task", style = style, color = Glass.Muted); inner() } },
        )
        actions.onAddMany?.let { many ->
            var pasting by rememberSaveable { mutableStateOf(false) }
            GlassIconButton(TabIcons.ListPlus, "Paste a list", plain = true) { pasting = true }
            if (pasting) PasteListSheet(project, onAdd = many, onDismiss = { pasting = false })
        }
    }
}

@Composable
private fun AddRow(project: String, onAdd: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onAdd)
            .semantics(mergeDescendants = true) { contentDescription = "Add a task to $project" }
            .padding(start = Space.xs, end = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Space.touch), contentAlignment = Alignment.Center) { Icon(TabIcons.Plus, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(20.dp)) }
        Text("Add task", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted)
    }
}

/** While a task is lifted: the other projects as tiles; drop on one to move the task there. */
@Composable
private fun MoveStrip(others: List<ProjectEntity>, target: String?, modifier: Modifier, onTop: (Float) -> Unit, onTile: (String, ClosedFloatingPointRange<Float>) -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.gutter).onPlaced { onTop(it.positionInRoot().y) }
        .clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.94f)).border(1.25.dp, Glass.Primary.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
        .padding(Space.md)) {
        Text("Move to", style = MaterialTheme.typography.labelLarge, color = Glass.Muted)
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            // The newest projects first: the one being filled is usually the one just made.
            others.sortedByDescending { it.sort }.take(6).forEach { p ->
                val on = target == p.name
                Column(
                    Modifier.weight(1f).onPlaced { val x = it.positionInRoot().x; onTile(p.name, x..(x + it.size.width)) }
                        .clip(RoundedCornerShape(14.dp)).background(if (on) Glass.PrimarySoft else Color.Transparent).padding(vertical = Space.xs)
                        .semantics(mergeDescendants = true) { contentDescription = "Move to ${shortName(p.name, p.title)}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.graphicsLayer { val s = if (on) 1.15f else 1f; scaleX = s; scaleY = s }) { ProjectTile(p.name, p.color, p.title, size = 32.dp, icon = p.icon) }
                    Text(shortName(p.name, p.title), style = MaterialTheme.typography.labelSmall, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RowLine() = HorizontalDivider(Modifier.padding(start = Space.rowText, end = Space.lg), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))

/** A row that opens and closes what is under it: a chevron that turns, a title, a count. */
@Composable
private fun FoldHead(title: String, count: String, open: Boolean, onToggle: () -> Unit, strong: Boolean = false) {
    val turn by animateFloatAsState(if (open) 90f else 0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), label = "fold")
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = if (open) "Open" else "Folded" }
            .padding(start = Space.xl, end = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TabIcons.ChevronRight, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turn })
        Spacer(Modifier.width(Space.xl))
        Text(title, style = if (strong) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge, color = if (strong) Glass.Ink else Glass.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count.isNotEmpty()) { Spacer(Modifier.width(Space.sm)); Text(count, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun Unfold(open: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        open,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
    ) { Column { content() } }
}

/** The done ones of a heading, folded as "N done" (the web's DoneList). */
@Composable
private fun DoneFold(done: List<ProjectRow>, key: String, onOpenTask: (String) -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    FoldHead("${done.size} done", "", open, onToggle = { open = !open })
    Unfold(open) { done.forEach { r -> DoneLine(r) { onOpenTask(r.id) } } }
}

/** Later or finished headings in one pane, each folded into one line until tapped. */
@Composable
private fun FoldPane(title: String, groups: List<PhaseGroup>, today: LocalDate, actions: ProjectActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.padding(start = Space.rowLead, bottom = Space.rowLead))
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = Space.xs)) {
                groups.forEachIndexed { i, g ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = Space.lg), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                    var open by rememberSaveable("fold-" + g.title) { mutableStateOf(false) }
                    FoldHead(g.title, if (g.rows.isNotEmpty()) "${g.rows.size} open" else "${g.done.size} done", open, onToggle = { open = !open }, strong = true)
                    Unfold(open) {
                        g.rows.forEach { r -> TaskLine(r, today, onOpen = { actions.onOpenTask(r.id) }, onTick = { actions.onTick(r.id) }) }
                        g.done.forEach { r -> DoneLine(r) { actions.onOpenTask(r.id) } }
                    }
                }
            }
        }
    }
}

/** A finished task: a filled mint ring with a tick, the words struck through and quiet; a tap still opens it. */
@Composable
private fun DoneLine(r: ProjectRow, onOpen: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = "Open", onClick = onOpen)
            .semantics(mergeDescendants = true) { stateDescription = "Done" }
            .padding(start = Space.xs, end = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Space.touch), contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).background(Glass.Primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(TabIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
        Text(r.title, style = MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.LineThrough), color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The tick ring in the task's priority colour (a bigger one on the Next step card). */
@Composable
private fun Ring(r: ProjectRow, big: Boolean = false, onTick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val flag = PriorityColors.getOrNull(r.priority)?.takeIf { r.priority < 3 } ?: Color(0xFF9AA2BB)
    Box(
        Modifier.size(Space.touch).pressScale(press)
            .clickable(press, indication = null, role = Role.Checkbox, onClick = onTick)
            .semantics { contentDescription = "Done: ${r.title}" },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(if (big) 28.dp else 20.dp)) {
            val line = (if (big) 2.dp else 1.5.dp).toPx()
            drawCircle(flag.copy(alpha = 0.08f), size.minDimension / 2)
            drawCircle(flag, size.minDimension / 2 - line / 2, style = Stroke(line))
        }
    }
}

@Composable
private fun TaskLine(r: ProjectRow, today: LocalDate, onOpen: () -> Unit, onTick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val open = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressOnTouch()
            .clickable(open, indication = null, role = Role.Button, onClickLabel = "Open", onClick = onOpen)
            .padding(start = Space.xs, end = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ring(r) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onTick() }
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val d = r.due
            if (d != null || r.every != null) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val small = MaterialTheme.typography.labelSmall
                if (d != null) {
                    val day = d.take(10)
                    val c = if (day < today.toString()) Glass.Risk else if (day == today.toString()) Glass.Primary else Glass.Muted
                    Icon(TabIcons.Calendar, contentDescription = null, tint = c, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text(DatePick.dueText(d, today), style = small, color = c)
                    Spacer(Modifier.width(10.dp))
                }
                r.every?.let { Text(Repeat.label(it), style = small, color = Glass.Muted) }
            }
        }
    }
}

/** A milestone's progress from the tasks linked to it: done of linked (0/0 = none linked yet). */
internal fun milestoneProgress(groups: List<PhaseGroup>, id: String): Pair<Int, Int> {
    val open = groups.sumOf { g -> g.rows.count { it.milestone == id } }
    val done = groups.sumOf { g -> g.done.count { it.milestone == id } }
    return done to open + done
}

private fun newId(prefix: String) = prefix + java.lang.Long.toString(System.currentTimeMillis(), 36).takeLast(8)

/**
 * The project's 1-year goals (normal synced data, not the sealed Compass): each goal's milestones with dates, each
 * milestone's progress from its linked tasks; tap a milestone to link tasks, its circle to mark it reached.
 */
@Composable
private fun GoalsPane(ui: ProjectUi, today: LocalDate, actions: ProjectActions) {
    var dating by remember { mutableStateOf<Pair<String, String>?>(null) }
    var linking by remember { mutableStateOf<org.pagel.ai.data.projects.Milestone?>(null) }
    fun put(g: List<ProjectGoal>) = actions.onGoals(g)
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Text("1-year goals", style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.padding(start = Space.rowLead, bottom = Space.rowLead).semantics { heading() })
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
                ui.goals.forEachIndexed { gi, g ->
                    if (gi > 0) HorizontalDivider(Modifier.padding(vertical = Space.sm), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                    val reached = g.milestones.count { it.done }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(TabIcons.Flag, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.sm))
                        // Tap to fix a typo; clear the words to remove the goal (a goal used to be write-once).
                        InlineField(g.title, "Goal", "Goal: ${g.title}", MaterialTheme.typography.titleMedium, Modifier.weight(1f), allowBlank = true) { t ->
                            put(if (t.isBlank()) ui.goals.filter { it.id != g.id } else ui.goals.map { x -> if (x.id == g.id) x.copy(title = t.trim()) else x })
                        }
                        if (g.milestones.isNotEmpty()) Text("$reached/${g.milestones.size}", style = Glass.NumberText, color = Glass.Muted)
                    }
                    g.milestones.forEach { m ->
                        val (d, n) = milestoneProgress(ui.groups, m.id)
                        val press = remember(m.id) { MutableInteractionSource() }
                        Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(press, indication = null, role = Role.Button, onClickLabel = "Link tasks") { linking = m },
                            verticalAlignment = Alignment.CenterVertically) {
                            val cp = remember(m.id + "c") { MutableInteractionSource() }
                            Box(Modifier.size(Space.touch).clickable(cp, indication = null, role = Role.Checkbox) {
                                put(ui.goals.map { x -> if (x.id != g.id) x else x.copy(milestones = x.milestones.map { y -> if (y.id == m.id) y.copy(done = !y.done) else y }) })
                            }.semantics { contentDescription = if (m.done) "Reached: ${m.title}" else "Mark reached: ${m.title}" }, contentAlignment = Alignment.Center) {
                                Box(Modifier.size(18.dp).clip(CircleShape).background(if (m.done) Glass.Primary else Color.Transparent).border(1.5.dp, Glass.Primary, CircleShape),
                                    contentAlignment = Alignment.Center) { if (m.done) Icon(TabIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(m.title, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (n > 0) Box(Modifier.padding(top = Space.xs).fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Glass.Ink.copy(alpha = 0.08f))) {
                                    Box(Modifier.fillMaxWidth(d.toFloat() / n).height(4.dp).background(Glass.Primary))
                                }
                            }
                            if (n > 0) { Text("$d/$n", style = Glass.NumberText, color = Glass.Muted); Spacer(Modifier.width(Space.sm)) }
                            val dp2 = remember(m.id + "d") { MutableInteractionSource() }
                            Text(m.due?.let { DatePick.dueText(it, today) } ?: "+ date", style = MaterialTheme.typography.labelLarge, color = if (m.due == null) Glass.Muted else Glass.Ink,
                                modifier = Modifier.heightIn(min = Space.touch).clickable(dp2, indication = null, role = Role.Button, onClickLabel = "Pick a date") { dating = g.id to m.id }
                                    .padding(vertical = Space.md))
                        }
                    }
                    WordField("Add a milestone", "Add a milestone to ${g.title}") { t ->
                        put(ui.goals.map { x -> if (x.id != g.id) x else x.copy(milestones = x.milestones + org.pagel.ai.data.projects.Milestone(newId("m"), t)) })
                    }
                }
                if (ui.goals.size < 5) WordField(if (ui.goals.isEmpty()) "Add a 1-year goal" else "Add another goal", "Add a 1-year goal") { t -> put(ui.goals + ProjectGoal(newId("g"), t)) }
            }
        }
    }
    dating?.let { (gid, mid) ->
        val m = ui.goals.firstOrNull { it.id == gid }?.milestones?.firstOrNull { it.id == mid }
        org.pagel.ai.ui.glass.DateSheet(m?.due, today, onPick = { due ->
            put(ui.goals.map { x -> if (x.id != gid) x else x.copy(milestones = x.milestones.map { y -> if (y.id == mid) y.copy(due = due?.take(10)) else y }) })
        }, onDismiss = { dating = null })
    }
    linking?.let { m -> LinkSheet(ui, m, actions.onLink) { linking = null } }
}

/** Which tasks move this milestone: a tick per open task; the progress follows from them. */
@Composable
private fun LinkSheet(ui: ProjectUi, m: org.pagel.ai.data.projects.Milestone, onLink: (String, String?) -> Unit, onDismiss: () -> Unit) {
    org.pagel.ai.ui.glass.GlassSheet(onDismiss) {
        Column(Modifier.padding(bottom = Space.xxxl)) {
            org.pagel.ai.ui.glass.SheetTitle("Tasks for " + m.title, onDone = onDismiss)
            val rows = ui.groups.flatMap { it.rows }
            if (rows.isEmpty()) Text("No open tasks yet.", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, modifier = Modifier.padding(horizontal = Space.xl))
            rows.forEach { r ->
                var on by remember(r.id, r.milestone) { mutableStateOf(r.milestone == m.id) }
                val press = remember(r.id) { MutableInteractionSource() }
                Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(press, indication = null, role = Role.Checkbox) { on = !on; onLink(r.id, if (on) m.id else null) }
                    .semantics(mergeDescendants = true) { stateDescription = if (on) "Linked" else "Not linked" }.padding(horizontal = Space.xl),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Glass.Primary else Color.Transparent).border(1.5.dp, Glass.Primary, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center) { if (on) Icon(TabIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    Spacer(Modifier.width(Space.md))
                    Text(r.title, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A one-line field: type, Enter adds, the field empties for the next one. */
@Composable
private fun WordField(placeholder: String, description: String, onAdd: (String) -> Unit) {
    var text by rememberSaveable(description) { mutableStateOf("") }
    val style = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink)
    androidx.compose.foundation.text.BasicTextField(
        value = text, onValueChange = { text = it.replace('\n', ' ').take(120) },
        modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch).padding(vertical = Space.md).semantics { contentDescription = description },
        textStyle = style, singleLine = true, cursorBrush = androidx.compose.ui.graphics.SolidColor(Glass.Primary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (text.isNotBlank()) { onAdd(text.trim()); text = "" } }),
        decorationBox = { inner -> Box { if (text.isEmpty()) Text("+ $placeholder", style = style, color = Glass.Muted); inner() } },
    )
}
