package org.pagel.ai.ui.habits

import org.pagel.ai.ui.glass.HabitIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.HabitLook
import org.pagel.ai.data.habits.Mark
import org.pagel.ai.data.habits.Part
import org.pagel.ai.data.habits.partAt
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.InlineField
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TileColors
import org.pagel.ai.ui.glass.TimeWheel
import org.pagel.ai.ui.glass.pressScale
import java.time.LocalDate
import java.time.LocalTime

/**
 * A habit is edited in the task panel and nowhere else (Boss 2026-10-31: "why did you create two? keep that one for
 * editing"). The panel stays the one editor; for a habit these pieces take the places of the task-only ones: the icon
 * tile for the ring, the identity line for the finish line, Time / Repeat / Reminder / After for Date / Repeat /
 * Priority / Pin, and the Today card for Make it tiny. Each row shows its value and opens its control only when tapped.
 */
class HabitPanelState(
    val t: TaskEntity, val ui: HabitsUi, val reminderOn: Boolean, val notify: Boolean = true, val exact: Boolean = true,
) {
    val title = titleOf(t)
    private val pref get() = ui.prefs[t.id]
    val iconKey = HabitIcons.keyFor(title, pref?.emoji)
    val colour = HabitLook.colourOf(pref?.emoji) ?: HabitIcons.defaultColour(title, pref?.emoji)
    /** "07:30", or null for a habit with no time of day. */
    val time: String? = t.due?.drop(11)?.trim()?.take(5)?.takeIf(String::isNotEmpty)
    val part: Part = time?.let { runCatching { partAt(LocalTime.parse(it)) }.getOrNull() } ?: Part.ANYTIME
    val identity = pref?.identity.orEmpty()
}

/** What the habit rows can change; each one writes through the existing repositories (Room first, then the outbox). */
class HabitPanelActions(
    val onIcon: (String) -> Unit = {}, val onColour: (String) -> Unit = {}, val onIdentity: (String?) -> Unit = {},
    val onPart: (Part) -> Unit = {}, val onTime: (String) -> Unit = {}, val onReminder: (Boolean) -> Unit = {},
    val onAnchor: (HabitLook.Anchor?) -> Unit = {},
    val onTimer: (Int) -> Unit = {}, val onResetTimer: () -> Unit = {}, val onDone: () -> Unit = {},
    val onFreeze: () -> Unit = {}, val onUnfreeze: () -> Unit = {}, val onUntick: () -> Unit = {},
    val onFixNotify: () -> Unit = {}, val onFixExact: () -> Unit = {},
)

class HabitPanel(val state: HabitPanelState, val actions: HabitPanelActions)

/** The habit's state and actions for the task panel, from the Habits view model; null until the habit is loaded. */
@Composable
fun rememberHabitPanel(id: String): HabitPanel? {
    val vm: HabitsViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val grants = rememberReminderGrants(vm)
    val t = ui.byId[id] ?: return null
    val today = runCatching { LocalDate.parse(ui.today) }.getOrElse { LocalDate.now() }
    val state = HabitPanelState(t, ui, reminderOn = id in ui.reminders, notify = grants.notify, exact = grants.exact)
    return HabitPanel(
        state,
        HabitPanelActions(
            onIcon = { k -> vm.setEmoji(id, k) }, onColour = { c -> vm.setColour(id, c) }, onIdentity = { vm.setIdentity(id, it) },
            onPart = { vm.setPart(id, it) }, onTime = { vm.setTime(id, it) },
            onReminder = { on ->
                // A reminder rings at a time: a habit with none gets 08:00, the time the old sheet showed.
                if (on && state.time == null) vm.setTime(id, "08:00")
                grants.remind(id, on)
            },
            onAnchor = { vm.setAnchor(id, it) },
            onTimer = { vm.toggleTimer(id, it) }, onResetTimer = { vm.resetTimer(id) }, onDone = { vm.tickOn(id, today) },
            onFreeze = { vm.freeze(id, onToday = true) }, onUnfreeze = { vm.unfreeze(id, onToday = true) }, onUntick = { vm.untick(id, onToday = true) },
            onFixNotify = grants.fixNotify, onFixExact = grants.fixExact,
        ),
    )
}

// ── The header: the tile and the identity line ─────────────────────────────────────────────────────────────────────

/** The habit's tile where a task has its ring; a tap opens the icon and colour sheet. */
@Composable
fun HabitTitleTile(h: HabitPanel, lookOpen0: Boolean = false) {
    var look by remember { mutableStateOf(lookOpen0) }
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.size(Space.touch).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = "Change icon and colour") { look = true }
            .semantics { contentDescription = "Icon and colour" },
        contentAlignment = Alignment.Center,
    ) { HabitTile(HabitIcons.all.getValue(h.state.iconKey), h.state.colour, 40.dp) }
    if (look) HabitLookSheet(h.state, h.actions) { look = false }
}

