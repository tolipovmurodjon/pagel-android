package org.pagel.ai.ui.upcoming

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import org.pagel.ai.data.upcoming.BlockDrag
import org.pagel.ai.data.upcoming.CalView
import org.pagel.ai.data.upcoming.Grid
import org.pagel.ai.data.upcoming.Slot
import org.pagel.ai.data.upcoming.blockAt
import org.pagel.ai.data.upcoming.blockOf
import org.pagel.ai.data.upcoming.gridDay
import org.pagel.ai.data.upcoming.hhmm
import org.pagel.ai.data.upcoming.monthWeeks
import org.pagel.ai.data.upcoming.tasksOn
import org.pagel.ai.data.upcoming.timelineDays
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassMenu
import org.pagel.ai.ui.glass.GlassStage
import org.pagel.ai.ui.glass.MenuItem
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
import kotlin.math.roundToInt

/**
 * The calendar's parts. Round C gave it liquid glass, five views and project colours; core round 4 (Boss 2026-10-29:
 * "trash, look at the top, messy; see Google Calendar, it is clean") made it Google's: one header row (the month, a
 * view button, Today), the dates once on the columns, Week = Monday to Sunday, hour lines only, blocks as one soft tint
 * of their project's colour, and a real drag (long-press, a glass ghost on the 15-minute grid, read at the drop).
 */

/** Strip pages (the List view): half a year back, a year ahead; page [STRIP_BACK] is today and the next 6 days. */
const val STRIP_BACK = 26
const val STRIP_PAGES = STRIP_BACK + 53

/** Timeline pages (Day, 3 days, Week): ten years of weeks, or 520 days, each side of today's page [TL_BACK]. */
const val TL_BACK = 520
const val TL_PAGES = TL_BACK * 2

private val LETTER = DateTimeFormatter.ofPattern("EEE", Locale.UK)
private val NARROW = DateTimeFormatter.ofPattern("EEEEE", Locale.UK)
private val SPOKEN = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
private val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")
private val Hair = 2.dp

/**
 * A project's own colour (its gradient tile's first stop, rules/gradient-project-icons), as the calendar paints it: a
 * warm red (ABABIL's "red", Me's coral) read as an alarm in Month's bars, so here only it is softened (see [calendarSafe]).
 */
fun projectColor(ui: UpcomingUi, project: String): Color = calendarSafe(TileColors.stops(project, ui.projects[project]?.color).first)

/** OKLCH hues the calendar never paints at full strength: red, from pink (340°) round to coral (40°). */
internal fun isWarmRed(hue: Double): Boolean = hue < 40.0 || hue > 340.0

/**
 * Checker round 2 #2 (never alarm red): a warm-red fill keeps its hue but its chroma drops to 0.07 and its lightness
 * rises to 0.80, so it reads rose or peach. Every other colour is kept; the saved colour itself never changes.
 */
internal fun calendarSafe(c: Color): Color {
    fun lin(v: Float) = if (v <= 0.04045f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
    val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    val ll = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    val aa = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
    val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    val chroma = Math.hypot(aa, bb)
    val hue = (Math.toDegrees(Math.atan2(bb, aa)) + 360.0) % 360.0
    if (chroma < 0.02 || !isWarmRed(hue)) return c
    return TileColors.oklch(maxOf(ll, 0.80), minOf(chroma, 0.07), hue).copy(alpha = c.alpha)
}

private fun titleOf(t: TaskEntity) = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)

/** The test tag on every word-safe calendar title (a block's, the ghost's, an all-day chip's). */
const val CAL_TITLE = "cal-title"

/** What a title shows in its box: whole words only, at [sp], on [lines] lines. */
internal data class TitleFit(val text: String, val sp: Float, val lines: Int)

private fun sized(style: TextStyle, sp: Float, ratio: Float) =
    style.copy(fontSize = sp.sp, lineHeight = (sp * ratio).sp, hyphens = Hyphens.None)

/**
 * Checker round 2 #1 ("Recor", "Gy"): a calendar title breaks only between words. The whole title first, shrinking from
 * [maxSp] to [minSp]; then fewer words (the first ones, whole); last, the first word alone, made small enough to fit.
 * Never a word cut through. [w], [h] are the box in px.
 */
