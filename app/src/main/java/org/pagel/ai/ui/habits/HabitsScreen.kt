package org.pagel.ai.ui.habits

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.Dot
import org.pagel.ai.data.habits.HabitItem
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import org.pagel.ai.data.habits.Part
import org.pagel.ai.reminders.HabitAlarms
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SheetLabel
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TimeWheel
import org.pagel.ai.ui.glass.pressOnTouch
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.task.LocalTaskOpener
import org.pagel.ai.ui.today.Burst
import org.pagel.ai.ui.today.ConfettiBurst
import org.pagel.ai.ui.today.Ding
import org.pagel.ai.ui.today.headline
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val COMING = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

@Composable
fun HabitsRoute(contentPadding: PaddingValues, reselect: Int, vm: HabitsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var bell by remember { mutableStateOf<String?>(null) }
    var week by remember { mutableStateOf<String?>(null) }
    var celebrate by remember { mutableStateOf(0) }
    var exact by remember { mutableStateOf(true) }
    var notify by remember { mutableStateOf(true) }
    val alarms = remember { ctx.getSystemService(AlarmManager::class.java) }
    fun readGrants() {
        exact = alarms == null || HabitAlarms.canExact(alarms)
        notify = notificationsOn(ctx)
    }
    // Back from Settings (alarms & reminders, notifications): plan again with what Boss granted.
    LifecycleResumeEffect(Unit) {
        readGrants()
        vm.replan()
        onPauseOrDispose {}
    }
    // Read again when the sheet opens: a grant given while PAGEL stayed in front (adb, a quick settings tile) used to
    // show "off" until the next resume (walk 38, checker 2026-10-28 #7).
    LaunchedEffect(bell) { if (bell != null) readGrants() }
    LaunchedEffect(Unit) { vm.celebrate.collect { celebrate++ } }

    fun askExact() = vm.askExactOnce(needed = alarms != null && !HabitAlarms.canExact(alarms)) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) }
    }
    // Asked at first use (the first reminder turned on), never at launch, never twice in a row (RULES.md, security).
    // A no leaves the sheet's "Notifications off · Turn on" row; the exact-alarm ask waits for a yes (checker #6).
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> readGrants(); if (granted) askExact() }
    fun remind(id: String, on: Boolean) {
        vm.setReminder(id, on)
        if (!on) return
        val needs = android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else askExact()
    }
    fun openNotificationSettings() {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
    }
    fun openExactSettings() {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) }
    }

    HabitsScreen(ui, contentPadding, reselect, celebrate = celebrate, onTick = vm::tick, onBell = { bell = it }, onUntick = vm::untickOne, onWeek = { week = it })
    week?.let { id ->
        val h = (ui.day.parts.flatMap { it.second } + ui.day.done).firstOrNull { it.task.id == id }
        if (h == null) week = null
        else WeekSheet(h, runCatching { LocalDate.parse(ui.today) }.getOrElse { LocalDate.now() }, onSet = { day, done -> vm.setDay(id, day, done) }) { week = null }
    }
    bell?.let { id ->
        ui.byId[id]?.let { t ->
            ReminderSheet(
                t, on = id in ui.reminders, exact = exact, notify = notify, onToggle = { remind(id, it) }, onTime = { vm.setTime(id, it) },
                onFixNotify = { openNotificationSettings() }, onFixExact = { openExactSettings() },
            ) { bell = null }
        }
    }
}

/**
 * Habits, native (NATIVE-PLAN Phase 3, the web's /habits): the daily things by part of the day, each with its 🔥
 * streak; a tick fills the ring with Today's ding and moves the habit to "Done today"; the day's bar fills; the last
 * one gets the all-done card with confetti, once a day. The bell sets a reminder at the habit's time.
 */
