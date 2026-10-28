package org.pagel.ai.ui.notebook

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.notebook.VaultFolderEntity
import org.pagel.ai.ui.compass.CompassSwitch
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.LocalToasts
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale

@Composable
fun FoldersRouteScreen(padding: PaddingValues, nav: NotebookNav, vm: FoldersViewModel = hiltViewModel()) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val toasts = LocalToasts.current
    LaunchedEffect(Unit) { vm.notices.collect { toasts.show(it, good = false) } }
    FoldersScreen(folders, padding, onBack = nav.back, onSwitch = vm::setAllowed, onOpen = { nav.open(FolderNotesRoute(it)) })
}

/**
 * Notebook → Folders (Boss 2026-10-28: "I allow it to read or not"): every vault folder pagel.uz has seen, with its
 * switch. Only this phone flips a switch. An open folder's notes reach pagel.uz and the AI may read them; a closed one
 * stays on the laptop. Kundalik is greyed with a lock and one line why: it never opens.
 */
@Composable
fun FoldersScreen(folders: List<VaultFolderEntity>?, padding: PaddingValues, onBack: () -> Unit = {}, onSwitch: (String, Boolean) -> Unit = { _, _ -> }, onOpen: (String) -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        item(key = "head", contentType = "head") {
            Column {
                BackHead("Folders", onBack)
                Text(
                    "An open folder's notes reach pagel.uz, and PAGEL may read them. A closed one stays on your laptop.",
                    style = MaterialTheme.typography.bodyMedium, color = Glass.Muted,
                    modifier = Modifier.padding(horizontal = Space.textEdge),
                )
            }
        }
        item(key = "list", contentType = "list") {
            Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                when {
                    folders == null -> SkeletonRows(4, Modifier.padding(Space.paneInset))
                    folders.isEmpty() -> Text(
                        "No folders yet. They come from your laptop's vault sync.",
                        style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(Space.paneInset),
                    )
                    else -> Column(Modifier.padding(vertical = Space.xs)) {
                        for (f in folders) FolderRow(f, onSwitch, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(f: VaultFolderEntity, onSwitch: (String, Boolean) -> Unit, onOpen: (String) -> Unit) {
    val depth = if (f.path == ".") 0 else f.path.count { it == '/' }
    val name = if (f.path == ".") "Top level" else f.path.substringAfterLast('/')
    val haptics = LocalHapticFeedback.current
    val toasts = LocalToasts.current
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).graphicsLayer { alpha = if (f.locked) 0.6f else 1f }
            .padding(start = Space.paneInset + Space.lg * depth, end = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The name side opens the folder's notes when it is open; a closed folder only says how to open it.
        Row(
            Modifier.weight(1f).heightIn(min = Space.row).pressScale(press)
                .clickable(press, indication = null, role = Role.Button, enabled = !f.locked) {
                    if (f.allowed) onOpen(f.path) else toasts.show("Turn $name on to read it here.", good = false)
                }
                .semantics(mergeDescendants = true) { contentDescription = if (f.locked) "$name: closed for good" else "Folder $name" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).background(
                    if (f.locked) Brush.linearGradient(listOf(Glass.Muted.copy(alpha = 0.5f), Glass.Muted)) else Brush.linearGradient(listOf(Glass.Aurora1, Glass.Primary)),
                    RoundedCornerShape(9.dp),
                ),
                contentAlignment = Alignment.Center,
            ) { Icon(if (f.locked) NotebookIcons.Lock else TabIcons.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp)) }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        f.locked -> "Closed for good. Your diary never leaves the phone as text."
                        f.allowed && !f.switched -> "Open with its parent · ${notes(f.notes)}"
                        f.allowed -> notes(f.notes)
                        else -> "Closed"
                    },
                    style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The switch's 48 dp slot: a switch, or the lock for Kundalik (it never turns on).
        Box(
            Modifier.size(width = 64.dp, height = Space.touch)
                .then(
                    if (f.locked) Modifier.semantics { contentDescription = "$name is locked" }
                    else Modifier.clickable(role = Role.Switch) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSwitch(f.path, !f.allowed) }
                        .semantics { contentDescription = "Allow $name"; stateDescription = if (f.allowed) "On" else "Off" },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (f.locked) Icon(NotebookIcons.Lock, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(20.dp)) else CompassSwitch(f.allowed)
        }
    }
}

private fun notes(n: Int) = if (n == 1) "1 note" else "$n notes"

@Composable
fun FolderNotesRouteScreen(folder: String, padding: PaddingValues, nav: NotebookNav, vm: FoldersViewModel = hiltViewModel()) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    FolderNotesScreen(folder, notes.filter { it.folder == folder || (folder == "." && it.folder.isEmpty()) }, padding, nav)
}

/** One open folder's notes, A to Z; a + writes a new one into it. */
@Composable
fun FolderNotesScreen(folder: String, notes: List<NoteRow>, padding: PaddingValues, nav: NotebookNav = NotebookNav()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        item(key = "head", contentType = "head") {
            BackHead(if (folder == ".") "Top level" else folder.substringAfterLast('/'), nav.back) {
                org.pagel.ai.ui.glass.GlassIconButton(TabIcons.Plus, "New note here", tint = Glass.Primary) { nav.open(WriteRoute(template = "in:$folder")) }
            }
        }
        item(key = "list", contentType = "list") {
            Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                if (notes.isEmpty()) Text("No notes here yet.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(Space.paneInset))
                else Column(Modifier.padding(vertical = Space.xs)) { for (n in notes) NoteRowView(n) { nav.open(NoteRoute(n.path)) } }
            }
        }
    }
}
