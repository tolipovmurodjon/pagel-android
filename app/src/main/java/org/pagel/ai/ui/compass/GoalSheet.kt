package org.pagel.ai.ui.compass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.pagel.ai.data.compass.CompassRules
import org.pagel.ai.data.compass.Goal
import org.pagel.ai.data.compass.GoalBody
import org.pagel.ai.data.compass.KeyResult
import org.pagel.ai.data.compass.Level
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TouchMin
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.rituals.Hint
import org.pagel.ai.ui.rituals.MintButton
import org.pagel.ai.ui.rituals.QuietButton

/** Writing one goal: a full glass sheet, its own window secured (FLAG_SECURE), nothing leaves the phone in words. */
@Composable
fun GoalSheet(draft: Draft, ui: CompassUi, act: CompassActions, onDismiss: () -> Unit) {
    GlassSheet(onDismiss = onDismiss, full = true) {
        SecureWindow()
        GoalForm(draft, ui, onSave = { g, why -> act.save(g, why); onDismiss() }, onDelete = { g, why -> act.delete(g, why); onDismiss() }, onDoor = { act.door(it); onDismiss() })
    }
}

private val LEVEL_HINT = mapOf(
    Level.PURPOSE to "The one goal every other goal leads to.",
    Level.TEN_YEAR to "A direction, not a map: reword it any time.",
    Level.YEAR to "Birthday to birthday. Write it on 1 November.",
    Level.SEASON to "12 weeks. At most 3. One small weekly step each.",
    Level.SOMEDAY to "Parked, no limit. Bring it into a season when its time comes.",
)

/**
 * The sheet's body (tests draw it directly). Purpose has only its words and why; every other level adds "My part"
 * (in his hands, scored), "Outcome" (not in his hands, never scored), key results, and, for a season goal, the one
 * weekly lead action that becomes a habit. A season goal added or taken out after the season's first week asks why.
 */
@Composable
fun GoalForm(draft: Draft, ui: CompassUi, onSave: (Goal, String?) -> Unit, onDelete: (Goal, String?) -> Unit = { _, _ -> }, onDoor: (Goal) -> Unit = {}) {
    val g = draft.goal
    val level = g.level
    val isNew = g.id.isEmpty()
    var title by remember { mutableStateOf(g.body.title) }
    var why by remember { mutableStateOf(g.body.why) }
    var myPart by remember { mutableStateOf(g.body.myPart) }
    var outcome by remember { mutableStateOf(g.body.outcome) }
    var lead by remember { mutableStateOf(g.body.leadAction) }
    var parent by remember { mutableStateOf(g.parent) }
    val krs = remember { mutableStateListOf<KeyResult>().apply { addAll(g.body.keyResults) } }
    var newKr by remember { mutableStateOf("") }
    var change by remember { mutableStateOf("") }
    val needsWhy = level == Level.SEASON && CompassRules.changeNeedsWhy(ui.season, ui.today)
    val parentOk = level.parentLevel == null || parent != null
    val ready = title.isNotBlank() && parentOk && (!needsWhy || !isNew || change.isNotBlank())

    fun built() = g.copy(
        parent = parent,
        body = g.body.copy(
            title = title.trim(), why = why.trim(), myPart = myPart.trim(), outcome = outcome.trim(),
            keyResults = (krs + listOfNotNull(newKr.trim().takeIf(String::isNotEmpty)?.let { KeyResult(it) })).toList(), leadAction = lead.trim(),
        ),
    )

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Column(Modifier.heightIn(min = Space.sheetTitle).padding(start = Space.xs)) {
            Text(if (level == Level.YEAR) CompassRules.yearTitle(java.time.LocalDate.parse(g.starts ?: ui.yearStart.toString())) else level.label, style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
            Text(if (isNew) "Write it in your words" else "Your goal", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            Hint(LEVEL_HINT[level].orEmpty(), lines = 2)
        }
        if (draft.parents.isNotEmpty() && level.parentLevel != null || (level == Level.SOMEDAY && draft.parents.isNotEmpty())) {
            Column {
                Text("Serves", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.padding(start = Space.xs, bottom = Space.xs))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.chipGap)) {
                    if (level == Level.SOMEDAY) ParentChip("Nothing yet", parent == null) { parent = null }
                    for (p in draft.parents) ParentChip(CompassRules.firstWords(p.body.title), parent == p.id) { parent = p.id }
                }
            }
        }
        SealedField(title, { title = it }, label = if (level == Level.PURPOSE) "Why I live" else "The goal", hint = if (level == Level.PURPOSE) "Why you live, in one sentence" else "In your words", lines = 2, maxChars = 400)
        SealedField(why, { why = it }, label = "Why it matters", hint = "One line")
        if (level != Level.PURPOSE) {
            SealedField(myPart, { myPart = it }, label = "My part (in my hands)", hint = "What you will do", lines = 2)
            SealedField(outcome, { outcome = it }, label = "Outcome (not in my hands)", hint = "What you hope comes; it is never scored", lines = 2)
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("Key results you tick", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.padding(start = Space.xs))
                krs.forEachIndexed { i, kr ->
                    Row(Modifier.fillMaxWidth().heightIn(min = TouchMin), verticalAlignment = Alignment.CenterVertically) {
                        Text(kr.text, style = MaterialTheme.typography.bodyMedium, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = Space.xs))
                        val press = remember { MutableInteractionSource() }
                        Box(
                            Modifier.size(TouchMin).pressScale(press).clickable(press, indication = null, role = Role.Button) { krs.removeAt(i) }
                                .semantics { contentDescription = "Remove key result ${i + 1}" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(TabIcons.Close, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(18.dp)) }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    SealedField(newKr, { newKr = it }, label = "New key result", hint = "A result you can tick", modifier = Modifier.weight(1f))
                    val press = remember { MutableInteractionSource() }
                    Box(
                        Modifier.size(TouchMin).pressScale(press).clickable(press, indication = null, role = Role.Button, enabled = newKr.isNotBlank()) {
                            krs.add(KeyResult(newKr.trim())); newKr = ""
                        }.semantics { contentDescription = "Add key result" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(TabIcons.Plus, contentDescription = null, tint = if (newKr.isNotBlank()) Glass.Primary else Glass.Muted, modifier = Modifier.size(22.dp)) }
                }
            }
        }
        if (level == Level.SEASON) {
            Column {
                SealedField(lead, { lead = it }, label = "One weekly step", hint = "Small enough for a bad week")
                Hint(
                    if (g.body.habitId == null) "It becomes a weekly habit in Habits. Only these words go there, never the goal's." else "Already a weekly habit in Habits.",
                    modifier = Modifier.padding(start = Space.xs, top = Space.xs), lines = 2,
                )
            }
        }
        if (needsWhy) {
            SealedField(change, { change = it }, label = "Why change the season now?", hint = "One line, only for you")
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            MintButton(if (isNew) "Save" else "Save changes", TabIcons.Check, Modifier.fillMaxWidth(), enabled = ready) {
                onSave(built(), change.takeIf { needsWhy && it.isNotBlank() })
            }
            if (!isNew && g.body.outcome.isNotBlank() && g.body.state == GoalBody.OPEN) {
                QuietButton("Close as a different door", CompassIcons.Door, Modifier.fillMaxWidth()) { onDoor(g) }
            }
            if (!isNew) {
                QuietButton("Take it off the Compass", TabIcons.Trash, Modifier.fillMaxWidth(), enabled = !needsWhy || change.isNotBlank()) { onDelete(g, change.takeIf { needsWhy && it.isNotBlank() }) }
            }
        }
        Spacer(Modifier.height(Space.xxxl))
    }
}

