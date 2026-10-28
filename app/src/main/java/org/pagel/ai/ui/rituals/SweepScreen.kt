package org.pagel.ai.ui.rituals

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.api.SweepRowDto
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.SweepEntity
import org.pagel.ai.data.rituals.Kind
import org.pagel.ai.data.rituals.Rituals
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassChip
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.GlassMenu
import org.pagel.ai.ui.glass.InlineField
import org.pagel.ai.ui.glass.MenuItem
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.projects.shortName

/** Allen's trigger list, cut to Boss's life (sweep-client.tsx TRIGGERS): one prompt at a time, never homework. */
internal val TRIGGERS = listOf(
    "Humopedia: what is unfinished, promised, or waiting on you?",
    "University: deadlines, labs, exams, papers?",
    "UzCosmos internship: what did you say you would do?",
    "Other projects: ABABIL, PAGEL, games, Samarqand…",
    "People: who do you owe a reply, a call, a thank-you?",
    "Money, documents, taxes, bills?",
    "Body: sleep, gym, food, doctor?",
    "Home and things: buy, fix, return, clean?",
    "Ideas you keep thinking about but never wrote down?",
)

@Composable
fun SweepRoute(contentPadding: PaddingValues, onToday: () -> Unit, vm: SweepViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    SweepScreen(
        ui, contentPadding, onType = vm::type, onSplit = vm::split, onRetry = vm::retry, onBack = vm::back,
        rows = RowActions(vm::setKind, vm::setText, vm::setProject, vm::remove), onAdd = vm::add, onToday = onToday, onAgain = vm::again,
    )
}

/** What a row can change (kind, words, project) or be removed. */
class RowActions(
    val kind: (Int, Kind) -> Unit = { _, _ -> },
    val text: (Int, String) -> Unit = { _, _ -> },
    val project: (Int, String) -> Unit = { _, _ -> },
    val remove: (Int) -> Unit = {},
)

/**
 * Empty your head, native (Phase 5, the web's /sweep): one big box Boss dumps into (saved as he types); the AI turns
 * it into rows (a verb-first step, Do · Someday · Drop, a project); he edits or removes rows; "Add N" adds them all.
 * Offline the dump waits and is read when online: a skeleton row per line, one short line, never a spinner.
 */
@Composable
fun SweepScreen(
    ui: SweepUi,
    contentPadding: PaddingValues,
    onType: (String) -> Unit = {},
    onSplit: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onBack: () -> Unit = {},
    rows: RowActions = RowActions(),
    onAdd: () -> Unit = {},
    onToday: () -> Unit = {},
    onAgain: () -> Unit = {},
) {
    // The box's words live here while Boss types (the saved copy loads once): a save never moves the cursor.
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.loading, ui.id) { if (text == null && !ui.loading) text = ui.dump }
    var hint by rememberSaveable { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head", contentType = "head") { RitualHead("Mind sweep · 5 minutes", "Empty your head") }
        val done = ui.added
        when {
            done != null -> item(key = "done", contentType = "pane") { Column {
                RitualPane("Your head is on paper.") {
                    Text(
                        "${done.actions} next ${if (done.actions == 1) "action" else "actions"} and ${done.someday} someday ${if (done.someday == 1) "idea" else "ideas"} are in your plans. You can stop holding them.",
                        style = MaterialTheme.typography.bodyMedium, color = Glass.Muted,
                    )
                }
                Spacer(Modifier.size(12.dp))
                ButtonRow {
                    QuietButton("Again", TabIcons.Refresh, Modifier.weight(1f), description = "Empty again") { text = ""; onAgain() }
                    MintButton("Today's 3", TabIcons.Star, Modifier.weight(1f), description = "Pick today's 3", onClick = onToday)
                }
            } }
            ui.loading -> item(key = "loading", contentType = "pane") { Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(3) } }
            ui.state == SweepEntity.DRAFT -> {
                item(key = "lede", contentType = "text") {
                    Text("Everything on your mind. Any order, any language, half sentences. The AI turns it into next steps.",
                        style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(horizontal = 20.dp))
                }
                item(key = "prompt", contentType = "pane") { Prompt(TRIGGERS[hint % TRIGGERS.size]) { hint++ } }
                item(key = "box", contentType = "pane") {
                    DumpBox(text.orEmpty()) { text = it; onType(it) }
                }
                item(key = "go", contentType = "buttons") {
                    Column {
                        ButtonRow { MintButton("Turn into steps", TabIcons.Sparkles, Modifier.weight(1f), enabled = !text.isNullOrBlank()) { onSplit(text.orEmpty()) } }
                        if (!ui.online) Hint("Offline: it is saved, and the AI reads it once PAGEL is online.", modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
                    }
                }
            }
            ui.state == SweepEntity.WAITING || ui.state == SweepEntity.READING -> item(key = "reading", contentType = "pane") {
                val line = when {
                    ui.state == SweepEntity.READING -> "The AI is reading it · about 20 s"
                    !ui.online -> "Saved. The AI reads it once PAGEL is online."
                    else -> "Saved. The AI reads it next."
                }
                Column {
                    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(ui.dump, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                            SkeletonRows(Rituals.lines(ui.dump))
                        }
                    }
                    Hint(line, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).semantics { contentDescription = line })
                    Spacer(Modifier.size(12.dp))
                    ButtonRow { QuietButton("Edit text", TabIcons.ChevronLeft, Modifier.weight(1f), onClick = onBack) }
                }
            }
            ui.state == SweepEntity.FAILED -> item(key = "failed", contentType = "pane") {
                Column {
                    RitualPane("Not split") { Text(ui.message ?: "pagel.uz could not read it.", style = MaterialTheme.typography.bodyMedium, color = Glass.Risk) }
                    Spacer(Modifier.size(12.dp))
                    ButtonRow {
                        QuietButton("Edit text", TabIcons.ChevronLeft, Modifier.weight(1f), onClick = onBack)
                        MintButton("Try again", TabIcons.Refresh, Modifier.weight(1f), onClick = onRetry)
                    }
                }
            }
            else -> {
                item(key = "lede-rows", contentType = "text") {
                    Text("Each line is one next step. Fix what is wrong, then add. You don't have to do them now.",
                        style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(horizontal = 20.dp))
                }
                item(key = "rows", contentType = "pane") {
                    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ui.rows.forEachIndexed { i, r ->
                                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                                androidx.compose.runtime.key(i, r.text) { SweepRow(i, r, ui.projects, rows) }
                            }
                            if (ui.rows.isEmpty()) Hint("No rows left.", modifier = Modifier.padding(18.dp))
                        }
                    }
                }
                item(key = "add", contentType = "buttons") {
                    ButtonRow {
                        QuietButton("Edit text", TabIcons.ChevronLeft, Modifier.weight(1f), onClick = onBack)
                        MintButton("Add ${ui.keep}", TabIcons.Plus, Modifier.weight(1f), enabled = ui.keep > 0 && !ui.tooLong && ui.state == SweepEntity.READY, onClick = onAdd)
                    }
                }
            }
        }
    }
}