@Composable
fun HabitsScreen(
    ui: HabitsUi,
    contentPadding: PaddingValues,
    reselect: Int = 0,
    celebrate: Int = 0,
    onTick: (String) -> Unit = {},
    onBell: (String) -> Unit = {},
    /** A long press on a ticked count ring: one tap taken off. */
    onUntick: (String) -> Unit = {},
    /** The dots tapped: the habit's last 7 days as a sheet. */
    onWeek: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val openTask = LocalTaskOpener.current
    val bursts = remember { mutableStateListOf<Burst>() }
    var nextBurst by remember { mutableLongStateOf(0L) }
    var rootAt by remember { mutableStateOf(Offset.Zero) }
    var allDoneAt by remember { mutableStateOf(Offset.Zero) }
    val list = rememberLazyListState()

    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    LaunchedEffect(celebrate) {
        if (celebrate == 0) return@LaunchedEffect
        scope.launch(Dispatchers.Default) { Ding.play(ctx.applicationContext) }
        bursts += Burst(nextBurst++, allDoneAt - rootAt)
    }

    fun tick(id: String, centre: Offset) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch(Dispatchers.Default) { Ding.play(ctx.applicationContext) }
        bursts += Burst(nextBurst++, centre - rootAt)
        onTick(id)
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootAt = it.positionInRoot() }) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "head", contentType = "head") { Header(ui) }
            val d = ui.day
            when {
                ui.loading -> item(key = "loading", contentType = "note") {
                    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(3, Modifier.padding(vertical = 6.dp)) }
                }
                d.total == 0 && d.coming.isEmpty() -> item(key = "none", contentType = "note") {
                    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("No habits yet", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                            Text("Give a task a repeat; it lives here.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
                        }
                    }
                }
            }
            if (d.allDone && ui.finishing.isEmpty()) item(key = "all-done", contentType = "all-done") {
                AllDone(d.doneCount, d.best, Modifier.onGloballyPositioned { allDoneAt = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 3f) })
            }
            // Panes slide to their new place when one grows or shrinks (apple-design §7, spatial consistency): a ticked
            // habit's row folds out of its part and "Done today" grows, instead of the row teleporting (checker #10).
            for ((part, items) in d.parts) item(key = "part-${part.name}", contentType = "part") {
                PartPane(part, items, ui, onTick = ::tick, onOpen = openTask, onBell = onBell, onUntick = onUntick, onWeek = onWeek, modifier = Modifier.animateItem())
            }
            if (d.done.isNotEmpty()) item(key = "done", contentType = "done") { DonePane(d.done, onUntick = onUntick, onWeek = onWeek, modifier = Modifier.animateItem()) }
            if (d.coming.isNotEmpty()) item(key = "coming", contentType = "coming") { ComingPane(d.coming, Modifier.animateItem()) }
        }
        for (b in bursts) key(b.id) { ConfettiBurst(b, onEnd = { bursts.remove(b) }, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Header(ui: HabitsUi) {
    val d = ui.day
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
        Text("Every day, the same few things", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        Text("Habits", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
        Text(if (d.total > 0) "${d.doneCount} of ${d.total} done today" else "Nothing due today", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        if (d.total > 0) {
            Spacer(Modifier.height(10.dp))
            DayBar(d.doneCount, d.total)
        }
    }
}

/** The day's bar (the web's .habits-bar): a mint fill that springs forward with each tick. */
@Composable
private fun DayBar(done: Int, total: Int) {
    val target = if (total <= 0) 0f else done.toFloat() / total
    // Critically damped: a tap is not a fling, so the bar must not overshoot (apple-design §4, checker #10).
    val p by animateFloatAsState(target, spring(dampingRatio = 1f, stiffness = 220f), label = "bar")
    Box(
        Modifier.fillMaxWidth().height(8.dp).background(Glass.Ink.copy(alpha = 0.08f), CircleShape)
            .semantics { progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(done.toFloat(), 0f..total.toFloat()); contentDescription = "$done of $total habits done today" },
    ) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(8.dp).background(Brush.horizontalGradient(listOf(Glass.Aurora1, Glass.Primary)), CircleShape))
    }
}

/** The day's last habit ticked (the web's AllDone): one card, the flame, the count and the longest streak. */
@Composable
private fun AllDone(count: Int, best: Int, modifier: Modifier) {
    Pane(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 20.dp).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔥", fontSize = 44.sp, modifier = Modifier.clearAndSetSemantics {})
            Spacer(Modifier.height(6.dp))
            Text("All done today", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink)
            Spacer(Modifier.height(4.dp))
            val habits = if (count == 1) "habit" else "habits"
            Text("$count $habits ticked." + if (best > 1) " Longest streak: $best in a row." else " Tomorrow starts a streak.",
                style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
        }
    }
}

@Composable
private fun PaneTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 2.dp).semantics { heading() })
}

