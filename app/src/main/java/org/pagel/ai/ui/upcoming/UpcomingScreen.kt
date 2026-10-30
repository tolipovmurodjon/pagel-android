package org.pagel.ai.ui.upcoming

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import org.pagel.ai.data.upcoming.dailyLine
import org.pagel.ai.data.upcoming.foldDaily
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.upcoming.CalView
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import java.time.LocalDate
import org.pagel.ai.data.upcoming.UpcomingDay
import org.pagel.ai.data.upcoming.plannedLine
import org.pagel.ai.data.upcoming.stripDayAt
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressOnTouch
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.projects.shortName
import org.pagel.ai.ui.task.LocalTaskOpener
import org.pagel.ai.ui.today.Ding
import org.pagel.ai.ui.today.DueTone
import org.pagel.ai.ui.today.TickRing
import org.pagel.ai.ui.today.dueLabel
import org.pagel.ai.ui.today.headline
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val DAY_TITLE = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.UK)
/** "Tomorrow · Thu 29 Oct": the long name left "Oct" alone on a line at 1.5x (checker P4 #7). */
private val DAY_SHORT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val SPOKEN = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
private val LETTER = DateTimeFormatter.ofPattern("EEE", Locale.UK)

@Composable
fun UpcomingRoute(contentPadding: PaddingValues, reselect: Int, vm: UpcomingViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    UpcomingScreen(ui, contentPadding, reselect, onMove = vm::reschedule, onMoveAll = vm::moveAllToToday, onFinish = vm::finish, onMoveTo = vm::moveTo)
}

/** A row being dragged to another day: where the finger holds it, where it started, the day under the finger (strip or month). */
@Stable
private class Drag {
    var id by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var shown by mutableStateOf(false)
    var hovered by mutableStateOf<LocalDate?>(null)
    var grab = Offset.Zero
    var origin = Offset.Zero
    var finger = Offset.Zero
    var size by mutableStateOf(IntSize.Zero)
    /** The held row's own width, so the card is the row (ring + title where the row drew them), not a title alone. */
    var rowWidth by mutableIntStateOf(0)
    var due by mutableStateOf<String?>(null)
    var priority by mutableIntStateOf(2)
    /** The screen's width: the card stays 8 dp inside it (walk 45 had it off the left edge). */
    var screenWidth by mutableFloatStateOf(0f)
    val pos = Animatable(Offset.Zero, Offset.VectorConverter)
    val scale = Animatable(1f)
    /** 0 → 1 over the strip: the card shrinks to 0.6 and rises 56 dp above the finger, so the ink day shows (iOS). */
    val lift = Animatable(0f)
}

/**
 * Upcoming, native (Phase 4, the web's /upcoming): a week strip (iOS Calendar: today marked, a dot under each day with
 * tasks), Overdue on top with one tap to move it all to today, then each of the next 7 days. Long-press a row and drag
 * it onto a day of the strip to move it there: a haptic tick per day crossed, a spring drop, Room first, Undo.
 */
