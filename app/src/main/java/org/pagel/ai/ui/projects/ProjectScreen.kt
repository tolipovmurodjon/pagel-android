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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.ui.glass.DatePick
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.PriorityColors
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressOnTouch
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.today.headline
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One task row on a project page. */
data class ProjectRow(val id: String, val title: String, val priority: Int, val due: String?, val every: String?)

/** One phase of the plan: its title, whether it is NOW, its open tasks and the ones done lately, in plan order. */
data class PhaseGroup(val title: String, val now: Boolean, val rows: List<ProjectRow>, val done: List<ProjectRow> = emptyList())

private fun TaskEntity.row() = ProjectRow(id, title?.takeIf(String::isNotBlank) ?: headline(text), priority, due, every)

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

/** [open] counts every phase, [now] the NOW phase only: the web's "2 now · 14 open in all". */
data class ProjectUi(val loading: Boolean = true, val name: String = "", val title: String = "", val color: String? = null, val open: Int = 0, val now: Int = 0, val groups: List<PhaseGroup> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectViewModel @Inject constructor(private val tasks: TaskRepository, private val today: TodayRepository, session: Session) : ViewModel() {
    private val name = MutableStateFlow<String?>(null)

    val ui: StateFlow<ProjectUi> = name.filterNotNull().flatMapLatest { n ->
        combine(tasks.projectTasks(n), session.projects()) { list, projects ->
            val p = projects.firstOrNull { it.name == n }
            val groups = projectGroups(list)
            ProjectUi(
                loading = false, name = n, title = shortName(n, p?.title ?: n), color = p?.color,
                open = list.count { it.status == "open" }, now = groups.filter { it.now }.sumOf { it.rows.size }, groups = groups,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUi())

    fun bind(project: String) { name.value = project }

    fun tick(id: String) { viewModelScope.launch { today.tick(id, done = true) } }
}

@Composable
fun ProjectRoute(project: String, contentPadding: PaddingValues, reselect: Int, onOpenTask: (String) -> Unit, onAdd: (String) -> Unit = {}, vm: ProjectViewModel = hiltViewModel()) {
    LaunchedEffect(project) { vm.bind(project) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    ProjectScreen(ui, contentPadding, reselect, onOpenTask = onOpenTask, onTick = vm::tick, onAdd = { onAdd(project) })
}

private val TASHKENT = ZoneId.of("Asia/Tashkent")

/**
 * A project's plan as the web's plans/[project] page: the NOW phase open in its own pane (its tasks, + Add task, the
 * done ones folded as "N done"), later phases folded into one pane, finished phases into another. A row opens the task
 * panel, the ring ticks it.
 */
@Composable
fun ProjectScreen(ui: ProjectUi, contentPadding: PaddingValues, reselect: Int = 0, today: LocalDate = LocalDate.now(TASHKENT), onOpenTask: (String) -> Unit = {}, onTick: (String) -> Unit = {}, onAdd: () -> Unit = {}) {
    val list = rememberLazyListState()
    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    val now = ui.groups.filter { it.now }
    val later = ui.groups.filter { !it.now && it.rows.isNotEmpty() }
    val finished = ui.groups.filter { !it.now && it.rows.isEmpty() && it.done.isNotEmpty() }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head", contentType = "head") {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.name.isNotEmpty()) { ProjectTile(ui.name, ui.color, ui.title, size = 40.dp); Spacer(Modifier.width(14.dp)) }
                Column {
                    Text(ui.title.ifEmpty { "Project" }, style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    if (!ui.loading) Text(if (ui.groups.isEmpty()) "No plan yet" else "${ui.now} now · ${ui.open} open in all", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                }
            }
        }
        when {
            ui.loading -> item(key = "loading", contentType = "note") { Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(4) } }
            ui.groups.isEmpty() -> item(key = "empty", contentType = "note") {
                Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("Nothing planned here yet.", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                        AddRow(ui.title, onAdd)
                    }
                }
            }
            else -> {
                items(now, key = { "now-" + it.title }, contentType = { "phase" }) { g -> NowPhase(g, ui.title, today, onOpenTask, onTick, onAdd) }
                if (now.isEmpty()) item(key = "add", contentType = "note") { Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { AddRow(ui.title, onAdd) } }
                if (later.isNotEmpty()) item(key = "later", contentType = "fold") { FoldPane("Later · ${later.size} ${if (later.size == 1) "phase" else "phases"}", later, today, onOpenTask, onTick) }
                if (finished.isNotEmpty()) item(key = "finished", contentType = "fold") { FoldPane("Done · ${finished.size} ${if (finished.size == 1) "phase" else "phases"}", finished, today, onOpenTask, onTick) }
            }
        }
    }
}

@Composable
private fun RowLine() = HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))

