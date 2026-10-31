package org.pagel.ai.ui.habits

import androidx.compose.animation.core.Spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.pagel.ai.data.habits.StripContext
import org.pagel.ai.ui.glass.GlassMenu
import org.pagel.ai.ui.glass.HabitIcons
import org.pagel.ai.ui.glass.MenuItem
import org.pagel.ai.data.habits.HabitBoard
import org.pagel.ai.data.habits.HabitCard
import org.pagel.ai.data.habits.Mark
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.NamozQaza
import org.pagel.ai.data.habits.Part
import org.pagel.ai.data.habits.StripDay
import org.pagel.ai.data.habits.Clear
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TileColors
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.today.Burst
import org.pagel.ai.ui.today.ConfettiBurst
import org.pagel.ai.ui.today.Ding
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val DAY_TITLE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
/** The widest header lines any day can show (today's long date, another day's short one): one decides the line count. */
private val LONGEST_DAY_LINES = listOf("Wednesday 28 September", "Wed 28 Sep · looking back")
private val DAY_SHORT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

@Composable
fun HabitsRoute(
    contentPadding: PaddingValues,
    reselect: Int,
    onOpenHabit: (String) -> Unit = {},
    onHistory: () -> Unit = {},
    onOpenNamoz: () -> Unit = {},
    onOpenTimer: (String) -> Unit = {},
    vm: HabitsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var namozSheet by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var celebrate by remember { mutableStateOf(0) }
    var finished by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val grants = rememberReminderGrants(vm)
    val openTask = org.pagel.ai.ui.task.LocalTaskOpener.current
    LifecycleResumeEffect(Unit) {
        vm.replan()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) { vm.celebrate.collect { celebrate++ } }
    LaunchedEffect(Unit) { var n = 0; vm.timerDone.collect { finished = it to ++n } }

    HabitsScreen(
        ui, contentPadding, reselect, celebrate = celebrate, finished = finished,
        onTick = vm::tick, onUntick = vm::untick, onOpen = onOpenHabit,
        onSelectDay = vm::selectDay, onSelectPart = vm::selectPart, onHistory = onHistory,
        onStartNamoz = vm::startNamoz, onNamozMenu = { namozSheet = true }, onOpenNamoz = onOpenNamoz,
        qaza = vm.qaza.collectAsStateWithLifecycle().value, onPrayedLater = vm::prayedLater,
        onNew = { adding = true }, onLink = vm::link,
        onDayOff = { id, off -> if (off) vm.unfreeze(id) else vm.freeze(id) }, onEdit = openTask, onOpenTimer = onOpenTimer,
    )
    if (namozSheet) NamozSheet(ui.board?.namoz.orEmpty(), ui.reminders, onToggle = grants.remind) { namozSheet = false }
    if (adding) NewHabitSheet(ui.part, vm::create) { adding = false }
}

