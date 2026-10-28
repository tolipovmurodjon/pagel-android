package org.pagel.ai.ui.habits

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
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
import org.pagel.ai.reminders.HabitAlarms
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
    val ctx = LocalContext.current
    var bell by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var namozSheet by remember { mutableStateOf(false) }
    var celebrate by remember { mutableStateOf(0) }
    var finished by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var exact by remember { mutableStateOf(true) }
    var notify by remember { mutableStateOf(true) }
    val alarms = remember { ctx.getSystemService(AlarmManager::class.java) }
    fun readGrants() {
        exact = alarms == null || HabitAlarms.canExact(alarms)
        notify = notificationsOn(ctx)
    }
    LifecycleResumeEffect(Unit) {
        readGrants()
        vm.replan()
        onPauseOrDispose {}
    }
    LaunchedEffect(bell) { if (bell != null) readGrants() }
    LaunchedEffect(Unit) { vm.celebrate.collect { celebrate++ } }
    LaunchedEffect(Unit) { var n = 0; vm.timerDone.collect { finished = it to ++n } }

    fun askExact() = vm.askExactOnce(needed = alarms != null && !HabitAlarms.canExact(alarms)) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) }
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> readGrants(); if (granted) askExact() }
    fun remind(id: String, on: Boolean) {
        vm.setReminder(id, on)
        if (!on) return
        val needs = android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else askExact()
    }

    HabitsScreen(
        ui, contentPadding, reselect, celebrate = celebrate, finished = finished,
        onTick = vm::tick, onUntick = vm::untick, onBell = { bell = it }, onOpen = onOpenHabit, onMenu = { menu = it },
        onSelectDay = vm::selectDay, onSelectPart = vm::selectPart, onTimer = vm::toggleTimer, onHistory = onHistory,
        onStartNamoz = vm::startNamoz, onNamozMenu = { namozSheet = true },
    )
    bell?.let { id ->
        ui.byId[id]?.let { t ->
            ReminderSheet(
                t, on = id in ui.reminders, exact = exact, notify = notify, onToggle = { remind(id, it) }, onTime = { vm.setTime(id, it) },
                onFixNotify = { runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) } },
                onFixExact = { runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) } },
            ) { bell = null }
        }
    }
    menu?.let { id ->
        val card = ui.board?.let { it.cards + it.namoz }?.firstOrNull { it.task.id == id }
        if (card == null) menu = null
        else HabitMenuSheet(
            card, freezesLeft = ui.board?.freezesLeft ?: 0,
            onDetail = { menu = null; onOpenHabit(id) }, onReminder = { menu = null; bell = id },
            onFreeze = { menu = null; if (card.mark == Mark.FROZEN) vm.unfreeze(id) else vm.freeze(id) },
            onUndo = { menu = null; vm.untick(id) }, onResetTimer = { menu = null; vm.resetTimer(id) },
        ) { menu = null }
    }
    if (namozSheet) NamozSheet(ui.board?.namoz.orEmpty(), ui.reminders, onToggle = { id, on -> remind(id, on) }) { namozSheet = false }
}

/**
 * Habits 2.0 (round D; Boss: "cleaner, better, like a chain"): the week as a chain on top (held days fuse into one mint
 * bar, a miss is a gap), Morning · Afternoon · Evening pills that start on the clock's part, then one bold card per
 * habit with its own gradient tile and Fluent emoji, its progress, a "•••" menu and the time tab on its edge. The
 * check sits outside the card; a done card goes quiet with "✓ Finished". The five prayers are one Namoz group.
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
    onBell: (String) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onMenu: (String) -> Unit = {},
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
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
            item(key = "head", contentType = "head") { Header(ui, board, onHistory) }
            if (ui.loading || board == null) {
                item(key = "loading", contentType = "note") { Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) { SkeletonRows(3, Modifier.padding(vertical = Space.sm)) } }
                return@LazyColumn
            }
            item(key = "strip", contentType = "strip") { WeekStrip(board, onSelectDay) }
            item(key = "parts", contentType = "parts") { PartPills(ui.part, board, onSelectPart) }
            item(key = "namoz", contentType = "namoz") {
                NamozGroup(board, today, onTick = ::tick, onUntick = onUntick, onStart = onStartNamoz, onMenu = onNamozMenu, centres = centres, modifier = Modifier.animateItem())
            }
            val cards = board.inPart(ui.part)
            if (cards.isEmpty()) item(key = "empty-${ui.part}", contentType = "note") { EmptyPart(ui.part, board.total) }
            items(cards, key = { it.task.id }, contentType = { "card" }) { c ->
                HabitCardRow(
                    c, finishing = c.task.id in ui.finishing, reminder = c.task.id in ui.reminders, timer = ui.timers[c.task.id], now = ui.now,
                    onTick = ::tick, onUntick = onUntick, onOpen = onOpen, onMenu = onMenu, onBell = onBell, onTimer = onTimer,
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
 * Seven days, Monday first. A day where every habit held is a filled mint circle, and held days side by side fuse into
 * one bar (the chain); a day with some is a soft ring; a miss is a plain number with a gap around it; today is a mint
 * ring; days ahead are faint. Tap a day to look at it; swipe for the week before or after.
 */
