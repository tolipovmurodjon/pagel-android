package org.pagel.ai.ui.habits

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import kotlinx.coroutines.launch
import org.pagel.ai.data.habits.HabitBoard
import org.pagel.ai.data.habits.HabitCard
import org.pagel.ai.data.habits.Mark
import org.pagel.ai.data.habits.Namoz
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

@Composable
fun HabitsRoute(
    contentPadding: PaddingValues,
    reselect: Int,
    onOpenHabit: (String) -> Unit = {},
    onHistory: () -> Unit = {},
    vm: HabitsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var namozSheet by remember { mutableStateOf(false) }
    var celebrate by remember { mutableStateOf(0) }
    var finished by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val grants = rememberReminderGrants(vm)
    LifecycleResumeEffect(Unit) {
        vm.replan()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) { vm.celebrate.collect { celebrate++ } }
    LaunchedEffect(Unit) { var n = 0; vm.timerDone.collect { finished = it to ++n } }

    HabitsScreen(
        ui, contentPadding, reselect, celebrate = celebrate, finished = finished,
        onTick = vm::tick, onUntick = vm::untick, onOpen = onOpenHabit,
        onSelectDay = vm::selectDay, onSelectPart = vm::selectPart, onTimer = vm::toggleTimer, onHistory = onHistory,
        onStartNamoz = vm::startNamoz, onNamozMenu = { namozSheet = true },
    )
    if (namozSheet) NamozSheet(ui.board?.namoz.orEmpty(), ui.reminders, onToggle = grants.remind) { namozSheet = false }
}

/**
 * Habits 2.0 (round D; Boss 2026-10-29: "minimalistic… the main screen = the main task, no friction, no mental load"):
 * on top the ALL-HABITS chain (a day fills only when every habit that day is done, a partial day is a thin ring, full
 * days in a row join into one bar), Morning · Afternoon · Evening pills, then one quiet row per habit with at most five
 * things: the check · the emoji · the title · the progress · 🔥 the streak. Tapping a row opens the habit's own page,
 * where the reminder, days, emoji, freeze, stacking and history live. The five prayers are one Namoz group.
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
    onTimer: (String, Int) -> Unit = { _, _ -> },
    onHistory: () -> Unit = {},
    onStartNamoz: () -> Unit = {},
    onNamozMenu: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val bursts = remember { mutableStateListOf<Burst>() }
    var nextBurst by remember { mutableLongStateOf(0L) }
    var rootAt by remember { mutableStateOf(Offset.Zero) }
    val centres = remember { mutableStateMapOf<String, Offset>() }
    val list = rememberLazyListState()
    val board = ui.board
    val today = runCatching { LocalDate.parse(ui.today) }.getOrNull()

    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    fun burstAt(at: Offset) {
        scope.launch(Dispatchers.Default) { Ding.play(ctx.applicationContext) }
        bursts += Burst(nextBurst++, at - rootAt)
    }
    LaunchedEffect(celebrate) { if (celebrate > 0) burstAt(rootAt + Offset(540f, 600f)) }
    LaunchedEffect(finished) { finished?.first?.let { id -> centres[id]?.let(::burstAt) } }
    fun tick(id: String) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        centres[id]?.let(::burstAt)
        onTick(id)
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootAt = it.positionInRoot() }) {
        // One spacing rhythm (Boss 2026-10-29: "the spacing is different for all"): every block and every habit row sits
        // Space.paneGap apart, every row has the same height (HabitsScreenshotTest.rowsHaveOneHeightAndOneGap).
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
            item(key = "head", contentType = "head") { Header(ui, board, onHistory) }
            if (ui.loading || board == null) {
                item(key = "loading", contentType = "note") { Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) { SkeletonRows(3, Modifier.padding(vertical = Space.sm)) } }
                return@LazyColumn
            }
            item(key = "strip", contentType = "strip") { WeekStrip(board, onSelectDay) }
            item(key = "parts", contentType = "parts") { PartPills(ui.part, board, onSelectPart) }
            item(key = "namoz", contentType = "namoz") {
                NamozGroup(board, today, onTick = ::tick, onUntick = onUntick, onOpen = onOpen, onStart = onStartNamoz, onMenu = onNamozMenu, centres = centres, modifier = Modifier.animateItem())
            }
            val cards = board.inPart(ui.part)
            if (cards.isEmpty()) item(key = "empty-${ui.part}", contentType = "note") { EmptyPart(ui.part, board.total) }
            items(cards, key = { it.task.id }, contentType = { "card" }) { c ->
                HabitCardRow(
                    c, finishing = c.task.id in ui.finishing, timer = ui.timers[c.task.id], now = ui.now,
                    onTick = ::tick, onUntick = onUntick, onOpen = onOpen, onTimer = onTimer,
                    onCentre = { centres[c.task.id] = it }, modifier = Modifier.animateItem(),
                )
            }
            if (ui.day.allDone && ui.finishing.isEmpty() && board.selected == board.today) item(key = "all-done", contentType = "all-done") {
                AllDone(ui.day.doneCount, ui.day.best, Modifier.animateItem())
            }
        }
        // Browsing another day: one pill back to today (Boss's reference "› Today").
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
private fun Header(ui: HabitsUi, board: HabitBoard?, onHistory: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = Space.textEdge, end = Space.gutter, top = Space.headerTop), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Habits", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            // One line, always there, so the strip under it never moves (RULES.md Layout 1).
            val line = when {
                board == null -> "Every day, the same few things"
                board.selected == board.today -> board.selected.format(DAY_TITLE)
                else -> board.selected.format(DAY_TITLE) + if (board.selected.isAfter(board.today)) " · ahead" else " · looking back"
            }
            Text(line, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (board != null) DayRing(board.doneCount, board.total, Modifier.padding(end = Space.sm))
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

// ── The week as a chain ─────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The ALL-HABITS chain, seven days, Monday first (Boss 2026-10-29: "when I complete ALL habits, it marks that day"). A
 * day fills mint only when every habit due that day is done; full days side by side join into one solid bar of the
 * same mint (the chain, like his reference's 26-27); a partial day is a thin ring that shows how much (2 of 4 = half a
 * ring), never a fill; a miss is a plain number with a gap; today is a ring; days ahead are faint. The prayers are not
 * in it (Namoz is its own group with its own ring). Tap a day to look at it; swipe for the week before or after.
 */