/** The NOW phase, open: its tasks, + Add task (into this project, not Today), and the done ones folded. */
@Composable
private fun NowPhase(g: PhaseGroup, project: String, today: LocalDate, onOpenTask: (String) -> Unit, onTick: (String) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(start = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(g.title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text("NOW", style = MaterialTheme.typography.labelSmall, color = Glass.PrimaryInk,
                modifier = Modifier.background(Color.White.copy(alpha = 0.72f), RoundedCornerShape(8.dp)).border(1.dp, Glass.Primary, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
            Spacer(Modifier.width(8.dp))
            Text("${g.rows.size}", style = Glass.NumberText, color = Glass.Muted)
        }
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                g.rows.forEach { r ->
                    TaskLine(r, today, onOpen = { onOpenTask(r.id) }, onTick = { onTick(r.id) })
                    RowLine()
                }
                AddRow(project, onAdd)
                if (g.done.isNotEmpty()) {
                    RowLine()
                    DoneFold(g.done, "done-now-" + g.title, onOpenTask)
                }
            }
        }
    }
}

/** "+ Add task" inside a pane: opens the Add sheet set to this project. */
@Composable
private fun AddRow(project: String, onAdd: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onAdd)
            .semantics(mergeDescendants = true) { contentDescription = "Add a task to $project" }
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(TabIcons.Plus, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(20.dp)) }
        Text("Add task", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted)
    }
}

/** A row that opens and closes what is under it: a chevron that turns, a title, a count. */
@Composable
private fun FoldHead(title: String, count: String, open: Boolean, onToggle: () -> Unit, strong: Boolean = false) {
    val turn by animateFloatAsState(if (open) 90f else 0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), label = "fold")
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = if (open) "Open" else "Folded" }
            .padding(start = 18.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TabIcons.ChevronRight, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turn })
        Spacer(Modifier.width(20.dp))
        Text(title, style = if (strong) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge, color = if (strong) Glass.Ink else Glass.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text(count, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, softWrap = false) }
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

/** The done ones of a phase, folded as "N done" (the web's DoneList). */
@Composable
private fun DoneFold(done: List<ProjectRow>, key: String, onOpenTask: (String) -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    FoldHead("${done.size} done", "", open, onToggle = { open = !open })
    Unfold(open) { done.forEach { r -> DoneLine(r) { onOpenTask(r.id) } } }
}

/** Later or finished phases in one pane, each folded into one line until tapped. */
@Composable
private fun FoldPane(title: String, groups: List<PhaseGroup>, today: LocalDate, onOpenTask: (String) -> Unit, onTick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                groups.forEachIndexed { i, g ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                    var open by rememberSaveable("fold-" + g.title) { mutableStateOf(false) }
                    FoldHead(g.title, if (g.rows.isNotEmpty()) "${g.rows.size} open" else "${g.done.size} done", open, onToggle = { open = !open }, strong = true)
                    Unfold(open) {
                        g.rows.forEach { r -> TaskLine(r, today, onOpen = { onOpenTask(r.id) }, onTick = { onTick(r.id) }) }
                        g.done.forEach { r -> DoneLine(r) { onOpenTask(r.id) } }
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
        Modifier.fillMaxWidth().heightIn(min = 48.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = "Open", onClick = onOpen)
            .semantics(mergeDescendants = true) { stateDescription = "Done" }
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).background(Glass.Primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(TabIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
        Text(r.title, style = MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.LineThrough), color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TaskLine(r: ProjectRow, today: LocalDate, onOpen: () -> Unit, onTick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val open = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressOnTouch()
            .clickable(open, indication = null, role = Role.Button, onClickLabel = "Open", onClick = onOpen)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val press = remember { MutableInteractionSource() }
        val flag = PriorityColors.getOrNull(r.priority)?.takeIf { r.priority < 3 } ?: Color(0xFF9AA2BB)
        Box(
            Modifier.size(48.dp).pressScale(press)
                .clickable(press, indication = null, role = Role.Checkbox) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onTick() }
                .semantics { contentDescription = "Done: ${r.title}" },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                val line = 1.5.dp.toPx()
                drawCircle(flag.copy(alpha = 0.08f), size.minDimension / 2)
                drawCircle(flag, size.minDimension / 2 - line / 2, style = Stroke(line))
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val d = r.due
            if (d != null || r.every != null) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val small = MaterialTheme.typography.labelSmall
                if (d != null) {
                    val day = d.take(10)
                    val c = if (day < today.toString()) Glass.Risk else if (day == today.toString()) Glass.Primary else Glass.Muted
                    Icon(TabIcons.Calendar, contentDescription = null, tint = c, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(DatePick.dueText(d, today), style = small, color = c)
                    Spacer(Modifier.width(10.dp))
                }
                r.every?.let { Text(Repeat.label(it), style = small, color = Glass.Muted) }
            }
        }
    }
}
