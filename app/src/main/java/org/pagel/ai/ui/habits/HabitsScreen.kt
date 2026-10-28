package org.pagel.ai.ui.habits

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import org.pagel.ai.data.habits.HabitItem
import org.pagel.ai.data.habits.Part
import org.pagel.ai.reminders.HabitAlarms
import org.pagel.ai.ui.glass.Glass
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
import org.pagel.ai.ui.today.TickRing
import org.pagel.ai.ui.today.dueLabel
import org.pagel.ai.ui.today.DueTone
import org.pagel.ai.ui.today.headline
import org.pagel.ai.ui.today.reducedMotion
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val COMING = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

@Composable
fun HabitsRoute(contentPadding: PaddingValues, reselect: Int, vm: HabitsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var bell by remember { mutableStateOf<String?>(null) }
    var celebrate by remember { mutableStateOf(0) }
    var exact by remember { mutableStateOf(true) }
    val alarms = remember { ctx.getSystemService(AlarmManager::class.java) }
    // Back from Settings (alarms & reminders, notifications): plan again with what Boss granted.
    LifecycleResumeEffect(Unit) {
        exact = alarms == null || HabitAlarms.canExact(alarms)
        vm.replan()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) { vm.celebrate.collect { celebrate++ } }

    fun askExact() = vm.askExactOnce(needed = alarms != null && !HabitAlarms.canExact(alarms)) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) }
    }
    // Asked at first use (the first reminder turned on), never at launch, never twice in a row (RULES.md, security).
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askExact() }
    fun remind(id: String, on: Boolean) {
        vm.setReminder(id, on)
        if (!on) return
        val needs = android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) notify.launch(Manifest.permission.POST_NOTIFICATIONS) else askExact()
    }

    HabitsScreen(ui, contentPadding, reselect, celebrate = celebrate, onTick = vm::tick, onBell = { bell = it })
    bell?.let { id ->
        ui.byId[id]?.let { t -> ReminderSheet(t, on = id in ui.reminders, exact = exact, onToggle = { remind(id, it) }, onTime = { vm.setTime(id, it) }) { bell = null } }
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
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val openTask = LocalTaskOpener.current
    val reduce = remember { reducedMotion(ctx) }
    val bursts = remember { mutableStateListOf<Burst>() }
    var nextBurst by remember { mutableLongStateOf(0L) }
    var rootAt by remember { mutableStateOf(Offset.Zero) }
    var allDoneAt by remember { mutableStateOf(Offset.Zero) }
    val list = rememberLazyListState()
    val today = remember(ui.today) { runCatching { LocalDate.parse(ui.today) }.getOrElse { LocalDate.now() } }

    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    LaunchedEffect(celebrate) {
        if (celebrate == 0) return@LaunchedEffect
        scope.launch(Dispatchers.Default) { Ding.play() }
        if (!reduce) bursts += Burst(nextBurst++, allDoneAt - rootAt)
    }

    fun tick(id: String, centre: Offset) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch(Dispatchers.Default) { Ding.play() }
        if (!reduce) bursts += Burst(nextBurst++, centre - rootAt)
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
            for ((part, items) in d.parts) item(key = "part-${part.name}", contentType = "part") {
                PartPane(part, items, ui, today, onTick = ::tick, onOpen = openTask, onBell = onBell)
            }
            if (d.done.isNotEmpty()) item(key = "done", contentType = "done") { DonePane(d.done) }
            if (d.coming.isNotEmpty()) item(key = "coming", contentType = "coming") { ComingPane(d.coming) }
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
    val p by animateFloatAsState(target, spring(dampingRatio = 0.8f, stiffness = 220f), label = "bar")
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
private fun PartPane(part: Part, items: List<HabitItem>, ui: HabitsUi, today: LocalDate, onTick: (String, Offset) -> Unit, onOpen: (String) -> Unit, onBell: (String) -> Unit) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(bottom = 6.dp)) {
            PaneTitle("${part.icon} ${part.label}")
            for ((i, h) in items.withIndex()) key(h.task.id) {
                if (i > 0) HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                HabitRow(h, done = h.task.id in ui.finishing, reminder = h.task.id in ui.reminders, today = today, onTick = onTick, onOpen = onOpen, onBell = onBell)
            }
        }
    }
}