internal fun fitWords(m: TextMeasurer, text: String, style: TextStyle, w: Int, h: Int, maxSp: Float, minSp: Float, ratio: Float, maxLines: Int, d: Density): TitleFit {
    val words = text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty() || w <= 0) return TitleFit(text, maxSp, 1)
    fun measure(t: String, sp: Float) = m.measure(AnnotatedString(t), sized(style, sp, ratio), softWrap = true, constraints = Constraints(maxWidth = w), density = d)
    fun lines(t: String, sp: Float, last: Boolean): Int? {
        val lineH = with(d) { (sp * ratio).sp.toPx() }
        if (lineH > h && !last) return null
        val room = minOf(maxLines, (h / lineH).toInt()).coerceAtLeast(1)
        val r = measure(t, sp)
        if (r.lineCount > room || (r.size.height > h && !last)) return null
        for (i in 0 until r.lineCount) {
            if (r.getLineRight(i) > w + 0.5f) return null
            val end = r.getLineEnd(i)
            if (end in 1 until t.length && !t[end - 1].isWhitespace() && !t[end].isWhitespace()) return null
        }
        return r.lineCount
    }
    val sizes = generateSequence(maxSp) { it - 0.5f }.takeWhile { it >= minSp - 0.01f }.toList()
    for (n in words.size downTo 1) {
        val t = words.take(n).joinToString(" ")
        for ((k, sp) in sizes.withIndex()) lines(t, sp, last = k == sizes.lastIndex)?.let { return TitleFit(t, sp, it) }
    }
    // One word wider than the box even at [minSp]: that word, whole, as small as it must be (never under 6 dp).
    val first = words[0]
    val wide = m.measure(AnnotatedString(first), sized(style, minSp, ratio), softWrap = false, maxLines = 1, density = d).size.width.coerceAtLeast(1)
    val sp = maxOf(minSp * w / wide * 0.97f, 6f / d.fontScale)
    return TitleFit(first, sp, 1)
}

/**
 * A calendar title in the box it is given: whole words only ([fitWords]), clipped never with "…". Tagged [CAL_TITLE] so
 * the screenshot tests can read every line of it.
 */
@Composable
internal fun WordSafeTitle(text: String, style: TextStyle, maxSp: Float, maxLines: Int, modifier: Modifier = Modifier, ratio: Float = 1.2f, minSp: Float = 8f, alignment: Alignment = Alignment.TopStart) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = alignment) {
        val w = constraints.maxWidth - 1
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val fit = remember(text, w, h, density, maxSp, maxLines, style) { fitWords(measurer, text, style, w, h, maxSp, minSp, ratio, maxLines, density) }
        Text(fit.text, style = sized(style, fit.sp, ratio), maxLines = fit.lines, overflow = TextOverflow.Clip, modifier = Modifier.testTag(CAL_TITLE))
    }
}

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

/**
 * The one header row (Google Calendar's): the month, the Month view's arrows, a small view button that opens a glass
 * menu, and Today (its slot kept, so nothing moves when it shows).
 */
@Composable
fun CalHeader(title: String, view: CalView, showToday: Boolean, onView: (CalView) -> Unit, onToday: () -> Unit, onMonth: ((Long) -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).padding(start = Space.md), verticalAlignment = Alignment.CenterVertically) {
        // It shrinks before it is cut: "October 2026" never ends as "Oct…" at 1.5x on a 360 dp screen.
        BasicText(
            title, maxLines = 1, softWrap = false, style = MaterialTheme.typography.titleMedium.copy(color = Glass.Ink),
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.titleMedium.fontSize),
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onMonth != null) {
            org.pagel.ai.ui.glass.GlassIconButton(TabIcons.ChevronLeft, "Previous month", size = 36.dp) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onMonth(-1) }
            org.pagel.ai.ui.glass.GlassIconButton(TabIcons.ChevronRight, "Next month", size = 36.dp) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onMonth(1) }
        }
        ViewButton(view, onView)
        TodayPill(showToday, onToday)
    }
}

private fun iconOf(v: CalView) = when (v) {
    CalView.SCHEDULE -> TabIcons.ListTodo
    CalView.DAY -> TabIcons.Today
    CalView.THREE -> TabIcons.CalendarRange
    CalView.WEEK -> TabIcons.CalendarCheck
    CalView.MONTH -> TabIcons.Calendar
}

/** The view, said in one word with a chevron; a tap opens the five views in a glass menu (the switch took a third of the top). */
@Composable
private fun ViewButton(view: CalView, onView: (CalView) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val press = remember { MutableInteractionSource() }
    Box {
        Box(
            Modifier.heightIn(min = Space.touch).pressScale(press)
                .clickable(press, indication = null) { open = true }
                .clearAndSetSemantics { contentDescription = "Change view"; stateDescription = view.label; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.background(Glass.Ink.copy(alpha = 0.06f), CircleShape).padding(start = Space.md, end = Space.sm, top = Space.xs, bottom = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // At large font the view's own icon says it (the word pushed the month off the row).
                if (LocalDensity.current.fontScale > 1.3f) Icon(iconOf(view), contentDescription = null, tint = Glass.Ink, modifier = Modifier.size(20.dp))
                else Text(view.label, style = MaterialTheme.typography.labelLarge, color = Glass.Ink, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(Space.xs))
                Icon(TabIcons.ChevronDown, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp))
            }
        }
        if (open) GlassMenu(
            CalView.entries.map { MenuItem(iconOf(it), it.label, chosen = it == view) },
            onPick = { open = false; onView(CalView.entries[it]) },
            onDismiss = { open = false },
        )
    }
}

/**
 * The List view's week strip, paged by week with a swipe (snap, a tick per week): 7 days from today + 7 × page. Today is
 * bright mint, the focused day ringed, a day a row is dragged over ink; under each day up to 3 dots in the projects' colours.
 */