/**
 * Habits 3.0 (Boss 2026-10-31, after his 667-day habit app): the all-habits chain on top, one pager of weeks that swipes
 * both ways; All, Morning, Afternoon, Evening; then one bold card per habit in its own colour (the check sits outside
 * on the left, the line icon, the name, the progress, the streak, a menu). A day off is drawn as done. The five prayers
 * are one calm Namoz tile. Tapping a card opens the habit's own page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HabitsScreen(
    ui: HabitsUi,
    contentPadding: PaddingValues,
    reselect: Int = 0,
    celebrate: Int = 0,
    finished: Pair<String, Int>? = null,
    onTick: (String) -> Unit = {},
    onUntick: (String) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onSelectDay: (LocalDate?) -> Unit = {},
    onSelectPart: (Part) -> Unit = {},
    onHistory: () -> Unit = {},
    onStartNamoz: () -> Unit = {},
    onNamozMenu: () -> Unit = {},
    onOpenNamoz: () -> Unit = {},
    qaza: Set<String> = emptySet(),
    onPrayedLater: (String) -> Unit = {},
    onNew: () -> Unit = {},
    onLink: (String, String) -> Unit = { _, _ -> },
    onDayOff: (String, Boolean) -> Unit = { _, _ -> },
    onEdit: (String) -> Unit = {},
    onOpenTimer: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val bursts = remember { mutableStateListOf<Burst>() }
    var nextBurst by remember { mutableLongStateOf(0L) }
    var rootAt by remember { mutableStateOf(Offset.Zero) }
    val centres = remember { mutableStateMapOf<String, Offset>() }
    val links = remember { LinkDrag() }
    val list = rememberLazyListState()
    val board = ui.board
    val today = runCatching { LocalDate.parse(ui.today) }.getOrNull()
    // One timed card open at a time: its Timer and Finish buttons sit under it (Boss 2026-10-31).
    var expandedId by remember { mutableStateOf<String?>(null) }
    val intentionContext = androidx.compose.ui.platform.LocalContext.current
    val intention = remember { org.pagel.ai.data.habits.NamozIntention.load(intentionContext) }
    val strip = remember(ui.byId, ui.ticks, ui.freezes, today) { today?.let { StripContext(ui.byId.values.toList(), ui.ticks, ui.freezes, it) } }

    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    fun burstAt(at: Offset) {
        scope.launch(Dispatchers.Default) { Ding.play(ctx.applicationContext) }
        bursts += Burst(nextBurst++, at - rootAt)
    }
    LaunchedEffect(celebrate) { if (celebrate > 0) burstAt(rootAt + Offset(540f, 600f)) }
    LaunchedEffect(finished) { finished?.first?.let { id -> centres[id]?.let(::burstAt) } }
    fun tick(id: String) {
        if (expandedId == id) expandedId = null
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        centres[id]?.let(::burstAt)
        onTick(id)
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootAt = it.positionInRoot() }) {
        // One spacing rhythm (Boss 2026-10-29: "the spacing is different for all"): every block and every habit row sits
        // Space.paneGap apart, every row has the same height (HabitsScreenshotTest.rowsHaveOneHeightAndOneGap).
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
            item(key = "head", contentType = "head") { Header(ui, board, onHistory, onNew) }
            if (ui.loading || board == null || strip == null) {
                item(key = "loading", contentType = "note") { Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) { SkeletonRows(3, Modifier.padding(vertical = Space.sm)) } }
                return@LazyColumn
            }
            item(key = "strip", contentType = "strip") { WeekStrip(board, strip, onSelectDay) }
            item(key = "parts", contentType = "parts") { PartPills(ui.part, board, onSelectPart) }
            // Above the pager: the pager is as tall as its fullest page, so a card under it would sit far down.
            if (ui.day.allDone && ui.finishing.isEmpty() && board.selected == board.today) item(key = "all-done", contentType = "all-done") {
                AllDone(ui.day.doneCount, ui.day.best, Modifier.animateItem())
            }
            // Before the first prayer is added the one Start card; after it, each prayer sits in its own time of day.
            if (board.namoz.isEmpty()) item(key = "namoz", contentType = "namoz") {
                NamozGroup(board, today, onTick = ::tick, onUntick = onUntick, onOpen = onOpen, onStart = onStartNamoz, onMenu = onNamozMenu, onPage = onOpenNamoz, centres = centres, modifier = Modifier.animateItem())
            }
            // All, Morning, Afternoon, Evening as one swipe pager (Boss's note 5): the chips and the pages follow each other.
            item(key = "pager", contentType = "pager") {
                PartPager(ui.part, board, onSelectPart) { part, current ->
                    val cards = cardsFor(board, part)
                    val prayers = board.prayersIn(part)
                    if (cards.isEmpty() && prayers.isEmpty()) EmptyPart(part, board.total, onNew)
                    for ((c, depth) in org.pagel.ai.data.habits.HabitLook.stackOrder(cards, { it.task.id }, ui.prefs)) key(c.task.id) { LinkableRow(c.task.id, links, onLink) {
                        HabitCardRow(
                            c, depth = depth.coerceAtMost(2), finishing = c.task.id in ui.finishing, timer = ui.timers[c.task.id], now = ui.now,
                            onTick = ::tick, onUntick = onUntick, onOpen = onOpen,
                            onCentre = { if (current) centres[c.task.id] = it },
                            onDayOff = onDayOff, onEdit = onEdit, onOpenTimer = onOpenTimer,
                            expanded = expandedId == c.task.id, onExpand = { expandedId = if (expandedId == it) null else it },
                        ) }
                    }
                    for (c in prayers) key("prayer-${c.task.id}") {
                        PrayerRow(
                            c, board.selected == today, NamozQaza.key(today.toString(), c.task.id) in qaza, intention,
                            onTick = ::tick, onUntick = onUntick, onOpenPage = onOpenNamoz, onPrayedLater = onPrayedLater,
                            onCentre = { if (current) centres[c.task.id] = it },
                        )
                    }
                }
            }
            // The glass bar must never sit on the last card: its own clearance, then the page's edge fade takes over.
            // Browsing another day shows the Today pill above the bar: the list leaves it its own strip, so it never covers a card.
            item(key = "end", contentType = "end") { Spacer(Modifier.height(Space.xxl + if (board != null && board.selected != board.today) Space.touch + Space.md else 0.dp)) }
        }
        // Browsing another day: one pill back to today (Boss's reference "Today").
        if (board != null && board.selected != board.today) {
            val press = remember { MutableInteractionSource() }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = Space.gutter, bottom = contentPadding.calculateBottomPadding() + Space.md)
                    .heightIn(min = Space.touch).pressScale(press)
                    .background(Brush.horizontalGradient(listOf(Glass.Aurora2, Glass.Aurora1)), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                    .clickable(press, indication = null, role = Role.Button) { onSelectDay(null) }
                    .semantics(mergeDescendants = true) { contentDescription = "Back to today" }
                    .padding(horizontal = Space.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TabIcons.ChevronRight, null, tint = Glass.Ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.xs))
                Text("Today", style = MaterialTheme.typography.titleSmall, color = Glass.Ink)
            }
        }
        for (b in bursts) key(b.id) { ConfettiBurst(b, onEnd = { bursts.remove(b) }, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Header(ui: HabitsUi, board: HabitBoard?, onHistory: () -> Unit, onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Space.textEdge, end = Space.gutter, top = Space.headerTop), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) { val room = constraints.maxWidth; Column {
            Text("Habits", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            // Always there with a fixed number of lines, so the strip under it never moves (RULES.md Layout 1). Another day
            // uses the short date so "· looking back" fits. When the longest line of any day does not fit (a large font, a
            // 320 dp phone: "Wednesday 28 Oct…", checker 3 #8), the line owns two lines on every day and never cuts.
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val style = MaterialTheme.typography.labelSmall
            val big = remember(room, style, androidx.compose.ui.platform.LocalDensity.current) {
                LONGEST_DAY_LINES.any { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width > room }
            }
            val line = when {
                board == null -> "Every day, the same few things"
                board.selected == board.today -> board.selected.format(DAY_TITLE)
                else -> board.selected.format(DAY_SHORT) + if (board.selected.isAfter(board.today)) " · ahead" else " · looking back"
            }
            val lines = if (big) 2 else 1
            Text(line, style = style, color = Glass.Muted, minLines = lines, maxLines = lines, overflow = TextOverflow.Ellipsis)
        } }
        if (board != null) DayRing(board.doneCount, board.total, Modifier.padding(end = Space.sm))
        GlassIconButton(TabIcons.Plus, "New habit", size = Space.touch, plain = true, onClick = onNew)
        GlassIconButton(TabIcons.CalendarCheck, "History", size = Space.touch, onClick = onHistory)
    }
}

/** The day's ring: done of due, the count in the number face (Apple Fitness's one ring). */
@Composable
internal fun DayRing(done: Int, total: Int, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val target = if (total <= 0) 0f else done.toFloat() / total
    val p by animateFloatAsState(target, spring(dampingRatio = 1f, stiffness = 220f), label = "ring")
    Box(modifier.size(size).semantics { contentDescription = "$done of $total habits done today" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = 5.dp.toPx()
            drawCircle(Glass.Ink.copy(alpha = 0.08f), radius = this.size.minDimension / 2 - w / 2, style = Stroke(w))
            drawArc(
                Brush.sweepGradient(listOf(Glass.Aurora1, Glass.Primary, Glass.Aurora1)), -90f, 360f * p, false,
                topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round),
            )
        }
        Text("$done/$total", style = Glass.NumberText, color = Glass.Ink)
    }
}

// ── The week as a chain ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Weeks the strip can swipe back through (about 14 months); the last page is this week. */
private const val STRIP_WEEKS = 60