@Composable
fun UpcomingScreen(
    ui: UpcomingUi,
    contentPadding: PaddingValues,
    reselect: Int = 0,
    onMove: (String, LocalDate) -> Unit = { _, _ -> },
    onMoveAll: (List<String>) -> Unit = {},
    onFinish: (String) -> Unit = {},
    /** A calendar block dropped on a new time or day: its id and the new due. */
    onMoveTo: (String, String) -> Unit = { _, _ -> },
    /** Tests open a view; the app opens the one Boss chose last (CalendarPrefs). */
    initialView: CalView? = null,
    /** Tashkent's time now for the timeline's opening hour and now line; tests pass a fixed one. */
    now: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Tashkent")) },
) {
    val haptics = LocalHapticFeedback.current
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val density = LocalDensity.current
    // The view Boss chose last is remembered (round C: "the choice is remembered").
    var view by rememberSaveable { mutableStateOf(initialView ?: CalView.SCHEDULE) }
    LaunchedEffect(Unit) { if (initialView == null) kotlinx.coroutines.withContext(Dispatchers.IO) { CalendarPrefs.view(ctx) }?.let { view = it } }
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = STRIP_BACK) { STRIP_PAGES }
    val page = pager.currentPage - STRIP_BACK
    val stripStart = org.pagel.ai.data.upcoming.pageStart(ui.today, page)
    var focusText by rememberSaveable { mutableStateOf("") }
    val focus = focusText.takeIf(String::isNotEmpty)?.let(LocalDate::parse) ?: ui.today
    var monthText by rememberSaveable { mutableStateOf("") }
    val month = monthText.takeIf(String::isNotEmpty)?.let(java.time.YearMonth::parse) ?: java.time.YearMonth.from(focus)
    // A tick per week paged (Apple's detents), none on the first frame; the focus follows the strip's week.
    var firstPage by remember { mutableStateOf(true) }
    LaunchedEffect(pager.settledPage) {
        if (firstPage) { firstPage = false; return@LaunchedEffect }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        val start = org.pagel.ai.data.upcoming.pageStart(ui.today, pager.settledPage - STRIP_BACK)
        if (focus < start || focus > start.plusDays(6)) focusText = start.toString()
    }
    // The timeline's own pages (Day, 3 days, Week: Week is Monday to Sunday), opened on the focused day; one per view.
    val timelineView = view.takeIf { it == CalView.DAY || it == CalView.THREE } ?: CalView.WEEK
    val tl = key(timelineView) {
        androidx.compose.foundation.pager.rememberPagerState(initialPage = TL_BACK + org.pagel.ai.data.upcoming.timelinePageOf(timelineView, ui.today, focus)) { TL_PAGES }
    }
    val timelineScroll = rememberScrollState()
    val holding = remember { mutableStateOf<Held?>(null) }
    var allDayOpen by rememberSaveable { mutableStateOf(false) }
    var firstTl by remember(tl) { mutableStateOf(true) }
    LaunchedEffect(tl, tl.settledPage) {
        if (firstTl) { firstTl = false; return@LaunchedEffect }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        val shownDays = org.pagel.ai.data.upcoming.timelineDays(timelineView, ui.today, tl.settledPage - TL_BACK)
        if (focus !in shownDays) focusText = shownDays.first().toString()
    }
    val local = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    var headH by remember { mutableIntStateOf(0) }
    val topPad = contentPadding.calculateTopPadding()
    val headDp = with(density) { headH.toDp() } + topPad + Space.sm
    val scope = rememberCoroutineScope()
    val openTask = LocalTaskOpener.current
    val list = rememberLazyListState()
    val drag = remember { Drag() }
    var rootAt by remember { mutableStateOf(Offset.Zero) }
    // The strip's (List) and the month grid's (Month) place on screen, read when a drag needs it, at that moment.
    val strip = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val monthAt = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val slop = with(LocalDensity.current) { 28.dp.toPx() }
    // Page 0 is Upcoming as before (Overdue + today and the next 6); another week is its 7 days, no Overdue.
    val shownView = if (page == 0) ui.view else remember(ui.tasks, stripStart) {
        org.pagel.ai.data.upcoming.upcomingView(ui.tasks, ui.today, from = stripStart).copy(overdue = emptyList())
    }
    val days by rememberUpdatedState(shownView.days)
    val moveTo by rememberUpdatedState(onMove)
    val viewNow by rememberUpdatedState(view)
    val monthNow by rememberUpdatedState(month)
    val overdueFirst = if (shownView.overdue.isNotEmpty()) 1 else 0

    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    // One tick for each day the finger crosses (Apple's picker detents), none when it leaves the strip.
    LaunchedEffect(drag.hovered) { if (drag.hovered != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    val over = drag.hovered != null
    LaunchedEffect(over) { drag.lift.animateTo(if (over) 1f else 0f, spring(dampingRatio = 1f, stiffness = 600f)) }
    // The day under the finger, read where the strip or the month grid is now (Month: the day's list drags onto it).
    fun dayUnder(finger: Offset): LocalDate? {
        if (viewNow == CalView.MONTH) {
            val c = monthAt[0]?.takeIf { it.isAttached } ?: return null
            val at = c.positionInRoot()
            return org.pagel.ai.data.upcoming.monthDayAt(finger.x - at.x, finger.y - at.y, c.size.width.toFloat(), c.size.height.toFloat(),
                org.pagel.ai.data.upcoming.monthWeeks(monthNow))
        }
        val c = strip[0]?.takeIf { it.isAttached } ?: return null
        val at = c.positionInRoot()
        return stripDayAt(finger.x - at.x, finger.y - at.y, c.size.width.toFloat(), c.size.height.toFloat(), days.size, slop)?.let { days.getOrNull(it)?.day }
    }
    fun cellCentre(d: LocalDate): Offset? {
        if (viewNow == CalView.MONTH) {
            val c = monthAt[0]?.takeIf { it.isAttached } ?: return null
            val weeks = org.pagel.ai.data.upcoming.monthWeeks(monthNow)
            val row = weeks.indexOfFirst { d in it }.takeIf { it >= 0 } ?: return null
            val at = c.positionInRoot()
            val w = c.size.width / 7f
            val h = c.size.height / weeks.size.toFloat()
            return Offset(at.x + w * (weeks[row].indexOf(d) + 0.5f), at.y + h * (row + 0.5f))
        }
        val i = days.indexOfFirst { it.day == d }.takeIf { it >= 0 } ?: return null
        val c = strip[0]?.takeIf { it.isAttached } ?: return null
        val at = c.positionInRoot()
        val w = c.size.width.toFloat() / days.size.coerceAtLeast(1)
        return Offset(at.x + w * (i + 0.5f), at.y + c.size.height / 2f)
    }
    val handlers = remember {
        DragHandlers(
            start = { t, rowAt, local, rowSize ->
                drag.id = t.id; drag.title = titleOf(t); drag.due = t.due; drag.priority = t.priority; drag.rowWidth = rowSize.width
                drag.grab = local; drag.origin = rowAt; drag.finger = rowAt + local; drag.shown = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch {
                    drag.pos.snapTo(rowAt); drag.lift.snapTo(0f); drag.scale.snapTo(1f)
                    drag.scale.animateTo(1.04f, spring(dampingRatio = 0.7f, stiffness = 600f))
                }
            },
            move = { delta ->
                drag.finger += delta
                scope.launch { drag.pos.snapTo(drag.finger - drag.grab) }
                drag.hovered = dayUnder(drag.finger)
            },
            end = { cancelled ->
                val id = drag.id
                // The day under the finger as the screen is now, at the release (round 0.3.33's lesson), not the last hover.
                val day = if (cancelled) null else dayUnder(drag.finger)
                drag.hovered = null
                val target = day?.let(::cellCentre)
                if (id != null && day != null && target != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    moveTo(id, day)
                    drag.id = null
                    // The spring drop: the held point flies into the day's cell and the card shrinks into it.
                    scope.launch {
                        launch { drag.pos.animateTo(target - drag.grab, spring(dampingRatio = 0.8f, stiffness = 380f)) }
                        drag.scale.animateTo(0.2f, spring(dampingRatio = 1f, stiffness = 380f))
                        drag.shown = false
                    }
                } else scope.launch {
                    launch { drag.scale.animateTo(1f, spring(dampingRatio = 1f, stiffness = 500f)) }
                    drag.pos.animateTo(drag.origin, spring(dampingRatio = 0.8f, stiffness = 500f))
                    // Only now the row comes back: un-dimming it while the card still flew showed two copies (checker #10).
                    drag.id = null
                    drag.shown = false
                }
            },
        )
    }

    fun tapDay(d: LocalDate) {
        focusText = d.toString()
        if (view == CalView.SCHEDULE) {
            val i = days.indexOfFirst { it.day == d }
            if (i >= 0) scope.launch { list.animateScrollToItem(if (i == 0 && page == 0) 0 else 1 + overdueFirst + i) }
        }
    }
    fun pick(v: CalView) {
        view = v
        scope.launch(Dispatchers.IO) { CalendarPrefs.setView(ctx, v) }
    }
    val tlDays = org.pagel.ai.data.upcoming.timelineDays(timelineView, ui.today, tl.currentPage - TL_BACK)
    // The header says the month once (Google): the shown days' month, or both when a week crosses one; short at large font.
    // Month's arrows on a 320 dp screen leave the title ~40 dp: "Oct", not "Octo" at 9 sp (CI walk 79).
    val short = density.fontScale > 1.3f || (view == CalView.MONTH && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360)
    val title = when (view) {
        CalView.MONTH -> monthsOf(month.atDay(1), month.atDay(1), ui.today, short)
        CalView.SCHEDULE -> monthsOf(stripStart, stripStart, ui.today, short)
        else -> monthsOf(tlDays.first(), tlDays.last(), ui.today, short)
    }
    val away = when (view) {
        CalView.SCHEDULE -> page != 0 || focus != ui.today
        CalView.MONTH -> month != java.time.YearMonth.from(ui.today) || focus != ui.today
        else -> ui.today !in tlDays
    }
    val anyAllDay = remember(ui.tasks) { hasAllDay(ui.tasks) }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootAt = it.positionInRoot(); drag.screenWidth = it.size.width.toFloat() }) {
        // The screen's own layer: the glass top bends and blurs what scrolls under it (round C: "not liquid glass").
        Box(Modifier.fillMaxSize().layerBackdrop(local)) {
            org.pagel.ai.ui.glass.AuroraBackground(Modifier.fillMaxSize()) {
                when (view) {
                    CalView.SCHEDULE -> LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        contentPadding = PaddingValues(top = headDp + Space.sm, bottom = contentPadding.calculateBottomPadding() + Space.sm),
                        verticalArrangement = Arrangement.spacedBy(Space.paneGap),
                    ) {
                        item(key = "head", contentType = "head") { Header(shownView, page) }
                        when {
                            ui.loading -> item(key = "loading", contentType = "note") {
                                Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) { SkeletonRows(3, Modifier.padding(vertical = 6.dp)) }
                            }
                            else -> {
                                // Overdue stays on top of the list view only (round C).
                                if (shownView.overdue.isNotEmpty()) item(key = "overdue", contentType = "overdue") {
                                    OverduePane(ui, drag, handlers, onMoveAll = { onMoveAll(shownView.overdue.map(TaskEntity::id)) }, onOpen = openTask, onFinish = onFinish,
                                        modifier = Modifier.animateItem())
                                }
                                for (d in shownView.days) item(key = "day-${d.day}", contentType = "day") {
                                    val rel = java.time.temporal.ChronoUnit.DAYS.between(ui.today, d.day).toInt()
                                    DayPane(d, rel, ui, drag, handlers, onOpen = openTask, onFinish = onFinish, modifier = Modifier.animateItem())
                                }
                            }
                        }
                    }
                    CalView.MONTH -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(headDp + Space.sm))
                        MonthGrid(month, ui, focus, drag.hovered, onGrid = { monthAt[0] = it }) { d -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); focusText = d.toString() }
                        Spacer(Modifier.height(Space.paneGap))
                        DayList(focus, ui, drag.id, handlers, openTask)
                        Spacer(Modifier.height(contentPadding.calculateBottomPadding() + Space.sm))
                    }
                    else -> Timeline(timelineView, tl, ui, timelineScroll, top = headDp + Space.sm, bottom = contentPadding.calculateBottomPadding() + Space.sm,
                        onOpen = openTask, onMove = onMoveTo, holding = holding, clipTop = headDp, now = now)
                }
            }
        }
        // The pinned glass top: one header row, then the List's week strip or the timeline's dates (and its All-day line).
        GlassTop(local, Modifier.padding(start = Space.gutter, end = Space.gutter, top = topPad + Space.sm).onSizeChanged { headH = it.height }) {
            Column(Modifier.padding(Space.xs)) {
                CalHeader(
                    title, view, showToday = away, onView = ::pick,
                    onToday = {
                        focusText = ""; monthText = ""
                        scope.launch {
                            if (view == CalView.SCHEDULE) { pager.animateScrollToPage(STRIP_BACK); list.animateScrollToItem(0) }
                            else if (view != CalView.MONTH) tl.animateScrollToPage(TL_BACK)
                        }
                    },
                    onMonth = if (view == CalView.MONTH) { k -> monthText = month.plusMonths(k).toString() } else null,
                )
                when (view) {
                    CalView.SCHEDULE -> WeekPager(pager, ui, focus, drag.hovered, Modifier.padding(bottom = Space.xs).onGloballyPositioned { strip[0] = it }) { d -> tapDay(d) }
                    CalView.MONTH -> Unit
                    else -> {
                        TimelineHeads(tl, timelineView, ui.today, Modifier.padding(start = GUTTER - Space.xl)) { d ->
                            focusText = d.toString()
                            if (view != CalView.DAY) pick(CalView.DAY)
                        }
                        if (anyAllDay) AllDayLine(tl, timelineView, ui, allDayOpen, Modifier.padding(start = GUTTER - Space.xl, bottom = Space.xs),
                            onToggle = { allDayOpen = !allDayOpen }, onOpen = openTask)
                    }
                }
            }
        }
        if (drag.shown) Ghost(drag, rootAt)
        // The timeline's held block, on glass over the screen's layer (drawn here: a glass inside the layer it bends cannot be).
        holding.value?.let { TimelineGhost(it, local, it.origin - rootAt) }
    }
}