@Composable
fun WeekPager(
    pager: PagerState, ui: UpcomingUi, focus: LocalDate, hovered: LocalDate?, modifier: Modifier = Modifier, onTap: (LocalDate) -> Unit,
) {
    HorizontalPager(pager, modifier.fillMaxWidth(), key = { it }) { page ->
        val start = org.pagel.ai.data.upcoming.pageStart(ui.today, page - STRIP_BACK)
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.xs)) {
            for (i in 0 until 7) {
                val d = start.plusDays(i.toLong())
                key(d) { DayCell(d, ui, today = d == ui.today, focused = d == focus, over = hovered == d, modifier = Modifier.weight(1f)) { onTap(d) } }
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
        Row(Modifier.height(DOT), horizontalArrangement = Arrangement.spacedBy(Hair)) {
            for (p in tasks.map { it.project }.distinct().take(3)) Box(Modifier.size(DOT).background(projectColor(ui, p), CircleShape))
        }
    }
}

/** "Today": back to today, shown only away from it (its slot is kept, so the header never moves). */
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

// ── The timeline (Day · 3 days · Week) ─────────────────────────────────────────────────────────────────────────────

/** The hour column: hours as "05" (checker #5: "05:00" was wider than the column at 1.5x). */
val GUTTER = 36.dp
/** The timeline's right edge = the glass top's inner edge (16 + 4), so its columns line up with the day heads. */
val TIMELINE_END = 20.dp
/** A strip dot: the slot is kept with or without dots. */
private val DOT = 5.dp
/** The held block's ghost is at least this tall, so its title and its new time both show. */
private val GHOST_MIN = 38.dp
/** A block is drawn at least this tall (a 15-minute task stays readable and grabbable). */
private val MIN_BLOCK = 22.dp

/** The hour's height: the Day view's one column gets more room. */
fun hourHeight(view: CalView): Dp = if (view == CalView.DAY) 60.dp else 52.dp

/**
 * Pages that follow the timeline's pager by hand: the page before, this one and the next, each moved by the pager's own
 * offset in the layout phase. The day heads and the All-day line slide with the finger (no jump on a swipe).
 */
@Composable
private fun Sliding(pager: PagerState, modifier: Modifier = Modifier, page: @Composable (Int) -> Unit) {
    var w by remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxWidth().clipToBounds().onSizeChanged { w = it.width }) {
        val at = pager.currentPage
        for (p in at - 1..at + 1) key(p) {
            Box(Modifier.fillMaxWidth().offset { IntOffset(((p - pager.currentPage - pager.currentPageOffsetFraction) * w).roundToInt(), 0) }) { page(p) }
        }
    }
}

/** The dates, once, on the columns (Google's letter over number, today a mint circle); a tap opens that day. */
@Composable
fun TimelineHeads(pager: PagerState, view: CalView, today: LocalDate, modifier: Modifier = Modifier, onDay: (LocalDate) -> Unit) {
    Sliding(pager, modifier) { p ->
        val days = remember(view, today, p) { timelineDays(view, today, p - TL_BACK) }
        Row(Modifier.fillMaxWidth()) { for (d in days) key(d) { DayHead(d, d == today, Modifier.weight(1f)) { onDay(d) } } }
    }
}

@Composable
private fun DayHead(d: LocalDate, today: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier.heightIn(min = Space.touch).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = "Open the day", onClick = onTap)
            .semantics(mergeDescendants = true) { contentDescription = d.format(SPOKEN) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // "Mon" shrinks before it touches "Tue" (a 37 dp column at 1.5x); at large font one letter, as Google's week.
        val small = MaterialTheme.typography.labelSmall
        BasicText(
            d.format(if (LocalDensity.current.fontScale > 1.3f) NARROW else LETTER), maxLines = 1, softWrap = false,
            style = small.copy(color = if (today) Glass.PrimaryInk else Glass.Muted),
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = small.fontSize),
        )
        Box(
            Modifier.sizeIn(minWidth = 30.dp, minHeight = 30.dp)
                .then(if (today) Modifier.background(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 1, softWrap = false) }
    }
}

/** The untimed tasks of a day for the All-day line: never a habit (Boss: habits live in Habits). */
fun allDayOf(tasks: List<TaskEntity>, d: LocalDate): List<TaskEntity> = tasksOn(tasks, d).filter { it.every.isNullOrBlank() && blockOf(it) == null }

/** Any untimed dated task at all: the All-day line keeps its place on every page then (a swipe never moves the grid). */
fun hasAllDay(tasks: List<TaskEntity>): Boolean = tasks.any { it.status == "open" && it.every.isNullOrBlank() && (it.due?.length ?: 0) >= 10 && blockOf(it) == null }

/**
 * All day, one line (Boss: "a wall of cut chips"): a day's one task as a soft chip; two or more fold to "+N" (a wider
 * column keeps the first title) and a tap opens every day's list in place; "Show less" folds it back.
 */
