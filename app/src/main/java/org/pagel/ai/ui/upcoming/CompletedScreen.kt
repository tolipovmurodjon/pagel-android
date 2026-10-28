package org.pagel.ai.ui.upcoming

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.upcoming.DoneGroup
import org.pagel.ai.data.upcoming.doneTimeOf
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressOnTouch
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.projects.shortName
import org.pagel.ai.ui.task.LocalTaskOpener
import org.pagel.ai.ui.today.headline

@Composable
fun CompletedRoute(contentPadding: PaddingValues, reselect: Int, vm: CompletedViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    CompletedScreen(ui, contentPadding, reselect, onReopen = vm::reopen)
}

/**
 * Completed, native (Phase 4, the web's /completed, grouped by day as Things' Logbook): Today, Yesterday, weekday
 * names, then dates, each task with the time it was done and its project tile. A tap opens its panel (read only for a
 * done task); the filled check opens it again (pagel.uz's undone), Room first, with Undo.
 */
@Composable
fun CompletedScreen(ui: CompletedUi, contentPadding: PaddingValues, reselect: Int = 0, onReopen: (String) -> Unit = {}) {
    val list = rememberLazyListState()
    val openTask = LocalTaskOpener.current
    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head", contentType = "head") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                Text("The proof that the list moves", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                Text("Completed", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
                Text(if (ui.count == 1) "1 task done" else "${ui.count} tasks done", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
            }
        }
        when {
            ui.loading -> item(key = "loading", contentType = "note") {
                Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(3, Modifier.padding(vertical = 6.dp)) }
            }
            ui.groups.isEmpty() -> item(key = "none", contentType = "note") {
                Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Nothing finished yet", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                        Text("Tick a task and it shows here, by day.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
                    }
                }
            }
            else -> for (g in ui.groups) item(key = "day-${g.day}", contentType = "day") {
                DayPane(g, ui, onOpen = openTask, onReopen = onReopen, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun DayPane(g: DoneGroup, ui: CompletedUi, onOpen: (String) -> Unit, onReopen: (String) -> Unit, modifier: Modifier) {
    Pane(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(bottom = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.label, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.weight(1f).semantics { heading() })
                Text("${g.tasks.size}", style = Glass.NumberText, color = Glass.Muted, modifier = Modifier.semantics { contentDescription = "${g.tasks.size} done" })
            }
            for ((i, t) in g.tasks.withIndex()) key(t.id) {
                if (i > 0) HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                DoneRow(t, ui, onOpen, onReopen)
            }
        }
    }
}

/** One done task: the filled check (tap = open it again), the title, then the time it was done · project. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DoneRow(t: TaskEntity, ui: CompletedUi, onOpen: (String) -> Unit, onReopen: (String) -> Unit) {
    val title = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp), verticalAlignment = Alignment.Top) {
        val press = remember { MutableInteractionSource() }
        Box(
            Modifier.size(48.dp).pressScale(press)
                .clickable(press, indication = null, role = Role.Checkbox) { haptics.performHapticFeedback(HapticFeedbackType.ToggleOff); onReopen(t.id) }
                .semantics { contentDescription = "Undo: $title"; stateDescription = "Done" },
            contentAlignment = Alignment.Center,
        ) {
            // Bright mint with an ink tick: done, without a 4th green (Boss's colour theory).
            Box(Modifier.size(22.dp).background(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), CircleShape), contentAlignment = Alignment.Center) {
                Icon(TabIcons.Check, contentDescription = null, tint = Glass.Ink, modifier = Modifier.size(14.dp))
            }
        }
        val open = remember { MutableInteractionSource() }
        Column(
            Modifier.weight(1f).pressOnTouch().clickable(open, indication = null, role = Role.Button, onClickLabel = "Open") { onOpen(t.id) }
                .padding(top = 12.dp, bottom = 10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val small = MaterialTheme.typography.labelSmall
            val icon = with(LocalDensity.current) { 13.sp.toDp() }
            FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val time = t.doneAt?.let(::doneTimeOf).orEmpty()
                if (time.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(TabIcons.Clock, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(icon))
                    Spacer(Modifier.width(4.dp))
                    Text(time, style = small, color = Glass.Muted, maxLines = 1)
                }
                val p = ui.projects[t.project]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProjectTile(t.project, p?.color, p?.title ?: t.project, size = 16.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(shortName(t.project, p?.title ?: t.project), style = small, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
