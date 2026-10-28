package org.pagel.ai.ui.upcoming

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.upcoming.Block
import org.pagel.ai.data.upcoming.CalView
import org.pagel.ai.data.upcoming.droppedDue
import org.pagel.ai.data.upcoming.hhmm
import org.pagel.ai.data.upcoming.layoutDay
import org.pagel.ai.data.upcoming.monthWeeks
import org.pagel.ai.data.upcoming.snap15
import org.pagel.ai.data.upcoming.tasksOn
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassStage
import org.pagel.ai.ui.glass.PaneRim
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TileColors
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.today.headline
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Round C (Boss: "top is trash, not liquid glass, no reflection; slide left for other dates; week view like Google's,
 * many view formats, colouring, clean; tap other dates; a better timeline"): the calendar's parts. The pinned top is
 * real liquid glass over the screen's own layer (the list scrolls under it, bent and blurred), the strip pages by week,
 * the views are Google Calendar's (List · Day · 3 days · Week · Month), and colour is the project's own tile colour.
 */

/** Strip pages: half a year back, a year ahead; page [STRIP_BACK] is today and the next 6 days. */
const val STRIP_BACK = 26
const val STRIP_PAGES = STRIP_BACK + 53

private val LETTER = DateTimeFormatter.ofPattern("EEE", Locale.UK)
private val SPOKEN = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)
private val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")

/** A project's own colour (its gradient tile's first stop, rules/gradient-project-icons). */
fun projectColor(ui: UpcomingUi, project: String): Color = TileColors.stops(project, ui.projects[project]?.color).first

private fun titleOf(t: TaskEntity) = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)

/**
 * The pinned top, liquid glass as the tab bar: vibrancy, an 8 dp blur and a lens over the screen's own layer, a rim light
 * and a shadow; on a cold start's first frames a plain white pane (GlassStage).
 */
@Composable
fun GlassTop(backdrop: Backdrop, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val surface = if (!GlassStage.on) Modifier.background(Color.White.copy(alpha = 0.80f), shape) else Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { RoundedRectangle(24.dp) },
        effects = { vibrancy(); blur(8f.dp.toPx()); lens(16f.dp.toPx(), 24f.dp.toPx()) },
        highlight = { Highlight.Default },
        shadow = { Shadow(alpha = 0.5f) },
        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.34f)) },
    )
    Box(modifier.fillMaxWidth().then(surface).border(1.25.dp, PaneRim, shape)) { content() }
}

/** The view switch: one row of five, the chosen one lifted white (Apple's segmented control). */
@Composable
fun ViewSwitch(view: CalView, modifier: Modifier = Modifier, onPick: (CalView) -> Unit) {
    val haptics = LocalHapticFeedback.current
    // Full width, five equal parts, so it never runs off a 360 dp screen at 1.5x (the rituals' switch hugs its words).
    Row(modifier.fillMaxWidth().heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.fillMaxWidth().background(Glass.Ink.copy(alpha = 0.06f), RoundedCornerShape(12.dp)).padding(Space.xs)) {
            for (v in CalView.entries) {
                val on = v == view
                val press = remember { MutableInteractionSource() }
                val lift by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 1f, stiffness = 600f), label = "seg")
                Box(
                    Modifier.weight(1f).heightIn(min = 36.dp).pressScale(press)
                        .graphicsLayer { shadowElevation = lift * 3.dp.toPx(); shape = RoundedCornerShape(10.dp); clip = false }
                        .background(Color.White.copy(alpha = lift), RoundedCornerShape(10.dp))
                        .clickable(press, indication = null, role = Role.Tab) { if (!on) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(v) } }
                        .semantics { selected = on; contentDescription = "View: ${v.label}" },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.text.BasicText(
                        v.label, maxLines = 1, softWrap = false,
                        style = MaterialTheme.typography.labelLarge.copy(color = if (on) Glass.Ink else Glass.Muted),
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                    )
                }
            }
        }
    }
}

