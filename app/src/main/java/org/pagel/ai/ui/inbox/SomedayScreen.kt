package org.pagel.ai.ui.inbox

import androidx.compose.foundation.interaction.MutableInteractionSource
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.GlassSheet
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
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

    /**
     * "Bring back" (checker 2026-10-30 #9: Inbox or Action): as an Action, the maybe is a P1 task in its project again;
     * to the Inbox, it moves to the Inbox list (P2) to be sorted again. Each has a 5 s Undo that takes the edits back
     * while they still wait on the phone, else sends the old values. Nothing here has a date or a count to clear.
     */
    fun bringBack(t: TaskEntity, toInbox: Boolean) {
        viewModelScope.launch {
            val ops = if (toInbox) listOfNotNull(tasks.move(t.id, INBOX_LIST), tasks.priority(t.id, 2)) else listOfNotNull(tasks.priority(t.id, 1))
            toasts.show(if (toInbox) "Back in the Inbox list" else "Back as an Action", action = "Undo", millis = 5_000) {
                viewModelScope.launch {
                    val back = ops.reversed().map { tasks.takeBack(t.id, it) }
                    if (back.any { !it }) {
                        tasks.priority(t.id, t.priority)
                        if (toInbox) tasks.move(t.id, t.project)
                    }
                }
            }
        }
    }

    companion object {
        /** The tasks' own Inbox list on pagel.uz (where a deleted project's tasks go too). */
        const val INBOX_LIST = "inbox"
    }
}

@Composable
fun SomedayRoute(contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (String) -> Unit, vm: SomedayViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var bringing by remember { mutableStateOf<TaskEntity?>(null) }
    Someday(ui, contentPadding, onBack, onOpen, onBringBack = { bringing = it })
    bringing?.let { t -> BringBackSheet(t, onPick = { toInbox -> vm.bringBack(t, toInbox); bringing = null }, onDismiss = { bringing = null }) }
}

/** Where a maybe comes back to: the Inbox list (sort it again) or an Action in its project. */
@Composable
fun BringBackSheet(t: TaskEntity, onPick: (toInbox: Boolean) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) { BringBackContent(t, onPick); Spacer(Modifier.height(SheetBottom)) }
}

@Composable
fun BringBackContent(t: TaskEntity, onPick: (toInbox: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Text("Bring back", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.padding(start = Space.xs))
        Text(t.title?.takeIf(String::isNotBlank) ?: t.text, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Space.xs, top = Space.xs, bottom = Space.md))
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = Space.xs)) {
                BringRow(TabIcons.ListTodo, "As an Action", "A next step in its project again") { onPick(false) }
                BringRow(TabIcons.Inbox, "To the Inbox", "The Inbox list, to sort again") { onPick(true) }
            }
        }
    }
}

@Composable
private fun BringRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = title }.padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(Space.xl))
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f).padding(vertical = Space.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        }
    }
}

/** Someday first (the maybes, no dates), then Waiting for with each check day. A row opens its project. */
@Composable
fun Someday(ui: SomedayUi, contentPadding: PaddingValues, onBack: () -> Unit = {}, onOpen: (String) -> Unit = {}, onBringBack: (TaskEntity) -> Unit = {}) {
    val today = remember { java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tashkent")) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TabIcons.ArrowLeft, "Back", size = Space.touch, plain = true, onClick = onBack)
            Icon(TabIcons.Moon, contentDescription = null, tint = Color(0xFF4F5FA8), modifier = Modifier.padding(start = Space.xs).size(Space.xl))
            Text("Someday", style = MaterialTheme.typography.headlineMedium, color = Glass.Ink, modifier = Modifier.padding(start = Space.xs).semantics { heading() })
        }
        Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
            Column(Modifier.padding(vertical = Space.xs)) {
                if (ui.someday.isEmpty()) Text("Nothing here. The maybes you file from the Inbox wait in this list.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(Space.xl))
                // Bring back is an icon (checker 2026-10-30 #4: the chip took 40 % of the row and broke the title per word).
                for (t in ui.someday) TaskRow(t, ui.projects[t.project], null, today, onOpen, action = {
                    GlassIconButton(TabIcons.Undo, "Bring back", size = Space.touch, plain = true) { onBringBack(t) }
                })
            }
        }
        if (ui.waiting.isNotEmpty()) {
            Row(Modifier.padding(start = Space.textEdge), verticalAlignment = Alignment.CenterVertically) {
                Icon(TabIcons.UserClock, contentDescription = null, tint = Color(0xFF0E7490), modifier = Modifier.size(Space.lg))
                Spacer(Modifier.width(Space.sm))
                Text("Waiting for", style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            }
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
            // Two lines at 1x; at a large font the title grows instead of being cut.
            val big = LocalDensity.current.fontScale >= 1.3f
            Text(t.title?.takeIf(String::isNotBlank) ?: t.text, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = if (big) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            val place = listOfNotNull(project?.let { shortName(it.name, it.title) } ?: t.project, due?.let { "check ${DatePick.dueText(it, today)}" }).joinToString(" · ")
            Text(place, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
        }
        action()
    }
}