/**
 * The ALL-HABITS chain, seven days, Monday first (Boss 2026-10-29: "when I complete ALL habits, it marks that day"). A
 * day fills mint only when every habit due that day is done or taken off; full days side by side join into one solid bar
 * of the same mint (the chain, like his reference's 26-27); a partial day is a thin ring that shows how much (2 of 4 =
 * half a ring), never a fill; a miss is a plain number with a gap; today is a ring; days ahead are faint. The prayers
 * are not in it. Tap a day to look at it.
 *
 * One pager of weeks (Boss 2026-10-31: "when I swipe the week back it is glitching", "why could I not swipe right"):
 * the pager owns the week, so a finger moves it 1:1, a flick lands on a week with the pager's spring, and a swipe can be
 * grabbed and reversed at any moment, both ways, forward up to this week. The chosen day follows the settled page (the
 * same weekday), never the other way round while a finger is down.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekStrip(board: HabitBoard, strip: StripContext, onSelect: (LocalDate?) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val thisMonday = remember(board.today) { board.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    fun mondayOf(page: Int): LocalDate = thisMonday.minusWeeks((STRIP_WEEKS - 1 - page).toLong())
    fun pageOf(d: LocalDate): Int =
        (STRIP_WEEKS - 1 - java.time.temporal.ChronoUnit.WEEKS.between(d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), thisMonday).toInt()).coerceIn(0, STRIP_WEEKS - 1)
    val pager = rememberPagerState(initialPage = pageOf(board.selected)) { STRIP_WEEKS }
    val current by rememberUpdatedState(board)
    // A tap on another day or "back to today" brings its week in, unless a finger is moving the pager.
    LaunchedEffect(board.selected) {
        val p = pageOf(board.selected)
        if (!pager.isScrollInProgress && pager.targetPage != p) pager.animateScrollToPage(p)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect { page ->
            val b = current
            val monday = mondayOf(page)
            if (b.selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) == monday) return@collect
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            val d = monday.plusDays((b.selected.dayOfWeek.value - 1).toLong())
            onSelect(if (!d.isBefore(b.today)) null else d)
        }
    }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        HorizontalPager(pager, Modifier.fillMaxWidth().testTag("week-strip"), beyondViewportPageCount = 1, key = { it }) { page ->
            val monday = mondayOf(page)
            val week = remember(strip, monday) { strip.week(monday) }
            Column(Modifier.fillMaxWidth().padding(vertical = Space.md)) {
                // The month follows the swipe: it lives in the page, so it slides with the week.
                val sunday = monday.plusDays(6)
                val month = if (monday.month == sunday.month) monday.format(MONTH_YEAR) else monday.format(MONTH_SHORT) + " - " + sunday.format(MONTH_SHORT_YEAR)
                Text(month, style = MaterialTheme.typography.labelMedium, color = Glass.Muted, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.xs))
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm)) {
                    // At 1.5x font "MON" wrapped to two lines: one letter then (Apple's narrow weekday).
                    val big = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
                    for (d in week.days) Text(
                        d.date.dayOfWeek.getDisplayName(if (big) TextStyle.NARROW else TextStyle.SHORT, Locale.UK).uppercase(), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        color = if (d.date == board.selected) Glass.Ink else Glass.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(Space.sm))
                ChainRow(
                    week.days.map { it.date to it.clear.toMark(it, board.today) }, board.today, board.selected,
                    counts = week.days.map { it.held to it.due }, bleedIn = week.chainIn, bleedOut = week.chainOut,
                    onSelect = { d -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(if (d == board.today) null else d) },
                    modifier = Modifier.padding(horizontal = Space.sm),
                )
            }
        }
    }
}

private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)
private val MONTH_SHORT = DateTimeFormatter.ofPattern("MMM", Locale.UK)
private val MONTH_SHORT_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.UK)

private fun Clear.toMark(d: StripDay, today: LocalDate): Mark = when (this) {
    // Only a whole day holds the chain (checker #2: a partial day with one day off used to link it). A whole day with a
    // day off in it is drawn exactly like any done day (Boss 2026-10-31: "don't mark a used day as frozen").
    Clear.ALL -> Mark.DONE
    Clear.SOME -> Mark.PARTIAL
    Clear.NONE -> if (d.date == today) Mark.TODAY else Mark.MISSED
    Clear.EMPTY -> Mark.OFF
    Clear.FUTURE -> Mark.FUTURE
}

/**
 * A row of day circles with the chain drawn behind them (the strip, the month calendars and the Namoz row use it).
 * [counts] = (held, due) per day: a PARTIAL day's thin ring shows held / due; without counts the ring is whole. A bar
 * grows between two days with a critically damped spring the moment they join (a tick closes the gap), never pops.
 */
@Composable
internal fun ChainRow(
    days: List<Pair<LocalDate, Mark>>, today: LocalDate, chosen: LocalDate?, onSelect: ((LocalDate) -> Unit)?, modifier: Modifier = Modifier,
    circle: androidx.compose.ui.unit.Dp = 38.dp, slots: Int = days.size, startSlot: Int = 0, counts: List<Pair<Int, Int>>? = null,
    bleedIn: Boolean = false, bleedOut: Boolean = false,
) {
    val marks = days.map { it.second }
    val joined = org.pagel.ai.data.habits.links(marks)
    val reduce = org.pagel.ai.ui.today.reducedMotion(LocalContext.current)
    // Read in the draw phase only: a growing bar redraws, it never recomposes the row.
    val grow = joined.map { j -> animateFloatAsState(if (j) 1f else 0f, if (reduce) snap() else spring(dampingRatio = 1f, stiffness = 380f), label = "chain") }
    Box(modifier.fillMaxWidth().height(circle + Space.sm)) {
        // The chain: one solid bar, the circles' own mint, from centre to centre of every pair of linked days.
        Canvas(Modifier.fillMaxWidth().height(circle + Space.sm)) {
            val cell = size.width / slots
            val h = circle.toPx() * CHAIN_BAR
            val y = size.height / 2 - Space.xs.toPx() / 2
            for (i in joined.indices) {
                val g = grow[i].value
                if (g <= 0.001f) continue
                val x0 = (startSlot + i + 0.5f) * cell
                drawRect(CHAIN, topLeft = Offset(x0, y - h / 2), size = Size(cell * g, h))
            }
            // The chain goes on into last week / next week: the bar fades out at the edge, so it reads as "goes on", never
            // as a cut stub (PNG finding 5).
            // The fade starts a few px inside the strip, so no pale stub shows at the very edge.
            val inset = 6.dp.toPx()
            if (bleedIn && marks.firstOrNull() == Mark.DONE) {
                val x = cell * (startSlot + 0.5f)
                drawRect(Brush.horizontalGradient(listOf(CHAIN.copy(alpha = 0f), CHAIN), startX = inset, endX = x), topLeft = Offset(inset, y - h / 2), size = Size(x - inset, h))
            }
            if (bleedOut && marks.lastOrNull() == Mark.DONE) {
                val x = (startSlot + marks.size - 0.5f) * cell
                drawRect(Brush.horizontalGradient(listOf(CHAIN, CHAIN.copy(alpha = 0f)), startX = x, endX = size.width - inset), topLeft = Offset(x, y - h / 2), size = Size(size.width - inset - x, h))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            if (startSlot > 0) Spacer(Modifier.weight(startSlot.toFloat()))
            for ((i, pair) in days.withIndex()) {
                val (d, m) = pair
                val count = counts?.getOrNull(i)
                val press = remember { MutableInteractionSource() }
                Box(
                    Modifier.weight(1f).height(circle + Space.sm).then(
                        if (onSelect != null) Modifier.pressScale(press).clickable(press, indication = null, role = Role.Button) { onSelect(d) } else Modifier,
                    ).semantics(mergeDescendants = true) {
                        contentDescription = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK))
                        stateDescription = if (m == Mark.PARTIAL && count != null) "${count.first} of ${count.second} done" else markWord(m)
                        if (d == chosen) selected = true
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    val p = if (count != null && count.second > 0) count.first.toFloat() / count.second else null
                    DayDot(d.dayOfMonth, m, d == today, d == chosen, circle, p)
                }
            }
            val rest = slots - startSlot - days.size
            if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
        }
    }
}

/**
 * The chain's one mint: a full day's circle and the bar that joins it are the same colour. 3.55:1 on white, 3.11:1 on
 * the pane's tint (marks ≥ 3:1, checker #7; the old #66DDA8 was 1.68:1); ink numerals on it 5.0:1.
 */
internal val CHAIN = Color(0xFF109A78)

/** The bar's height as a share of the circle (Boss's reference: about half). */
internal const val CHAIN_BAR = 0.5f

internal fun markWord(m: Mark) = when (m) {
    // A day off is a done day (Boss 2026-10-31), so it is announced as one.
    Mark.DONE, Mark.FROZEN -> "Done"; Mark.PARTIAL -> "Some"; Mark.MISSED -> "Missed"; Mark.OFF -> "Not its day"
    Mark.TODAY -> "Today, not yet"; Mark.FUTURE -> "Ahead"; Mark.BEFORE -> "Not counted yet"
}