/** "Who you're becoming": a quiet line under the title, edited in place, no box. */
@Composable
fun HabitIdentity(h: HabitPanel, modifier: Modifier = Modifier) {
    InlineField(
        h.state.identity, "Add who you're becoming", "Who you are becoming", MaterialTheme.typography.bodyMedium, modifier,
        allowBlank = true, color = Glass.PrimaryInk, onSave = { h.actions.onIdentity(it.ifBlank { null }) },
    )
}

/** Icons and colours in one small glass sheet; a pick shows on the preview tile at once. */
@Composable
fun HabitLookSheet(s: HabitPanelState, a: HabitPanelActions, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) { HabitLookContent(s, a) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HabitLookContent(s: HabitPanelState, a: HabitPanelActions) {
        Column(Modifier.fillMaxWidth()) {
            SheetTitle("Icon and colour")
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter).padding(bottom = SheetBottom), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { HabitTile(HabitIcons.all.getValue(s.iconKey), s.colour, 56.dp) }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    for ((key, icon) in HabitIcons.all) {
                        val on = key == s.iconKey
                        val p = remember(key) { MutableInteractionSource() }
                        Box(
                            Modifier.size(Space.touch).clip(RoundedCornerShape(16.dp))
                                .background(if (on) Glass.PrimarySoft else Color.White.copy(alpha = 0.5f))
                                .border(if (on) 1.5.dp else 1.dp, if (on) Glass.Primary else Glass.Ink.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                                .pressScale(p).clickable(p, indication = null, role = Role.RadioButton) { a.onIcon(key) }
                                .semantics { contentDescription = "Icon $key"; selected = on },
                            contentAlignment = Alignment.Center,
                        ) { Icon(icon, contentDescription = null, tint = if (on) Glass.PrimaryInk else Glass.Ink.copy(alpha = 0.78f), modifier = Modifier.size(22.dp)) }
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    for (c in HabitLook.COLOURS) {
                        val (x, y) = TileColors.stops("", c)
                        val on = c == s.colour
                        val p = remember(c) { MutableInteractionSource() }
                        Box(
                            Modifier.size(Space.touch).pressScale(p).clickable(p, indication = null, role = Role.RadioButton) { a.onColour(c) }
                                .semantics { contentDescription = "Colour $c"; selected = on },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(x, y)))
                                .border(if (on) 2.dp else 1.dp, if (on) Glass.Ink else Color.White.copy(alpha = 0.7f), CircleShape))
                        }
                    }
                }
            }
        }
}

// ── The rows: Time · Repeat · Reminder · After ─────────────────────────────────────────────────────────────────────

/**
 * The habit's rows card. A row is its icon, its name and its value; the control (the time of day and the wheel, the
 * reminder wheel, the list of anchors) opens under the row only when it is tapped, one at a time. Repeat opens the
 * panel's own Repeat sheet through [onRepeat].
 */
@Composable
fun HabitRows(h: HabitPanel, every: String?, times: Int, onRepeat: () -> Unit, openRow0: String = "") {
    val s = h.state
    val a = h.actions
    var openRow by rememberSaveable { mutableStateOf(openRow0) }
    fun toggle(key: String) { openRow = if (openRow == key) "" else key }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(vertical = Space.xs)) {
            HabitRow(TabIcons.Clock, "Time", if (s.time != null) "${s.part.label} · ${s.time}" else "Anytime", set = s.time != null, open = openRow == "time", onClick = { toggle("time") }) {
                Column(Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    PartCapsule(s.part, a.onPart)
                    if (s.part != Part.ANYTIME && s.time != null) TimeWheel(s.time, a.onTime)
                }
            }
            RowLine()
            HabitRow(TabIcons.Habits, "Repeat", Repeat.summary(every) + if (times > 1) " · ${times}×" else "", set = every != null, open = false, onClick = onRepeat)
            RowLine()
            HabitRow(
                if (s.reminderOn) TabIcons.BellRing else TabIcons.Bell, "Reminder", if (s.reminderOn) s.time ?: "08:00" else "Off", set = s.reminderOn,
                open = s.reminderOn && openRow == "reminder",
                onClick = { if (s.reminderOn) toggle("reminder") else { a.onReminder(true); openRow = "reminder" } },
                trailing = { ReminderSwitch(s.reminderOn) { on -> a.onReminder(on); openRow = if (on) "reminder" else if (openRow == "reminder") "" else openRow } },
            ) {
                Column(Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.md)) {
                    when {
                        !s.notify -> FixRow(TabIcons.Bell, "Notifications off", "Turn on", Glass.Amber, a.onFixNotify)
                        !s.exact -> FixRow(TabIcons.Clock, "Exact time", "Allow", Glass.Ink, a.onFixExact)
                    }
                    TimeWheel(s.time ?: "08:00", a.onTime)
                }
            }
            RowLine()
            val now = HabitLook.anchorOf(s.ui.prefs[s.t.id]?.after)
            val after = when (now) {
                null -> "None"
                is HabitLook.Anchor.Moment -> plainMoment(now.text)
                is HabitLook.Anchor.Habit -> s.ui.byId[now.id]?.let(::titleOf) ?: "None"
            }
            HabitRow(TabIcons.CornerDownLeft, "After", after, set = now != null, open = openRow == "after", onClick = { toggle("after") }) {
                AnchorList(s, now) { a.onAnchor(it) }
            }
        }
    }
}

