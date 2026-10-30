package org.pagel.ai.ui.projects

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.projects.ProjectRepository
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.sync.LiveStatus
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectCreateField
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressOnTouch
import javax.inject.Inject

data class ProjectsUiState(val loading: Boolean = true, val projects: List<ProjectEntity> = emptyList(), val status: String = "")

/** The project list, read from Room only; the sync engine fills Room from pagel.uz. */
@HiltViewModel
class ProjectsViewModel @Inject constructor(session: Session, private val repo: ProjectRepository) : ViewModel() {
    val ui: StateFlow<ProjectsUiState> = combine(session.projects(), session.live) { projects, live ->
        ProjectsUiState(loading = projects.isEmpty() && live !is LiveStatus.Offline, projects = projects, status = live.line())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

    /**
     * A new project from its name; [then] gets its name (the page opens on it). The same rule as the picker: a name that
     * is taken opens that project instead of a twin, and an archived one comes back first.
     */
    fun create(title: String, then: (String) -> Unit) {
        viewModelScope.launch {
            val p = repo.existing(title)
            if (p?.archived == true) repo.archive(p.name, false)
            then(p?.name ?: repo.create(title))
        }
    }
    fun order(names: List<String>) { viewModelScope.launch { repo.order(names) } }
}

/**
 * Sync is silent: the saved copy shows at once and updates in place (Boss 2026-10-27: "I hate Syncing and progress
 * bars"). Only being offline is worth one word.
 */
internal fun LiveStatus.line(): String = when (this) {
    is LiveStatus.Offline -> "Offline"
    else -> ""
}

@Composable
fun ProjectsRoute(contentPadding: PaddingValues, reselect: Int, onOpenProject: (String) -> Unit = {}, vm: ProjectsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    ProjectsScreen(ui, contentPadding, reselect, onOpenProject, onCreate = { t -> vm.create(t, onOpenProject) }, onOrder = vm::order)
}

/** One line of the list: a project (indented when it is a sub-project), or an Area's heading. */
internal sealed interface ListLine {
    data class Area(val title: String) : ListLine
    data class Item(val p: ProjectEntity, val child: Boolean) : ListLine
}

/**
 * The running projects grouped by Area (Area → Project → sub-project, 2 levels at most), in their own order; a
 * sub-project sits under its parent. Projects with no area come first. Finished and archived ones are listed apart.
 */
internal fun projectLines(all: List<ProjectEntity>): List<ListLine> {
    val running = all.filter { !it.archived && it.finishedAt == null }
    val names = running.map { it.name }.toSet()
    val tops = running.filter { it.parent == null || it.parent !in names }
    val kids = running.filter { it.parent != null && it.parent in names }.groupBy { it.parent }
    val out = mutableListOf<ListLine>()
    for ((area, list) in tops.groupBy { it.area }.entries.sortedBy { it.key != null }) {
        if (area != null) out += ListLine.Area(area)
        for (p in list) { out += ListLine.Item(p, false); kids[p.name].orEmpty().forEach { out += ListLine.Item(it, true) } }
    }
    return out
}

/** Every project, grouped by area: + makes a new one in 2 taps, long-press drags to reorder, a row opens its page. */
@Composable
fun ProjectsScreen(
    ui: ProjectsUiState, contentPadding: PaddingValues, reselect: Int = 0, onOpenProject: (String) -> Unit = {},
    onCreate: (String) -> Unit = {}, onOrder: (List<String>) -> Unit = {},
) {
    val list = rememberLazyListState()
    // Re-tapping the tab scrolls back to the top (RULES.md, navigation).
    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val lines = projectLines(ui.projects)
    val done = ui.projects.filter { it.finishedAt != null && !it.archived }
    val archived = ui.projects.filter { it.archived }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item(key = "head", contentType = "head") {
            Row(Modifier.padding(start = Space.xl, end = Space.sm, top = Space.md), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Projects", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
                    Text("${ui.projects.filter { !it.archived && it.finishedAt == null }.sumOf { it.openCount }} open tasks" + if (ui.status.isEmpty()) "" else " · ${ui.status}",
                        style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                }
                GlassIconButton(TabIcons.Plus, "New project", on = adding) { adding = !adding }
            }
        }
        if (adding) item(key = "new", contentType = "new") {
            // The one create field (the picker's): Enter makes it and its page opens; an empty Enter closes it.
            Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                ProjectCreateField(onCreate = { t -> adding = false; onCreate(t) }, onCancel = { adding = false })
            }
        }
        if (ui.loading) item(key = "loading", contentType = "loading") { SkeletonRows(6) }
        if (!ui.loading && ui.projects.isEmpty()) item(key = "empty", contentType = "empty") {
            Text("No projects yet. Tap + and name one.", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, modifier = Modifier.padding(Space.xl))
        }
        if (lines.isNotEmpty()) item(key = "list", contentType = "list") { ReorderPane(lines, onOpenProject, onOrder) }
        if (done.isNotEmpty()) item(key = "done", contentType = "fold") { FoldedProjects("Done projects", done, onOpenProject) }
        if (archived.isNotEmpty()) item(key = "archived", contentType = "fold") { FoldedProjects("Archived", archived, onOpenProject) }
    }
}