@Composable
fun AllDayLine(pager: PagerState, view: CalView, ui: UpcomingUi, open: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit, onOpen: (String) -> Unit) {
    val now = remember(view, ui.tasks, ui.today, pager.currentPage) { timelineDays(view, ui.today, pager.currentPage - TL_BACK).map { allDayOf(ui.tasks, it) } }
    val lines = if (open) now.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1 else 1
    val wide = view != CalView.WEEK
    Column(modifier.fillMaxWidth()) {
        Sliding(pager, Modifier.height(Space.xxl * lines)) { p ->
            val days = remember(view, ui.today, p) { timelineDays(view, ui.today, p - TL_BACK) }
            Row(Modifier.fillMaxWidth()) {
                for (d in days) key(d) {
                    val tasks = remember(ui.tasks, d) { allDayOf(ui.tasks, d) }
                    Column(Modifier.weight(1f).padding(horizontal = Hair / 2), verticalArrangement = Arrangement.spacedBy(Hair)) {
                        when {
                            tasks.isEmpty() -> Unit
                            open || tasks.size == 1 -> { for (t in tasks) key(t.id) { AllDayChip(titleOf(t), projectColor(ui, t.project), titleOf(t)) { onOpen(t.id) } } }
                            else -> AllDayChip(
                                // "+N" says a count, not a project: mint, never a project's warm tint read as an alarm.
                                if (wide) titleOf(tasks[0]) else "+${tasks.size}", if (wide) projectColor(ui, tasks[0].project) else Glass.Primary,
                                "${tasks.size} all-day tasks on ${d.format(SPOKEN)}, show all", count = if (wide) "+${tasks.size - 1}" else null, onClick = onToggle,
                            )
                        }
                    }
                }
            }
        }
        if (open) {
            val press = remember { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth().height(Space.xxl).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onToggle)
                    .clearAndSetSemantics { contentDescription = "Show less"; role = Role.Button },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) { Icon(TabIcons.ChevronDown, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f }) }
        }
    }
}

@Composable
private fun AllDayChip(title: String, color: Color, spoken: String, count: String? = null, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Space.sm)
    Box(
        Modifier.fillMaxWidth().height(MIN_BLOCK).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .background(Color.White.copy(alpha = 0.7f), shape).background(color.copy(alpha = 0.24f), shape)
            .padding(horizontal = Space.xs),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Whole words only (checker round 2 #4: "Revie"): it shrinks, then keeps the first words; a count stays whole beside it.
        val small = MaterialTheme.typography.labelSmall
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            WordSafeTitle(title, small.copy(color = Glass.Ink), maxSp = small.fontSize.value, maxLines = 1, modifier = Modifier.weight(1f).fillMaxHeight(),
                alignment = Alignment.CenterStart)
            if (count != null) Text(count, style = small, color = Glass.Ink, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = Hair))
        }
    }
}

/**
 * A block held on the timeline: the lift (pure, [BlockDrag]), the finger, and where the ghost is drawn now ([ghost] in
 * the timeline's space, [origin] = the timeline's place on screen: the ghost is drawn by the screen, over its glass layer).
 */
@Stable
internal class Held(val drag: BlockDrag, val color: Color, val title: String, val liftY: Float) {
    var origin = Offset.Zero
    var finger = Offset.Zero
    var slot by mutableStateOf<Slot?>(null)
    var ghost by mutableStateOf(Rect.Zero)
    /** The grid scrolls (-1 up, 1 down) only while the finger is carried into the top or bottom edge, never from a lift there. */
    var scrolls by mutableIntStateOf(0)
    /** The finger rests beside the columns (-1 before, 1 after): the week turns after 0.6 s. */
    var side by mutableIntStateOf(0)
}

/**
 * Google's week: hour lines only and very faint, no lines between the days, today's column a soft mint wash, a thin
 * mint line at now, timed tasks as soft blocks in their project's colour. The columns page sideways (previous / next
 * week, the vertical place kept). Long-press a block: a glass ghost follows the finger on the 15-minute grid with a
 * tick per slot, into another day (the edge pages the week); the bottom edge resizes a range. The drop is read from
 * where the grid is at that moment, then Room first, one PATCH, Undo. Habits and Namoz stay where they are.
 */
