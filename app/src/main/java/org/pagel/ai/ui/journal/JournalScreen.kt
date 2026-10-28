package org.pagel.ai.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.compass.VaultState
import org.pagel.ai.data.journal.JournalEntry
import org.pagel.ai.data.journal.JournalRepository
import org.pagel.ai.ui.compass.AutoLock
import org.pagel.ai.ui.compass.CompassIcons
import org.pagel.ai.ui.compass.PrivateKeyboard
import org.pagel.ai.ui.compass.SecureWindow
import org.pagel.ai.ui.compass.UnlockAnswer
import org.pagel.ai.ui.compass.askUnlock
import org.pagel.ai.ui.compass.findActivity
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.SheetLabel
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.rituals.Hint
import org.pagel.ai.ui.rituals.MintButton
import org.pagel.ai.ui.rituals.QuietButton
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the journal can ask for. Tests pass no-ops; the route wires them to the ViewModel and the system unlock. */
data class JournalActions(
    val unlock: (startOver: Boolean) -> Unit = {},
    val save: (id: String, day: String, text: String, mood: String?) -> Unit = { _, _, _, _ -> },
    val delete: (String) -> Unit = {},
    val lock: () -> Unit = {},
    val back: () -> Unit = {},
    val openSettings: () -> Unit = {},
)

/** An entry being written: empty id = a new one for [day]. */
data class Draft(val id: String, val day: String, val text: String, val mood: String?)

/**
 * The locked journal behind the Notebook (Phase 6b): screenshots blocked on every window, the key wiped 60 s after
 * Boss leaves, the keyboard told not to learn, nothing in a notification, a log or a crash report.
 */
@Composable
fun JournalRouteScreen(padding: PaddingValues, onBack: () -> Unit, vm: JournalViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    SecureWindow()
    AutoLock(onLeave = vm::leave, onStay = vm::stay)
    fun ask(after: (javax.crypto.Cipher) -> Unit) {
        val activity = ctx.findActivity() ?: return
        vm.beginUnlock { cipher ->
            askUnlock(activity, "Open your journal", cipher) { a ->
                when (a) {
                    is UnlockAnswer.Ok -> after(a.cipher)
                    UnlockAnswer.Cancelled -> {}
                    is UnlockAnswer.Failed -> vm.said(a.why)
                }
            }
        }
    }
    JournalScreen(
        ui, padding,
        JournalActions(
            unlock = { over -> ask { c -> vm.unlocked(c, over) } },
            save = vm::save, delete = vm::delete, lock = vm::lock, back = onBack,
            openSettings = { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) } },
        ),
    )
}

@Composable
fun JournalScreen(ui: JournalUi, padding: PaddingValues, act: JournalActions = JournalActions(), startDraft: Draft? = null) {
    var draft by remember { mutableStateOf(startDraft) }
    when (ui.state) {
        VaultState.READING -> Box(Modifier.fillMaxSize())
        VaultState.NO_LOCK -> LockFrame(
            padding, "Set a screen lock first", "Your journal is locked with your fingerprint or PIN. This phone has neither yet.", ui.message, act.back,
        ) { MintButton("Open settings", TabIcons.ExternalLink, Modifier.fillMaxWidth(), onClick = act.openSettings) }
        VaultState.NEW -> LockFrame(
            padding, "A place only you can read",
            "Sealed on this phone with its own key. pagel.uz keeps a locked copy it cannot open. No AI, no search, ever.", ui.message, act.back,
        ) { MintButton("Start my journal", CompassIcons.Lock, Modifier.fillMaxWidth()) { act.unlock(false) } }
        VaultState.LOCKED -> LockFrame(padding, "Your journal is locked", "Only your fingerprint or PIN opens it.", ui.message, act.back) {
            MintButton("Unlock", CompassIcons.KeyRound, Modifier.fillMaxWidth()) { act.unlock(false) }
        }
        VaultState.LOST -> LockFrame(
            padding, "This phone cannot open your journal",
            "The key lived on your old phone. Nobody can open those entries, not even pagel.uz.", ui.message, act.back,
        ) { QuietButton("Start a new journal", CompassIcons.Lock, Modifier.fillMaxWidth()) { act.unlock(true) } }
        VaultState.OPEN -> OpenJournalView(ui, padding, act) { draft = it }
    }
    val d = draft
    if (ui.state == VaultState.OPEN && d != null) {
        WriteSheet(d, onDismiss = { draft = null }, onSave = { text, mood -> act.save(d.id, d.day, text, mood); draft = null })
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

private fun dayLabel(day: String, today: String): String = when (day) {
    today -> "Today"
    runCatching { LocalDate.parse(today).minusDays(1).toString() }.getOrNull() -> "Yesterday"
    else -> runCatching { LocalDate.parse(day).format(DAY) }.getOrDefault(day)
}

@Composable
private fun OpenJournalView(ui: JournalUi, padding: PaddingValues, act: JournalActions, onWrite: (Draft) -> Unit) {
    val byDay = ui.opened.entries.groupBy { it.body.day }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        item(key = "head", contentType = "head") {
            Row(Modifier.fillMaxWidth().heightIn(min = Space.sheetTitle).padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                org.pagel.ai.ui.glass.GlassIconButton(TabIcons.ChevronLeft, "Back", onClick = act.back)
                Text("Journal", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.weight(1f).padding(horizontal = Space.xs).semantics { heading() })
                org.pagel.ai.ui.glass.GlassIconButton(CompassIcons.Lock, "Lock now", onClick = act.lock)
            }
        }
        item(key = "write", contentType = "write") {
            MintButton("Write today", TabIcons.Plus, Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) { onWrite(Draft("", ui.today, "", null)) }
        }
        if (ui.opened.entries.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Hint("Nothing here yet. Write a line; only you will ever read it.", modifier = Modifier.padding(horizontal = Space.textEdge), lines = 2)
            }
        }
        for ((day, entries) in byDay) {
            item(key = "d-$day", contentType = "day") {
                Column(Modifier.padding(horizontal = Space.gutter)) {
                    SheetLabel(dayLabel(day, ui.today))
                    Pane(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = Space.xs)) {
                            for (e in entries) EntryRow(e) { onWrite(Draft(e.id, e.body.day, e.body.text, e.body.mood)) }
                        }
                    }
                }
            }
        }
        if (ui.opened.unreadable > 0) item(key = "bad") { Hint("${ui.opened.unreadable} older entries this key cannot open.", modifier = Modifier.padding(horizontal = Space.textEdge)) }
        item(key = "end") { Spacer(Modifier.height(Space.xxl)) }
    }
}