@Composable
private fun DayDot(n: Int, m: Mark, isToday: Boolean, isSelected: Boolean, circle: androidx.compose.ui.unit.Dp, progress: Float? = null) {
    // A day off holds the chain and is drawn exactly like a done day: no snowflake, no ice, no other mark.
    val full = m == Mark.DONE || m == Mark.FROZEN
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(circle), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(circle)) {
                val r = size.minDimension / 2
                val w = 2.5.dp.toPx()
                // Today is a soft filled disc (the ref's grey 28), so it never reads as a partial ring.
                if (isToday && !full) drawCircle(Glass.PrimarySoft, r)
                when {
                    full -> drawCircle(CHAIN, r)
                    // A partial day: a thin ring, no fill; the mint arc shows how much of the day was held.
                    m == Mark.PARTIAL -> {
                        drawCircle(Glass.Ink.copy(alpha = 0.10f), r - w / 2, style = Stroke(w))
                        drawArc(
                            Glass.Primary, -90f, 360f * (progress ?: 1f).coerceIn(0.08f, 1f), false,
                            topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
                        )
                    }
                    else -> Unit
                }
            }
            val faint = m == Mark.FUTURE || m == Mark.OFF || m == Mark.BEFORE
            Text(
                "$n", style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (full || isToday) FontWeight.Bold else FontWeight.Medium),
                color = if (faint) Glass.Muted.copy(alpha = 0.6f) else Glass.Ink,
            )
        }
        // The selected day's mark: a short mint line under it; it keeps its slot on every day (no jump).
        Box(Modifier.padding(top = Space.xs).size(width = 14.dp, height = 3.dp).background(if (isSelected) Glass.Primary else Color.Transparent, CircleShape))
    }
}

// ── Parts of the day ────────────────────────────────────────────────────────────────────────────────────────────────────

/** "All" is the Anytime page: every habit of the day; the other three are the parts (an anytime habit sits on each). */
private val PARTS = listOf(Part.ANYTIME, Part.MORNING, Part.AFTERNOON, Part.EVENING)

internal fun cardsFor(board: HabitBoard, p: Part): List<HabitCard> = if (p == Part.ANYTIME) board.cards else board.inPart(p)

private fun chipLabel(p: Part) = if (p == Part.ANYTIME) "All" else p.label

/**
 * The parts of the day as pages. Only the page on screen is composed when still (an anytime habit sits on every page; a
 * second copy off screen would steal its taps and its confetti spot). The pager is as tall as the fullest page, worked
 * out from one row's height (every row has one height), so a swipe never makes the list below jump.
 *
 * Why it glitched (Boss 2026-10-31): two owners moved the pager. A flick settled on a page, which set the chosen part,
 * which started animateScrollToPage for that part while the finger was already dragging toward the next page: the pager
 * was pulled back under the finger. Now the pager's own settled page is the one truth; the chosen part only brings the
 * pager in when no finger or fling is moving it.
 */
@Composable
private fun PartPager(part: Part, board: HabitBoard, onSelect: (Part) -> Unit, page: @Composable (Part, Boolean) -> Unit) {
    val pager = rememberPagerState(initialPage = PARTS.indexOf(part).coerceAtLeast(0)) { PARTS.size }
    val chosen by rememberUpdatedState(part)
    LaunchedEffect(part) {
        val i = PARTS.indexOf(part)
        if (i >= 0 && !pager.isScrollInProgress && pager.targetPage != i) pager.animateScrollToPage(i)
    }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { if (PARTS[it] != chosen) onSelect(PARTS[it]) } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { Space.paneGap.roundToPx() }
    val most = PARTS.maxOf { cardsFor(board, it).size }
    var rowPx by remember { mutableIntStateOf(0) }
    val min = if (rowPx > 0 && most > 0) with(density) { (most * rowPx + (most - 1) * gap).toDp() } else 0.dp
    HorizontalPager(pager, Modifier.fillMaxWidth().heightIn(min = min), verticalAlignment = Alignment.Top, key = { PARTS[it] }) { i ->
        val n = cardsFor(board, PARTS[i]).size
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top).onSizeChanged { if (n > 0) rowPx = (it.height - (n - 1) * gap) / n },
            verticalArrangement = Arrangement.spacedBy(Space.paneGap),
        ) { page(PARTS[i], i == pager.currentPage) }
    }
}

/**
 * All, Morning, Afternoon, Evening (Boss's reference: time-of-day chips with drawn icons). Equal-ish chips with a line icon
 * when they fit; on a narrow phone the icon steps aside; at a large font the row scrolls sideways, as his reference does,
 * so no word is ever cut.
 */