@Composable
internal fun Timeline(
    view: CalView, pager: PagerState, ui: UpcomingUi, scroll: ScrollState, top: Dp, bottom: Dp,
    onOpen: (String) -> Unit, onMove: (String, String) -> Unit,
    /** The held block, read by the screen, which draws its glass ghost ([TimelineGhost]) outside the backdrop's layer. */
    holding: MutableState<Held?> = remember { mutableStateOf<Held?>(null) },
    /** Where the glass top ends: nothing of the grid is drawn above it (checker round 2 #5). */
    clipTop: Dp = 0.dp,
) {
    val dayView = view == CalView.DAY
    val hourH = hourHeight(view)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val hourPx = with(density) { hourH.toPx() }
    val minBlockMin = (MIN_BLOCK.value / hourH.value * 60f).roundToInt()
    val minBlockPx = with(density) { MIN_BLOCK.toPx() }
    val topPx = with(density) { top.toPx() }
    val bottomPx = with(density) { bottom.toPx() }
    val edgeZonePx = with(density) { Space.xxxl.toPx() + Space.sm.toPx() }
    val slackPx = with(density) { Space.sm.toPx() }
    val latest by rememberUpdatedState(ui)
    val move by rememberUpdatedState(onMove)
    // [0] this box, [1] the columns (the pager): read at the moment of need, never kept from the lift.
    val at = remember { arrayOfNulls<LayoutCoordinates>(2) }
    var held by holding
    val clipPx = with(density) { clipTop.toPx() }
    var hoursAt by remember { mutableIntStateOf(-1) }

    fun grid(): Grid? {
        val box = at[0]?.takeIf { it.isAttached } ?: return null
        val cols = at[1]?.takeIf { it.isAttached } ?: return null
        val o = box.localPositionOf(cols, Offset.Zero)
        val page = if (pager.isScrollInProgress) pager.targetPage else pager.currentPage
        return Grid(o.x, o.y, cols.size.width.toFloat(), hourPx, timelineDays(view, latest.today, page - TL_BACK))
    }

    fun place(h: Held) {
        val g = grid() ?: return
        at[0]?.takeIf { it.isAttached }?.let { h.origin = it.positionInRoot() }
        val s = h.drag.slot(g, h.finger.x, h.finger.y) ?: return
        if (s != h.slot) { if (h.slot != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); h.slot = s }
        val colW = g.width / g.days.size
        val col = g.days.indexOf(s.day).coerceAtLeast(0)
        val b = h.drag.block
        val start = if (h.drag.resize) b.start else s.minute
        val end = if (h.drag.resize) maxOf(s.minute, b.start + 15) else s.minute + (b.end - b.start)
        val y = g.top + start / 60f * hourPx
        h.ghost = Rect(g.left + col * colW, y, g.left + (col + 1) * colW, y + maxOf((end - start) / 60f * hourPx, minBlockPx))
        val fy = h.finger.y
        val boxH = at[0]?.takeIf { it.isAttached }?.size?.height ?: 0
        h.scrolls = when {
            fy < topPx + edgeZonePx && fy < h.liftY - slackPx -> -1
            boxH > 0 && fy > boxH - bottomPx - edgeZonePx && fy > h.liftY + slackPx -> 1
            else -> 0
        }
        h.side = when {
            h.drag.resize -> 0
            h.finger.x < g.left - slackPx / 2 -> -1
            h.finger.x > g.left + g.width + slackPx / 2 -> 1
            else -> 0
        }
    }

    // Opened at the hour before now when today is shown, else at 06:00 (Google opens at the working morning); once per view.
    LaunchedEffect(view, hoursAt) {
        if (hoursAt < 0) return@LaunchedEffect
        val now = LocalTime.now(TASHKENT).hour
        val hour = if (ui.today in timelineDays(view, ui.today, pager.currentPage - TL_BACK)) (now - 1).coerceIn(0, 20) else 6
        scroll.scrollTo((hoursAt + (hourPx * hour).roundToInt() - topPx.roundToInt()).coerceAtLeast(0))
    }
    // While a block is held: the grid scrolls when the finger nears the top or the bottom, and the week turns when the
    // finger rests beside the columns for 0.6 s; the ghost is placed again after each.
    // Frames run only while the finger is in an edge (a held block in the middle leaves the screen idle).
    LaunchedEffect(held, held?.scrolls, held?.side) {
        val h = held ?: return@LaunchedEffect
        if (h.scrolls == 0 && h.side == 0) return@LaunchedEffect
        val step = with(density) { Space.sm.toPx() }
        var rest = 0L
        var last = 0L
        while (true) {
            val now = withFrameMillis { it }
            val dt = if (last == 0L) 0L else now - last
            last = now
            // At the grid's end with no week to turn: stop asking for frames (the keys stay, so it does not restart).
            if (h.scrolls != 0 && scroll.dispatchRawDelta(step * h.scrolls) == 0f && h.side == 0) { place(h); return@LaunchedEffect }
            if (h.side != 0 && !pager.isScrollInProgress) {
                rest += dt
                if (rest > 600) { rest = 0; pager.animateScrollToPage(pager.currentPage + h.side) }
            }
            place(h)
        }
    }
    val nowMin by produceState(LocalTime.now(TASHKENT).let { it.hour * 60 + it.minute }) {
        while (true) { delay(60_000); value = LocalTime.now(TASHKENT).let { it.hour * 60 + it.minute } }
    }

    Box(
        Modifier.fillMaxSize().onPlaced { at[0] = it }.pointerInput(view, pager, hourPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val g = grid() ?: return@awaitEachGesture
                val p = long.position
                // A block under the glass top is not drawn there, so it is not lifted there either.
                if (p.y < clipPx) return@awaitEachGesture
                val colW = g.width / g.days.size
                val ci = ((p.x - g.left) / colW).toInt()
                if (p.x < g.left || ci !in g.days.indices) return@awaitEachGesture
                val blocks = gridDay(tasksOn(latest.tasks, g.days[ci]), dayView).blocks
                val b = blockAt(blocks, (p.y - g.top) / hourPx * 60f, (p.x - g.left - ci * colW) / colW, minBlockMin) ?: return@awaitEachGesture
                // A habit's due is its series and Namoz has its own times: neither moves here.
                if (b.habit) return@awaitEachGesture
                val topY = g.top + b.start / 60f * hourPx
                val shown = maxOf((b.end - b.start) / 60f * hourPx, minBlockPx)
                val resize = shown >= 24.dp.toPx() && topY + shown - p.y <= minOf(12.dp.toPx(), shown / 3f)
                val h = Held(BlockDrag.lift(b, resize, p.y, if (resize) g.top + b.end / 60f * hourPx else topY), projectColor(latest, b.task.project), titleOf(b.task), p.y)
                h.finger = p
                long.consume()
                place(h)
                held = h
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                try {
                    while (true) {
                        // The Initial pass: the scroll and the pager under the finger never see a held block's move.
                        val ch = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == long.id } ?: break
                        ch.consume()
                        if (!ch.pressed) break
                        h.finger = ch.position
                        place(h)
                    }
                    // Read now, where the grid is now (round 0.3.33: a target kept from the lift was stale).
                    val due = grid()?.let { h.drag.dueAt(it, h.finger.x, h.finger.y) }
                    if (due != null) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); move(b.task.id, due) }
                } finally {
                    held = null
                }
            }
        },
    ) {
        // The grid is drawn only below the glass top: a block never peeks out above it, and today's mint column is not
        // blurred into a dark band under the dates (checker round 2 #5).
        Column(Modifier.fillMaxSize().drawWithContent { clipRect(top = clipPx) { this@drawWithContent.drawContent() } }.verticalScroll(scroll)) {
            Spacer(Modifier.height(top))
            Box(Modifier.fillMaxWidth().height(hourH * 24).onPlaced { hoursAt = it.positionInParent().y.toInt() }) {
                // The hour lines, very faint, and nothing between the days (Boss: "keep it clean").
                val line = Glass.Ink.copy(alpha = 0.05f)
                Canvas(Modifier.matchParentSize()) {
                    val h = hourH.toPx()
                    for (k in 0..24) drawLine(line, Offset(GUTTER.toPx(), k * h), Offset(size.width - TIMELINE_END.toPx(), k * h), 1.dp.toPx())
                }
                // A label under the glass top is not drawn (checker #10: "02", "03" peeked, cut, beside it).
                for (k in 1..23) Text("%02d".format(k), style = Glass.NumberText, color = Glass.Muted,
                    modifier = Modifier.offset(x = Space.sm, y = hourH * k - 8.dp).graphicsLayer {
                        alpha = if (hoursAt + (hourH * k).toPx() - scroll.value < top.toPx() + 4.dp.toPx()) 0f else 1f
                    })
                HorizontalPager(
                    pager, Modifier.fillMaxSize().padding(start = GUTTER, end = TIMELINE_END).onPlaced { at[1] = it },
                    beyondViewportPageCount = 1, key = { it },
                ) { page ->
                    val days = remember(view, ui.today, page) { timelineDays(view, ui.today, page - TL_BACK) }
                    Row(Modifier.fillMaxSize()) {
                        for (d in days) key(d) {
                            val layout = remember(ui.tasks, d, dayView) { gridDay(tasksOn(ui.tasks, d), dayView) }
                            DayColumn(d, d == ui.today, layout.blocks, ui, hourH, held?.drag?.block?.task?.id, nowMin, Modifier.weight(1f), onOpen)
                        }
                    }
                }
            }
            Spacer(Modifier.height(bottom))
        }
    }
}