/**
 * The week strip, paged by week with a swipe (snap, a tick per week): 7 days from today + 7 × page. Today is bright mint,
 * the focused day ringed, a hovered day (a row dragged over it) ink; under each day up to 3 dots in the projects' colours.
 */
@Composable
fun WeekPager(
    pager: PagerState, ui: UpcomingUi, focus: LocalDate, hovered: Int?, modifier: Modifier = Modifier, onTap: (LocalDate) -> Unit,
) {
    HorizontalPager(pager, modifier.fillMaxWidth(), key = { it }) { page ->
        val start = org.pagel.ai.data.upcoming.pageStart(ui.today, page - STRIP_BACK)
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.xs)) {
            for (i in 0 until 7) {
                val d = start.plusDays(i.toLong())
                key(d) { DayCell(d, ui, today = d == ui.today, focused = d == focus, over = hovered == i && page == pager.currentPage, modifier = Modifier.weight(1f)) { onTap(d) } }
            }
        }
    }
}

@Composable
private fun DayCell(d: LocalDate, ui: UpcomingUi, today: Boolean, focused: Boolean, over: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val tasks = remember(ui.tasks, d) { tasksOn(ui.tasks, d) }
    val grow by animateFloatAsState(if (over) 1.16f else 1f, spring(dampingRatio = 1f, stiffness = 700f), label = "cell")
    val press = remember { MutableInteractionSource() }
    Column(
        modifier.heightIn(min = Space.touch).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = "Show") { onTap() }
            .semantics(mergeDescendants = true) {
                contentDescription = d.format(SPOKEN) + when (tasks.size) { 0 -> ""; 1 -> ", 1 task"; else -> ", ${tasks.size} tasks" }
                selected = focused
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(d.format(LETTER), style = MaterialTheme.typography.labelSmall, color = if (today) Glass.PrimaryInk else Glass.Muted, maxLines = 1)
        Spacer(Modifier.height(Space.xs))
        Box(
            Modifier.size(34.dp).graphicsLayer { scaleX = grow; scaleY = grow }.then(
                when {
                    over -> Modifier.background(Glass.Ink, CircleShape)
                    today -> Modifier.background(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), CircleShape)
                    focused -> Modifier.border(1.25.dp, Glass.Ink, CircleShape)
                    else -> Modifier
                },
            ),
            contentAlignment = Alignment.Center,
        ) { Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, maxLines = 1, color = if (over) Color.White else Glass.Ink) }
        Spacer(Modifier.height(Space.xs))
        // slot: always a 5 dp row, dots or not (the strip never changes height).
        Row(Modifier.height(DOT), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (p in tasks.map { it.project }.distinct().take(3)) Box(Modifier.size(DOT).background(projectColor(ui, p), CircleShape))
        }
    }
}

/** "Today": back to this week and today, shown only away from it (its slot is kept, so the strip never moves). */
@Composable
fun TodayPill(shown: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.heightIn(min = Space.touch).graphicsLayer { alpha = if (shown) 1f else 0f }.pressScale(press)
            .clickable(press, indication = null, enabled = shown, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (shown) "Back to today" else "" },
        contentAlignment = Alignment.Center,
    ) {
        Text("Today", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk,
            modifier = Modifier.background(Glass.PrimarySoft, CircleShape).padding(horizontal = Space.md, vertical = 6.dp))
    }
}

/** The month's title and its arrows, in the glass top while the Month view shows. */
@Composable
fun MonthBar(month: YearMonth, onMonth: (YearMonth) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(month.atDay(1).format(MONTH), style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.weight(1f))
        org.pagel.ai.ui.glass.GlassIconButton(TabIcons.ChevronLeft, "Previous month", size = 36.dp) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onMonth(month.minusMonths(1)) }
        org.pagel.ai.ui.glass.GlassIconButton(TabIcons.ChevronRight, "Next month", size = 36.dp) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onMonth(month.plusMonths(1)) }
    }
}