private val RANGE_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

private val MONTH_FULL = DateTimeFormatter.ofPattern("MMMM", Locale.UK)
private val MONTH_ONLY = DateTimeFormatter.ofPattern("MMM", Locale.UK)

/**
 * The header's month, as Google says it: "October" (the year only when it is not this one), "Oct – Nov" for a week that
 * crosses a month; [short] (large font) says "Oct", so the row never cuts it to "Oc".
 */
internal fun monthsOf(first: LocalDate, last: LocalDate, today: LocalDate, short: Boolean = false): String {
    fun name(d: LocalDate, full: Boolean) = d.format(if (full) MONTH_FULL else MONTH_ONLY) + if (d.year != today.year) " ${d.year}" else ""
    return if (first.month == last.month && first.year == last.year) name(first, !short) else "${name(first, false)} – ${name(last, false)}"
}

/** The view Boss chose last, kept on the phone (read and written off Main). */
internal object CalendarPrefs {
    private const val PREFS = "pagel-calendar"
    fun view(ctx: android.content.Context): CalView? =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("view", null)?.let { n -> CalView.entries.firstOrNull { it.name == n } }
    fun setView(ctx: android.content.Context, v: CalView) =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("view", v.name).apply()
}

/** What a row needs to start, move and end a drag; the screen owns the state. */
internal class DragHandlers(
    val start: (TaskEntity, Offset, Offset, IntSize) -> Unit,
    val move: (Offset) -> Unit,
    val end: (cancelled: Boolean) -> Unit,
)

