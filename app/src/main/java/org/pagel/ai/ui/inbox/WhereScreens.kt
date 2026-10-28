package org.pagel.ai.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.db.InboxSortedEntity
import org.pagel.ai.data.db.NoteCount
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.QuickNoteEntity
import org.pagel.ai.data.inbox.WhereItWent
import org.pagel.ai.data.session.Session
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.projects.shortName
import javax.inject.Inject

/** Notes (round D item 2): the projects with quick notes, or one project's notes, from Room; pagel.uz refreshes them. */
@HiltViewModel
class NotesViewModel @Inject constructor(private val where: WhereItWent, private val accounts: AccountStore, session: Session) : ViewModel() {
    val projects: StateFlow<Map<String, ProjectEntity>> = combine(session.projects(), where.noteCounts()) { p, _ -> p.associateBy { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val counts: StateFlow<List<NoteCount>> = where.noteCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun notes(project: String): Flow<List<QuickNoteEntity>> = where.notes(project)

    fun refresh(project: String?) {
        viewModelScope.launch {
            val account = (accounts.load() as? AccountState.Paired)?.account ?: return@launch
            if (project == null) where.refreshAll(account) else where.refreshNotes(account, project)
        }
    }
}

private val ACTION_WORDS = mapOf(
    "act" to ("✅" to "Tasks"), "note" to ("📝" to "Notes"), "keep" to ("🧠" to "Kept to learn"), "later" to ("⏳" to "Later"),
    "drop" to ("🗑️" to "Dropped"), "now" to ("⚡" to "Done now"),
)

/** "Sorted today": today's decisions grouped by where they went; a row opens its place; Undo while it still waits. */
@Composable
fun SortedTodayRoute(contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (InboxOpen) -> Unit, vm: InboxViewModel = hiltViewModel()) {
    val sorted by vm.sortedToday.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    SortedToday(sorted, ui.projects.associateBy { it.name }, contentPadding, onBack, onOpen, onUndo = vm::undoSorted)
}

@Composable
fun SortedToday(
    sorted: List<InboxSortedEntity>, projects: Map<String, ProjectEntity>, contentPadding: PaddingValues,
    onBack: () -> Unit = {}, onOpen: (InboxOpen) -> Unit = {}, onUndo: (InboxSortedEntity) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TabIcons.ArrowLeft, "Back", size = Space.touch, plain = true, onClick = onBack)
            Text("Sorted today", style = MaterialTheme.typography.headlineMedium, color = Glass.Ink, modifier = Modifier.padding(start = Space.xs).semantics { heading() })
        }
        if (sorted.isEmpty()) Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
            Text("Nothing sorted yet today. Each choice shows here with where it went.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(Space.xl))
        }
        for ((action, rows) in sorted.groupBy { it.action }.toList().sortedBy { ACTION_WORDS.keys.indexOf(it.first) }) {
            val (glyph, label) = ACTION_WORDS[action] ?: ("•" to action)
            Text("$glyph $label · ${rows.size}", style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.padding(start = Space.textEdge).semantics { heading() })
            Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                Column(Modifier.padding(vertical = Space.xs)) {
                    for (s in rows) {
                        val target = when { s.action == "act" && s.project != null -> InboxOpen.Project(s.project); s.action == "note" && s.project != null -> InboxOpen.Notes(s.project); else -> null }
                        val press = remember(s.file) { MutableInteractionSource() }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
                                .clickable(press, indication = null, enabled = target != null, role = Role.Button) { target?.let(onOpen) }
                                .padding(start = Space.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = Space.sm)) {
                                Text(s.line?.takeIf(String::isNotBlank) ?: s.claim, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val place = listOfNotNull(
                                    s.project?.let { p -> projects[p]?.let { shortName(it.name, it.title) } ?: p },
                                    when (s.due) { null -> null; else -> s.due },
                                ).joinToString(" · ")
                                if (place.isNotEmpty()) Text(place, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
                            }
                            GlassIconButton(TabIcons.Undo, "Undo: ${s.claim.take(40)}", size = Space.touch, plain = true) { onUndo(s) }
                        }
                    }
                }
            }
        }
    }
}

/** Notes: with no project, the projects that have notes; with one, its notes newest first (the Inbox's "Note" lines). */
@Composable
fun NotesRoute(project: String?, contentPadding: PaddingValues, onBack: () -> Unit, onOpenProject: (String) -> Unit, vm: NotesViewModel = hiltViewModel()) {
    LaunchedEffect(project) { vm.refresh(project) }
    val projects by vm.projects.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val notes by remember(project) { project?.let(vm::notes) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    Notes(project, projects, counts, notes, contentPadding, onBack, onOpenProject)
}

@Composable
fun Notes(
    project: String?, projects: Map<String, ProjectEntity>, counts: List<NoteCount>, notes: List<QuickNoteEntity>, contentPadding: PaddingValues,
    onBack: () -> Unit = {}, onOpenProject: (String) -> Unit = {},
) {
    val uri = LocalUriHandler.current
    val title = project?.let { p -> projects[p]?.let { "${shortName(it.name, it.title)} notes" } ?: "$p notes" } ?: "Notes"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TabIcons.ArrowLeft, "Back", size = Space.touch, plain = true, onClick = onBack)
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Space.xs).semantics { heading() })
        }
        if (project == null) {
            Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                Column(Modifier.padding(vertical = Space.xs)) {
                    if (counts.isEmpty()) Text("No notes yet. An Inbox “Note” lands in its project here.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(Space.xl))
                    for (c in counts) {
                        val p = projects[c.project]
                        val press = remember(c.project) { MutableInteractionSource() }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press).clickable(press, indication = null, role = Role.Button) { onOpenProject(c.project) }
                                .semantics(mergeDescendants = true) { contentDescription = "${p?.title ?: c.project} notes, ${c.count}" }.padding(horizontal = Space.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProjectTile(c.project, p?.color, p?.title ?: c.project, size = 28.dp)
                            Spacer(Modifier.width(Space.md))
                            Text(p?.let { shortName(it.name, it.title) } ?: c.project, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
                            Text("${c.count}", style = Glass.NumberText, color = Glass.Muted)
                        }
                    }
                }
            }
            return@Column
        }
        Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
            Column(Modifier.padding(vertical = Space.xs)) {
                if (notes.isEmpty()) Text("No notes in this project yet.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(Space.xl))
                for (n in notes) Row(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm), verticalAlignment = Alignment.Top) {
                    androidx.compose.foundation.Image(painterResource(Emoji.res("memo")), null, Modifier.size(22.dp))
                    Spacer(Modifier.width(Space.md))
                    Column(Modifier.weight(1f)) {
                        Text(n.text, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            n.day?.let { Text(it, style = Glass.NumberText, color = Glass.Muted) }
                            n.link?.let { l ->
                                val press = remember(l) { MutableInteractionSource() }
                                Text("post", style = MaterialTheme.typography.labelMedium, color = Glass.PrimaryInk,
                                    modifier = Modifier.heightIn(min = Space.touch).pressScale(press).clickable(press, indication = null, role = Role.Button) { runCatching { uri.openUri(l) } }
                                        .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = Space.md))
                            }
                        }
                    }
                }
            }
        }
    }
}