@Composable
private fun ParentChip(text: String, on: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.heightIn(min = TouchMin).pressScale(press).clickable(press, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { selected = on; contentDescription = "Serves: $text" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.background(if (on) Glass.PrimarySoft else Color.White.copy(alpha = 0.72f), CircleShape)
                .border(1.dp, if (on) Glass.Primary else Glass.Ink.copy(alpha = 0.14f), CircleShape)
                .padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (on) { Icon(TabIcons.Check, contentDescription = null, tint = Glass.PrimaryInk, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(Space.xs)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) Glass.PrimaryInk else Glass.Ink, maxLines = 1)
        }
    }
}

/** The optional recovery passphrase: off by default, explained in one line, typed twice. */
@Composable
fun RecoverySheet(on: Boolean, act: CompassActions, onDismiss: () -> Unit) {
    GlassSheet(onDismiss = onDismiss) {
        SecureWindow()
        RecoveryForm(on, onSet = { act.setRecovery(it); onDismiss() }, onOff = { act.setRecovery(null); onDismiss() })
    }
}

@Composable
fun RecoveryForm(on: Boolean, onSet: (CharArray) -> Unit, onOff: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val ok = a.length >= 12 && a == b
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Text("Recovery passphrase", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.semantics { heading() })
        Hint("If this phone is lost, only this passphrase opens your goals on a new one. Nobody can reset it.", lines = 2)
        SealedField(a, { a = it }, label = "Passphrase", hint = "12 letters or more")
        SealedField(b, { b = it }, label = "Same passphrase again", hint = "Type it again")
        MintButton(if (on) "Change passphrase" else "Turn on", CompassIcons.KeyRound, Modifier.fillMaxWidth(), enabled = ok) { onSet(a.toCharArray()); a = ""; b = "" }
        if (on) QuietButton("Turn off", TabIcons.Close, Modifier.fillMaxWidth(), onClick = onOff)
    }
}

/** Lucide icons the Compass needs (ISC License, Copyright Lucide Icons and Contributors), 24×24, 2 px round strokes. */
object CompassIcons {
    val Lock = icon("lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4")
    val Compass = icon("compass", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "m16.24 7.76-1.804 5.411a2 2 0 0 1-1.265 1.265L7.76 16.24l1.804-5.411a2 2 0 0 1 1.265-1.265z")
    val KeyRound = icon("key-round", "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z", "M16 7.5a.5.5 0 1 0 1 0a.5.5 0 1 0 -1 0")
    val Door = icon("door-open", "M11 20H2", "M11 4.562v16.157a1 1 0 0 0 1.242.97L19 20V5.562a2 2 0 0 0-1.515-1.94l-4-1A2 2 0 0 0 11 4.561z", "M11 4H8a2 2 0 0 0-2 2v14", "M14 12h.01", "M22 20h-3")
    val Reset = icon("rotate-ccw", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}