private fun titleOf(t: TaskEntity) = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)

@Composable
private fun Header(view: org.pagel.ai.data.upcoming.UpcomingView, page: Int) {
    Column(Modifier.padding(start = Space.textEdge, end = Space.textEdge, top = Space.xs)) {
        Text(if (page == 0) "Next 7 days" else "Week of ${view.days.first().day.format(RANGE_DAY)}", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        Text("Upcoming", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
        Text(plannedLine(view), style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
    }
}

/** A pane's title and its one action; at large font the action wraps under the title ("Overd/ue" at 1.5x shared a row). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PaneTitle(text: String, color: Color = Glass.Ink, trailing: (@Composable () -> Unit)? = null) {
    FlowRow(
        // Every pane title row is 48 dp, with or without a button, so the title-to-first-row gap is one (spacing audit #21).
        Modifier.fillMaxWidth().heightIn(min = Space.touch).padding(start = Space.paneInset, end = 10.dp, top = Space.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 2, modifier = Modifier.padding(vertical = 6.dp).semantics { heading() })
        trailing?.invoke()
    }
}

@Composable
private fun OverduePane(ui: UpcomingUi, drag: Drag, handlers: DragHandlers, onMoveAll: () -> Unit, onOpen: (String) -> Unit, onFinish: (String) -> Unit, modifier: Modifier) {
    Pane(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(bottom = 6.dp)) {
            PaneTitle("Overdue", Glass.Risk) {
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier.heightIn(min = 48.dp).pressScale(press)
                        .clickable(press, indication = null, role = Role.Button, onClick = onMoveAll)
                        .semantics(mergeDescendants = true) { contentDescription = "Move all to today" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.background(Glass.PrimarySoft, CircleShape).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(TabIcons.Today, contentDescription = null, tint = Glass.PrimaryInk, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Move all to today", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, maxLines = 1)
                    }
                }
            }
            Rows(ui.view.overdue, ui, drag, handlers, onOpen, onFinish, late = true)
        }
    }
}

@Composable
private fun DayPane(d: UpcomingDay, i: Int, ui: UpcomingUi, drag: Drag, handlers: DragHandlers, onOpen: (String) -> Unit, onFinish: (String) -> Unit, modifier: Modifier) {
    val short = i <= 1 || LocalDensity.current.fontScale > 1.3f
    val title = when (i) { 0 -> "Today · "; 1 -> "Tomorrow · "; else -> "" } + d.day.format(if (short) DAY_SHORT else DAY_TITLE)
    val (rows, folded) = remember(d.tasks, i) { foldDaily(d.tasks, i) }
    var open by rememberSaveable(d.day.toString()) { mutableStateOf(false) }
    Pane(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(bottom = 6.dp)) {
            PaneTitle(title)
            if (d.tasks.isEmpty()) Text("Nothing planned", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 8.dp))
            else {
                if (rows.isNotEmpty()) Rows(rows, ui, drag, handlers, onOpen, onFinish, late = false)
                if (folded.isNotEmpty()) {
                    if (rows.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                    FoldedHabits(folded.size, open) { open = !open }
                    AnimatedVisibility(open, enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(), exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut()) {
                        Column {
                            HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                            Rows(folded, ui, drag, handlers, onOpen, onFinish, late = false)
                        }
                    }
                }
            }
        }
    }
}

/** The daily habits of a later day, folded into one quiet line: the repeat mark, "5 daily habits", a chevron. Tap = open. */
@Composable
private fun FoldedHabits(n: Int, open: Boolean, onToggle: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "fold")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = if (open) "Fold" else "Show") { onToggle() }
            .semantics(mergeDescendants = true) { contentDescription = dailyLine(n) + if (open) ", shown" else ", folded" }
            .padding(start = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(TabIcons.Habits, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(18.dp))
        }
        Text(dailyLine(n), style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.weight(1f))
        Icon(TabIcons.ChevronDown, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn })
    }
}