@Composable
private fun DayColumn(d: LocalDate, today: Boolean, blocks: List<Block>, ui: UpcomingUi, hourH: Dp, heldId: String?, nowMin: Int, modifier: Modifier, onOpen: (String) -> Unit) {
    var colW by remember { mutableIntStateOf(0) }
    Box(
        modifier.fillMaxHeight().onSizeChanged { colW = it.width }
            .then(if (today) Modifier.background(Glass.PrimarySoft.copy(alpha = 0.55f)) else Modifier)
            .semantics { contentDescription = "Column ${d.format(SPOKEN)}" },
    ) {
        for (b in blocks) key(b.task.id) { BlockView(b, ui, hourH, colW, held = b.task.id == heldId) { onOpen(b.task.id) } }
        if (today) NowLine(nowMin, hourH)
    }
}

/**
 * One block (Boss: "remove that vertical line of the block, keep it clean"): one soft tint of its project's colour,
 * 10 dp corners, a 1 px lighter rim, the title in ink (two lines, clipped, never cut to nothing), the time small.
 */
@Composable
private fun BlockView(b: Block, ui: UpcomingUi, hourH: Dp, colW: Int, held: Boolean, onOpen: () -> Unit) {
    val density = LocalDensity.current
    val color = projectColor(ui, b.task.project)
    val top = hourH * (b.start / 60f)
    val height = maxOf(hourH * ((b.end - b.start) / 60f), MIN_BLOCK)
    val time = timeText(b)
    val laneW = with(density) { (colW.toFloat() / b.lanes).toDp() }
    val shape = RoundedCornerShape(10.dp)
    val press = remember { MutableInteractionSource() }
    val dim by animateFloatAsState(if (held) 0.35f else 1f, label = "held")
    Column(
        Modifier.offset { IntOffset((colW.toFloat() / b.lanes * b.lane).roundToInt(), top.roundToPx()) }.width(laneW).height(height)
            .padding(end = Hair, bottom = Hair)
            .graphicsLayer { alpha = dim }
            .background(Color.White, shape).background(color.copy(alpha = 0.22f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.75f), shape)
            .pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = "Open", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "${titleOf(b.task)}, $time" }
            .padding(horizontal = Space.xs, vertical = Hair),
    ) {
        // A week column is ~40 dp: 10 sp on 12 sp lines gives a half-hour block "Record the", and a word that does not
        // fit shrinks (to 8 sp) or waits for the next line, never cut through ("Recor", checker round 2 #1).
        val narrow = laneW < 60.dp
        val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, color = Glass.Ink)
        WordSafeTitle(titleOf(b.task), style, maxSp = if (narrow) 10f else 12.5f, maxLines = 2, modifier = Modifier.fillMaxWidth().weight(1f, fill = false))
        if (height > 40.dp && laneW > 56.dp) Text(time, style = Glass.NumberText, color = Glass.Muted, maxLines = 1, softWrap = false)
    }
}