@Composable
private fun PartPills(part: Part, board: HabitBoard, onSelect: (Part) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val big = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val weights = mapOf(Part.ANYTIME to 0.8f, Part.MORNING to 1.1f, Part.AFTERNOON to 1.25f, Part.EVENING to 1.0f)
    val glyph = mapOf(Part.ANYTIME to TabIcons.ListTodo, Part.MORNING to TabIcons.Sunrise, Part.AFTERNOON to TabIcons.Sun, Part.EVENING to TabIcons.Moon)
    Row(
        Modifier.fillMaxWidth().then(if (big) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        for (p in PARTS) {
            val on = p == part
            val left = cardsFor(board, p).count { !it.finished }
            val press = remember { MutableInteractionSource() }
            Column(
                (if (big) Modifier else Modifier.weight(weights.getValue(p))).heightIn(min = if (big) Space.touch else Space.row).pressScale(press).clip(CircleShape)
                    .background(if (on) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.34f))
                    .border(if (on) 1.5.dp else 1.dp, if (on) Glass.Primary else Color.White.copy(alpha = 0.8f), CircleShape)
                    // One merging node: clickable + semantics(mergeDescendants) made two, the label on one and
                    // "selected" on the other, so TalkBack never heard "Afternoon, selected".
                    .selectable(on, press, indication = null, role = Role.Tab) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(p) }
                    .semantics { contentDescription = chipLabel(p); if (left > 0) stateDescription = "$left left" }
                    .padding(horizontal = if (big) Space.lg else Space.xs),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                // The icon sits over the word, so the word keeps the chip's whole width at 320 dp (a large font drops the icon and scrolls).
                val g = glyph[p]
                if (!big && g != null) {
                    Icon(g, null, tint = if (on) Glass.PrimaryInk else Glass.Muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.height(Space.xs))
                }
                Text(chipLabel(p), style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    color = if (on) Glass.Ink else Glass.Muted, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun EmptyPart(part: Part, total: Int, onNew: () -> Unit) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Row(Modifier.fillMaxWidth().padding(Space.xl), verticalAlignment = Alignment.CenterVertically) {
            Icon(HabitIcons.vector("sprout"), null, tint = Glass.Primary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(Space.md))
            Column {
                Text(if (total == 0) "No habits yet" else "Nothing this ${part.label.lowercase()}", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                Text(if (total == 0) "Start with one small thing." else "Habits with no time show in every part.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
                Text(
                    "+ New habit", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk,
                    modifier = Modifier.heightIn(min = Space.touch).clickable(role = Role.Button, onClick = onNew).wrapContentHeight(Alignment.CenterVertically),
                )
            }
        }
    }
}

// ── Habit stacking by drag (note 4) ─────────────────────────────────────────────────────────────────────────────────

/** Where every row on the page is, which one is lifted, how far it moved, and the row it would link to. */
@androidx.compose.runtime.Stable
internal class LinkDrag {
    val bounds = mutableMapOf<String, androidx.compose.ui.geometry.Rect>()
    var dragging by mutableStateOf<String?>(null)
    var offset by mutableStateOf(Offset.Zero)
    var hover by mutableStateOf<String?>(null)
    fun target(): String? {
        val id = dragging ?: return null
        val at = (bounds[id] ?: return null).center + offset
        return bounds.entries.firstOrNull { it.key != id && it.value.contains(at) }?.key
    }
    fun reset() { dragging = null; offset = Offset.Zero; hover = null }
}

/**
 * A row that can be stacked: hold it, it lifts; drag it onto another habit and let go = "after that one" (drop it on
 * the one it already follows = unlinked). The step in and the ↳ live inside the card (HabitCardRow), so every check
 * stays on one x position.
 */
@Composable
private fun LinkableRow(id: String, drag: LinkDrag, onLink: (String, String) -> Unit, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val lifted = drag.dragging == id
    val shape = RoundedCornerShape(Space.lg)
    Box(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { drag.bounds[id] = it.boundsInRoot() }
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer { if (lifted) { translationX = drag.offset.x; translationY = drag.offset.y; scaleX = 1.03f; scaleY = 1.03f; alpha = 0.92f } }
            .then(if (drag.hover == id) Modifier.border(2.dp, Glass.Primary, shape) else Modifier)
            .pointerInput(id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); drag.dragging = id; drag.offset = Offset.Zero },
                    onDrag = { change, by ->
                        change.consume()
                        drag.offset += by
                        val t = drag.target()
                        if (t != drag.hover) { drag.hover = t; if (t != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                    },
                    onDragEnd = { drag.target()?.let { onLink(id, it) }; drag.reset() },
                    onDragCancel = { drag.reset() },
                )
            },
    ) { content() }
}

// ── One habit ───────────────────────────────────────────────────────────────────────────────────────────────────────

/** The tile colour a habit wears, by what it is about (brain rules/gradient-project-icons: ten named OKLCH pairs). */
internal fun tileOf(key: String): String = when (HabitIcons.forEmoji(key)) {
    "utensils", "salad", "apple", "drumstick", "milk", "coffee" -> "amber"
    "glass-water", "droplets", "shower-head", "bath", "snowflake", "waves" -> "sky"
    "dumbbell", "footprints", "bike", "person-standing", "activity", "heart-pulse", "zap", "flame" -> "coral"
    "flower-2", "moon", "bed", "moon-star", "phone-off", "sofa", "wind", "mountain" -> "violet"
    "book-open", "book", "brain", "laptop", "pen", "pencil", "notebook", "headphones", "lightbulb", "code", "languages", "graduation-cap" -> "blue"
    "inbox", "calendar-check", "brush-cleaning", "wallet", "piggy-bank", "alarm-clock", "clock", "timer", "house", "shirt" -> "teal"
    "scale", "ruler", "pill", "toothbrush", "heart", "palette", "camera", "music", "gamepad-2" -> "pink"
    else -> "mint"
}

/** White words when the colour is dark enough for 4.5:1, else the page's ink (never a faint white on a bright tile). */
internal fun readableOn(a: Color, b: Color): Color {
    val mid = androidx.compose.ui.graphics.lerp(a, b, 0.5f).luminance()
    return if (1.05f / (mid + 0.05f) >= 4.5f) Color.White else Glass.Ink
}

/** A card's three colours: its tile pair, a touch deeper, greyed a little by how few of the last 30 days were done (a card reaches full colour at 30 of 30), and the words' colour. */
internal data class CardColours(val a: Color, val b: Color, val content: Color)

internal fun cardColours(colour: String, heat: Float): CardColours {
    val (la, lb) = TileColors.stops("", colour)
    val grey = Color(0xFFA9B1C2)
    val k = 0.26f * (1f - heat.coerceIn(0f, 1f))
    val a = androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.lerp(la, Color.Black, 0.04f), grey, k)
    val b = androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.lerp(lb, Color.Black, 0.12f), grey, k)
    return CardColours(a, b, readableOn(a, b))
}

/** A line icon on a lit gradient tile: the habit's picture in the habit's colour (no emoji anywhere). */
@Composable
internal fun EmojiTile(emoji: String, size: androidx.compose.ui.unit.Dp, quiet: Boolean = false, colour: String? = null) {
    // The habit's own colour when Boss picked one (HabitLook), else one by what it is about.
    val c = cardColours(colour ?: tileOf(emoji), 1f)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
            // A glass rim: one thin light edge, never a dark outline.
            .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(size * 0.3f))
            .background(if (quiet) Brush.linearGradient(listOf(Color(0xFFEDEFF3), Color(0xFFDCE0E7))) else Brush.linearGradient(listOf(c.a, c.b), start = Offset.Zero, end = Offset.Infinite))
            // The one light, top-left: a soft inner highlight so the tile reads as a lit object.
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), endY = 40f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(HabitIcons.vector(HabitIcons.forEmoji(emoji)), null, tint = if (quiet) Glass.Muted else c.content, modifier = Modifier.size(size * 0.56f))
    }
}

/**
 * The row's progress, one short line: "Finished", "0 / 10 min" (a timer's "3:20 / 10 min"), "2 / 3", a prayer's
 * window, the time of a timed habit, or "0 / 1". Never the repeat rule: that lives on the habit's page.
 */
internal fun progressText(c: HabitCard, timer: HabitTimer?, now: Long, done: Boolean = c.finished): String = when {
    done -> "Finished"
    c.minutes != null && timer != null -> {
        val s = (timer.spent(now) / 1000).coerceAtMost(c.minutes * 60L)
        "%d:%02d / %d min".format(s / 60, s % 60, c.minutes)
    }
    c.minutes != null -> "0 / ${c.minutes} min"
    c.times > 1 -> "${c.taps} / ${c.times}"
    c.window != null -> c.window.label()
    c.time != null -> c.time
    else -> "0 / 1"
}

/** One title line on a habit row (two lines fit the row's fixed box). */
private val TITLE_LINE = 22.sp

/** The tags a row's parts carry, so a test can count them. */
internal object RowPart {
    const val ROW = "habit-row"
    const val TICK = "row-tick"
    const val EMOJI = "row-emoji"
    const val TITLE = "row-title"
    const val PROGRESS = "row-progress"
    const val STREAK = "row-streak"
    const val MENU = "row-menu"
    const val TIMER = "row-timer"
}

