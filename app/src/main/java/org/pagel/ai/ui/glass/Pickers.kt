package org.pagel.ai.ui.glass

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.ui.projects.shortName
import java.time.LocalDate
import java.time.YearMonth

/**
 * Todoist's flag colours, as the web (task-panel.tsx FLAG): P1 red, P2 orange, P3 blue, P4 grey. PAGEL counts from
 * P0, so index 0 is "P1". The grey is darker than the web's #9aa2bb, and the orange darker than its #eb8909 (2.44:1), so
 * every flag keeps 3:1 on white (colour theory; checker 2026-10-27).
 */
val PriorityColors = listOf(Color(0xFFD1453B), Color(0xFFC77400), Color(0xFF246FE0), Color(0xFF7A8299))
/** What each priority means in PAGEL (the pagel MCP's own words): now, this week, this phase, someday. */
val PriorityHints = listOf("now", "this week", "this phase", "someday")

/** The priority menu's rows: a flag in its colour, filled on the chosen one. */
fun priorityItems(chosen: Int?): List<MenuItem> = (0..3).map {
    MenuItem(if (it == chosen) TabIcons.FlagFilled else TabIcons.Flag, "P${it + 1}", PriorityHints[it], PriorityColors[it], it == chosen)
}

/** A sheet's title row: the name, and what is chosen now in mono on the right. */
@Composable
fun SheetTitle(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Glass.PrimaryInk) }
    }
}

@Composable
private fun PickRow(icon: ImageVector?, label: String, hint: String? = null, chosen: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics { selected = chosen }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
        hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Glass.Muted) }
        if (chosen) { Spacer(Modifier.width(10.dp)); Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun Divider(start: Int = 50) = HorizontalDivider(Modifier.padding(start = start.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))

// ── Date ────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The web's date popover, as a sheet: quick days with their weekday, a Monday-first month (past days off, today
 * ringed, the chosen day filled mint), and a time: half-hour slots or any typed time. A day closes the sheet; a time
 * applies at once and leaves it open. [onPick] gets the new due (null = no date) and whether to close.
 */
@Composable
fun DateContent(value: String?, today: LocalDate, onPick: (String?, Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val chosenDay = value?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var month by remember(value) { mutableStateOf(YearMonth.from(chosenDay ?: today)) }
    val time = value?.drop(11).orEmpty()
    var slots by rememberSaveable { mutableStateOf(false) }
    fun pickDay(d: LocalDate) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onPick(DatePick.due(d, time), true) }

    Column(Modifier.fillMaxWidth()) {
        SheetTitle("Date", DatePick.dueText(value, today).takeIf { value != null })
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                val icons = mapOf("today" to TabIcons.Sun, "tomorrow" to TabIcons.Sunrise, "weekend" to TabIcons.Sofa, "week" to TabIcons.Calendar)
                DatePick.quickDays(today).forEachIndexed { i, q ->
                    if (i > 0) Divider()
                    PickRow(icons.getValue(q.kind), q.label, DatePick.weekday(q.day), chosen = q.day == chosenDay) { pickDay(q.day) }
                }
                if (value != null) {
                    Divider()
                    PickRow(TabIcons.CalendarX, "No date", chosen = false) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onPick(null, true) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(DatePick.monthTitle(month), style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    GlassIconButton(TabIcons.ChevronLeft, "Previous month", size = 36.dp) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); month = month.minusMonths(1) }
                    GlassIconButton(TabIcons.ChevronRight, "Next month", size = 36.dp) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); month = month.plusMonths(1) }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    for (l in listOf("M", "T", "W", "T", "F", "S", "S")) {
                        Text(l, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(4.dp))
                DatePick.cells(month).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        for (d in week) DayCell(d, inMonth = d.month == month.month, today = today, chosen = d == chosenDay, modifier = Modifier.weight(1f)) { pickDay(d) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassChip(TabIcons.Clock, time.ifEmpty { "Add time" }, set = time.isNotEmpty(), description = if (time.isEmpty()) "Add time" else "Time $time") { slots = !slots }
                    if (time.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        GlassIconButton(TabIcons.Close, "No time", size = 36.dp) { onPick(chosenDay?.toString() ?: today.toString(), false) }
                    }
                }
                if (slots) TimeSlots(time) { t -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(DatePick.due(chosenDay ?: today, t), false); slots = false }
            }
        }
    }
}

@Composable
private fun DayCell(d: LocalDate, inMonth: Boolean, today: LocalDate, chosen: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val past = d.isBefore(today)
    val isToday = d == today
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.height(44.dp).pressScale(press)
            .clickable(press, indication = null, enabled = !past, role = Role.Button, onClick = onPick)
            .semantics { contentDescription = DatePick.spoken(d); selected = chosen },
        contentAlignment = Alignment.Center,
    ) {
        val ring = when {
            chosen -> Modifier.background(Glass.Primary, CircleShape)
            isToday -> Modifier.border(1.5.dp, Glass.Primary, CircleShape)
            else -> Modifier
        }
        Box(Modifier.size(38.dp).then(ring), contentAlignment = Alignment.Center) {
            Text(
                d.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = when { chosen -> Color.White; past -> Glass.Ink.copy(alpha = 0.28f); isToday -> Glass.PrimaryInk; !inMonth -> Glass.Muted; else -> Glass.Ink },
            )
        }
    }
}

