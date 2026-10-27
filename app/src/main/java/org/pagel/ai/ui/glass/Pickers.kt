package org.pagel.ai.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
private fun PickRow(icon: ImageVector?, label: String, hint: String? = null, chosen: Boolean, tint: Color = Glass.Primary, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics { selected = chosen; contentDescription = label }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
        hint?.let { Spacer(Modifier.width(10.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.weight(1f))
        if (chosen) { Spacer(Modifier.width(10.dp)); Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun Divider(start: Int = 50) = HorizontalDivider(Modifier.padding(start = start.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))

// ── Date ────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The web's date popover, as a sheet: the quick days as one chip row, a Monday-first month (past days off, today
 * ringed, the chosen day filled mint, only the weeks the month needs), and a time row. A day closes the sheet.
 * The time is Apple's wheel (Boss 2026-10-28: the slot chips were "2005 style"): it takes the calendar's place, and
 * the row then shows the day ("Thu 29", back to the calendar) beside the time. Opening the wheel saves nothing; the
 * first turn saves, or a tap on the shown time (checker 2026-10-28 #5: "Add time" wrote 09:00 at once).
 * [onPick] gets the new due (null = no date) and whether to close.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DateContent(value: String?, today: LocalDate, onPick: (String?, Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val chosenDay = value?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var month by remember(value?.take(10)) { mutableStateOf(YearMonth.from(chosenDay ?: today)) }
    val time = value?.drop(11).orEmpty()
    var timeMode by rememberSaveable { mutableStateOf("") } // "" calendar · "wheel" · "type"
    fun pickDay(d: LocalDate) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onPick(DatePick.due(d, time), true) }
    fun setTime(t: String?) = onPick(DatePick.due(chosenDay ?: today, t), false)

    Column(Modifier.fillMaxWidth()) {
        SheetTitle("Date", DatePick.dueText(value, today).takeIf { value != null })
        // The quick days: one row of chips (checker 2026-10-28 #2: four list rows pushed the time below the fold).
        val quickScroll = rememberScrollState()
        Row(Modifier.fadeEnd({ quickScroll.canScrollForward }, 40.dp).horizontalScroll(quickScroll).padding(start = 16.dp, end = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val icons = mapOf("today" to TabIcons.Sun, "tomorrow" to TabIcons.Sunrise, "weekend" to TabIcons.Sofa, "week" to TabIcons.Calendar)
            for (q in DatePick.quickDays(today)) {
                val on = q.day == chosenDay
                GlassChip(icons.getValue(q.kind), q.label, set = on, checked = on, description = "${q.label}, ${DatePick.weekday(q.day)}",
                    modifier = Modifier.semantics { selected = on }) { pickDay(q.day) }
            }
            if (value != null) GlassChip(TabIcons.CalendarX, "No date", set = false, description = "No date") { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onPick(null, true) }
        }
        Spacer(Modifier.height(8.dp))
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp)) {
                when (timeMode) {
                    "wheel" -> TimeWheel(time.ifEmpty { DEFAULT_TIME }, onChange = { setTime(it) }, modifier = Modifier.padding(vertical = 6.dp))
                    "type" -> TypedTime { t -> haptics.performHapticFeedback(HapticFeedbackType.Confirm); setTime(t); timeMode = "wheel" }
                    else -> MonthGrid(month, today, chosenDay, onMonth = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); month = it }, onPick = ::pickDay)
                }
                HorizontalDivider(thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                // A flow: at 1.5x font the clear and keyboard buttons take a second line instead of cutting the time
                // ("15:3" in picker-time-wheel-360-font-1.5, round 3).
                FlowRow(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (timeMode != "") {
                        // The day beside the time: a tap goes back to the calendar.
                        val d = chosenDay ?: today
                        GlassChip(TabIcons.Calendar, "${DatePick.weekday(d)} ${d.dayOfMonth}", set = false, description = "Back to the calendar") {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); timeMode = ""
                        }
                    }
                    val shown = if (timeMode == "") time.ifEmpty { "Add time" } else time.ifEmpty { DEFAULT_TIME }
                    val said = when { time.isNotEmpty() -> "Time $time"; timeMode == "" -> "Add time"; else -> "Set $DEFAULT_TIME" }
                    GlassChip(TabIcons.Clock, shown, set = time.isNotEmpty(), description = said) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        when {
                            timeMode == "" -> timeMode = "wheel"
                            time.isEmpty() -> setTime(DEFAULT_TIME)
                            else -> timeMode = ""
                        }
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (time.isNotEmpty()) GlassIconButton(TabIcons.Close, "No time", size = 36.dp, plain = true) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); setTime(null); timeMode = ""
                        }
                        GlassIconButton(TabIcons.Keyboard, "Type a time", on = timeMode == "type", size = 36.dp, plain = true) { timeMode = if (timeMode == "type") "wheel" else "type" }
                    }
                }
            }
        }
    }
}