@Composable
private fun PartPane(
    part: Part, items: List<HabitItem>, ui: HabitsUi, onTick: (String, Offset) -> Unit, onOpen: (String) -> Unit, onBell: (String) -> Unit,
    onUntick: (String) -> Unit, onWeek: (String) -> Unit, modifier: Modifier = Modifier,
) {
    Pane(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)).padding(bottom = 4.dp)) {
            PaneTitle("${part.icon} ${part.label}")
            for ((i, h) in items.withIndex()) key(h.task.id) {
                if (i > 0) HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                HabitRow(h, done = h.task.id in ui.finishing, reminder = h.task.id in ui.reminders, onTick = onTick, onOpen = onOpen, onBell = onBell, onUntick = onUntick, onWeek = onWeek)
            }
        }
    }
}

internal fun titleOf(t: TaskEntity) = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)

/**
 * One habit, compact (round B, Boss: "they are kind of big, it is daily habits"; Streaks / Apple Fitness): the ring on
 * the left (segmented for a habit done N times a day), the title on at most 2 lines, then a small time chip, today's
 * count, the 🔥 streak and the last 7 days as dots. The whole row is the tick; a long press takes one tap off (a count
 * habit) or opens the task; the dots open the week; the bell sets the reminder. A missed day is never a red date: the
 * row shows today's slot and the miss only breaks the streak.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HabitRow(
    h: HabitItem, done: Boolean, reminder: Boolean, onTick: (String, Offset) -> Unit, onOpen: (String) -> Unit, onBell: (String) -> Unit,
    onUntick: (String) -> Unit, onWeek: (String) -> Unit,
) {
    val t = h.task
    val title = titleOf(t)
    val time = h.slot?.drop(11)?.trim()?.takeIf(String::isNotEmpty)?.let(org.pagel.ai.ui.glass.DatePick::timeText)
    val haptics = LocalHapticFeedback.current
    var centre by remember { mutableStateOf(Offset.Zero) }
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(press)
            .combinedClickable(
                // A done row stays pressable: its long press takes the tick back (round B checker #12: it was dead).
                press, indication = null, role = Role.Checkbox,
                onLongClickLabel = when { done -> "Undo"; h.taps > 0 -> "Take one off"; else -> "Open" },
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); if (done || h.taps > 0) onUntick(t.id) else onOpen(t.id) },
            ) { if (!done) onTick(t.id, centre) }
            .semantics { contentDescription = "Done: $title"; if (h.times > 1) stateDescription = "${h.taps} of ${h.times}" }
            .padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).onGloballyPositioned { centre = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }, contentAlignment = Alignment.Center) {
            HabitRing(t.priority, h.times, if (done) h.times else h.taps, done)
        }
        Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (done) Glass.Muted else Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // The meta wraps at 1.5x instead of cutting.
            // An empty meta adds no 4 dp under the title (spacing audit #20).
            // The week's dots live on this meta line, under the title, so a title keeps the row's width (checker #4:
            // "Read 10 / pages" wrapped at 1x with the dots and the bell at the row's end).
            // The dots sit at the meta line's END, so the row's centre (where a thumb ticks) is never the week's button.
            if (time != null || h.times > 1 || h.streak > 0 || h.week.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f).padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val small = MaterialTheme.typography.labelSmall
                if (time != null) Row(
                    Modifier.background(Glass.Ink.copy(alpha = 0.05f), CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(TabIcons.Clock, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(with(androidx.compose.ui.platform.LocalDensity.current) { 12.sp.toDp() }))
                    Spacer(Modifier.width(3.dp))
                    Text(time, style = small, color = Glass.Muted)
                }
                if (h.times > 1) Text("${if (done) h.times else h.taps}/${h.times}", style = Glass.NumberText, color = Glass.PrimaryInk)
                if (h.streak > 0) Streak(h.streak)
            }
            WeekDots(h.week) { onWeek(t.id) }
            }
        }
        if (time != null) GlassIconButton(if (reminder) TabIcons.BellRing else TabIcons.Bell, "Reminder: $title", plain = true, on = reminder) { onBell(t.id) }
    }
}

/**
 * The tick ring, 22 dp: once a day = the ring that fills mint with an ink tick (Today's); N times a day = N arcs with
 * gaps, one filling per tap (Apple Fitness), the tick drawn once all are full.
 */