private fun timeText(b: Block): String = if (b.task.due?.drop(11)?.contains('-') == true) "${hhmm(b.start)}–${hhmm(b.end)}" else hhmm(b.start)

/**
 * The held block, lifted on liquid glass as the tab bar's (checker round 2 #5: it was white 92 % and a shadow): the
 * screen's own layer bent by a small lens and blurred, a rim light, the project's tint, on the slot it will land on,
 * its new time said in it. [shift] = the timeline's place in the screen box that draws it.
 */
@Composable
internal fun TimelineGhost(h: Held, backdrop: Backdrop, shift: Offset) {
    val density = LocalDensity.current
    val r = h.ghost
    if (r == Rect.Zero) return
    val s = h.slot
    val b = h.drag.block
    val time = when {
        s == null -> timeText(b)
        h.drag.resize -> "${hhmm(b.start)}–${hhmm(maxOf(s.minute, b.start + 15))}"
        b.task.due?.drop(11)?.contains('-') == true -> "${hhmm(s.minute)}–${hhmm(minOf(s.minute + b.end - b.start, 23 * 60 + 55))}"
        else -> hhmm(s.minute)
    }
    val shape = RoundedCornerShape(12.dp)
    val tint = h.color
    val surface = if (!GlassStage.on) Modifier.background(Color.White.copy(alpha = 0.92f), shape).background(tint.copy(alpha = 0.30f), shape) else Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { RoundedRectangle(12.dp) },
        effects = { vibrancy(); blur(4f.dp.toPx()); lens(8f.dp.toPx(), 12f.dp.toPx()) },
        highlight = { Highlight.Default },
        shadow = { Shadow(alpha = 0.5f) },
        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.45f)); drawRect(tint.copy(alpha = 0.26f)) },
    )
    Column(
        Modifier.offset { IntOffset((r.left + shift.x).roundToInt(), (r.top + shift.y).roundToInt()) }
            // At least two lines tall: the new time is the point of the ghost (a half-hour block cut it in half).
            .width(with(density) { r.width.toDp() }).height(maxOf(with(density) { r.height.toDp() }, GHOST_MIN))
            .then(surface)
            .border(1.25.dp, PaneRim, shape)
            // Merged, not cleared: the title inside keeps its own node, so the tests can read that it is whole words.
            .semantics(mergeDescendants = true) { contentDescription = "Moving ${h.title} to $time" }
            .padding(horizontal = Space.xs, vertical = Hair),
    ) {
        val narrow = r.width < with(density) { 56.dp.toPx() }
        WordSafeTitle(h.title, MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, color = Glass.Ink),
            maxSp = MaterialTheme.typography.labelSmall.fontSize.value, maxLines = 2, modifier = Modifier.fillMaxWidth().weight(1f, fill = false))
        // "14:30" whole in a 37 dp week column (CI walk 82: "14:3").
        Text(time, style = if (narrow) Glass.NumberText.copy(fontSize = 9.sp) else Glass.NumberText,
            color = Glass.PrimaryInk, maxLines = 1, softWrap = false)
    }
}

/** Now: a thin mint line and a dot (never red: Boss's rule). */
@Composable
private fun NowLine(nowMin: Int, hourH: Dp) {
    val y = hourH * (nowMin / 60f)
    Box(Modifier.fillMaxWidth().offset(y = y - 1.dp).height(Hair).background(Glass.Primary).semantics { contentDescription = "Now" })
    Box(Modifier.offset(x = (-4).dp, y = y - 4.dp).size(Space.sm).background(Glass.Primary, CircleShape))
}