@Composable
private fun Rows(tasks: List<TaskEntity>, ui: UpcomingUi, drag: Drag, handlers: DragHandlers, onOpen: (String) -> Unit, onFinish: (String) -> Unit, late: Boolean) {
    for ((n, t) in tasks.withIndex()) key(t.id) {
        if (n > 0) HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
        TaskRow(t, ui, held = drag.id == t.id, handlers = handlers, late = late, onOpen = onOpen, onFinish = onFinish)
    }
}

/** One task: ring (a habit shows its repeat mark; it is ticked on Habits), title, then time or late date · project. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(t: TaskEntity, ui: UpcomingUi, held: Boolean, handlers: DragHandlers, late: Boolean, onOpen: (String) -> Unit, onFinish: (String) -> Unit) {
    val title = titleOf(t)
    val done = t.id in ui.finishing
    val haptics = LocalHapticFeedback.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val dim by animateFloatAsState(if (held) 0.35f else 1f, label = "held")
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = dim }
            .onPlaced { coords[0] = it }
            // Long-press, then drag onto a day of the strip (iOS Calendar's move). A plain tap still opens the panel.
            // Never a habit: a new due would move its whole series and can undo today's tick on pagel.uz (checker P4 #1).
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
            .padding(start = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (t.every.isNullOrBlank()) TickRing(t.priority, done, title) { _ ->
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            val app = ctx.applicationContext; scope.launch(Dispatchers.Default) { Ding.play(app) }
            onFinish(t.id)
        } else Box(Modifier.size(48.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Icon(TabIcons.Habits, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(18.dp))
        }
        val open = remember { MutableInteractionSource() }
        Column(
            Modifier.weight(1f).pressOnTouch()
                .clickable(open, indication = null, enabled = !done, role = Role.Button, onClickLabel = "Open") { onOpen(t.id) }
                .padding(top = 12.dp, bottom = 10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (done) Glass.Muted else Glass.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Meta(t, ui, late)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Meta(t: TaskEntity, ui: UpcomingUi, late: Boolean) {
    val small = MaterialTheme.typography.labelSmall
    val icon = with(LocalDensity.current) { 13.sp.toDp() }
    FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val time = t.due?.drop(11)?.trim()?.takeIf(String::isNotEmpty)?.let(org.pagel.ai.ui.glass.DatePick::timeText)
        val lateLabel = if (late) dueLabel(t.due, ui.today)?.takeIf { it.tone == DueTone.LATE } else null
        if (lateLabel != null || time != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (lateLabel != null) TabIcons.Calendar else TabIcons.Clock, contentDescription = null, tint = if (lateLabel != null) Glass.Risk else Glass.Muted, modifier = Modifier.size(icon))
            Spacer(Modifier.width(4.dp))
            Text(lateLabel?.text ?: time.orEmpty(), style = small, color = if (lateLabel != null) Glass.Risk else Glass.Muted, maxLines = 1)
        }
        t.every?.takeIf(String::isNotBlank)?.let { Text(Repeat.label(it), style = small, color = Glass.Muted, maxLines = 1) }
        val p = ui.projects[t.project]
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProjectTile(t.project, p?.color, p?.title ?: t.project, size = 16.dp)
            Spacer(Modifier.width(5.dp))
            Text(shortName(t.project, p?.title ?: t.project), style = small, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The held row, lifted: the row itself (its ring and title where the row drew them, so nothing jumps under the finger)
 * on white glass with a shadow. Over the strip it shrinks to 0.6 and rises 56 dp above the finger, so the ink day under
 * the thumb shows; it always stays 8 dp inside the screen. It drops into a day or springs home.
 */