@Composable
private fun HabitRing(priority: Int, times: Int, taps: Int, done: Boolean) {
    val flag = listOf(Color(0xFFD1453B), Color(0xFFEB8909), Color(0xFF246FE0)).getOrNull(priority) ?: Color(0xFF9AA2BB)
    val full = done || taps >= times
    val pop by animateFloatAsState(if (full) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "pop")
    Canvas(Modifier.size(22.dp).graphicsLayer { val k = 1f + 0.12f * pop * (1f - pop) * 4f; scaleX = k; scaleY = k }) {
        val r = size.minDimension / 2
        val line = 2.dp.toPx()
        if (full) drawCircle(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), r)
        else if (times <= 1) { drawCircle(flag.copy(alpha = 0.08f), r); drawCircle(flag, r - line / 2, style = Stroke(1.5.dp.toPx())) }
        else {
            val gap = 360f / times * 0.18f
            val sweep = 360f / times - gap
            val inset = line / 2
            for (i in 0 until times) {
                drawArc(
                    // An empty arc ≥ 3:1 on the pane (checker #8: 0.35 read ~1.6:1, "1 of 3 left" was invisible).
                    if (i < taps) Glass.Primary else flag.copy(alpha = 0.6f), startAngle = -90f + i * 360f / times + gap / 2, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - line, size.height - line), style = Stroke(line, cap = StrokeCap.Round),
                )
            }
        }
        if (full) {
            val u = size.minDimension / 20f
            drawLine(Glass.Ink, Offset(5.5f * u, 10.5f * u), Offset(8.5f * u, 13.5f * u), 2.2f * u, StrokeCap.Round)
            drawLine(Glass.Ink, Offset(8.5f * u, 13.5f * u), Offset(14.5f * u, 6.5f * u), 2.2f * u, StrokeCap.Round)
        }
    }
}