@Composable
private fun EntryRow(e: JournalEntry, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Journal entry" }
            .padding(horizontal = Space.paneInset, vertical = Space.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Text(e.body.mood ?: "·", style = MaterialTheme.typography.titleMedium, color = Glass.Muted) }
        Spacer(Modifier.width(Space.md))
        Text(e.body.text.ifBlank { "(empty)" }, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** A calm locked-state page: one glass circle with the lock, one title, one line, one button, a reserved message slot. */
@Composable
private fun LockFrame(padding: PaddingValues, title: String, line: String, message: String?, onBack: () -> Unit, button: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().heightIn(min = Space.sheetTitle).padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            org.pagel.ai.ui.glass.GlassIconButton(TabIcons.ChevronLeft, "Back", onClick = onBack)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.xxxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(88.dp).background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f), Glass.Aurora2)), CircleShape)
                    .border(1.25.dp, Brush.linearGradient(listOf(Color.White, Glass.Aurora1)), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(CompassIcons.Lock, contentDescription = null, tint = Glass.PrimaryInk, modifier = Modifier.size(36.dp)) }
            Spacer(Modifier.height(Space.xxl))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Space.sm))
            Text(line, style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.xxl))
            button()
            // The message slot is always laid out (RULES.md Layout 1): a failed unlock never moves the button.
            Box(Modifier.fillMaxWidth().height(Space.xxxl), contentAlignment = Alignment.Center) { if (message != null) Hint(message, color = Glass.Ink) }
        }
    }
}

/**
 * Writing, calm and full screen: the day on top, an optional mood, then only the words. The sheet's own window is
 * FLAG_SECURE; the keyboard is told not to learn (PrivateKeyboard), so nothing he types is suggested in another app.
 */
@Composable
internal fun WriteSheet(d: Draft, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var text by remember(d.id) { mutableStateOf(d.text) }
    var mood by remember(d.id) { mutableStateOf(d.mood) }
    val haptics = LocalHapticFeedback.current
    GlassSheet(onDismiss = onDismiss, full = true, secure = true) {
        SecureWindow()
        Row(Modifier.fillMaxWidth().heightIn(min = Space.sheetTitle).padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            org.pagel.ai.ui.glass.GlassIconButton(TabIcons.Close, "Close without saving", onClick = onDismiss)
            Text(
                runCatching { LocalDate.parse(d.day).format(DAY) }.getOrDefault(d.day), style = MaterialTheme.typography.titleMedium, color = Glass.Ink,
                textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f),
            )
            org.pagel.ai.ui.glass.GlassIconButton(TabIcons.Check, "Save entry", tint = Glass.Primary, on = text.isNotBlank()) { if (text.isNotBlank()) onSave(text.trim(), mood) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            for (m in JournalRepository.MOODS) {
                val on = m == mood
                val press = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(Space.touch).pressScale(press)
                        .background(if (on) Glass.PrimarySoft else Color.White.copy(alpha = 0.5f), CircleShape)
                        .border(if (on) 1.5.dp else 1.dp, if (on) Glass.Primary else Color.White.copy(alpha = 0.9f), CircleShape)
                        .clickable(press, indication = null, role = Role.RadioButton) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); mood = if (on) null else m }
                        .semantics { selected = on; contentDescription = "Mood $m" },
                    contentAlignment = Alignment.Center,
                ) { Text(m, style = MaterialTheme.typography.titleLarge) }
            }
        }
        PrivateKeyboard {
            BasicTextField(
                value = text, onValueChange = { text = it.take(MAX_CHARS) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.15f),
                cursorBrush = SolidColor(Glass.Primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(horizontal = Space.textEdge, vertical = Space.md).semantics { contentDescription = "Journal words" },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text("Write what is on your mind. Only you can read this.", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted)
                        inner()
                    }
                },
            )
        }
    }
}

/** One entry's most words: a long evening's writing fits; the sealed blob stays under pagel.uz's 400 KB. */
private const val MAX_CHARS = 100_000
