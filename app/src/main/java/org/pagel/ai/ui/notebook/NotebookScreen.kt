package org.pagel.ai.ui.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.pagel.ai.data.notebook.Hit
import org.pagel.ai.data.notebook.HitKind
import org.pagel.ai.data.notebook.SearchLogic
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.LocalToasts
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.SheetLabel
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.journal.JournalRoute
import org.pagel.ai.ui.rituals.MintButton
import org.pagel.ai.ui.task.LocalTaskOpener

/** What the Notebook's first screen can ask for. Tests pass no-ops. */
data class NotebookActions(
    val search: (String) -> Unit = {},
    val open: (androidx.navigation3.runtime.NavKey) -> Unit = {},
    val openHit: (Hit) -> Unit = {},
)

/** The Notebook tab (Phase 6b): the second brain. Respond first: Room answers, pagel.uz merges in. */
@Composable
fun NotebookRouteScreen(padding: PaddingValues, reselect: Int, nav: NotebookNav, focusSearch: Boolean = false, vm: NotebookViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val toasts = LocalToasts.current
    val openTask = LocalTaskOpener.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { vm.notices.collect { toasts.show(it, good = false) } }
    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    NotebookScreen(
        ui, padding, list, focusSearch,
        NotebookActions(
            search = vm::search, open = nav.open,
            openHit = { h ->
                when (h.kind) {
                    HitKind.TASK -> openTask(h.ref)
                    HitKind.STEP -> openTask(h.ref.substringBefore('#'))
                    HitKind.NOTE -> nav.open(NoteRoute(h.ref))
                    HitKind.RESOURCE -> nav.open(ResourceRoute(h.ref))
                    // A quick note is one line: its words in a toast-sized note, never a page of its own.
                    HitKind.QUICK_NOTE -> scope.launch { toasts.show(vm.quickNote(h.ref)?.text ?: h.title, millis = 6_000) }
                }
            },
        ),
    )
}

@Composable
fun NotebookScreen(ui: NotebookUi, padding: PaddingValues, list: LazyListState = rememberLazyListState(), focusSearch: Boolean = false, act: NotebookActions = NotebookActions()) {
    LazyColumn(
        Modifier.fillMaxSize(), state = list, contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(Space.paneGap),
    ) {
        item(key = "head", contentType = "head") { PageHead("Your second brain", "Notebook") }
        item(key = "search", contentType = "search") { SearchField(ui.query, act.search, focusSearch, Modifier.padding(horizontal = Space.gutter)) }
        if (ui.query.isNotBlank()) {
            if (ui.groups.isEmpty()) {
                item(key = "none", contentType = "none") {
                    // The slot is always one line tall (Layout 1): "Looking…" → "Nothing found" never moves anything.
                    Text(
                        if (ui.asking) " " else "Nothing found for “${ui.query.trim()}”.",
                        style = MaterialTheme.typography.bodyMedium, color = Glass.Muted,
                        modifier = Modifier.padding(horizontal = Space.textEdge).heightIn(min = Space.messageSlot),
                    )
                }
            }
            for (g in ui.groups) {
                item(key = "g-${g.kind.wire}", contentType = "group") {
                    Column(Modifier.padding(horizontal = Space.gutter)) {
                        SheetLabel("${g.kind.label} · ${g.hits.size}")
                        Pane(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(vertical = Space.xs)) {
                                for (h in g.hits.take(SearchLogic.PER_GROUP)) HitRow(h) { act.openHit(h) }
                            }
                        }
                    }
                }
            }
        } else {
            item(key = "doors", contentType = "doors") {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.paneGap)) {
                    Door(NotebookIcons.Lock, "Journal", "Locked", Modifier.weight(1f)) { act.open(JournalRoute) }
                    Door(TabIcons.Folder, "Folders", if (ui.openFolders == 0) "None open" else "${ui.openFolders} open", Modifier.weight(1f)) { act.open(FoldersRoute) }
                    Door(NotebookIcons.PenLine, "Write", "Note · Book", Modifier.weight(1f)) { act.open(WriteRoute()) }
                }
            }
            if (ui.due.isNotEmpty()) item(key = "recall", contentType = "recall") { ReviewCard(ui.due.size) { act.open(RecallRoute) } }
            item(key = "recent", contentType = "recent") {
                Column(Modifier.padding(horizontal = Space.gutter)) {
                    SheetLabel("Recent notes")
                    Pane(Modifier.fillMaxWidth()) {
                        if (ui.recent.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(Space.paneInset), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                                Text("Open a folder and its notes show here.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
                            }
                        } else Column(Modifier.padding(vertical = Space.xs)) {
                            for (n in ui.recent) NoteRowView(n) { act.open(NoteRoute(n.path)) }
                        }
                    }
                }
            }
        }
        item(key = "end", contentType = "end") { Spacer(Modifier.height(Space.lg)) }
    }
}

