package org.pagel.ai.ui.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.notebook.BacklinkDto
import org.pagel.ai.data.notebook.Markdown
import org.pagel.ai.data.notebook.NoteTemplates
import org.pagel.ai.data.notebook.OpenNote
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassChip
import org.pagel.ai.ui.glass.LocalToasts
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.SheetLabel
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.rituals.Hint
import org.pagel.ai.ui.rituals.MintButton
import org.pagel.ai.ui.rituals.Segmented

private fun openWeb(ctx: android.content.Context, url: String) {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun NoteRouteScreen(path: String, padding: PaddingValues, nav: NotebookNav, vm: NoteViewModel = hiltViewModel()) {
    LaunchedEffect(path) { vm.bind(path) }
    val note by vm.note.collectAsStateWithLifecycle()
    val paths by vm.paths.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val toasts = LocalToasts.current
    val links = remember(paths) {
        NoteLinks(
            wiki = { t -> Markdown.resolve(t, paths)?.let { nav.open(NoteRoute(it)) } ?: toasts.show("“$t” is not in an open folder.", good = false) },
            web = { openWeb(ctx, it) },
            picture = vm::picture,
        )
    }
    NoteReader(path, note, padding, links, known = { Markdown.resolve(it, paths) != null }, onBack = nav.back, onEdit = { nav.open(WriteRoute(path)) }, onOpen = { nav.open(NoteRoute(it)) })
}

/**
 * The native reader (Phase 6b): the note's blocks top to bottom, wikilinks that open their note, pictures from the
 * vault, and "Linked from" at the foot. The title row keeps its edit button's slot; the first open of a note fetched
 * from pagel.uz shows a skeleton once, never a spinner.
 */
@Composable
fun NoteReader(
    path: String,
    note: OpenNote?,
    padding: PaddingValues,
    links: NoteLinks = NoteLinks(),
    known: (String) -> Boolean = { true },
    onBack: () -> Unit = {},
    onEdit: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    editLabel: String = "Edit note",
    editIcon: androidx.compose.ui.graphics.vector.ImageVector = NotebookIcons.PenLine,
) {
    val blocks = remember(note?.body) { note?.body?.let(Markdown::parse) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "head", contentType = "head") {
            BackHead(note?.title ?: path.substringAfterLast('/').removeSuffix(".md"), onBack) {
                org.pagel.ai.ui.glass.GlassIconButton(editIcon, editLabel, tint = Glass.Primary, onClick = onEdit)
            }
        }
        item(key = "where", contentType = "where") {
            val folder = path.substringBeforeLast('/', "")
            Hint(
                listOfNotNull(folder.ifEmpty { null }, if (note?.pending == true) "waiting to send" else null, note?.refused?.let { "not saved on pagel.uz: $it" }).joinToString(" · ").ifEmpty { " " },
                color = if (note?.refused != null) Glass.Risk else Glass.Muted,
                modifier = Modifier.padding(horizontal = Space.textEdge).padding(bottom = Space.md),
            )
        }
        if (blocks == null) {
            item(key = "loading", contentType = "loading") { SkeletonRows(5, Modifier.padding(horizontal = Space.textEdge)) }
        } else {
            // A note's blocks never change order while it is open: their place is their key.
            itemsIndexed(blocks, key = { i, _ -> "b$i" }, contentType = { _, b -> b::class.simpleName }) { _, b ->
                Box(Modifier.padding(horizontal = Space.textEdge, vertical = Space.xs)) { MarkdownBlock(b, links, known) }
            }
            if (blocks.isEmpty()) item(key = "empty") { Hint("This note is empty.", modifier = Modifier.padding(horizontal = Space.textEdge)) }
        }
        val backs = note?.backlinks.orEmpty()
        if (backs.isNotEmpty()) item(key = "backlinks", contentType = "backlinks") { Backlinks(backs, onOpen) }
        item(key = "end") { Spacer(Modifier.height(Space.xxl)) }
    }
}

@Composable
private fun Backlinks(backs: List<BacklinkDto>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.xxl)) {
        SheetLabel("Linked from · ${backs.size}")
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = Space.xs)) {
                for (b in backs) NoteRowView(NoteRow(b.path, b.title.ifBlank { b.path.substringAfterLast('/').removeSuffix(".md") }, b.path.substringBeforeLast('/', ""), false)) { onOpen(b.path) }
            }
        }
    }
}

@Composable
fun ResourceRouteScreen(id: String, padding: PaddingValues, nav: NotebookNav, vm: NoteViewModel = hiltViewModel()) {
    LaunchedEffect(id) { vm.bindResource(id) }
    val r by vm.resource.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val links = remember { NoteLinks(web = { openWeb(ctx, it) }) }
    val res = r
    NoteReader(
        path = res?.title ?: "Resource",
        note = res?.let { OpenNote(it.id, it.title, it.body.ifBlank { it.url ?: "" }, emptyList(), emptyList(), pending = false, refused = null) },
        padding = padding, links = links, onBack = nav.back,
        onEdit = { res?.url?.let { openWeb(ctx, it) } },
        editLabel = "Open link", editIcon = NotebookIcons.Link,
    )
}

/** What the writer starts from: an existing note's words, or a template for a new one. */
private enum class Kind(val label: String) { NOTE("Note"), DAILY("Daily"), BOOK("Book") }