/** 48 half-hour capsules in one row (the chosen one, or 09:00, in view) and a box for any exact time. */
@Composable
private fun TimeSlots(time: String, onPick: (String) -> Unit) {
    val start = DatePick.SLOTS.indexOf(time).takeIf { it >= 0 } ?: 18
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    var typed by remember { mutableStateOf("") }
    val exact = DatePick.parseTime(typed)
    Spacer(Modifier.height(10.dp))
    LazyRow(Modifier.fadeEnd({ list.canScrollForward }), state = list, contentPadding = PaddingValues(end = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(DatePick.SLOTS, key = { it }) { t ->
            val on = t == time
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier.height(40.dp).pressScale(press)
                    .background(if (on) Glass.Primary else Color.White.copy(alpha = 0.72f), RoundedCornerShape(20.dp))
                    .border(1.dp, if (on) SolidColor(Glass.Primary) else SolidColor(Glass.Ink.copy(alpha = 0.10f)), RoundedCornerShape(20.dp))
                    .clickable(press, indication = null, role = Role.Button) { onPick(t) }
                    .semantics { selected = on }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(t, style = MaterialTheme.typography.labelSmall, color = if (on) Color.White else Glass.Ink) }
        }
    }
    Spacer(Modifier.height(10.dp))
    val bad = typed.isNotEmpty() && exact == null
    Row(
        Modifier.fillMaxWidth().height(44.dp)
            .background(Color.White.copy(alpha = 0.72f), RoundedCornerShape(22.dp))
            .border(1.dp, if (bad) SolidColor(Glass.Risk) else SolidColor(Glass.Ink.copy(alpha = 0.10f)), RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TabIcons.Clock, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = typed,
            onValueChange = { typed = it.take(8) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink),
            cursorBrush = SolidColor(Glass.Primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { exact?.let { onPick(it); typed = "" } }),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Exact time" },
            decorationBox = { inner -> Box { if (typed.isEmpty()) Text("Type a time: 9:05", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted); inner() } },
        )
        if (exact != null) Text(exact, style = MaterialTheme.typography.labelSmall, color = Glass.PrimaryInk)
    }
}

/** The date picker in its own glass sheet. */
@Composable
fun DateSheet(value: String?, today: LocalDate, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        DateContent(value, today) { due, close -> onPick(due); if (close) onDismiss() }
        Spacer(Modifier.height(SheetBottom))
    }
}

// ── Repeat ──────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The web's repeat popover: a preset in one tap, or any days of the week as toggles. A change applies at once and the
 * sheet stays (days can be added); "Never" closes it.
 */
@Composable
fun RepeatContent(value: String?, onChange: (String?, Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val v = value.orEmpty()
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("Repeat", value?.let(Repeat::label))
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Repeat.PRESETS.forEachIndexed { i, (key, label) ->
                    if (i > 0) Divider()
                    PickRow(RepeatIcons[key], label, chosen = v == key) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange(key.ifEmpty { null }, key.isEmpty()) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SheetLabel("On these days", Modifier.padding(start = 16.dp))
        val lit = Repeat.litDays(value)
        // Seven days share the width; each touch box is the full share × 48 dp, the drawn circle 42 dp.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Repeat.WEEK.forEachIndexed { i, d ->
                val on = d in lit
                val press = remember { MutableInteractionSource() }
                Box(
                    Modifier.weight(1f).height(TouchMin).pressScale(press)
                        .clickable(press, indication = null, role = Role.Checkbox) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange(Repeat.toggle(value, d), false) }
                        .semantics { contentDescription = Repeat.NAMES[d]; selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(42.dp)
                            .background(if (on) Glass.Primary else Color.White.copy(alpha = 0.72f), CircleShape)
                            .border(1.dp, if (on) SolidColor(Glass.Primary) else SolidColor(Glass.Ink.copy(alpha = 0.10f)), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("MTWTFSS"[i].toString(), style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else Glass.Ink) }
                }
            }
        }
    }
}

/** Each preset's own icon, as the date list has (checker 2026-10-27). */
private val RepeatIcons = mapOf(
    "" to TabIcons.CalendarX, "day" to TabIcons.Sun, "weekday" to TabIcons.Briefcase, "week" to TabIcons.Calendar, "month" to TabIcons.CalendarRange,
)

@Composable
fun RepeatSheet(value: String?, onChange: (String?) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        RepeatContent(value) { every, close -> onChange(every); if (close) onDismiss() }
        Spacer(Modifier.height(SheetBottom))
    }
}

// ── Project ─────────────────────────────────────────────────────────────────────────────────────────────────────

/** The web's project picker: type to find one, every project as its gradient tile, a check on the chosen one. */
@Composable
fun ProjectContent(projects: List<ProjectEntity>, value: String, onPick: (String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val list = remember(projects, q) { projects.filter { shortName(it.name, it.title).contains(q.trim(), ignoreCase = true) || it.name.contains(q.trim(), ignoreCase = true) } }
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("Project")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp)
                .background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(24.dp))
                .border(1.dp, SolidColor(Glass.Ink.copy(alpha = 0.10f)), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TabIcons.Search, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink), cursorBrush = SolidColor(Glass.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { list.firstOrNull()?.let { onPick(it.name) } }),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Find a project" },
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Type a project name", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted); inner() } },
            )
        }
        Spacer(Modifier.height(12.dp))
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (list.isEmpty()) {
                Text("No project called “${q.trim()}”", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, modifier = Modifier.padding(20.dp))
            } else LazyColumn(Modifier.heightIn(max = 420.dp).padding(vertical = 4.dp)) {
                items(list, key = { it.name }, contentType = { "project" }) { p ->
                    val press = remember { MutableInteractionSource() }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press)
                            .clickable(press, indication = null, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(p.name) }
                            .semantics { selected = p.name == value }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProjectTile(p.name, p.color, p.title, size = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(shortName(p.name, p.title), style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (p.name == value) Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ProjectSheet(projects: List<ProjectEntity>, value: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        ProjectContent(projects, value) { onPick(it); onDismiss() }
        Spacer(Modifier.height(SheetBottom))
    }
}