/**
 * One habit as a bold card in its own colour (Boss 2026-10-31, from his 667-day habit app): the check sits outside on the
 * left; inside, a white line icon, the name (two lines, never cut), the progress and the streak, and a menu. The colour
 * is a 2-stop OKLCH gradient and warms with how many of the last 30 days were done (a missed day is only lighter, never
 * a mark). Done: the check fills with the card's colour and the card settles to 55 % with a spring. A tap opens the
 * habit's page; the menu holds Take a day off, Timer and Edit. A habit with minutes is ticked by its check like any other
 * (the clock is never required); a tap on its card opens Timer and Finish under it (Boss 2026-10-31, as his reference),
 * Finish counts it done at once with whatever time was spent. Every card is one height: two title lines, one progress line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HabitCardRow(
    c: HabitCard, finishing: Boolean, timer: HabitTimer?, now: Long, depth: Int = 0,
    onTick: (String) -> Unit, onUntick: (String) -> Unit, onOpen: (String) -> Unit,
    onCentre: (Offset) -> Unit, modifier: Modifier = Modifier,
    onDayOff: (String, Boolean) -> Unit = { _, _ -> }, onEdit: (String) -> Unit = {}, onOpenTimer: (String) -> Unit = {},
    expanded: Boolean = false, onExpand: (String) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val reduce = org.pagel.ai.ui.today.reducedMotion(LocalContext.current)
    val done = c.finished || finishing
    val id = c.task.id
    val tickable = c.mark != Mark.FUTURE && c.mark != Mark.BEFORE && c.mark != Mark.OFF
    val minutes = c.minutes
    // A timed habit opens its Timer and Finish buttons on a tap; every other card opens its page.
    val expandable = minutes != null && !done && tickable
    val look = cardColours(c.colour ?: tileOf(c.emoji), c.heat)
    val shape = RoundedCornerShape(Space.xl)
    // Done settles the card back to 55 %: a critically damped spring (no bounce: nothing here carried momentum).
    val dim by animateFloatAsState(if (done) 0.55f else 1f, if (reduce) snap() else spring(dampingRatio = 1f, stiffness = 420f), label = "dim")
    Column(modifier.fillMaxWidth().padding(start = Space.sm, end = Space.gutter).testTag(RowPart.ROW)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CheckCircle(
            c, done, enabled = tickable, fill = look.a to look.b, tick = look.content,
            onClick = { if (done) onUntick(id) else onTick(id) },
            modifier = Modifier.testTag(RowPart.TICK).onGloballyPositioned { onCentre(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) },
        )
        val press = remember { MutableInteractionSource() }
        Box(
            // A stacked habit's card steps in; its check stays in the one check column.
            // The dim is one layer over card and shadow together (a dimmed card used to show its own shadow through itself).
            Modifier.weight(1f).padding(start = Space.lg * depth).pressScale(press)
                .graphicsLayer { alpha = dim }
                .shadow(8.dp, shape, ambientColor = look.b.copy(alpha = 0.18f), spotColor = look.b.copy(alpha = 0.30f))
                .clip(shape)
                .background(Brush.linearGradient(listOf(look.a, look.b), start = Offset.Zero, end = Offset.Infinite))
                // The one light, top-left: a soft highlight so the card reads as a lit object, not a flat slab.
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), endY = 120f))
                .combinedClickable(press, indication = null, onClickLabel = if (expandable) "Show Timer and Finish" else "Open") {
                    if (expandable) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onExpand(id) } else onOpen(id)
                },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = Space.md, top = Space.md, bottom = Space.md), verticalAlignment = Alignment.CenterVertically) {
                if (depth > 0) {
                    Text("↳", style = MaterialTheme.typography.labelLarge, color = look.content.copy(alpha = 0.8f), modifier = Modifier.clearAndSetSemantics {})
                    Spacer(Modifier.width(Space.sm))
                }
                Icon(
                    HabitIcons.vector(HabitIcons.forEmoji(c.emoji)), null, tint = look.content,
                    modifier = Modifier.size(28.dp).testTag(RowPart.EMOJI),
                )
                Spacer(Modifier.width(Space.md))
                // Two title lines and the progress line in one fixed box (in sp, so it grows with the font): a one-line title
                // sits centred, every row is the same height. Each line converts on its own: font scaling is non-linear
                // (Android 14+), so a summed sp came out shorter than two 22sp lines + one 18sp line at 1.5x.
                // Measured, in whole pixels, exactly as the column lays itself out.
                val titleStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, lineHeight = TITLE_LINE)
                val density = androidx.compose.ui.platform.LocalDensity.current
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                val box = remember(titleStyle, density) {
                    with(density) { (measurer.measure("Ag\nAg", titleStyle).size.height + Space.xs.roundToPx() + 18.sp.toDp().roundToPx()).toDp() }
                }
                Column(Modifier.weight(1f).heightIn(min = box), verticalArrangement = Arrangement.Center) {
                    Text(c.title, style = titleStyle, color = look.content, modifier = Modifier.testTag(RowPart.TITLE))
                    Spacer(Modifier.height(Space.xs))
                    Row(Modifier.height(with(density) { 18.sp.toDp() }), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            progressText(c, timer, now, done), maxLines = 1, overflow = TextOverflow.Ellipsis, color = look.content.copy(alpha = 0.82f),
                            // One line height for words and numbers, so a finished row is exactly as tall as an open one.
                            style = (if (done) MaterialTheme.typography.labelMedium else Glass.NumberText.copy(fontSize = 13.sp)).copy(lineHeight = 18.sp),
                            modifier = Modifier.testTag(RowPart.PROGRESS).weight(1f, fill = false),
                        )
                        if (c.streak > 0) Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = Space.md).testTag(RowPart.STREAK).semantics(mergeDescendants = true) { contentDescription = "${c.streak} in a row" },
                        ) {
                            Icon(TabIcons.Flame, null, tint = look.content, modifier = Modifier.size(13.dp).clearAndSetSemantics {})
                            Spacer(Modifier.width(Space.xs))
                            Text("${c.streak}", style = Glass.NumberText, color = look.content, modifier = Modifier.clearAndSetSemantics {})
                        }
                    }
                }
                CardMenu(c, look.content, done, onDayOff, onEdit, onOpenTimer, onOpen)
            }
        }
      }
      // Under the card, two calm buttons side by side (his reference): Timer opens the full-screen timer, Finish counts it done.
      AnimatedVisibility(
        expanded && expandable,
        enter = if (reduce) fadeIn() else expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(),
        exit = if (reduce) fadeOut() else shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(),
      ) {
        Row(Modifier.fillMaxWidth().padding(start = Space.touch + Space.lg * depth, top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ExpandedButton("Timer", TabIcons.Clock, filled = false, modifier = Modifier.weight(1f)) { onOpenTimer(id) }
            ExpandedButton("Finish", TabIcons.Check, filled = true, modifier = Modifier.weight(1f)) { onTick(id) }
        }
      }
    }
}

/** One of the two buttons under an opened timed card: Finish is the mint one, Timer the quiet glass one. */
@Composable
private fun ExpandedButton(label: String, icon: ImageVector, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Space.lg)
    Row(
        modifier.heightIn(min = Space.row).pressScale(press).clip(shape)
            .then(
                if (filled) Modifier.background(Brush.horizontalGradient(listOf(Glass.Aurora2, Glass.Aurora1)))
                else Modifier.background(Color.White.copy(alpha = 0.72f)).border(1.dp, Glass.Ink.copy(alpha = 0.10f), shape),
            )
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Glass.Ink, modifier = Modifier.size(22.dp).clearAndSetSemantics {})
        Spacer(Modifier.width(Space.sm))
        Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Glass.Ink, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** The card's menu: Take a day off (or take it back), Timer, Edit. It opens from its own button. */