// ── The timeline (Day · 3 days · Week) ─────────────────────────────────────────────────────────────────────────────

private val GUTTER = 44.dp
/** A strip dot: the slot is kept with or without dots. */
private val DOT = 5.dp

/**
 * Google's timeline: an "All day" row on top (untimed tasks; daily habits folded to one count), then 24 hours, opened at
 * 05:00, timed tasks as blocks in their project's colour (a range is a tall block), a red line at now. Long-press a
 * block and drag it to another time or day: 15-minute steps with a tick each, a Confirm on the drop; Room first, the
 * outbox after, Undo in the toast. A habit is not draggable (its due is its series).
 */
@Composable
fun Timeline(
    days: List<LocalDate>, ui: UpcomingUi, scroll: ScrollState, top: Dp, bottom: Dp,
    onOpen: (String) -> Unit, onMove: (String, String) -> Unit,
) {
    val hourH = if (days.size == 1) 60.dp else 52.dp
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val layouts = remember(ui.tasks, days) { days.map { layoutDay(tasksOn(ui.tasks, it)) } }
    var colW by remember { mutableFloatStateOf(0f) }
    var hoursAt by remember { mutableIntStateOf(-1) }
    // Opened at 05:00 under the glass top, once per view (Google opens at the working morning).
    LaunchedEffect(days.size, hoursAt) {
        if (hoursAt >= 0) scroll.scrollTo((hoursAt + with(density) { (hourH * 5).roundToPx() - top.roundToPx() }).coerceAtLeast(0))
    }
    val nowMin by produceState(LocalTime.now(TASHKENT).let { it.hour * 60 + it.minute }) {
        while (true) { delay(60_000); value = LocalTime.now(TASHKENT).let { it.hour * 60 + it.minute } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Spacer(Modifier.height(top))
        // All day: untimed tasks per day, the daily habits as one "N habits" chip.
        Row(Modifier.fillMaxWidth().padding(end = Space.sm)) {
            Text("All day", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 2,
                modifier = Modifier.width(GUTTER).padding(start = Space.xs, top = Space.xs))
            for ((i, l) in layouts.withIndex()) Column(Modifier.weight(1f).padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (t in l.allDay.take(if (days.size > 3) 2 else 4)) AllDayChip(titleOf(t), projectColor(ui, t.project)) { onOpen(t.id) }
                val more = (l.allDay.size - if (days.size > 3) 2 else 4).coerceAtLeast(0)
                if (more > 0) Text("+$more", style = Glass.NumberText, color = Glass.Muted, modifier = Modifier.padding(start = Space.xs))
                if (l.dailyHabits > 0) Text(if (days.size > 3) "${l.dailyHabits} ⟳" else "${l.dailyHabits} daily habits", style = MaterialTheme.typography.labelSmall,
                    color = Glass.Muted, maxLines = 1, modifier = Modifier.padding(start = Space.xs).semantics { contentDescription = "${l.dailyHabits} daily habits on ${days[i].format(SPOKEN)}" })
                if (l.allDay.isEmpty() && l.dailyHabits == 0) Spacer(Modifier.height(Space.lg))
            }
        }
        Spacer(Modifier.height(Space.sm))
        Box(Modifier.fillMaxWidth().height(hourH * 24).onPlaced { hoursAt = it.positionInParent().y.toInt() }) {
            // The hour lines and labels.
            val line = Glass.Ink.copy(alpha = 0.08f)
            Canvas(Modifier.matchParentSize()) {
                val h = hourH.toPx()
                for (k in 0..24) drawLine(line, Offset(GUTTER.toPx(), k * h), Offset(size.width, k * h), 1.dp.toPx())
            }
            for (k in 1..23) Text(hhmm(k * 60), style = Glass.NumberText, color = Glass.Muted,
                modifier = Modifier.offset(x = Space.xs, y = hourH * k - 8.dp))
            Row(Modifier.fillMaxSize().padding(start = GUTTER, end = Space.sm)) {
                for ((i, d) in days.withIndex()) Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { colW = it.width.toFloat() }) {
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Glass.Ink.copy(alpha = 0.06f)))
                    for (b in layouts[i].blocks) key(b.task.id) {
                        BlockView(b, ui, hourH, colW, onOpen = { onOpen(b.task.id) }, onDrop = { dx, dy ->
                            val cols = if (colW > 0f) Math.round(dx / colW) else 0
                            val target = (i + cols).coerceIn(0, days.size - 1)
                            val minutes = snap15(b.start + dy / with(density) { hourH.toPx() } * 60f)
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onMove(b.task.id, droppedDue(b.task.due, days[target], minutes))
                        })
                    }
                    if (d == ui.today) NowLine(nowMin, hourH)
                }
            }
        }
        Spacer(Modifier.height(bottom))
    }
}