@Composable
fun WriteRouteScreen(route: WriteRoute, padding: PaddingValues, nav: NotebookNav, vm: NoteViewModel = hiltViewModel()) {
    val editing = route.path.isNotEmpty()
    LaunchedEffect(route.path) { if (editing) vm.bind(route.path) }
    val note by vm.note.collectAsStateWithLifecycle()
    val folders by vm.writable.collectAsStateWithLifecycle()
    val toasts = LocalToasts.current
    if (editing && note?.body == null) {
        Column(Modifier.fillMaxSize().padding(padding)) { BackHead("Edit", nav.back); SkeletonRows(4, Modifier.padding(Space.textEdge)) }
        return
    }
    NoteWriter(
        editing = if (editing) note else null, folders = folders, padding = padding,
        startKind = when (route.template) { "daily" -> 1; "book" -> 2; else -> 0 },
        startFolder = route.template.removePrefix("in:").takeIf { route.template.startsWith("in:") },
        today = vm.today().toString(),
        newPath = vm::newPath,
        onBack = nav.back,
        onSave = { p, body ->
            vm.save(p, body) { saved ->
                toasts.show("Saved", good = true)
                nav.back()
                if (!editing) nav.open(NoteRoute(saved))
            }
        },
    )
}

/**
 * The writer: a new note (Note · Daily · Book) into an open folder, or an existing note's words. Saved on the phone at
 * once and sent from the outbox (offline too). Kundalik is never offered; only open folders are.
 */
@Composable
fun NoteWriter(
    editing: OpenNote?,
    folders: List<String>,
    padding: PaddingValues,
    startKind: Int = 0,
    startFolder: String? = null,
    today: String,
    newPath: (folder: String, title: String) -> String = { f, t -> NoteTemplates.pathIn(f, t) },
    onBack: () -> Unit = {},
    onSave: (path: String, body: String) -> Unit = { _, _ -> },
) {
    var kind by rememberSaveable { mutableStateOf(startKind) }
    var title by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf(editing?.body ?: "") }
    val choices = folders.filter { !NoteTemplates.isLocked(it) }
    var folder by rememberSaveable(choices) {
        mutableStateOf(
            startFolder?.takeIf { it in choices }
                ?: choices.firstOrNull { (kind == 1 && it.contains("daily", true)) || (kind == 2 && it.contains("book", true)) } ?: choices.firstOrNull() ?: "",
        )
    }
    val newTitle = when (kind) { 1 -> today; else -> title.trim() }
    val canSave = if (editing != null) body.isNotBlank() else folder.isNotEmpty() && newTitle.isNotEmpty()
    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
        BackHead(if (editing != null) "Edit" else "New note", onBack) {
            org.pagel.ai.ui.glass.GlassIconButton(TabIcons.Check, "Save note", tint = Glass.Primary, on = canSave) {
                if (!canSave) return@GlassIconButton
                if (editing != null) onSave(editing.path, body) else {
                    val text = when (kind) {
                        1 -> NoteTemplates.daily(java.time.LocalDate.parse(today)) + body
                        2 -> NoteTemplates.book(title, author) + body
                        else -> NoteTemplates.blank(title) + body
                    }
                    onSave(newPath(folder, newTitle), text)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            if (editing == null) {
                Segmented(Kind.entries.map { it.label }, kind, "Kind of note", fill = true) { kind = it }
                if (choices.isEmpty()) {
                    Hint("Open a folder first (Notebook → Folders): a note lives in a folder you allowed.", color = Glass.Ink, lines = 2)
                } else {
                    SheetLabel("Folder")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.chipGap)) {
                        for (f in choices) GlassChip(TabIcons.Folder, if (f == ".") "Top level" else f.substringAfterLast('/'), set = f == folder, checked = f == folder, description = "Folder $f") { folder = f }
                    }
                }
                if (kind != 1) Field(title, { title = it }, if (kind == 2) "Book title" else "Title", singleLine = true)
                if (kind == 2) Field(author, { author = it }, "Author", singleLine = true)
                if (kind == 2) Hint("The note gets: why I read it, highlights, my words.")
            }
            Field(body, { body = it }, if (editing != null) "Note" else if (kind == 2) "First thoughts (optional)" else "Write", singleLine = false, modifier = Modifier.heightIn(min = 280.dp))
            Spacer(Modifier.height(Space.lg))
        }
        if (editing == null) MintButton("Save", TabIcons.Check, Modifier.fillMaxWidth().padding(Space.gutter), enabled = canSave, description = "Save the new note") {
            val text = when (kind) {
                1 -> NoteTemplates.daily(java.time.LocalDate.parse(today)) + body
                2 -> NoteTemplates.book(title, author) + body
                else -> NoteTemplates.blank(title) + body
            }
            onSave(newPath(folder, newTitle), text)
        }
    }
}

/** A writer field: white glass, ink words, the mint rim; [label] is its name to TalkBack and to the walkthrough. */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink),
        cursorBrush = SolidColor(Glass.Primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth().heightIn(min = Space.touch)
            .background(Color.White.copy(alpha = 0.84f), RoundedCornerShape(16.dp))
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Glass.Aurora1.copy(alpha = 0.55f))), RoundedCornerShape(16.dp))
            .padding(horizontal = Space.lg, vertical = Space.md)
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Muted)
                inner()
            }
        },
    )
}

/** Blocks for a screenshot or a preview: a reader with [md] as its body. */
internal fun sampleNote(path: String, md: String, backlinks: List<BacklinkDto> = emptyList()) =
    OpenNote(path, Markdown.titleOf(path, md), md, emptyList(), backlinks, pending = false, refused = null)