@Composable
private fun WeekStrip(board: HabitBoard, onSelect: (LocalDate?) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // The week follows the finger 1:1 and springs back; 80 px or a flick (> 0.11 px/ms) moves a week (apple-design §2, §5).
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    Pane(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter)
            .pointerInput(board.selected) {
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        val dx = offset.value
                        val step = when {
                            dx > 80f || (v > 110f && dx > 0f) -> -7L
                            dx < -80f || (v < -110f && dx < 0f) -> 7L
                            else -> 0L
                        }
                        val thisMonday = board.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        val to = board.selected.plusDays(step)
                        val ok = step != 0L && !to.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).isAfter(thisMonday)
                        scope.launch {
                            if (ok) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(if (to.isAfter(board.today)) null else to)
                                offset.snapTo(0f)
                            } else offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) } },
                ) { change, dx ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch { offset.snapTo(offset.value + dx) }
                }
            },
    ) {
        Column(Modifier.fillMaxWidth().clipToBounds().graphicsLayer { translationX = offset.value }.padding(vertical = Space.md)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm)) {
                // At 1.5x font "MON" wrapped to two lines: one letter then (Apple's narrow weekday).
                val big = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
                for (d in board.week) Text(
                    d.date.dayOfWeek.getDisplayName(if (big) TextStyle.NARROW else TextStyle.SHORT, Locale.UK).uppercase(), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (d.date == board.selected) Glass.Ink else Glass.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(Space.sm))
            ChainRow(
                board.week.map { it.date to it.clear.toMark(it, board.today) }, board.today, board.selected,
                counts = board.week.map { it.held to it.due }, bleedIn = board.chainIn, bleedOut = board.chainOut,
                onSelect = { d -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(if (d == board.today) null else d) },
                modifier = Modifier.padding(horizontal = Space.sm),
            )
        }
    }
}

private fun Clear.toMark(d: StripDay, today: LocalDate): Mark = when (this) {
    // Only a whole day holds the chain (checker #2: a partial day with one freeze used to link it); a whole day with a
    // freeze in it is ice.
    Clear.ALL -> if (d.frozen) Mark.FROZEN else Mark.DONE
    Clear.SOME -> Mark.PARTIAL
    Clear.NONE -> if (d.date == today) Mark.TODAY else Mark.MISSED
    Clear.EMPTY -> Mark.OFF
    Clear.FUTURE -> Mark.FUTURE
}