@Composable
private fun WeekStrip(board: HabitBoard, onSelect: (LocalDate?) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var drag by remember { mutableStateOf(0f) }
    Pane(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter)
            .pointerInput(board.selected) {
                detectHorizontalDragGestures(onDragEnd = {
                    val step = when { drag > 80f -> -7L; drag < -80f -> 7L; else -> 0L }
                    drag = 0f
                    if (step != 0L) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); val to = board.selected.plusDays(step); onSelect(if (to.isAfter(board.today)) board.today else to) }
                }) { _, dx -> drag += dx }
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.md)) {
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
                onSelect = { d -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(if (d == board.today) null else d) },
                modifier = Modifier.padding(horizontal = Space.sm),
            )
        }
    }
}

private fun Clear.toMark(d: StripDay, today: LocalDate): Mark = when (this) {
    Clear.ALL -> Mark.DONE
    Clear.SOME -> if (d.frozen) Mark.FROZEN else Mark.PARTIAL
    Clear.NONE -> if (d.date == today) Mark.TODAY else Mark.MISSED
    Clear.EMPTY -> Mark.OFF
    Clear.FUTURE -> Mark.FUTURE
}

/** A row of day circles with the chain drawn behind them (the strip, the month calendar and the Namoz row use it). */
@Composable
internal fun ChainRow(
    days: List<Pair<LocalDate, Mark>>, today: LocalDate, chosen: LocalDate?, onSelect: ((LocalDate) -> Unit)?, modifier: Modifier = Modifier,
    circle: androidx.compose.ui.unit.Dp = 38.dp, slots: Int = days.size, startSlot: Int = 0,
) {
    val marks = days.map { it.second }
    val joined = org.pagel.ai.data.habits.links(marks)
    Box(modifier.fillMaxWidth().height(circle + Space.sm)) {
        // The chain: one bar under every pair of linked neighbours, from centre to centre.
        Canvas(Modifier.fillMaxWidth().height(circle + Space.sm)) {
            val cell = size.width / slots
            val h = circle.toPx() * 0.46f
            val y = size.height / 2 - Space.xs.toPx() / 2
            for (i in joined.indices) if (joined[i]) {
                val x0 = (startSlot + i + 0.5f) * cell
                val ice = marks[i] == Mark.FROZEN || marks.getOrNull(i + 1) == Mark.FROZEN
                drawRoundRect(
                    if (ice) Brush.horizontalGradient(listOf(ICE, ICE)) else Brush.horizontalGradient(listOf(Glass.Aurora2, Glass.Aurora1)),
                    topLeft = Offset(x0, y - h / 2), size = Size(cell, h), cornerRadius = CornerRadius(0f),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            if (startSlot > 0) Spacer(Modifier.weight(startSlot.toFloat()))
            for ((d, m) in days) {
                val press = remember { MutableInteractionSource() }
                Box(
                    Modifier.weight(1f).height(circle + Space.sm).then(
                        if (onSelect != null) Modifier.pressScale(press).clickable(press, indication = null, role = Role.Button) { onSelect(d) } else Modifier,
                    ).semantics(mergeDescendants = true) {
                        contentDescription = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK))
                        stateDescription = markWord(m)
                        if (d == chosen) selected = true
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    DayDot(d.dayOfMonth, m, d == today, d == chosen, circle)
                }
            }
            val rest = slots - startSlot - days.size
            if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
        }
    }
}

internal val ICE = Color(0xFFCDE8FB)

internal fun markWord(m: Mark) = when (m) {
    Mark.DONE -> "Done"; Mark.PARTIAL -> "Some"; Mark.FROZEN -> "Frozen"; Mark.MISSED -> "Missed"; Mark.OFF -> "Not its day"
    Mark.TODAY -> "Today, not yet"; Mark.FUTURE -> "Ahead"; Mark.BEFORE -> "Not counted yet"
}

@Composable
private fun DayDot(n: Int, m: Mark, isToday: Boolean, isSelected: Boolean, circle: androidx.compose.ui.unit.Dp) {
    val fill: Brush? = when (m) {
        Mark.DONE -> Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1))
        Mark.FROZEN -> Brush.linearGradient(listOf(Color.White, ICE))
        Mark.PARTIAL -> Brush.linearGradient(listOf(Glass.PrimarySoft, Glass.PrimarySoft))
        else -> null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(circle).clip(CircleShape)
                .then(if (fill != null) Modifier.background(fill) else Modifier)
                .then(if (isToday && m != Mark.DONE) Modifier.border(2.dp, Glass.Primary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val faint = m == Mark.FUTURE || m == Mark.OFF || m == Mark.BEFORE
            Text(
                "$n", style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (m == Mark.DONE || isToday) FontWeight.Bold else FontWeight.Medium),
                color = if (faint) Glass.Muted.copy(alpha = 0.6f) else Glass.Ink,
            )
        }
        // The selected day's mark: a short mint line under it; it keeps its 3 dp slot on every day (no jump).
        Box(Modifier.padding(top = 2.dp).size(width = 14.dp, height = 3.dp).background(if (isSelected) Glass.Primary else Color.Transparent, CircleShape))
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
internal fun EmojiTile(emoji: String, size: androidx.compose.ui.unit.Dp, quiet: Boolean = false) {
    val (a, b) = TileColors.stops("", tileOf(emoji))
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
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

/** "0 / 10 min", "2 / 3", "3:20 / 10 min"; a once-a-day habit says how often it repeats. */
internal fun progressText(c: HabitCard, timer: HabitTimer?, now: Long): String = when {
    c.minutes != null && timer != null -> {
        val s = (timer.spent(now) / 1000).coerceAtMost(c.minutes * 60L)
        "%d:%02d / %d min".format(s / 60, s % 60, c.minutes)
    }
    c.minutes != null -> "${if (c.done) c.minutes else 0} / ${c.minutes} min"
    c.times > 1 -> "${if (c.done) c.times else c.taps} / ${c.times}"
    c.window != null -> c.window.label()
    else -> org.pagel.ai.ui.glass.Repeat.label(c.task.every.orEmpty())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HabitCardRow(
    c: HabitCard, finishing: Boolean, reminder: Boolean, timer: HabitTimer?, now: Long,
    onTick: (String) -> Unit, onUntick: (String) -> Unit, onOpen: (String) -> Unit, onMenu: (String) -> Unit, onBell: (String) -> Unit,
    onTimer: (String, Int) -> Unit, onCentre: (Offset) -> Unit, modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val done = c.done || finishing
    val id = c.task.id
    val tickable = c.mark != Mark.FUTURE && c.mark != Mark.BEFORE && c.mark != Mark.OFF
    Row(modifier.fillMaxWidth().padding(start = Space.sm, end = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
        CheckCircle(
            c, done, enabled = tickable,
            onClick = { if (done) onUntick(id) else onTick(id) },
            modifier = Modifier.onGloballyPositioned { onCentre(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) },
        )
        Box(Modifier.weight(1f).padding(bottom = if (c.time != null) Space.md else 0.dp)) {
            val press = remember { MutableInteractionSource() }
            Pane(
                Modifier.fillMaxWidth().pressScale(press).combinedClickable(
                    press, indication = null, onClickLabel = if (c.minutes != null && !done) (if (timer?.running == true) "Pause" else "Start") else "Open",
                    onLongClickLabel = "Open", onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onOpen(id) },
                ) {
                    when {
                        c.minutes != null && !done && tickable && c.mark != Mark.FROZEN -> { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onTimer(id, c.minutes) }
                        else -> onOpen(id)
                    }
                },
            ) {
                Column(Modifier.fillMaxWidth().padding(start = Space.md, top = Space.md, bottom = Space.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(contentAlignment = Alignment.Center) {
                            EmojiTile(c.emoji, 48.dp, quiet = done)
                            if (c.minutes != null && timer != null && !done) TimerRing(timer.spent(now) / (c.minutes * 60_000f), 56.dp)
                        }
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = if (done) Glass.Muted else Glass.Ink,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(Space.xs))
                            MetaLine(c, done, timer, now)
                        }
                        GlassIconButton(TabIcons.More, "More: ${c.title}", size = Space.touch, plain = true) { onMenu(id) }
                    }
                    if (!done && (c.times > 1 || c.minutes != null)) {
                        val p = when {
                            c.minutes != null -> (timer?.spent(now) ?: 0L) / (c.minutes * 60_000f)
                            else -> c.taps.toFloat() / c.times
                        }.coerceIn(0f, 1f)
                        val shown by animateFloatAsState(p, spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow), label = "progress")
                        Box(Modifier.padding(top = Space.md, end = Space.md).fillMaxWidth().height(4.dp).background(Glass.Ink.copy(alpha = 0.07f), CircleShape)) {
                            Box(Modifier.fillMaxWidth(shown).height(4.dp).background(Brush.horizontalGradient(listOf(Glass.Aurora1, Glass.Primary)), CircleShape))
                        }
                    }
                }
            }
            // The time tab hangs on the card's bottom edge (Boss's reference): the bell and the time, or the prayer's window.
            if (c.time != null) {
                val bellPress = remember { MutableInteractionSource() }
                Row(
                    Modifier.align(Alignment.BottomEnd).offset(y = Space.md).padding(end = Space.lg).heightIn(min = 28.dp).pressScale(bellPress)
                        .background(if (reminder) Glass.PrimarySoft else Color.White, RoundedCornerShape(10.dp))
                        .border(1.dp, if (reminder) Glass.Primary.copy(alpha = 0.5f) else Glass.Ink.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                        .clickable(bellPress, indication = null, role = Role.Button) { onBell(id) }
                        .semantics(mergeDescendants = true) { contentDescription = "Reminder: ${c.title}"; stateDescription = if (reminder) "On" else "Off" }
                        .padding(horizontal = Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (reminder) TabIcons.BellRing else TabIcons.Bell, null, tint = if (reminder) Glass.PrimaryInk else Glass.Muted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text(c.window?.label() ?: c.time, style = Glass.NumberText, color = Glass.Ink)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MetaLine(c: HabitCard, done: Boolean, timer: HabitTimer?, now: Long) {
    // Wraps instead of squeezing the chip at 1.5x font.
    androidx.compose.foundation.layout.FlowRow(itemVerticalAlignment = Alignment.CenterVertically, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (done) {
            Icon(TabIcons.Check, null, tint = Glass.Muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Space.xs))
            Text("Finished", style = MaterialTheme.typography.labelMedium, color = Glass.Muted)
        } else if (c.mark == Mark.FROZEN) {
            Text("❄️ Frozen · chain holds", style = MaterialTheme.typography.labelMedium, color = Glass.Muted)
        } else {
            Text(progressText(c, timer, now), style = if (c.times > 1 || c.minutes != null) Glass.NumberText.copy(fontSize = 13.sp) else MaterialTheme.typography.labelMedium, color = Glass.Muted, maxLines = 1)
        }
        if (c.streak > 0) {
            Spacer(Modifier.width(Space.sm))
            Text("🔥", style = Glass.NumberText, modifier = Modifier.clearAndSetSemantics {})
            Text("${c.streak}", style = Glass.NumberText, color = Color(0xFFC2410C), modifier = Modifier.semantics { contentDescription = "${c.streak} in a row" })
        }
        if (c.neverMissTwice && !done) {
            Spacer(Modifier.width(Space.sm))
            Text(
                "Never miss twice", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8A4B00), maxLines = 1,
                modifier = Modifier.background(Color(0xFFFFEDC7), CircleShape).padding(horizontal = Space.sm, vertical = 2.dp),
            )
        } else if (c.after != null && !done) {
            Spacer(Modifier.width(Space.sm))
            Text("after ${c.after}", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TimerRing(p: Float, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val w = 3.dp.toPx()
        drawArc(Glass.Primary, -90f, 360f * p.coerceIn(0f, 1f), false, topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/**
 * The check outside the card: a ring that fills mint with an ink tick (Today's), arcs for a count habit, ice for a
 * frozen day, faint for a day it cannot be ticked. 48 dp target, 28 dp drawn.
 */
@Composable
private fun CheckCircle(c: HabitCard, done: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val press = remember { MutableInteractionSource() }
    val pop by animateFloatAsState(if (done) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "pop")
    Box(
        modifier.size(Space.touch).pressScale(press)
            .clickable(press, indication = null, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .semantics { contentDescription = "Done: ${c.title}"; stateDescription = if (done) "Done" else if (c.times > 1) "${c.taps} of ${c.times}" else markWord(c.mark) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp).graphicsLayer { val k = 1f + 0.12f * pop * (1f - pop) * 4f; scaleX = k; scaleY = k }) {
            val r = size.minDimension / 2
            val line = 2.dp.toPx()
            when {
                done -> drawCircle(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), r)
                c.mark == Mark.FROZEN -> drawCircle(ICE, r)
                !enabled -> drawCircle(Glass.Ink.copy(alpha = 0.18f), r - line / 2, style = Stroke(1.5.dp.toPx()))
                c.times > 1 -> {
                    val gap = 360f / c.times * 0.18f
                    for (i in 0 until c.times) drawArc(
                        if (i < c.taps) Glass.Primary else Glass.Ink.copy(alpha = 0.45f), -90f + i * 360f / c.times + gap / 2, 360f / c.times - gap, false,
                        topLeft = Offset(line / 2, line / 2), size = Size(size.width - line, size.height - line), style = Stroke(line, cap = StrokeCap.Round),
                    )
                }
                else -> drawCircle(Glass.Ink.copy(alpha = 0.55f), r - line / 2, style = Stroke(line))
            }
            if (done) {
                val u = size.minDimension / 20f
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
    board: HabitBoard, today: LocalDate?, onTick: (String) -> Unit, onUntick: (String) -> Unit, onStart: () -> Unit, onMenu: () -> Unit,
    centres: MutableMap<String, Offset>, modifier: Modifier = Modifier,
) {
    val prayers = board.namoz
    Pane(modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.fillMaxWidth().padding(Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiTile("mosque", 40.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text("Namoz", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Glass.Ink, modifier = Modifier.semantics { heading() })
                    val started = Namoz.counts(board.selected.toString())
                    Text(if (prayers.isEmpty() || !started) "From 1 November" else "Five a day", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                }
                if (prayers.isNotEmpty()) {
                    DayRing(board.namozDone, prayers.size, size = 40.dp)
                    GlassIconButton(TabIcons.More, "Namoz reminders", size = Space.touch, plain = true, onClick = onMenu)
                }
            }
            if (prayers.isEmpty()) {
                val press = remember { MutableInteractionSource() }
                Spacer(Modifier.height(Space.md))
                Text(
                    "Start tracking", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch).pressScale(press).background(Glass.PrimarySoft, CircleShape)
                        .clickable(press, indication = null, role = Role.Button, onClick = onStart).padding(vertical = Space.md),
                )
                return@Column
            }
            Spacer(Modifier.height(Space.sm))
            val nowT = LocalTime.now(ZoneId.of("Asia/Tashkent"))
            for (c in prayers) key(c.task.id) {
                val w = c.window
                val current = board.selected == today && w != null && (if (w.to > w.from) nowT >= w.from && nowT < w.to else nowT >= w.from || nowT < w.to)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Space.touch).background(if (current) Glass.PrimarySoft.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(14.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckCircle(c, c.done, enabled = c.mark != Mark.BEFORE && c.mark != Mark.FUTURE, onClick = { if (c.done) onUntick(c.task.id) else onTick(c.task.id) },
                        modifier = Modifier.onGloballyPositioned { centres[c.task.id] = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) })
                    Image(painterResource(Emoji.res(c.emoji)), null, Modifier.size(22.dp))
                    Spacer(Modifier.width(Space.sm))
                    Text(c.title, style = MaterialTheme.typography.bodyLarge, color = if (c.done) Glass.Muted else Glass.Ink, modifier = Modifier.weight(1f))
                    Text(w?.label().orEmpty(), style = Glass.NumberText, color = Glass.Muted, modifier = Modifier.padding(end = Space.sm))
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

// ── The card's menu ─────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun HabitMenuSheet(
    c: HabitCard, freezesLeft: Int, onDetail: () -> Unit, onReminder: () -> Unit, onFreeze: () -> Unit, onUndo: () -> Unit, onResetTimer: () -> Unit, onDismiss: () -> Unit,
) {
    GlassSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            EmojiTile(c.emoji, 40.dp)
            Spacer(Modifier.width(Space.md))
            Text(c.title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
            Column {
                MenuRow(TabIcons.CalendarCheck, "Details and history", onDetail)
                if (c.time != null) MenuRow(TabIcons.Bell, "Reminder", onReminder)
                if (c.mark == Mark.FROZEN) MenuRow(TabIcons.Undo, "Unfreeze this day", onFreeze)
                else if (!c.done && !org.pagel.ai.data.habits.isNamoz(c.task)) MenuRow(TabIcons.Sparkles, "Freeze this day · $freezesLeft left", onFreeze)
                if (c.done || c.taps > 0) MenuRow(TabIcons.Undo, "Take back", onUndo)
                if (c.minutes != null) MenuRow(TabIcons.Refresh, "Reset the timer", onResetTimer)
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
