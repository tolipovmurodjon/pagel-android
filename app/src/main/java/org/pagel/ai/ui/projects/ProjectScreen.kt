package org.pagel.ai.ui.projects

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.sp
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

/** One phase of the plan: its title, whether it is NOW, and its open tasks in plan order. */
data class PhaseGroup(val title: String, val now: Boolean, val rows: List<ProjectRow>)

/**
 * Open tasks grouped by phase, NOW first, then in plan order (the rows come ordered by PagelDao.openTasks). An older
 * server sends no phase order, so its phases keep the order they first appear in.
 */
fun projectGroups(tasks: List<TaskEntity>): List<PhaseGroup> {
    val groups = LinkedHashMap<String, MutableList<TaskEntity>>()
    for (t in tasks) groups.getOrPut("${t.phaseNow}|${t.phaseSort}|${t.phase}") { mutableListOf() } += t
    return groups.values.map { list ->
        val first = list.first()
        PhaseGroup(first.phase.ifEmpty { "Tasks" }, first.phaseNow, list.map { ProjectRow(it.id, it.title?.takeIf(String::isNotBlank) ?: headline(it.text), it.priority, it.due, it.every) })
    }.sortedByDescending { it.now }
}

data class ProjectUi(val loading: Boolean = true, val name: String = "", val title: String = "", val color: String? = null, val open: Int = 0, val groups: List<PhaseGroup> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectViewModel @Inject constructor(private val tasks: TaskRepository, private val today: TodayRepository, session: Session) : ViewModel() {
    private val name = MutableStateFlow<String?>(null)

    val ui: StateFlow<ProjectUi> = name.filterNotNull().flatMapLatest { n ->
        combine(tasks.openTasks(n), session.projects()) { list, projects ->
            val p = projects.firstOrNull { it.name == n }
            ProjectUi(loading = false, name = n, title = shortName(n, p?.title ?: n), color = p?.color, open = list.size, groups = projectGroups(list))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUi())

    fun bind(project: String) { name.value = project }

    fun tick(id: String) { viewModelScope.launch { today.tick(id, done = true) } }
}

@Composable
fun ProjectRoute(project: String, contentPadding: PaddingValues, reselect: Int, onOpenTask: (String) -> Unit, vm: ProjectViewModel = hiltViewModel()) {
    LaunchedEffect(project) { vm.bind(project) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    ProjectScreen(ui, contentPadding, reselect, onOpenTask = onOpenTask, onTick = vm::tick)
}

private val TASHKENT = ZoneId.of("Asia/Tashkent")

/** A project's open tasks, one glass pane per phase, NOW first; a row opens the task panel, the ring ticks it. */
@Composable
fun ProjectScreen(ui: ProjectUi, contentPadding: PaddingValues, reselect: Int = 0, today: LocalDate = LocalDate.now(TASHKENT), onOpenTask: (String) -> Unit = {}, onTick: (String) -> Unit = {}) {
    val list = rememberLazyListState()
    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head", contentType = "head") {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.name.isNotEmpty()) { ProjectTile(ui.name, ui.color, ui.title, size = 40.dp); Spacer(Modifier.width(14.dp)) }
                Column {
                    Text(ui.title.ifEmpty { "Project" }, style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    Text("${ui.open} open", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                }
            }
        }
        when {
            ui.loading -> item(key = "loading", contentType = "note") { Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(4) } }
            ui.groups.isEmpty() -> item(key = "empty", contentType = "note") {
                Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("Nothing open here. Tap + to add the next step.", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, modifier = Modifier.padding(20.dp))
                }
            }
            else -> items(ui.groups, key = { "phase-" + it.title + it.now }, contentType = { "phase" }) { g -> Phase(g, today, onOpenTask, onTick) }
        }
    }
}

@Composable
private fun Phase(g: PhaseGroup, today: LocalDate, onOpenTask: (String) -> Unit, onTick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(start = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(g.title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (g.now) {
                Spacer(Modifier.width(8.dp))
                Text("NOW", style = MaterialTheme.typography.labelSmall, color = Glass.PrimaryInk,
                    modifier = Modifier.background(Glass.PrimarySoft, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("${g.rows.size}", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        }
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                g.rows.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                    TaskLine(r, today, onOpen = { onOpenTask(r.id) }, onTick = { onTick(r.id) })
                }
            }
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
                val small = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp)
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