@Composable
private fun AllDayChip(title: String, color: Color, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Text(
        title, style = MaterialTheme.typography.labelSmall, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = Space.xs, vertical = 2.dp),
    )
}

/**
 * One block: a light tint of its project's colour with a solid edge bar and ink words (≥ 4.5:1 on any tile colour).
 * Long-press + drag moves it: it lifts (1.03, shadow) and follows the finger; a tick per 15-minute step or column.
 */
@Composable
private fun BlockView(b: Block, ui: UpcomingUi, hourH: Dp, colW: Float, onOpen: () -> Unit, onDrop: (Float, Float) -> Unit) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val color = projectColor(ui, b.task.project)
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    val top = hourH * (b.start / 60f)
    val height = (hourH * ((b.end - b.start).coerceAtLeast(20) / 60f)).coerceAtLeast(22.dp)
    val ranged = b.task.due?.drop(11)?.contains('-') == true
    val time = if (ranged) "${hhmm(b.start)}–${hhmm(b.end)}" else hhmm(b.start)
    val laneW = with(density) { (colW / b.lanes).toDp() }
    val shape = RoundedCornerShape(8.dp)
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.offset { IntOffset((colW / b.lanes * b.lane).toInt(), top.roundToPx()) }.width(laneW).height(height).padding(end = 2.dp, bottom = 2.dp)
            .graphicsLayer {
                translationX = dx; translationY = dy
                val k = if (held) 1.03f else 1f; scaleX = k; scaleY = k
                shadowElevation = if (held) 10.dp.toPx() else 0f; this.shape = shape; clip = false
            }
            .background(Color.White, shape)
            .background(color.copy(alpha = 0.20f), shape)
            .border(1.dp, color.copy(alpha = 0.6f), shape)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = "Open", onClick = onOpen)
            .then(if (b.habit) Modifier else Modifier.pointerInput(b.task.id, b.start, colW) {
                val quarter = hourH.toPx() / 4f
                detectDragGesturesAfterLongPress(
                    onDragStart = { held = true; dx = 0f; dy = 0f; step = 0; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDragEnd = { held = false; onDrop(dx, dy); dx = 0f; dy = 0f },
                    onDragCancel = { held = false; dx = 0f; dy = 0f },
                ) { change, d ->
                    change.consume(); dx += d.x; dy += d.y
                    val s = Math.round(dy / quarter) + 1000 * (if (colW > 0f) Math.round(dx / colW) else 0)
                    if (s != step) { step = s; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                }
            })
            .semantics(mergeDescendants = true) { contentDescription = "${titleOf(b.task)}, $time" },
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(color, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)))
        Column(Modifier.weight(1f).padding(start = Space.xs, end = 2.dp, top = 2.dp)) {
            Text(titleOf(b.task), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Glass.Ink,
                maxLines = if (height > 40.dp) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (height > 40.dp && laneW > 72.dp) Text(time, style = Glass.NumberText, color = Glass.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun NowLine(nowMin: Int, hourH: Dp) {
    val y = hourH * (nowMin / 60f)
    Box(Modifier.fillMaxWidth().offset(y = y - 1.dp).height(2.dp).background(Glass.Risk).semantics { contentDescription = "Now" })
    Box(Modifier.offset(x = (-4).dp, y = y - 5.dp).size(10.dp).background(Glass.Risk, CircleShape))
}

// ── Month ──────────────────────────────────────────────────────────────────────────────────────────────────────────

/** Google's month: six weeks, each day with up to 3 bars in its tasks' project colours and "+N"; tap = that day's list. */
@Composable
fun MonthGrid(month: YearMonth, ui: UpcomingUi, focus: LocalDate, onPick: (LocalDate) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.sm)) {
        Row(Modifier.fillMaxWidth()) {
            for (l in listOf("M", "T", "W", "T", "F", "S", "S")) Text(l, style = MaterialTheme.typography.labelSmall, color = Glass.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
        }
        for (week in monthWeeks(month)) Row(Modifier.fillMaxWidth()) {
            for (d in week) key(d) {
                val tasks = remember(ui.tasks, d) { tasksOn(ui.tasks, d).filter { it.every != "day" } }
                val inMonth = d.month == month.month
                val press = remember { MutableInteractionSource() }
                Column(
                    Modifier.weight(1f).height(60.dp).pressScale(press)
                        .clickable(press, indication = null, role = Role.Button) { onPick(d) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = d.format(SPOKEN) + when (tasks.size) { 0 -> ""; 1 -> ", 1 task"; else -> ", ${tasks.size} tasks" }
                            selected = d == focus
                        }
                        .padding(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(26.dp).then(
                            when {
                                d == ui.today -> Modifier.background(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), CircleShape)
                                d == focus -> Modifier.border(1.25.dp, Glass.Ink, CircleShape)
                                else -> Modifier
                            },
                        ),
                        contentAlignment = Alignment.Center,
                    ) { Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge, color = if (inMonth) Glass.Ink else Glass.Muted.copy(alpha = 0.6f)) }
                    Spacer(Modifier.height(2.dp))
                    for (t in tasks.take(3)) Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp).padding(top = 2.dp).height(4.dp)
                        .background(projectColor(ui, t.project).copy(alpha = if (inMonth) 1f else 0.4f), RoundedCornerShape(2.dp)))
                    if (tasks.size > 3) Text("+${tasks.size - 3}", style = Glass.NumberText.copy(fontSize = Glass.NumberText.fontSize * 0.85f), color = Glass.Muted)
                }
            }
        }
    }
}

