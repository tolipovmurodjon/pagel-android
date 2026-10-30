package org.pagel.ai.ui.inbox

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.ui.glass.DatePick
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassChip
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.ToastCenter
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.projects.shortName
import javax.inject.Inject

/** The Someday list and the Waiting-for list, both from Room (Inbox clarify, 2026-10-30). */
data class SomedayUi(val someday: List<TaskEntity> = emptyList(), val waiting: List<TaskEntity> = emptyList(), val projects: Map<String, ProjectEntity> = emptyMap())

@HiltViewModel
class SomedayViewModel @Inject constructor(dao: PagelDao, private val tasks: TaskRepository, session: Session, private val toasts: ToastCenter) : ViewModel() {
    val ui: StateFlow<SomedayUi> = combine(dao.somedayTasks(), dao.waitingTasks(), session.projects()) { s, w, p -> SomedayUi(s, w, p.associateBy(ProjectEntity::name)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SomedayUi())

    /** "Bring back": a maybe becomes an Action (P1) again, with a 5 s Undo. Nothing here has a date or a count to clear. */
    fun bringBack(t: TaskEntity) {
        viewModelScope.launch {
            tasks.priority(t.id, 1)
            toasts.show("Back as an Action", action = "Undo", millis = 5_000) { viewModelScope.launch { tasks.priority(t.id, 3) } }
        }
    }
}

@Composable
fun SomedayRoute(contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (String) -> Unit, vm: SomedayViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Someday(ui, contentPadding, onBack, onOpen, vm::bringBack)
}

/** Someday first (the maybes, no dates), then Waiting for with each check day. A row opens its project. */
@Composable
fun Someday(ui: SomedayUi, contentPadding: PaddingValues, onBack: () -> Unit = {}, onOpen: (String) -> Unit = {}, onBringBack: (TaskEntity) -> Unit = {}) {
    val today = remember { java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tashkent")) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TabIcons.ArrowLeft, "Back", size = Space.touch, plain = true, onClick = onBack)
            Text("Someday", style = MaterialTheme.typography.headlineMedium, color = Glass.Ink, modifier = Modifier.padding(start = Space.xs).semantics { heading() })
        }
        Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
            Column(Modifier.padding(vertical = Space.xs)) {
                if (ui.someday.isEmpty()) Text("Nothing here. The maybes you file from the Inbox wait in this list.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(Space.xl))
                for (t in ui.someday) TaskRow(t, ui.projects[t.project], null, today, onOpen, action = { GlassChip(TabIcons.Undo, "Bring back", set = false, description = "Bring back: ${t.title ?: t.text}".take(60)) { onBringBack(t) } })
            }
        }
        if (ui.waiting.isNotEmpty()) {
            Text("Waiting for", style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.padding(start = Space.textEdge).semantics { heading() })
            Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                Column(Modifier.padding(vertical = Space.xs)) {
                    for (t in ui.waiting) TaskRow(t, ui.projects[t.project], t.due, today, onOpen)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: TaskEntity, project: ProjectEntity?, due: String?, today: java.time.LocalDate, onOpen: (String) -> Unit, action: @Composable () -> Unit = {}) {
    val press = remember(t.id) { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press).clickable(press, indication = null, role = Role.Button) { onOpen(t.project) }.padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectTile(t.project, project?.color, project?.title ?: t.project, size = 28.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f).padding(vertical = Space.sm)) {
            Text(t.title?.takeIf(String::isNotBlank) ?: t.text, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val place = listOfNotNull(project?.let { shortName(it.name, it.title) } ?: t.project, due?.let { "check ${DatePick.dueText(it, today)}" }).joinToString(" · ")
            Text(place, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
        }
        Spacer(Modifier.width(Space.sm))
        action()
    }
}