/** What the wheel shows before a time is chosen: shown, never saved by itself. */
private const val DEFAULT_TIME = "09:00"

/** The month: a title with its arrows, the weekday letters, and the days. */
@Composable
private fun MonthGrid(month: YearMonth, today: LocalDate, chosenDay: LocalDate?, onMonth: (YearMonth) -> Unit, onPick: (LocalDate) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(DatePick.monthTitle(month), style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.weight(1f).padding(start = 8.dp))
            GlassIconButton(TabIcons.ChevronLeft, "Previous month", size = 36.dp) { onMonth(month.minusMonths(1)) }
            GlassIconButton(TabIcons.ChevronRight, "Next month", size = 36.dp) { onMonth(month.plusMonths(1)) }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            for (l in listOf("M", "T", "W", "T", "F", "S", "S")) {
                Text(l, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        // Only the weeks this month needs: five most months, six for a long one (the sheet stays short, checker #2).
        DatePick.cells(month).chunked(7).filter { w -> w.any { it.month == month.month } }.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                for (d in week) DayCell(d, inMonth = d.month == month.month, today = today, chosen = d == chosenDay, modifier = Modifier.weight(1f)) { onPick(d) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DayCell(d: LocalDate, inMonth: Boolean, today: LocalDate, chosen: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val past = d.isBefore(today)
    val isToday = d == today
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.height(40.dp).pressScale(press)
            .clickable(press, indication = null, enabled = !past, role = Role.Button, onClick = onPick)
            .semantics { contentDescription = DatePick.spoken(d); selected = chosen },
        contentAlignment = Alignment.Center,
    ) {
        val ring = when {
            chosen -> Modifier.background(Glass.Primary, CircleShape)
            isToday -> Modifier.border(1.5.dp, Glass.Primary, CircleShape)
            else -> Modifier
        }
        Box(Modifier.size(36.dp).then(ring), contentAlignment = Alignment.Center) {
            Text(
                d.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = when { chosen -> Color.White; past -> Glass.Ink.copy(alpha = 0.50f); isToday -> Glass.PrimaryInk; !inMonth -> Glass.Muted; else -> Glass.Ink },
            )
        }
    }
}

/** The web's typed time ("9:05", "2:45pm"), behind the small keyboard: Done applies it. */
@Composable
private fun TypedTime(onPick: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val exact = DatePick.parseTime(typed)
    val bad = typed.isNotEmpty() && exact == null
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).height(48.dp)
            .background(Color.White.copy(alpha = 0.72f), RoundedCornerShape(24.dp))
            .border(1.dp, if (bad) SolidColor(Glass.Risk) else SolidColor(Glass.Ink.copy(alpha = 0.10f)), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = typed,
            onValueChange = { typed = it.take(8) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink),
            cursorBrush = SolidColor(Glass.Primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { exact?.let(onPick) }),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Exact time" },
            decorationBox = { inner -> Box { if (typed.isEmpty()) Text("9:05", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted); inner() } },
        )
        if (exact != null) Text(exact, style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk)
    }
}

/** The date picker in its own glass sheet. */
@Composable
fun DateSheet(value: String?, today: LocalDate, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DateContent(value, today) { due, close -> onPick(due); if (close) onDismiss() }
            Spacer(Modifier.height(SheetBottom))
        }
    }
}

// ── Repeat ──────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The web's repeat popover: a preset in one tap, then the days that fit it: weekdays for a weekly repeat (or none yet),
 * days of the month (1-31 and Last) for a monthly one, nothing for every day / weekdays. A change applies at once and
 * the sheet stays; "Never" closes it.
 */