/**
 * A row of day circles with the chain drawn behind them (the strip, the month calendars and the Namoz row use it).
 * [counts] = (held, due) per day: a PARTIAL day's thin ring shows held / due; without counts the ring is whole.
 */
@Composable
internal fun ChainRow(
    days: List<Pair<LocalDate, Mark>>, today: LocalDate, chosen: LocalDate?, onSelect: ((LocalDate) -> Unit)?, modifier: Modifier = Modifier,
    circle: androidx.compose.ui.unit.Dp = 38.dp, slots: Int = days.size, startSlot: Int = 0, counts: List<Pair<Int, Int>>? = null,
    bleedIn: Boolean = false, bleedOut: Boolean = false,
) {
    val marks = days.map { it.second }
    val joined = org.pagel.ai.data.habits.links(marks)
    Box(modifier.fillMaxWidth().height(circle + Space.sm)) {
        // The chain: one solid bar, the circles' own mint, from centre to centre of every pair of linked days.
        Canvas(Modifier.fillMaxWidth().height(circle + Space.sm)) {
            val cell = size.width / slots
            val h = circle.toPx() * CHAIN_BAR
            val y = size.height / 2 - Space.xs.toPx() / 2
            for (i in joined.indices) if (joined[i]) {
                val x0 = (startSlot + i + 0.5f) * cell
                val ice = marks[i] == Mark.FROZEN || marks.getOrNull(i + 1) == Mark.FROZEN
                drawRect(if (ice) ICE else CHAIN, topLeft = Offset(x0, y - h / 2), size = Size(cell, h))
            }
            // The chain goes on into last week / next week: the bar runs off the edge (Boss's reference, its 26).
            val edge = Space.sm.toPx()
            if (bleedIn && marks.firstOrNull() == Mark.DONE) drawRect(CHAIN, topLeft = Offset(-edge, y - h / 2), size = Size(cell * (startSlot + 0.5f) + edge, h))
            if (bleedOut && marks.lastOrNull() == Mark.DONE) {
                val x = (startSlot + marks.size - 0.5f) * cell
                drawRect(CHAIN, topLeft = Offset(x, y - h / 2), size = Size(size.width - x + edge, h))
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

/** A frozen day's ring: ice fill is 1.27:1 alone, so an ice-blue outline carries it (4.06:1). */
internal val ICE_LINE = Color(0xFF3B82C4)

/** The bar's height as a share of the circle (Boss's reference: about half). */
internal const val CHAIN_BAR = 0.5f

internal val ICE = Color(0xFFCDE8FB)

internal fun markWord(m: Mark) = when (m) {
    Mark.DONE -> "Done"; Mark.PARTIAL -> "Some"; Mark.FROZEN -> "Frozen"; Mark.MISSED -> "Missed"; Mark.OFF -> "Not its day"
    Mark.TODAY -> "Today, not yet"; Mark.FUTURE -> "Ahead"; Mark.BEFORE -> "Not counted yet"
}

@Composable
private fun DayDot(n: Int, m: Mark, isToday: Boolean, isSelected: Boolean, circle: androidx.compose.ui.unit.Dp, progress: Float? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(circle), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(circle)) {
                val r = size.minDimension / 2
                val w = 2.5.dp.toPx()
                // Today is a soft filled disc (the ref's grey 28), so it never reads as a partial ring.
                if (isToday && m != Mark.DONE && m != Mark.FROZEN) drawCircle(Glass.PrimarySoft, r)
                when (m) {
                    Mark.DONE -> drawCircle(CHAIN, r)
                    Mark.FROZEN -> { drawCircle(ICE, r); drawCircle(ICE_LINE, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx())) }
                    // A partial day: a thin ring, no fill; the mint arc shows how much of the day was held.
                    Mark.PARTIAL -> {
                        drawCircle(Glass.Ink.copy(alpha = 0.10f), r - w / 2, style = Stroke(w))
                        drawArc(
                            Glass.Primary, -90f, 360f * (progress ?: 1f).coerceIn(0.08f, 1f), false,
                            topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
                        )
                    }
                    else -> Unit
                }
            }
            // A frozen day keeps a small ❄ (the only trace of a freeze on the page body, Boss 2026-10-29).
            if (m == Mark.FROZEN) Text("❄", fontSize = 9.sp, modifier = Modifier.align(Alignment.TopEnd).clearAndSetSemantics {})
            val faint = m == Mark.FUTURE || m == Mark.OFF || m == Mark.BEFORE
            Text(
                "$n", style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (m == Mark.DONE || isToday) FontWeight.Bold else FontWeight.Medium),
                color = if (faint) Glass.Muted.copy(alpha = 0.6f) else Glass.Ink,
            )
        }
        // The selected day's mark: a short mint line under it; it keeps its slot on every day (no jump).
        Box(Modifier.padding(top = Space.xs).size(width = 14.dp, height = 3.dp).background(if (isSelected) Glass.Primary else Color.Transparent, CircleShape))
    }
}