private fun titleOf(t: TaskEntity) = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)

/** One habit: the tick ring, its words, its time · repeat · 🔥 streak, and the bell when it has a time. */
@Composable
private fun HabitRow(h: HabitItem, done: Boolean, reminder: Boolean, today: LocalDate, onTick: (String, Offset) -> Unit, onOpen: (String) -> Unit, onBell: (String) -> Unit) {
    val t = h.task
    val title = titleOf(t)
    val time = t.due?.drop(11)?.trim()?.takeIf(String::isNotEmpty)
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TickRing(t.priority, done, title) { centre -> if (!done) onTick(t.id, centre) }
        val open = remember { MutableInteractionSource() }
        Column(
            Modifier.weight(1f).pressOnTouch().clickable(open, indication = null, enabled = !done, role = Role.Button, onClickLabel = "Open") { onOpen(t.id) }
                .padding(top = 12.dp, bottom = 10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (done) Glass.Muted else Glass.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val small = MaterialTheme.typography.labelSmall
                val late = dueLabel(t.due, today)?.takeIf { it.tone == DueTone.LATE }
                when {
                    late != null -> { Icon(TabIcons.Calendar, contentDescription = null, tint = Glass.Risk, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)); Text(late.text, style = small, color = Glass.Risk) ; Spacer(Modifier.width(10.dp)) }
                    time != null -> { Icon(TabIcons.Clock, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)); Text(time, style = small, color = Glass.Muted); Spacer(Modifier.width(10.dp)) }
                }
                t.every?.let { Text(Repeat.label(it), style = small, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                if (h.streak > 0) { Spacer(Modifier.width(10.dp)); Streak(h.streak) }
            }
        }
        if (time != null) GlassIconButton(if (reminder) TabIcons.BellRing else TabIcons.Bell, "Reminder: $title", plain = true, on = reminder) { onBell(t.id) }
    }
}

/** 🔥 and the count (the web's tl-streak); the number alone in the number face. */
@Composable
private fun Streak(n: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$n in a row" }) {
        Text("🔥", fontSize = 12.sp)
        Spacer(Modifier.width(2.dp))
        Text("$n", style = Glass.NumberText, color = Color(0xFFC2410C))
    }
}

@Composable
private fun DonePane(done: List<HabitItem>) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(bottom = 10.dp)) {
            PaneTitle("Done today ✓")
            for (h in done) key(h.task.id) {
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).background(Color(0xFF058527), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(TabIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(titleOf(h.task), style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (h.streak > 1) { Spacer(Modifier.width(8.dp)); Streak(h.streak) }
                }
            }
        }
    }
}

@Composable
private fun ComingPane(coming: List<TaskEntity>) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
fun ReminderSheet(t: TaskEntity, on: Boolean, exact: Boolean, onToggle: (Boolean) -> Unit, onTime: (String) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) { ReminderContent(t, on, exact, onToggle, onTime); Spacer(Modifier.height(SheetBottom)) }
}

/**
 * A habit's reminder: one switch and Apple's time wheel. On = a notification at that time on the days it repeats,
 * with a Done button that ticks it even offline. Without the exact-alarm grant it says it may come up to 10 min late.
 */
@Composable
fun ReminderContent(t: TaskEntity, on: Boolean, exact: Boolean, onToggle: (Boolean) -> Unit, onTime: (String) -> Unit) {
    val time = t.due?.drop(11)?.trim()?.takeIf(String::isNotEmpty) ?: "08:00"
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
        val hint = when {
            !on -> "Off. The habit still shows here every day."
            exact -> "At $time on the days it repeats, with a Done button."
            else -> "Alarms & reminders is off for PAGEL, so it may come up to 10 min late."
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = if (on && !exact) Glass.Risk else Glass.Muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
    }
}

/** Apple's switch: a capsule that fills mint, a white knob that springs across. Drawn only; the row is the target. */
@Composable
private fun Switch(on: Boolean) {
    val x by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "knob")
    Box(
        Modifier.size(width = 51.dp, height = 31.dp).background(if (on) Glass.Primary else Glass.Ink.copy(alpha = 0.16f), CircleShape).padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.graphicsLayer { translationX = x * 20.dp.toPx() }.size(27.dp).background(Color.White, CircleShape))
    }
}