@Composable
fun RepeatContent(value: String?, onChange: (String?, Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val v = value.orEmpty()
    val monthly = Repeat.isMonthly(value)
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("Repeat", value?.let(Repeat::label))
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Repeat.PRESETS.forEachIndexed { i, (key, label) ->
                    if (i > 0) Divider()
                    val chosen = if (key == "month") monthly else v == key
                    PickRow(RepeatIcons[key], label, chosen = chosen) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        if (!(key == "month" && monthly)) onChange(key.ifEmpty { null }, key.isEmpty())
                    }
                }
            }
        }
        when {
            monthly -> {
                Spacer(Modifier.height(12.dp))
                Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { MonthDays(value) { d -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange(Repeat.toggleMonthDay(value, d), false) } }
            }
            v != "day" && v != "weekday" -> {
                Spacer(Modifier.height(12.dp))
                // In a pane like the month's days (checker 2026-10-28 #7: the toggles floated on the page).
                Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    WeekDays(value) { d -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange(Repeat.toggle(value, d), false) }
                }
            }
        }
    }
}

/** Seven days share the width; each touch box is the full share × 48 dp, the drawn circle 40 dp, as the month's days. */
@Composable
private fun WeekDays(value: String?, onToggle: (Int) -> Unit) {
    val lit = Repeat.litDays(value)
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
        Repeat.WEEK.forEachIndexed { i, d ->
            DayDot("MTWTFSS"[i].toString(), Repeat.NAMES[d], on = d in lit, size = 40.dp, glass = false, modifier = Modifier.weight(1f)) { onToggle(d) }
        }
    }
}

/** Days of the month: 1-31 and Last in a 7-column grid, the chosen ones filled mint. */
@Composable
private fun MonthDays(value: String?, onToggle: (Int) -> Unit) {
    val lit = Repeat.monthDays(value)
    Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
        ((1..31) + Repeat.LAST).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                for (d in week) {
                    val word = if (d == Repeat.LAST) "Last" else d.toString()
                    DayDot(word, if (d == Repeat.LAST) "Last day of the month" else "Day $d", on = d in lit, size = 40.dp, glass = false, modifier = Modifier.weight(1f)) { onToggle(d) }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One toggle day: a 48 dp touch box with a [size] circle, filled mint when on. [glass] draws the off circle too. */
@Composable
private fun DayDot(text: String, spoken: String, on: Boolean, size: androidx.compose.ui.unit.Dp, glass: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.height(TouchMin).pressScale(press)
            .clickable(press, indication = null, role = Role.Checkbox, onClick = onClick)
            .semantics { contentDescription = spoken; selected = on },
        contentAlignment = Alignment.Center,
    ) {
        val off = if (glass) Modifier.background(Color.White.copy(alpha = 0.72f), CircleShape).border(1.dp, Glass.Ink.copy(alpha = 0.10f), CircleShape) else Modifier
        Box(
            Modifier.size(size).then(if (on) Modifier.background(Glass.Primary, CircleShape) else off),
            contentAlignment = Alignment.Center,
        ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else Glass.Ink, maxLines = 1, softWrap = false) }
    }
}

/** Each preset's own icon, as the date list has (checker 2026-10-27). */
private val RepeatIcons = mapOf(
    "" to TabIcons.CalendarX, "day" to TabIcons.Sun, "weekday" to TabIcons.Briefcase, "week" to TabIcons.Calendar, "month" to TabIcons.CalendarRange,
)

@Composable
fun RepeatSheet(value: String?, onChange: (String?) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            RepeatContent(value) { every, close -> onChange(every); if (close) onDismiss() }
            Spacer(Modifier.height(SheetBottom))
        }
    }
}

// ── Priority ────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The priority as a sheet of four rows, like Date and Repeat: a flag in its colour (filled on the chosen one), P1-P4,
 * what it means, and the one ✓ (checker 2026-10-28 #8: the panel's red-ring pill was a second selection mark).
 */
@Composable
fun PriorityContent(value: Int, onPick: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("Priority")
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                for (p in 0..3) {
                    if (p > 0) Divider()
                    PickRow(if (p == value) TabIcons.FlagFilled else TabIcons.Flag, "P${p + 1}", PriorityHints[p], chosen = p == value, tint = PriorityColors[p]) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(p)
                    }
                }
            }
        }
    }
}

@Composable
fun PrioritySheet(value: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        PriorityContent(value) { onPick(it); onDismiss() }
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