@Composable
private fun CardMenu(c: HabitCard, tint: Color, done: Boolean, onDayOff: (String, Boolean) -> Unit, onEdit: (String) -> Unit, onOpenTimer: (String) -> Unit, onOpen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val press = remember { MutableInteractionSource() }
    val id = c.task.id
    val off = c.mark == Mark.FROZEN
    val actions = buildList<Pair<MenuItem, () -> Unit>> {
        if (c.mark != Mark.FUTURE && c.mark != Mark.BEFORE && (!done || off)) {
            add(MenuItem(TabIcons.Sofa, if (off) "Take the day off back" else "Take a day off") to { onDayOff(id, off) })
        }
        if (c.minutes != null && !done) add(MenuItem(TabIcons.Clock, "Timer") to { onOpenTimer(id) })
        // A timed card opens its buttons on a tap, so its page opens from here.
        if (c.minutes != null && !done) add(MenuItem(TabIcons.ArrowUpRight, "Open page") to { onOpen(id) })
        add(MenuItem(TabIcons.StickyNote, "Edit") to { onEdit(id) })
    }
    Box {
        Box(
            Modifier.size(Space.touch).pressScale(press).testTag(RowPart.MENU)
                .clickable(press, indication = null, role = Role.Button) { open = true }
                .semantics { contentDescription = "Habit menu: ${c.title}" },
            contentAlignment = Alignment.Center,
        ) { Icon(TabIcons.More, null, tint = tint.copy(alpha = 0.9f), modifier = Modifier.size(22.dp)) }
        if (open) GlassMenu(actions.map { it.first }, onPick = { i -> open = false; actions[i].second() }, onDismiss = { open = false })
    }
}

/**
 * The check outside the card: a ring that fills with the card's colour and a tick, arcs for a count habit, faint for a day
 * it cannot be ticked. A day off is drawn as done. A habit with minutes is ticked here like any other. 48 dp target, 28 dp drawn. [fill] and [tick] default to the app's mint and ink (the Namoz rows).
 */
@Composable
private fun CheckCircle(
    c: HabitCard, done: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    fill: Pair<Color, Color> = Glass.Aurora2 to Glass.Aurora1, tick: Color = Glass.Ink,
) {
    val press = remember { MutableInteractionSource() }
    val reduce = org.pagel.ai.ui.today.reducedMotion(LocalContext.current)
    val pop by animateFloatAsState(if (done) 1f else 0f, if (reduce) snap() else spring(dampingRatio = 0.8f, stiffness = 500f), label = "pop")
    Box(
        modifier.size(Space.touch).pressScale(press)
            .clickable(press, indication = null, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = "Done: ${c.title}"
                stateDescription = if (done) "Done" else if (c.times > 1) "${c.taps} of ${c.times}" else markWord(c.mark)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp).graphicsLayer { val k = 1f + 0.12f * pop * (1f - pop) * 4f; scaleX = k; scaleY = k }) {
            val r = size.minDimension / 2
            val line = 2.dp.toPx()
            val u = size.minDimension / 20f
            when {
                done -> drawCircle(Brush.linearGradient(listOf(fill.first, fill.second)), r)
                !enabled -> drawCircle(Glass.Ink.copy(alpha = 0.18f), r - line / 2, style = Stroke(1.5.dp.toPx()))
                // A count habit (checker #10: the dashed ring read as a spinner): the plain ring with one dot per tap inside.
                c.times > 1 -> {
                    drawCircle(Glass.Ink.copy(alpha = 0.55f), r - line / 2, style = Stroke(line))
                    val n = c.times.coerceAtMost(5)
                    val gap = 3.6f * u
                    val x0 = size.width / 2 - gap * (n - 1) / 2
                    for (i in 0 until n) drawCircle(if (i < c.taps) Glass.Primary else Glass.Ink.copy(alpha = 0.28f), 1.3f * u, Offset(x0 + i * gap, size.height / 2))
                }
                else -> drawCircle(Glass.Ink.copy(alpha = 0.55f), r - line / 2, style = Stroke(line))
            }
            if (done) {
                drawLine(tick, Offset(5.5f * u, 10.5f * u), Offset(8.5f * u, 13.5f * u), 2.2f * u, StrokeCap.Round)
                drawLine(tick, Offset(8.5f * u, 13.5f * u), Offset(14.5f * u, 6.5f * u), 2.2f * u, StrokeCap.Round)
            }
        }
    }
}