// ── Month ──────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Google's month: six weeks, each day with up to 3 bars in its tasks' project colours and "+N"; tap = that day's list.
 * [onGrid] gets the six weeks' place, so a row dragged from the day's list drops on the day under the finger.
 */
@Composable
fun MonthGrid(month: YearMonth, ui: UpcomingUi, focus: LocalDate, hovered: LocalDate?, onGrid: (LayoutCoordinates) -> Unit, onPick: (LocalDate) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.sm)) {
        Row(Modifier.fillMaxWidth()) {
            for (l in listOf("M", "T", "W", "T", "F", "S", "S")) Text(l, style = MaterialTheme.typography.labelSmall, color = Glass.Muted,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().onPlaced(onGrid)) {
            for (week in monthWeeks(month)) Row(Modifier.fillMaxWidth()) {
                for (d in week) key(d) {
                    val tasks = remember(ui.tasks, d) { tasksOn(ui.tasks, d).filter { it.every != "day" } }
                    val inMonth = d.month == month.month
                    val press = remember { MutableInteractionSource() }
                    val over = d == hovered
                    Column(
                        Modifier.weight(1f).height(60.dp).pressScale(press)
                            .clickable(press, indication = null, role = Role.Button) { onPick(d) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = d.format(SPOKEN) + when (tasks.size) { 0 -> ""; 1 -> ", 1 task"; else -> ", ${tasks.size} tasks" }
                                selected = d == focus
                            }
                            .padding(Hair),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            // At least 28 dp, one line: "28" never breaks into "2" at 1.5x (checker #1).
                            Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).then(
                                when {
                                    over -> Modifier.background(Glass.Ink, CircleShape)
                                    d == ui.today -> Modifier.background(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), CircleShape)
                                    d == focus -> Modifier.border(1.25.dp, Glass.Ink, CircleShape)
                                    else -> Modifier
                                },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
                                color = when { over -> Color.White; inMonth -> Glass.Ink; else -> Glass.Muted.copy(alpha = 0.6f) })
                        }
                        Spacer(Modifier.height(Hair))
                        for (t in tasks.take(3)) Box(Modifier.fillMaxWidth().padding(horizontal = Hair).padding(top = Hair).height(4.dp)
                            .background(projectColor(ui, t.project).copy(alpha = if (inMonth) 1f else 0.4f), RoundedCornerShape(Hair)))
                        if (tasks.size > 3) Text("+${tasks.size - 3}", style = Glass.NumberText.copy(fontSize = Glass.NumberText.fontSize * 0.85f), color = Glass.Muted)
                    }
                }
            }
        }
    }
}

/**
 * A day's tasks under the month grid, each a soft tint of its project's colour (no stripe), the title and the time; a
 * tap opens the task. Long-press a task (not a habit) and drop it on a day of the month: it moves there, its time kept.
 */
@Composable
internal fun DayList(day: LocalDate, ui: UpcomingUi, heldId: String?, handlers: DragHandlers, onOpen: (String) -> Unit) {
    val tasks = remember(ui.tasks, day) { tasksOn(ui.tasks, day) }
    org.pagel.ai.ui.glass.Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(vertical = Space.sm)) {
            Text(day.format(SPOKEN), style = MaterialTheme.typography.titleMedium, color = Glass.Ink,
                modifier = Modifier.padding(horizontal = Space.paneInset, vertical = Space.sm))
            if (tasks.isEmpty()) Text("Nothing planned", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted,
                modifier = Modifier.padding(horizontal = Space.paneInset, vertical = Space.xs))
            for (t in tasks) key(t.id) {
                val press = remember { MutableInteractionSource() }
                val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
                val dim by animateFloatAsState(if (heldId == t.id) 0.35f else 1f, label = "held")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Hair).heightIn(min = Space.touch)
                        .graphicsLayer { alpha = dim }
                        .onPlaced { coords[0] = it }
                        .background(projectColor(ui, t.project).copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                        .then(
                            if (!t.every.isNullOrBlank()) Modifier
                            else Modifier.pointerInput(t.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { local -> coords[0]?.takeIf { it.isAttached }?.let { handlers.start(t, it.positionInRoot(), local, it.size) } },
                                    onDrag = { change, amount -> change.consume(); handlers.move(amount) },
                                    onDragEnd = { handlers.end(false) },
                                    onDragCancel = { handlers.end(true) },
                                )
                            },
                        )
                        .pressScale(press)
                        .clickable(press, indication = null, role = Role.Button) { onOpen(t.id) }
                        .padding(horizontal = Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(titleOf(t), style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    t.due?.drop(11)?.takeIf(String::isNotBlank)?.let { Text(it.replace('-', '–'), style = Glass.NumberText, color = Glass.Muted, softWrap = false) }
                }
            }
        }
    }
}