@Composable
private fun Ghost(drag: Drag, rootAt: Offset) {
    val width = with(LocalDensity.current) { drag.rowWidth.toDp() }
    Row(
        Modifier
            .offset { val p = drag.pos.value - rootAt; IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
            .then(if (drag.rowWidth > 0) Modifier.width(width) else Modifier.widthIn(max = 340.dp))
            .onSizeChanged { drag.size = it }
            .graphicsLayer {
                val lift = drag.lift.value
                val s = drag.scale.value * (1f - 0.4f * lift)
                scaleX = s; scaleY = s
                val gx = drag.grab.x.coerceIn(0f, size.width)
                val gy = drag.grab.y.coerceIn(0f, size.height)
                transformOrigin = if (size.width > 0f && size.height > 0f) TransformOrigin(gx / size.width, gy / size.height) else TransformOrigin.Center
                // Keep the card as drawn (scaled about the finger) 8 dp inside the screen's sides and 4 dp below its top.
                val margin = 8.dp.toPx()
                val left = drag.pos.value.x - rootAt.x + gx * (1f - s)
                val w = size.width * s
                val room = drag.screenWidth - w - margin
                val wantLeft = if (room < margin) (drag.screenWidth - w) / 2f else left.coerceIn(margin, room)
                translationX = wantLeft - left
                val rise = -56.dp.toPx() * lift
                val top = drag.pos.value.y - rootAt.y + gy * (1f - s) + rise
                translationY = rise + (4.dp.toPx() - top).coerceAtLeast(0f)
                alpha = ((s - 0.2f) / 0.8f).coerceIn(0f, 1f)
                shadowElevation = 18.dp.toPx()
                shape = RoundedCornerShape(18.dp)
                clip = true
            }
            .background(Color.White.copy(alpha = 0.94f))
            .clearAndSetSemantics {}
            .padding(start = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp).border(1.5.dp, GhostRing.getOrElse(drag.priority) { Glass.Muted }, CircleShape))
        }
        Column(Modifier.weight(1f).padding(top = 12.dp, bottom = 12.dp)) {
            Text(drag.title, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            drag.due?.drop(11)?.trim()?.takeIf(String::isNotEmpty)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** The ring's colour by priority, as Today's TickRing draws it (P0 red, P1 orange, P2 blue; else grey). */
private val GhostRing = listOf(Color(0xFFD1453B), Color(0xFFEB8909), Color(0xFF246FE0))
