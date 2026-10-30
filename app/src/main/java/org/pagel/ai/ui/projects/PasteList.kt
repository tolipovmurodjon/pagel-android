package org.pagel.ai.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.ui.add.MAX_WORDS
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons

/** Most tasks one paste makes: a whole course is a few dozen lessons, not a book. */
const val MAX_PASTED = 60

private val Bullet = Regex("""^([-*•–]|\d{1,3}[.)])\s+""")

/**
 * One pasted line as a task: [text] is what pagel.uz takes as a task line (20 words at most); a longer line keeps every
 * word in [note] (the task's note, the full line the AI reads), never cut silently.
 */
data class PastedLine(val text: String, val note: String? = null)

/**
 * One task per line, in the order given: a leading bullet or "12." is dropped, blank lines vanish, and at most
 * [MAX_PASTED] lines are kept. A line over the 20 words pagel.uz takes is shown by its first 20 and kept whole as its note.
 */
fun pastedLines(raw: String): List<PastedLine> =
    raw.lines()
        .map { it.trim().replace(Bullet, "").replace(Regex("""\s+"""), " ").trim() }
        .filter(String::isNotEmpty)
        .map { l -> l.split(' ').let { w -> if (w.size > MAX_WORDS) PastedLine(w.take(MAX_WORDS).joinToString(" "), l) else PastedLine(l) } }
        .take(MAX_PASTED)

/**
 * Adds [lines] to [project] one by one, in order, so pagel.uz files them in that order (the outbox sends in the order
 * made); a long line gets its first 20 words as the title and the whole line as its note. Gives the ids, for the one Undo.
 */
suspend fun addPasted(today: TodayRepository, tasks: TaskRepository, project: String, lines: List<PastedLine>): List<String> =
    lines.map { l ->
        today.add(l.text, project, 2, null, null, onToday = false).also { id ->
            if (l.note != null) { tasks.title(id, l.text); tasks.text(id, l.note) }
        }
    }

/** The paste's one Undo: every task it made is taken away again, on the phone and on pagel.uz. */
suspend fun undoPasted(tasks: TaskRepository, ids: List<String>) { ids.forEach { tasks.delete(it) } }

/** "Paste a list": one lesson per line, many tasks in order, one Undo. Enter makes a new line; the button adds them all. */
@Composable
fun PasteListSheet(project: String, onAdd: (List<PastedLine>) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) { PasteListContent(project, onAdd = { onAdd(it); onDismiss() }) ; Spacer(Modifier.height(SheetBottom)) }
}

@Composable
fun PasteListContent(project: String, onAdd: (List<PastedLine>) -> Unit, autoFocus: Boolean = true, initial: String = "") {
    var raw by rememberSaveable { mutableStateOf(initial) }
    val lines = remember(raw) { pastedLines(raw) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    val style = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink)
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("Paste a list", trailing = if (lines.isEmpty()) null else if (lines.size == 1) "1 task" else "${lines.size} tasks")
        Box(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter).heightIn(min = Space.row * 3, max = Space.row * 5)
                .background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(Space.xl)).padding(Space.lg),
        ) {
            BasicTextField(
                value = raw, onValueChange = { raw = it },
                textStyle = style, cursorBrush = SolidColor(Glass.Primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Lessons, one per line" },
                decorationBox = { inner -> Box { if (raw.isEmpty()) Text("One task per line", style = style, color = Glass.Muted); inner() } },
            )
        }
        Spacer(Modifier.height(Space.md))
        Box(Modifier.padding(horizontal = Space.gutter)) {
            val label = when (lines.size) { 0 -> "Add tasks"; 1 -> "Add 1 task"; else -> "Add ${lines.size} tasks" }
            if (lines.isEmpty()) Text("Paste or type your lessons. Each line becomes a task in $project, in this order.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
            else Column {
                BigButton(label, TabIcons.ListPlus) { onAdd(lines) }
                val long = lines.count { it.note != null }
                // Said before the tap, so a long line is never a surprise: its words are all kept, in the note.
                if (long > 0) Text(
                    if (long == 1) "1 line is over 20 words: its task shows the first 20, the note keeps it all."
                    else "$long lines are over 20 words: their tasks show the first 20, the notes keep them all.",
                    style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(top = Space.sm),
                )
            }
        }
    }
}