/** One trigger at a time, with the next one a tap away (the web's "Next prompt"). */
@Composable
private fun Prompt(text: String, onNext: () -> Unit) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(TabIcons.Sparkles, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Glass.Ink, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            GlassIconButton(TabIcons.Refresh, "Next prompt", plain = true, onClick = onNext)
        }
    }
}

/** The big box: ink on glass, at least 220 dp tall, grows with the words. */
@Composable
private fun DumpBox(text: String, onChange: (String) -> Unit) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        BasicTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(18.dp).semantics { contentDescription = "What is on your mind" },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink),
            cursorBrush = SolidColor(Glass.Primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text("fix the login bug, call mom, gym tomorrow, finish the lab report…", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted)
                    inner()
                }
            },
        )
    }
}

/** One row: its step (tap to edit), then Do · Someday · Drop, its project, and ✕. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SweepRow(i: Int, r: SweepRowDto, projects: List<ProjectEntity>, actions: RowActions) {
    val kind = Kind.of(r.kind)
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp, top = 8.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            InlineField(
                r.text, "A verb and a thing", "Step ${i + 1}",
                MaterialTheme.typography.bodyLarge, Modifier.weight(1f).padding(top = 12.dp),
                color = if (kind == Kind.DROP) Glass.Muted else Glass.Ink,
            ) { actions.text(i, it) }
            GlassIconButton(TabIcons.Close, "Remove: ${r.text}", plain = true) { actions.remove(i) }
        }
        if (Rituals.tooLong(r)) Hint("${Rituals.words(r.text)} words: pagel.uz takes ${Rituals.MAX_WORDS}. Cut it down.", Glass.Risk)
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(Kind.entries.map { it.label }, kind.ordinal, "Kind of step ${i + 1}") { actions.kind(i, Kind.entries[it]) }
            val p = projects.firstOrNull { it.name == r.project }
            Box {
                GlassChip(
                    TabIcons.Folder, shortName(r.project, p?.title ?: r.project), set = false,
                    description = "Project: ${shortName(r.project, p?.title ?: r.project)}",
                    leading = { ProjectTile(r.project, p?.color, p?.title ?: r.project, size = 20.dp) },
                ) { picking = true }
                if (picking) {
                    GlassMenu(
                        projects.map { MenuItem(TabIcons.forProject(it.name), shortName(it.name, it.title), chosen = it.name == r.project) },
                        onPick = { k -> actions.project(i, projects[k].name); picking = false },
                        onDismiss = { picking = false },
                    )
                }
            }
        }
    }
}