/** A moment is stored with its old glyph ("moment:Coffee" for new ones); the screen shows the words only. */
internal fun plainMoment(text: String): String {
    val head = text.substringBefore(' ')
    return if (text.contains(' ') && head.none(Char::isLetterOrDigit)) text.substringAfter(' ').trim() else text.trim()
}

@Composable
internal fun RowLine() {
    val hairline = 1.dp
    HorizontalDivider(Modifier.padding(start = Space.xxxl + Space.sm), thickness = hairline, color = Glass.Ink.copy(alpha = 0.07f))
}

@Composable
internal fun HabitRow(
    icon: ImageVector, label: String, value: String, set: Boolean, open: Boolean, onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null, body: (@Composable () -> Unit)? = null,
) {
    val press = remember { MutableInteractionSource() }
    val into = remember { BringIntoViewRequester() }
    // The opened control scrolls itself into view with the panel's own spring, never a jump.
    LaunchedEffect(open) { if (open && body != null) { delay(120); runCatching { into.bringIntoView() } } }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(into)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 52.dp).pressScale(press)
                    .clickable(press, indication = null, role = Role.Button, onClick = onClick)
                    .semantics(mergeDescendants = true) { contentDescription = "$label: $value" }
                    .padding(start = Space.lg, end = if (trailing == null) Space.md else Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(Space.md))
                Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(Space.md))
                Text(
                    value, style = MaterialTheme.typography.bodyMedium, color = if (set) Glass.PrimaryInk else Glass.Muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                if (trailing == null) {
                    Spacer(Modifier.width(Space.xs))
                    Icon(if (open) TabIcons.ChevronDown else TabIcons.ChevronRight, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp))
                }
            }
            trailing?.invoke()
        }
        if (body != null) AnimatedVisibility(
            open, enter = expandVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(tween(160)) + fadeOut(),
        ) { body() }
    }
}

@Composable
internal fun ReminderSwitch(on: Boolean, onToggle: (Boolean) -> Unit) {
    val press = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier.heightIn(min = Space.touch).padding(end = Space.md).pressScale(press)
            .clickable(press, indication = null, role = Role.Switch) {
                haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onToggle(!on)
            }
            .semantics(mergeDescendants = true) { toggleableState = ToggleableState(on); stateDescription = if (on) "On" else "Off"; contentDescription = "Remind me" },
        contentAlignment = Alignment.Center,
    ) { Switch(on) }
}

/**
 * Morning · Afternoon · Evening · Anytime as one capsule. Four in a line only where the words fit whole; a narrow
 * phone or a large font gets two lines of two, so no word is ever cut.
 */