// ── Parts of the day ────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PartPills(part: Part, board: HabitBoard, onSelect: (Part) -> Unit) {
    val haptics = LocalHapticFeedback.current
    // At 1.5x font the word needs the whole pill: the emoji steps aside ("Afternoon" was cut to "Afte…").
    val big = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        for ((p, emoji) in listOf(Part.MORNING to "sunrise", Part.AFTERNOON to "sun", Part.EVENING to "moon")) {
            val on = p == part
            val left = board.cards.count { (it.part == p) && !it.done }
            val press = remember { MutableInteractionSource() }
            Row(
                Modifier.weight(1f).heightIn(min = Space.touch).pressScale(press).clip(CircleShape)
                    .background(if (on) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.34f))
                    .border(if (on) 1.5.dp else 1.dp, if (on) Glass.Primary else Color.White.copy(alpha = 0.8f), CircleShape)
                    .clickable(press, indication = null, role = Role.Tab) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(p) }
                    .semantics(mergeDescendants = true) { contentDescription = p.label; selected = on; if (left > 0) stateDescription = "$left left" }
                    .padding(horizontal = Space.xs),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                if (!big) {
                    Image(painterResource(Emoji.res(emoji)), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.xs))
                }
                Text(p.label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    color = if (on) Glass.Ink else Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyPart(part: Part, total: Int) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Row(Modifier.fillMaxWidth().padding(Space.xl), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(Emoji.res("seedling")), null, Modifier.size(40.dp))
            Spacer(Modifier.width(Space.md))
            Column {
                Text(if (total == 0) "No habits yet" else "Nothing this ${part.label.lowercase()}", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                Text(if (total == 0) "Give a task a repeat; it lives here." else "Habits with no time show in every part.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
            }
        }
    }
}

// ── One habit ───────────────────────────────────────────────────────────────────────────────────────────────────────

/** The tile colour a habit wears, by what it is about (brain rules/gradient-project-icons: ten named OKLCH pairs). */
internal fun tileOf(emoji: String): String = when (emoji) {
    "food", "salad", "apple", "chicken", "milk", "coffee" -> "amber"
    "water", "shower", "ice", "snowflake" -> "sky"
    "gym", "biceps", "shoe", "walk", "footprints" -> "coral"
    "lotus", "moon", "bed", "sleeping", "night", "phone_off" -> "violet"
    "book", "books", "brain", "laptop", "memo", "headphone", "bulb" -> "blue"
    "inbox", "calendar", "broom", "money", "alarm" -> "teal"
    "scale", "ruler", "pill", "toothbrush" -> "pink"
    "sunrise", "sun", "sun_cloud", "sunset", "mosque" -> "mint"
    else -> "mint"
}

@Composable
internal fun EmojiTile(emoji: String, size: androidx.compose.ui.unit.Dp, quiet: Boolean = false, colour: String? = null) {
    // The habit's own colour when Boss picked one (HabitLook), else one by what it is about.
    val (a, b) = TileColors.stops("", colour ?: tileOf(emoji))
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
            // A glass rim: one thin light edge, never a dark outline.
            .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(size * 0.3f))
            .background(if (quiet) Brush.linearGradient(listOf(Color(0xFFEDEFF3), Color(0xFFDCE0E7))) else Brush.linearGradient(listOf(a, b), start = Offset.Zero, end = Offset.Infinite))
            // The one light, top-left: a soft inner highlight so the tile reads as a lit object.
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), endY = 40f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(Emoji.res(emoji)), null, Modifier.size(size * 0.68f),
            colorFilter = if (quiet) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
            alpha = if (quiet) 0.7f else 1f,
        )
    }
}

/**
 * The row's progress, one short line: "Finished", "0 / 10 min" (a timer's "3:20 / 10 min"), "2 / 3", a prayer's
 * window, the time of a timed habit, or "0 / 1". Never the repeat rule: that lives on the habit's page.
 */