// ── Namoz ───────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The five prayers as one group (Boss 2026-10-28): the ring "3/5", one row each with today's time window, the prayer
 * whose time it is now marked. From 1 November; before that, the rows wait. Plain and calm: no streak shaming, no red.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NamozGroup(
    board: HabitBoard, today: LocalDate?, onTick: (String) -> Unit, onUntick: (String) -> Unit, onOpen: (String) -> Unit, onStart: () -> Unit, onMenu: () -> Unit, onPage: () -> Unit,
    centres: MutableMap<String, Offset>, modifier: Modifier = Modifier,
) {
    val prayers = board.namoz
    // The habit cards' own insets (HabitCardRow: Space.sm + the 48 dp check column), so every card shares one left edge.
    Pane(modifier.fillMaxWidth().padding(start = Space.sm + Space.touch, end = Space.gutter)) {
        Column(Modifier.fillMaxWidth().padding(Space.md)) {
            val started = Namoz.counts(board.selected.toString())
            // A small tonal button, never the heaviest thing on screen (the namaz row weight). It sits under the title at every
            // width: beside it, it took the whole row and "Namoz" broke one letter per line (1.5x and 320 dp).
            val StartPill: @Composable (Modifier) -> Unit = { m ->
                val press = remember { MutableInteractionSource() }
                Box(
                    m.heightIn(min = Space.touch).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onStart),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Start tracking", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiTile("mosque", 40.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f).heightIn(min = Space.touch).clickable(onClickLabel = "Open the Namoz page", onClick = onPage), verticalArrangement = Arrangement.Center) {
                    Text("Namoz", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Glass.Ink, modifier = Modifier.semantics { heading() })
                    Text(if (prayers.isEmpty() || !started) "From 1 November" else "Five a day", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                }
                if (prayers.isNotEmpty()) {
                    // No "0/5" before the first: nothing counts yet (checker 2026-10-30).
                    if (started) DayRing(board.namozDone, prayers.size, size = 40.dp)
                    GlassIconButton(TabIcons.More, "Namoz reminders", size = Space.touch, plain = true, onClick = onMenu)
                }
            }
            if (prayers.isEmpty()) {
                StartPill(Modifier.padding(start = 40.dp + Space.md))
                return@Column
            }
            Spacer(Modifier.height(Space.sm))
            val nowT = LocalTime.now(ZoneId.of("Asia/Tashkent"))
            // One calm tile, not five rows (design audit 2026-10-31: it sat like an ad in the list): the prayer whose time it is,
            // else the next one; all five live on the Namoz page. Before 1 November the rows wait on the first.
            for (c in listOfNotNull(shownPrayer(prayers, started, board.selected == today, nowT))) key(c.task.id) {
                val w = c.window
                val current = board.selected == today && w != null && isNow(w, nowT)
                val rowPress = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Space.touch).background(if (current) Glass.PrimarySoft.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable(rowPress, indication = null, onClickLabel = "Open") { onOpen(c.task.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckCircle(c, c.done, enabled = c.mark != Mark.BEFORE && c.mark != Mark.FUTURE, onClick = { if (c.done) onUntick(c.task.id) else onTick(c.task.id) },
                        modifier = Modifier.onGloballyPositioned { centres[c.task.id] = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) })
                    Icon(HabitIcons.vector(HabitIcons.forEmoji(c.emoji)), null, tint = Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(Space.sm))
                    // The name is one whole word ("Bomd / od" at 320 dp, checker 3 #4): when name and time do not fit on one
                    // line, the time moves under the name instead of breaking it.
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.weight(1f).padding(end = Space.sm), horizontalArrangement = Arrangement.SpaceBetween,
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, color = if (c.done) Glass.Muted else Glass.Ink, softWrap = false, maxLines = 1,
                            modifier = Modifier.padding(end = Space.sm))
                        Text(w?.label().orEmpty(), style = Glass.NumberText, color = Glass.Muted, softWrap = false, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * One prayer in its time of day: a quiet row, never a coloured card. Line icon, name, window, tick. It stands out with a mint
 * outline only while its time is on. A window that ended unticked stays neutral (no "missed", no red) and offers a quiet
 * "Prayed later". A tap on the row opens the Namoz page. Nothing here counts toward a chain, streak or badge.
 */
@Composable
private fun PrayerRow(
    c: HabitCard, isToday: Boolean, prayedLater: Boolean, intention: String, onTick: (String) -> Unit, onUntick: (String) -> Unit, onOpenPage: () -> Unit,
    onPrayedLater: (String) -> Unit, onCentre: (Offset) -> Unit,
) {
    val nowT = LocalTime.now(ZoneId.of("Asia/Tashkent"))
    val w = c.window
    val current = isToday && w != null && isNow(w, nowT) && !c.done
    val over = isToday && w != null && !c.done && !isNow(w, nowT) && nowT >= w.to && w.to > w.from
    val shape = RoundedCornerShape(Space.lg)
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(start = Space.sm, end = Space.gutter).testTag("prayer-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(
            c, c.done, enabled = c.mark != Mark.BEFORE && c.mark != Mark.FUTURE, onClick = { if (c.done) onUntick(c.task.id) else onTick(c.task.id) },
            modifier = Modifier.onGloballyPositioned { onCentre(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) },
        )
        Row(
            Modifier.weight(1f).heightIn(min = Space.row).pressScale(press).clip(shape)
                .background(Color.White.copy(alpha = if (current) 0.82f else 0.5f))
                .border(if (current) 1.5.dp else 1.dp, if (current) Glass.Primary else Color.White.copy(alpha = 0.8f), shape)
                .clickable(press, indication = null, role = Role.Button, onClickLabel = "Open the Namoz page") { onOpenPage() }
                .semantics { contentDescription = "Prayer: ${c.title}" }.padding(horizontal = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HabitIcons.vector(HabitIcons.forEmoji(c.emoji)), null, tint = Glass.Ink.copy(alpha = 0.78f), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f).padding(vertical = Space.sm)) {
                Text(c.title, style = MaterialTheme.typography.bodyLarge, color = if (c.done) Glass.Muted else Glass.Ink, maxLines = 1)
                Text(w?.label().orEmpty(), style = Glass.NumberText, color = Glass.Muted, maxLines = 1, softWrap = false)
                // His own plan, shown back to him quietly while the prayer's time is on.
                if (current && intention.isNotBlank()) Text(intention, style = MaterialTheme.typography.bodySmall, color = Glass.Muted, maxLines = 2)
            }
            if (over && !prayedLater) {
                val later = remember { MutableInteractionSource() }
                Text(
                    "Prayed later", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk,
                    modifier = Modifier.heightIn(min = Space.touch).pressScale(later).clickable(later, indication = null, role = Role.Button) { onPrayedLater(c.task.id) }
                        .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = Space.sm),
                )
            } else if (prayedLater && !c.done) {
                Text("Prayed later", style = MaterialTheme.typography.labelLarge, color = Glass.Muted, modifier = Modifier.padding(horizontal = Space.sm))
            }
        }
    }
}

private fun isNow(w: Namoz.Window, now: LocalTime) = if (w.to > w.from) now >= w.from && now < w.to else now >= w.from || now < w.to

/** The one prayer the Habits tab shows: the first before 1 November, else the one it is time for, else the next, else the last. */
private fun shownPrayer(prayers: List<HabitCard>, started: Boolean, isToday: Boolean, now: LocalTime): HabitCard? {
    if (prayers.isEmpty()) return null
    if (!started) return prayers.first()
    if (!isToday) return prayers.firstOrNull { !it.finished } ?: prayers.first()
    return prayers.firstOrNull { c -> c.window?.let { isNow(it, now) } == true } ?: prayers.firstOrNull { !it.finished } ?: prayers.last()
}

/** One switch per prayer, all off until Boss turns one on (Boss: reminders off by default). */
@Composable
private fun NamozSheet(prayers: List<HabitCard>, reminders: Set<String>, onToggle: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        org.pagel.ai.ui.glass.SheetTitle("Namoz reminders", null)
        Text("A plain note when a prayer's time comes: \"Peshin · 12:11\".", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted,
            modifier = Modifier.padding(start = Space.xl, end = Space.xl, bottom = Space.md))
        Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
            Column {
                for (c in prayers) {
                    val on = c.task.id in reminders
                    val press = remember { MutableInteractionSource() }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press).clickable(press, indication = null, role = Role.Switch) { onToggle(c.task.id, !on) }
                            .semantics(mergeDescendants = true) { contentDescription = "Remind me: ${c.title}"; stateDescription = if (on) "On" else "Off" }
                            .padding(horizontal = Space.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(HabitIcons.vector(HabitIcons.forEmoji(c.emoji)), null, tint = Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(Space.md))
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
                        Text(c.window?.label()?.take(5).orEmpty(), style = Glass.NumberText, color = Glass.Muted, modifier = Modifier.padding(end = Space.md))
                        Switch(on)
                    }
                }
            }
        }
        Spacer(Modifier.height(SheetBottom))
    }
}

@Composable
internal fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
    }
}

/** The day's last habit ticked (the web's AllDone): the party emoji, the count and the longest run. */
@Composable
private fun AllDone(count: Int, best: Int, modifier: Modifier) {
    Pane(modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.xxl, horizontal = Space.xl).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(HabitIcons.vector("sparkles"), null, tint = Glass.Primary, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(Space.sm))
            Text("All done today", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink)
            Spacer(Modifier.height(Space.xs))
            val habits = if (count == 1) "habit" else "habits"
            Text("$count $habits ticked." + if (best > 1) " Longest streak: $best in a row." else " Tomorrow starts a streak.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
        }
    }
}