/** The running projects in one pane; long-press lifts a row (shadow, a tick per slot), the drop saves the new order. */
@Composable
private fun ReorderPane(lines: List<ListLine>, onOpen: (String) -> Unit, onOrder: (List<String>) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val heights = remember { mutableStateMapOf<Int, Int>() }
    var from by remember { mutableIntStateOf(-1) }
    var slot by remember { mutableIntStateOf(-1) }
    var dy by remember { mutableFloatStateOf(0f) }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(vertical = Space.xs)) {
            lines.forEachIndexed { i, line ->
                key(if (line is ListLine.Item) line.p.name else "area:" + (line as ListLine.Area).title) {
                    val lifted = from == i
                    val shift = if (from >= 0 && !lifted && slot >= 0) {
                        val h = (heights[from] ?: 0).toFloat()
                        when { i in (from + 1)..slot -> -h; i in slot until from -> h; else -> 0f }
                    } else 0f
                    Box(
                        Modifier.fillMaxWidth().onSizeChanged { heights[i] = it.height }
                            .graphicsLayer {
                                translationY = if (lifted) dy else shift
                                if (lifted) { scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 16.dp.toPx(); shape = RoundedCornerShape(16.dp); clip = true }
                            }
                            .background(if (lifted) Color.White.copy(alpha = 0.92f) else Color.Transparent)
                            .then(if (line is ListLine.Item) Modifier.pointerInput(line.p.name) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { from = i; slot = i; dy = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDrag = { c, d ->
                                        c.consume(); dy += d.y
                                        val s = dropIndex(lines.indices.map { heights[it] ?: 0 }, i, dy)
                                        if (s != slot) { slot = s; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                                    },
                                    onDragEnd = {
                                        val f = from; val t = slot
                                        from = -1; slot = -1; dy = 0f
                                        if (f >= 0 && t >= 0 && t != f) onOrder(reordered(lines, f, t))
                                    },
                                    onDragCancel = { from = -1; slot = -1; dy = 0f },
                                )
                            } else Modifier)
                            .semantics { stateDescription = if (lifted) "Lifted" else "" },
                    ) {
                        when (line) {
                            is ListLine.Area -> Text(line.title, style = MaterialTheme.typography.labelLarge, color = Glass.Muted,
                                modifier = Modifier.padding(start = Space.lg, top = Space.md, bottom = Space.xs).semantics { heading() })
                            is ListLine.Item -> ProjectRow(line.p, line.child) { onOpen(line.p.name) }
                        }
                    }
                }
            }
        }
    }
}

/** The project names in their new order after moving line [from] to [to] (Area headings are not projects). */
internal fun reordered(lines: List<ListLine>, from: Int, to: Int): List<String> {
    val l = lines.toMutableList()
    l.add(to, l.removeAt(from))
    return l.filterIsInstance<ListLine.Item>().map { it.p.name }
}

@Composable
private fun FoldedProjects(title: String, list: List<ProjectEntity>, onOpen: (String) -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(vertical = Space.xs)) {
            val press = remember { MutableInteractionSource() }
            Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(press, indication = null, role = Role.Button) { open = !open }
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Open" else "Folded" }.padding(horizontal = Space.lg),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (open) TabIcons.ChevronDown else TabIcons.ChevronRight, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Space.md))
                Text(title, style = MaterialTheme.typography.labelLarge, color = Glass.Muted, modifier = Modifier.weight(1f))
                Text("${list.size}", style = Glass.NumberText, color = Glass.Muted)
            }
            if (open) list.forEach { p -> ProjectRow(p, false) { onOpen(p.name) } }
        }
    }
}

@Composable
private fun ProjectRow(p: ProjectEntity, child: Boolean, onOpen: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column {
        Row(
            Modifier.fillMaxWidth().pressOnTouch()
                .clickable(press, indication = null, role = Role.Button, onClick = onOpen)
                .padding(start = if (child) 40.dp else Space.lg, end = Space.lg, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProjectTile(p.name, p.color, p.title, size = if (child) 28.dp else 36.dp, icon = p.icon)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(shortName(p.name, p.title), style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                p.shipped?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (p.finishedAt == null) Text(p.openCount.toString(), style = Glass.NumberText, color = Glass.PrimaryInk,
                modifier = Modifier.background(Glass.PrimarySoft, RoundedCornerShape(10.dp)).padding(horizontal = Space.sm, vertical = 3.dp))
            else Icon(TabIcons.Check, contentDescription = "Finished", tint = Glass.Primary, modifier = Modifier.size(18.dp))
        }
        HorizontalDivider(Modifier.padding(start = 70.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
    }
}

/**
 * The name the web shows ("ABABIL", not "ABABIL — first-aid delivery drone plan"): a plan-style title (or none) uses
 * the known label or is cut at its dash or colon without a trailing "plan"; a name Boss typed is shown as typed.
 */
internal fun shortName(name: String, title: String): String {
    val planLike = title == name || title.endsWith(" plan", ignoreCase = true) || " — " in title || ": " in title
    if (!planLike) return title.trim().ifEmpty { name }
    return LABELS[name] ?: title
        .substringBefore(" — ").substringBefore(": ").removeSuffix(" plan").removeSuffix(" Plan").trim()
        .replaceFirstChar { it.uppercase() }.ifEmpty { name }
}

private val LABELS = mapOf(
    "humopedia" to "Humopedia", "ababil" to "ABABIL", "pagel" to "PAGEL", "uzcosmos" to "UzCosmos", "me" to "Me",
    "university" to "University", "gamedev" to "Game dev", "games" to "Games", "samarqand" to "Samarqand", "labs" to "Labs",
    "motion" to "Motion", "muhub" to "MuHub", "ovoz" to "Ovoz", "brilliant" to "Brilliant", "autonomy" to "Autonomy", "design" to "Design",
)