internal fun progressText(c: HabitCard, timer: HabitTimer?, now: Long, done: Boolean = c.done): String = when {
    done -> "Finished"
    c.mark == Mark.FROZEN -> "Frozen · chain holds"
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

/** The tags a row's parts carry, so a test can count them (Boss 2026-10-29: at most 5 things per row). */
internal object RowPart {
    const val ROW = "habit-row"
    const val TICK = "row-tick"
    const val EMOJI = "row-emoji"
    const val TITLE = "row-title"
    const val PROGRESS = "row-progress"
    const val STREAK = "row-streak"
}

/**
 * One habit, minimal (Boss 2026-10-29: "move unnecessary mental load to the habit's own page"): the check · the emoji
 * · the title · the progress · 🔥 the streak, nothing else. A tap opens the habit's page (reminder, days, emoji,
 * freeze, history live there). A habit with minutes has a play ring for a check: tap it to run the clock, which ticks
 * the habit at the end. "Never miss twice" is only a tiny amber dot on the emoji's corner. Every row is the same
 * height: one title line, one progress line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HabitCardRow(
    c: HabitCard, finishing: Boolean, timer: HabitTimer?, now: Long,
    onTick: (String) -> Unit, onUntick: (String) -> Unit, onOpen: (String) -> Unit,
    onTimer: (String, Int) -> Unit, onCentre: (Offset) -> Unit, modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val done = c.done || finishing
    val id = c.task.id
    val tickable = c.mark != Mark.FUTURE && c.mark != Mark.BEFORE && c.mark != Mark.OFF
    val minutes = c.minutes
    val timed = minutes != null && !done && tickable && c.mark != Mark.FROZEN
    Row(modifier.fillMaxWidth().padding(start = Space.sm, end = Space.gutter).testTag(RowPart.ROW), verticalAlignment = Alignment.CenterVertically) {
        CheckCircle(
            c, done, enabled = tickable, timer = if (timed) timer else null, now = now, timed = timed,
            onClick = {
                when {
                    done -> onUntick(id)
                    timed && minutes != null -> { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onTimer(id, minutes) }
                    else -> onTick(id)
                }
            },
            modifier = Modifier.testTag(RowPart.TICK).onGloballyPositioned { onCentre(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) },
        )
        val press = remember { MutableInteractionSource() }
        Pane(
            Modifier.weight(1f).pressScale(press).combinedClickable(
                press, indication = null, onClickLabel = "Open",
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onOpen(id) },
            ) { onOpen(id) },
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.testTag(RowPart.EMOJI)) {
                    EmojiTile(c.emoji, 40.dp, quiet = done, colour = c.colour)
                    // Never miss twice (Atomic Habits): only a small amber dot, no words on the row.
                    if (c.neverMissTwice && !done) Box(
                        Modifier.align(Alignment.TopEnd).size(10.dp).background(Color(0xFFF59E0B), CircleShape).border(1.5.dp, Color(0xFFB45309), CircleShape)
                            .semantics { contentDescription = "Missed yesterday: never miss twice" },
                    )
                }
                Spacer(Modifier.width(Space.md))
                // Two title lines and the progress line in one fixed box (in sp, so it grows with the font): a one-line title
                // sits centred, every row is the same height (Boss's ref: "Audio kitob eshitish" on 2 lines, same card).
                val box = with(androidx.compose.ui.platform.LocalDensity.current) { (TITLE_LINE.value * 2 + 18f).sp.toDp() } + Space.xs
                Column(Modifier.weight(1f).height(box), verticalArrangement = Arrangement.Center) {
                    Text(
                        c.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = TITLE_LINE), color = if (done) Glass.Muted else Glass.Ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(RowPart.TITLE),
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        progressText(c, timer, now, done), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Glass.Muted,
                        // One line height for words and numbers, so a finished row is exactly as tall as an open one.
                        style = (if (done || c.mark == Mark.FROZEN) MaterialTheme.typography.labelMedium else Glass.NumberText.copy(fontSize = 13.sp)).copy(lineHeight = 18.sp),
                        // A fixed line box (it scales with the font): words and the number face have different metrics.
                        modifier = Modifier.height(with(androidx.compose.ui.platform.LocalDensity.current) { 18.sp.toDp() }).testTag(RowPart.PROGRESS),
                    )
                }
                if (c.streak > 0) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = Space.sm).testTag(RowPart.STREAK).semantics(mergeDescendants = true) { contentDescription = "${c.streak} in a row" },
                ) {
                    Text("🔥", style = Glass.NumberText, modifier = Modifier.clearAndSetSemantics {})
                    Text("${c.streak}", style = Glass.NumberText, color = Color(0xFFC2410C), modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
}

/**
 * The check outside the card: a ring that fills mint with an ink tick, arcs for a count habit, ice for a frozen day,
 * faint for a day it cannot be ticked. A habit with minutes shows a play mark and its clock's ring instead (tap = run
 * or pause). 48 dp target, 28 dp drawn.
 */
@Composable
private fun CheckCircle(
    c: HabitCard, done: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    timer: HabitTimer? = null, now: Long = 0, timed: Boolean = false,
) {
    val press = remember { MutableInteractionSource() }
    val pop by animateFloatAsState(if (done) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 500f), label = "pop")
    val running = timer?.running == true
    val clock = if (timed) ((timer?.spent(now) ?: 0L) / ((c.minutes ?: 1) * 60_000f)).coerceIn(0f, 1f) else 0f
    Box(
        modifier.size(Space.touch).pressScale(press)
            .clickable(press, indication = null, enabled = enabled, role = if (timed) Role.Button else Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = when { !timed -> "Done: ${c.title}"; running -> "Pause: ${c.title}"; else -> "Start: ${c.title}" }
                stateDescription = if (done) "Done" else if (c.times > 1) "${c.taps} of ${c.times}" else markWord(c.mark)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp).graphicsLayer { val k = 1f + 0.12f * pop * (1f - pop) * 4f; scaleX = k; scaleY = k }) {
            val r = size.minDimension / 2
            val line = 2.dp.toPx()
            val u = size.minDimension / 20f
            when {
                done -> drawCircle(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), r)
                c.mark == Mark.FROZEN -> drawCircle(ICE, r)
                !enabled -> drawCircle(Glass.Ink.copy(alpha = 0.18f), r - line / 2, style = Stroke(1.5.dp.toPx()))
                timed -> {
                    drawCircle(Glass.Ink.copy(alpha = 0.18f), r - line / 2, style = Stroke(line))
                    drawArc(Glass.Primary, -90f, 360f * clock, false, topLeft = Offset(line / 2, line / 2), size = Size(size.width - line, size.height - line), style = Stroke(line, cap = StrokeCap.Round))
                    if (running) {
                        drawRoundRect(Glass.Ink, Offset(7f * u, 6.5f * u), Size(2.2f * u, 7f * u), CornerRadius(u))
                        drawRoundRect(Glass.Ink, Offset(10.8f * u, 6.5f * u), Size(2.2f * u, 7f * u), CornerRadius(u))
                    } else {
                        val tri = androidx.compose.ui.graphics.Path().apply { moveTo(8f * u, 6f * u); lineTo(14.5f * u, 10f * u); lineTo(8f * u, 14f * u); close() }
                        drawPath(tri, Glass.Ink)
                    }
                }
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
                drawLine(Glass.Ink, Offset(5.5f * u, 10.5f * u), Offset(8.5f * u, 13.5f * u), 2.2f * u, StrokeCap.Round)
                drawLine(Glass.Ink, Offset(8.5f * u, 13.5f * u), Offset(14.5f * u, 6.5f * u), 2.2f * u, StrokeCap.Round)
            }
        }
    }
}