/** The last 7 days, oldest first: filled mint = done, half = some taps, hollow ring = missed, faint = not its day. */
@Composable
private fun WeekDots(week: List<Dot>, onClick: () -> Unit) {
    if (week.isEmpty()) return
    val press = remember { MutableInteractionSource() }
    val done = week.count { it == Dot.DONE }
    Row(
        Modifier.heightIn(min = 48.dp).pressScale(press).clickable(press, indication = null, role = Role.Button, onClickLabel = "Open the week", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Last 7 days: $done done" }
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (d in week) Canvas(Modifier.size(8.dp)) {
            val r = size.minDimension / 2
            when (d) {
                Dot.DONE -> drawCircle(Glass.Primary, r)
                Dot.PARTIAL -> { drawCircle(Glass.Primary.copy(alpha = 0.35f), r); drawCircle(Glass.Primary, r - 0.6.dp.toPx(), style = Stroke(1.2.dp.toPx())) }
                Dot.MISSED -> drawCircle(Glass.Ink.copy(alpha = 0.55f), r - 0.6.dp.toPx(), style = Stroke(1.2.dp.toPx()))
                Dot.TODAY -> drawCircle(Glass.Primary, r - 0.6.dp.toPx(), style = Stroke(1.2.dp.toPx()))
                Dot.OFF -> drawCircle(Glass.Ink.copy(alpha = 0.12f), r * 0.5f)
            }
        }
    }
}

/** 🔥 and the count (the web's tl-streak); the number alone in the number face. */
@Composable
private fun Streak(n: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$n in a row" }) {
        // The glyph in the number's own 16 sp line box, so it sits on the meta's baseline, not ~3 dp under it (#20).
        Text("🔥", style = Glass.NumberText)
        Spacer(Modifier.width(2.dp))
        Text("$n", style = Glass.NumberText, color = Color(0xFFC2410C))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DonePane(done: List<HabitItem>, onUntick: (String) -> Unit, onWeek: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Pane(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)).padding(bottom = 8.dp)) {
            PaneTitle("Done today ✓")
            for (h in done) key(h.task.id) {
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        // A long press takes today's last tap off (it comes back to its part), as on a count ring.
                        .combinedClickable(press, indication = null, onLongClickLabel = "Take one off", onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress); onUntick(h.task.id)
                        }) {}
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Bright mint with an ink tick (Boss: "white, ink or bright mint"), not a 4th green (checker #9).
                    Box(Modifier.size(20.dp).background(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(TabIcons.Check, contentDescription = null, tint = Glass.Ink, modifier = Modifier.size(13.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(titleOf(h.task), style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (h.streak > 1) { Spacer(Modifier.width(8.dp)); Streak(h.streak) }
                    Spacer(Modifier.width(8.dp))
                    WeekDots(h.week) { onWeek(h.task.id) }
                }
            }
        }
    }
}

/**
 * A habit's last 7 days as big day circles (48 dp touch, the week's share of the width): tap a day to tick it or take
 * one tap off (pagel.uz counts it for that day; the date does not move). The best run is under the title.
 */
@Composable
internal fun WeekSheet(h: HabitItem, today: LocalDate, onSet: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        WeekContent(h, today, onSet)
        Spacer(Modifier.height(SheetBottom))
    }
}

@Composable
internal fun WeekContent(h: HabitItem, today: LocalDate, onSet: (String, Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("This week", if (h.best > 0) "Best 🔥 ${h.best}" else null)
        Text(titleOf(h.task), style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
        // An 8 dp gutter here, so each of the 7 days is ≥ 48 dp wide at 360 dp (checker: 45.7 dp with 16 + 4).
        Pane(Modifier.fillMaxWidth().padding(horizontal = Space.sm)) {
            Row(Modifier.fillMaxWidth().padding(vertical = Space.sm)) {
                h.week.forEachIndexed { i, dot ->
                    val day = today.minusDays((h.week.size - 1 - i).toLong())
                    val on = dot == Dot.DONE
                    val press = remember { MutableInteractionSource() }
                    Column(
                        Modifier.weight(1f).heightIn(min = 72.dp).pressScale(press)
                            .clickable(press, indication = null, role = Role.Checkbox) { haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onSet(day.toString(), !on) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.UK))
                                stateDescription = when (dot) { Dot.DONE -> "Done"; Dot.PARTIAL -> "Some"; Dot.MISSED -> "Missed"; Dot.TODAY -> "Today, not yet"; Dot.OFF -> "Not its day" }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(day.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.UK), style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.size(38.dp).then(
                                when (dot) {
                                    Dot.DONE -> Modifier.background(Glass.Primary, CircleShape)
                                    Dot.PARTIAL -> Modifier.background(Glass.PrimarySoft, CircleShape)
                                    Dot.OFF -> Modifier.background(Glass.Ink.copy(alpha = 0.04f), CircleShape)
                                    else -> Modifier.background(Color.White.copy(alpha = 0.72f), CircleShape)
                                },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (dot == Dot.MISSED || dot == Dot.TODAY) Canvas(Modifier.size(38.dp)) {
                                drawCircle(if (dot == Dot.TODAY) Glass.Primary else Glass.Ink.copy(alpha = 0.35f), size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                            }
                            Text("${day.dayOfMonth}", style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else Glass.Ink)
                        }
                    }
                }
            }
        }
        Text("Tap a day to tick it or take it back. Missed days only break the streak.", style = MaterialTheme.typography.labelSmall, color = Glass.Muted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
    }
}

@Composable
private fun ComingPane(coming: List<TaskEntity>, modifier: Modifier = Modifier) {
    Pane(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(bottom = 10.dp)) {
            PaneTitle("Coming up")
            for (t in coming) key(t.id) {
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(titleOf(t), style = MaterialTheme.typography.bodyMedium, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    val day = t.due?.take(10)?.let { runCatching { LocalDate.parse(it).format(COMING) }.getOrNull() }.orEmpty()
                    Text(listOf(day, t.due?.drop(11)?.trim().orEmpty()).filter(String::isNotEmpty).joinToString(" "), style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                }
            }
        }
    }
}

// ── The reminder sheet ─────────────────────────────────────────────────────────────────────────────────────────────

@Composable
fun ReminderSheet(
    t: TaskEntity, on: Boolean, exact: Boolean, notify: Boolean, onToggle: (Boolean) -> Unit, onTime: (String) -> Unit,
    onFixNotify: () -> Unit, onFixExact: () -> Unit, onDismiss: () -> Unit,
) {
    GlassSheet(onDismiss) { ReminderContent(t, on, exact, onToggle, onTime, notify, onFixNotify, onFixExact); Spacer(Modifier.height(SheetBottom)) }
}

/** Notifications allowed for PAGEL, and the habits channel not switched off (it is made at the first reminder). */
fun notificationsOn(ctx: android.content.Context): Boolean {
    if (!androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
    val channel = ctx.getSystemService(android.app.NotificationManager::class.java)?.getNotificationChannel(org.pagel.ai.reminders.HabitAlarmReceiver.CHANNEL)
    return channel == null || channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
}

/**
 * A habit's reminder: one switch and Apple's time wheel. On = a notification at that time on the days it repeats,
 * with a Done button that ticks it even offline. Without the exact-alarm grant it says it may come up to 10 min late.
 */
@Composable
fun ReminderContent(
    t: TaskEntity, on: Boolean, exact: Boolean, onToggle: (Boolean) -> Unit, onTime: (String) -> Unit,
    notify: Boolean = true, onFixNotify: () -> Unit = {}, onFixExact: () -> Unit = {},
) {
    // A range's reminder rings at its start.
    val time = t.due?.drop(11)?.trim()?.take(5)?.takeIf(String::isNotEmpty) ?: "08:00"
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("Reminder", time)
        Text(titleOf(t), style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val press = remember { MutableInteractionSource() }
            val haptics = LocalHapticFeedback.current
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(press)
                    .clickable(press, indication = null, role = Role.Switch) { haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onToggle(!on) }
                    .semantics(mergeDescendants = true) { toggleableState = ToggleableState(on); stateDescription = if (on) "On" else "Off"; contentDescription = "Remind me" }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (on) TabIcons.BellRing else TabIcons.Bell, contentDescription = null, tint = if (on) Glass.Primary else Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text("Remind me", style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
                Switch(on)
            }
        }
        Spacer(Modifier.height(16.dp))
        SheetLabel("Time", Modifier.padding(start = 16.dp))
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TimeWheel(time, onChange = onTime, modifier = Modifier.padding(vertical = 8.dp))
        }
        // A missing grant is a row Boss can tap, never a red paragraph (checker #6): notifications first (without them
        // nothing rings at all), then the exact time (without it a reminder may come up to 10 min late).
        when {
            !on -> Hint("Off. The habit still shows here every day.")
            !notify -> FixRow(TabIcons.Bell, "Notifications off", "Turn on", Glass.Risk, onFixNotify)
            !exact -> FixRow(TabIcons.Clock, "Exact time", "Allow", Glass.Ink, onFixExact)
            else -> Hint("At $time on the days it repeats, with a Done button.")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
}

/** A missing grant: its icon and name, and the one word that opens the right Settings page, on a 48 dp row. */
@Composable
internal fun FixRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: String, tone: Color, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).heightIn(min = 48.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = action, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label · $action" }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(action, style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, maxLines = 1,
            modifier = Modifier.background(Glass.PrimarySoft, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** Apple's switch: a capsule that fills mint, a white knob that springs across. Drawn only; the row is the target. */
@Composable
internal fun Switch(on: Boolean) {
    val x by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "knob")
    Box(
        // On = bright mint (Boss's colour theory), not a solid #1C7E69 slab (checker #9); the white knob carries the shape.
        Modifier.size(width = 51.dp, height = 31.dp)
            .background(if (on) Brush.horizontalGradient(listOf(Glass.Aurora1, Glass.Primary)) else androidx.compose.ui.graphics.SolidColor(Glass.Ink.copy(alpha = 0.16f)), CircleShape).padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.graphicsLayer { translationX = x * 20.dp.toPx() }.size(27.dp).background(Color.White, CircleShape))
    }
}