/** A day's tasks under the month grid: the project's colour bar, the title, the time; a tap opens the task. */
@Composable
fun DayList(day: LocalDate, ui: UpcomingUi, onOpen: (String) -> Unit) {
    val tasks = remember(ui.tasks, day) { tasksOn(ui.tasks, day) }
    org.pagel.ai.ui.glass.Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(vertical = Space.sm)) {
            Text(day.format(SPOKEN), style = MaterialTheme.typography.titleMedium, color = Glass.Ink,
                modifier = Modifier.padding(horizontal = Space.paneInset, vertical = Space.sm))
            if (tasks.isEmpty()) Text("Nothing planned", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted,
                modifier = Modifier.padding(horizontal = Space.paneInset, vertical = Space.xs))
            for (t in tasks) key(t.id) {
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Space.touch).pressScale(press)
                        .clickable(press, indication = null, role = Role.Button) { onOpen(t.id) }
                        .padding(horizontal = Space.paneInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(4.dp).height(24.dp).background(projectColor(ui, t.project), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(Space.md))
                    Text(titleOf(t), style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    t.due?.drop(11)?.takeIf(String::isNotBlank)?.let { Text(it.replace('-', '–'), style = Glass.NumberText, color = Glass.Muted) }
                }
            }
        }
    }
}