@Composable
internal fun PartCapsule(part: Part, onPart: (Part) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val wide = LocalConfiguration.current.screenWidthDp >= 400 && LocalDensity.current.fontScale <= 1.1f
    Column(
        Modifier.fillMaxWidth().background(Glass.Ink.copy(alpha = 0.06f), RoundedCornerShape(20.dp)).padding(Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        for (line in listOf(Part.MORNING, Part.AFTERNOON, Part.EVENING, Part.ANYTIME).chunked(if (wide) 4 else 2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                for (p in line) {
                    val on = p == part
                    val press = remember { MutableInteractionSource() }
                    Box(
                        Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(16.dp)).pressScale(press)
                            .background(if (on) Color.White else Color.Transparent)
                            .then(if (on) Modifier.border(1.dp, Glass.Primary, RoundedCornerShape(16.dp)) else Modifier)
                            .clickable(press, indication = null, role = Role.RadioButton) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPart(p) }
                            .semantics { selected = on; contentDescription = p.label },
                        contentAlignment = Alignment.Center,
                    ) { Text(p.label, style = MaterialTheme.typography.labelLarge, color = if (on) Glass.PrimaryInk else Glass.Ink, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/** After: None, the daily moments, then real habits by part of the day (itself and loops are never offered). */
@Composable
private fun AnchorList(s: HabitPanelState, now: HabitLook.Anchor?, onAnchor: (HabitLook.Anchor?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = Space.sm)) {
        AnchorLine("None", null, now == null) { onAnchor(null) }
        for (m in HabitLook.MOMENTS) {
            AnchorLine(plainMoment(m), null, (now as? HabitLook.Anchor.Moment)?.text == m) { onAnchor(HabitLook.Anchor.Moment(m)) }
        }
        InlineField(
            "", "Your own moment", "Your own moment", MaterialTheme.typography.bodyLarge,
            Modifier.fillMaxWidth().heightIn(min = Space.touch).padding(horizontal = Space.lg, vertical = Space.md),
        ) { onAnchor(HabitLook.Anchor.Moment(it.take(40))) }
        val choices = HabitLook.anchorChoices(s.t.id, s.ui.byId.values, s.ui.prefs)
        for (p in listOf(Part.MORNING, Part.AFTERNOON, Part.EVENING, Part.ANYTIME)) {
            val inPart = choices.filter { c ->
                (c.due?.drop(11)?.trim()?.take(5)?.takeIf(String::isNotEmpty)?.let { runCatching { partAt(LocalTime.parse(it)) }.getOrNull() } ?: Part.ANYTIME) == p
            }
            if (inPart.isEmpty()) continue
            Text(p.label, style = MaterialTheme.typography.labelMedium, color = Glass.Muted, modifier = Modifier.padding(start = Space.lg, top = Space.sm))
            for (c in inPart) {
                val title = titleOf(c)
                val pref = s.ui.prefs[c.id]?.emoji
                val tile = HabitIcons.iconFor(title, pref) to (HabitLook.colourOf(pref) ?: HabitIcons.defaultColour(title, pref))
                AnchorLine(title, tile, (now as? HabitLook.Anchor.Habit)?.id == c.id) { onAnchor(HabitLook.Anchor.Habit(c.id)) }
            }
        }
    }
}

@Composable
private fun AnchorLine(text: String, tile: Pair<ImageVector, String>?, on: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).pressScale(press).clickable(press, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "After: $text"; selected = on }.padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tile != null) { HabitTile(tile.first, tile.second, 28.dp); Spacer(Modifier.width(Space.md)) }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (on) Icon(TabIcons.Check, null, tint = Glass.Primary, modifier = Modifier.size(20.dp))
    }
}

// ── Today: the clock, done, freeze, take back ──────────────────────────────────────────────────────────────────────

private data class TodayAct(val icon: ImageVector, val label: String, val go: () -> Unit)

/** Where a task has Make it tiny / I'm stuck, a habit has what it can do today. Hidden when there is nothing to do. */
@Composable
fun HabitToday(h: HabitPanel) {
    val s = h.state
    val a = h.actions
    val ui = s.ui
    val card = ui.board?.let { it.cards + it.namoz }?.firstOrNull { it.task.id == s.t.id } ?: return
    val minutes = card.minutes
    val timer = ui.timers[s.t.id]
    val open = !card.done && card.mark != Mark.FROZEN
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(vertical = Space.xs)) {
            val rows = buildList {
                if (open && minutes != null) {
                    add(TodayAct(TabIcons.Clock, when {
                        timer?.running == true -> "Pause the clock · " + progressText(card, timer, ui.now)
                        timer != null -> "Go on · " + progressText(card, timer, ui.now)
                        else -> "Start $minutes min"
                    }, { a.onTimer(minutes) }))
                    if (timer != null) add(TodayAct(TabIcons.Refresh, "Reset the clock", a.onResetTimer))
                    add(TodayAct(TabIcons.Check, "Done without the clock", a.onDone))
                } else if (open) add(TodayAct(TabIcons.Check, "Done for today", a.onDone))
                if (!card.done) {
                    if (card.mark == Mark.FROZEN) add(TodayAct(TabIcons.Undo, "Unfreeze today", a.onUnfreeze))
                    else add(TodayAct(HabitIcons.all.getValue("snowflake"), "Freeze today · ${ui.board?.freezesLeft ?: 0} left this month", a.onFreeze))
                }
                if (card.done || card.taps > 0) add(TodayAct(TabIcons.Undo, "Take back today's tick", a.onUntick))
            }
            rows.forEachIndexed { i, (icon, label, go) ->
                if (i > 0) RowLine()
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = go)
                        .semantics(mergeDescendants = true) { contentDescription = label }.padding(horizontal = Space.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(Space.md))
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