// ── Namoz ───────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The five prayers as one group (Boss 2026-10-28): the ring "3/5", one row each with today's time window, the prayer
 * whose time it is now marked. From 1 November; before that, the rows wait. Plain and calm: no streak shaming, no red.
 */
@Composable
private fun NamozGroup(
    board: HabitBoard, today: LocalDate?, onTick: (String) -> Unit, onUntick: (String) -> Unit, onOpen: (String) -> Unit, onStart: () -> Unit, onMenu: () -> Unit,
    centres: MutableMap<String, Offset>, modifier: Modifier = Modifier,
) {
    val prayers = board.namoz
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val started = prayers.isNotEmpty() && Namoz.counts(board.selected.toString())
    val nowT = LocalTime.now(ZoneId.of("Asia/Tashkent"))
    // The one line under "Namoz": the prayer that comes next today, or how many on another day.
    val line = when {
        prayers.isEmpty() || !started -> "From 1 November"
        board.namozDone == prayers.size -> "All five today"
        board.selected != today -> "${board.namozDone} of ${prayers.size}"
        else -> prayers.firstOrNull { !it.done && it.window != null && (it.window.to > nowT || it.window.to < it.window.from) }
            ?.let { "${it.title} · ${it.window!!.label().take(5)}" } ?: "${board.namozDone} of ${prayers.size}"
    }
    Row(modifier.fillMaxWidth().padding(start = Space.sm, end = Space.gutter), verticalAlignment = Alignment.Top) {
        // The check's slot holds the day's prayer ring, so the group lines up with the habit rows.
        Box(Modifier.size(Space.touch).padding(top = Space.md), contentAlignment = Alignment.Center) {
            if (started) DayRing(board.namozDone, prayers.size, size = 36.dp)
        }
        val press = remember { MutableInteractionSource() }
        Pane(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().pressScale(press)
                        .clickable(press, indication = null, enabled = prayers.isNotEmpty(), onClickLabel = if (open) "Close" else "Open") { open = !open }
                        .semantics(mergeDescendants = true) { contentDescription = "Namoz"; stateDescription = line }
                        .padding(horizontal = Space.md, vertical = Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EmojiTile("mosque", 40.dp)
                    Spacer(Modifier.width(Space.md))
                    Column(Modifier.weight(1f)) {
                        Text("Namoz", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Glass.Ink, maxLines = 1)
                        Spacer(Modifier.height(Space.xs))
                        Text(line, style = MaterialTheme.typography.labelMedium, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (prayers.isEmpty()) {
                        val start = remember { MutableInteractionSource() }
                        Text(
                            "Start tracking", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, maxLines = 1,
                            modifier = Modifier.heightIn(min = Space.touch).pressScale(start).clip(CircleShape).background(Glass.PrimarySoft)
                                .clickable(start, indication = null, role = Role.Button, onClick = onStart).padding(horizontal = Space.md, vertical = Space.md),
                        )
                    } else {
                        val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium), label = "chevron")
                        Icon(TabIcons.ChevronDown, null, tint = Glass.Muted, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
                    }
                }
                if (open && prayers.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(start = Space.xs, end = Space.md, bottom = Space.sm)) {
                    for (c in prayers) key(c.task.id) {
                        val w = c.window
                        val current = board.selected == today && w != null && (if (w.to > w.from) nowT >= w.from && nowT < w.to else nowT >= w.from || nowT < w.to)
                        val rowPress = remember { MutableInteractionSource() }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Space.touch).background(if (current) Glass.PrimarySoft.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(14.dp))
                                .clickable(rowPress, indication = null, onClickLabel = "Open") { onOpen(c.task.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CheckCircle(c, c.done, enabled = c.mark != Mark.BEFORE && c.mark != Mark.FUTURE, onClick = { if (c.done) onUntick(c.task.id) else onTick(c.task.id) },
                                modifier = Modifier.onGloballyPositioned { centres[c.task.id] = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) })
                            Image(painterResource(Emoji.res(c.emoji)), null, Modifier.size(22.dp))
                            Spacer(Modifier.width(Space.sm))
                            Text(c.title, style = MaterialTheme.typography.bodyLarge, color = if (c.done) Glass.Muted else Glass.Ink, modifier = Modifier.weight(1f))
                            Text(w?.label().orEmpty(), style = Glass.NumberText, color = Glass.Muted)
                        }
                    }
                    MenuRow(TabIcons.Bell, "Namoz reminders", onMenu)
                }
            }
        }
    }
}

/** One switch per prayer, all off until Boss turns one on (Boss: reminders off by default). */
@Composable
private fun NamozSheet(prayers: List<HabitCard>, reminders: Set<String>, onToggle: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        org.pagel.ai.ui.glass.SheetTitle("Namoz reminders", null)
        Text("A plain note when a prayer's time comes: \"Peshin vaqti kirdi · 12:11\".", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted,
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
                        Image(painterResource(Emoji.res(c.emoji)), null, Modifier.size(22.dp))
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
            Image(painterResource(Emoji.res("party")), null, Modifier.size(56.dp))
            Spacer(Modifier.height(Space.sm))
            Text("All done today", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink)
            Spacer(Modifier.height(Space.xs))
            val habits = if (count == 1) "habit" else "habits"
            Text("$count $habits ticked." + if (best > 1) " Longest streak: $best in a row." else " Tomorrow starts a streak.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
        }
    }
}