/** A page head as every native tab: a small line, the big title. */
@Composable
internal fun PageHead(over: String, title: String) {
    Column(Modifier.padding(start = Space.textEdge, end = Space.textEdge, top = Space.headerTop)) {
        Text(over, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
    }
}

/** A pushed page's top row: back on the left, the title, an optional action on the right (its slot always kept). */
@Composable
internal fun BackHead(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = Space.sheetTitle).padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        org.pagel.ai.ui.glass.GlassIconButton(TabIcons.ChevronLeft, "Back", onClick = onBack)
        Text(
            title, style = MaterialTheme.typography.titleLarge, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = Space.xs).semantics { heading() },
        )
        Box(Modifier.size(Space.touch), contentAlignment = Alignment.Center) { action?.invoke() }
    }
}

/**
 * The glass search field: a white glass capsule with the mint rim, a search icon, the words, an ✕ to clear. The
 * keyboard's search key closes the keyboard; the results are already there.
 */
@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, focus: Boolean, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focus) { if (focus) runCatching { focusRequester.requestFocus() } }
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = Space.touch)
            .background(Color.White.copy(alpha = 0.72f), shape)
            .border(1.25.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Glass.Aurora1.copy(alpha = 0.55f), Color.White.copy(alpha = 0.95f))), shape)
            .padding(start = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TabIcons.Search, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.sm))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink),
            cursorBrush = SolidColor(Glass.Primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.weight(1f).focusRequester(focusRequester).semantics { contentDescription = "Search everything" },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("Tasks, notes, books, saves…", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, maxLines = 1)
                    inner()
                }
            },
        )
        // The ✕'s slot is kept while empty: the words never move when it comes.
        Box(Modifier.size(Space.touch), contentAlignment = Alignment.Center) {
            if (value.isNotEmpty()) org.pagel.ai.ui.glass.GlassIconButton(TabIcons.Close, "Clear search", size = 28.dp, plain = true) { onChange("") }
        }
    }
}

/** One result: its kind's icon, the title, the snippet with the matched words in bold mint ink, where it lives. */
@Composable
internal fun HitRow(h: Hit, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${h.kind.label.removeSuffix("s")}: ${h.title}" }
            .padding(horizontal = Space.paneInset, vertical = Space.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            kindIcon(h.kind), contentDescription = null, tint = Glass.Primary,
            modifier = Modifier.padding(top = Space.xs)
                .size(18.dp),
        )
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(h.title, style = MaterialTheme.typography.titleSmall, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.snippet.isNotBlank()) Text(marked(h.snippet), style = MaterialTheme.typography.bodySmall, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val where = h.project?.takeIf { it.isNotBlank() }
            if (where != null) Text(where, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun kindIcon(k: HitKind): ImageVector = when (k) {
    HitKind.TASK -> TabIcons.Done
    HitKind.STEP -> TabIcons.ListTodo
    HitKind.NOTE -> TabIcons.Notebook
    HitKind.QUICK_NOTE -> TabIcons.StickyNote
    HitKind.RESOURCE -> TabIcons.BookOpen
}

/** A snippet's ** marks as bold mint-ink words on a soft mint wash (the web's search highlight). */
internal fun marked(snippet: String): AnnotatedString = buildAnnotatedString {
    for ((text, hit) in SearchLogic.parts(snippet)) {
        if (hit) withStyle(SpanStyle(color = Glass.PrimaryInk, fontWeight = FontWeight.SemiBold, background = Glass.PrimarySoft)) { append(text) } else append(text)
    }
}

/** One note in a list: the notebook icon, its title, its folder, a small ↑ while it waits to reach pagel.uz. */
@Composable
internal fun NoteRowView(n: NoteRow, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Note: ${n.title}" }
            .padding(horizontal = Space.paneInset, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TabIcons.Notebook, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(n.title, style = MaterialTheme.typography.titleSmall, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (n.folder.isNotEmpty()) Text(n.folder, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (n.pending) Text("↑", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, modifier = Modifier.clearAndSetSemantics { contentDescription = "Waiting to send" })
    }
}

/** A door on the Notebook: a lit icon tile, one word, one quiet line. Square-ish, three in a row. */
@Composable
private fun Door(icon: ImageVector, label: String, line: String, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Pane(modifier.heightIn(min = 104.dp).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label }) {
        Column(Modifier.fillMaxWidth().padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Box(
                Modifier.size(34.dp).background(Brush.linearGradient(listOf(Glass.Aurora1, Glass.Primary)), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.height(Space.xs))
            Text(label, style = MaterialTheme.typography.titleSmall, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Review 5": shown only while cards are due; one mint button starts the session. */
@Composable
private fun ReviewCard(due: Int, onStart: () -> Unit) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Row(Modifier.fillMaxWidth().padding(Space.paneInset), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Glass.PrimarySoft, CircleShape), contentAlignment = Alignment.Center) {
                Text("🧠", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text("Review ${minOf(due, org.pagel.ai.data.notebook.RecallSession.SIZE)}", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                Text(if (due == 1) "1 card is due" else "$due cards are due", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
            }
            MintButton("Start", null, description = "Start the review", onClick = onStart)
        }
    }
}
