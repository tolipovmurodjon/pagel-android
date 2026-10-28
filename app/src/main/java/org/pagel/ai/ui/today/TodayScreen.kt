package org.pagel.ai.ui.today

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import org.pagel.ai.ui.glass.TabIcons
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.pagel.ai.ui.glass.EaseOutStrong
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.glass.pressOnTouch
import org.pagel.ai.ui.glass.LocalToasts
import org.pagel.ai.ui.task.LocalTaskOpener
import org.pagel.ai.ui.projects.shortName
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** pagel.uz counts days in Tashkent (today-list.tsx), so "Today" and "late" mean the same on both. */
private val TASHKENT = ZoneId.of("Asia/Tashkent")
private val HEAD_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

/** Todoist's flag colours, as the web: P0 red, P1 orange, P2 blue, P3 none (today-list.tsx FLAG). */
private val FLAGS = listOf(Color(0xFFD1453B), Color(0xFFEB8909), Color(0xFF246FE0))
private val RingIdle = Color(0xFF9AA2BB)

@Composable
fun TodayRoute(
    contentPadding: PaddingValues, reselect: Int, links: org.pagel.ai.ui.rituals.RitualLinks = org.pagel.ai.ui.rituals.RitualLinks(),
    vm: TodayViewModel = hiltViewModel(), rituals: org.pagel.ai.ui.rituals.RitualPillsViewModel = hiltViewModel(),
    compass: org.pagel.ai.ui.compass.CompassLineViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pills by rituals.pills.collectAsStateWithLifecycle()
    val line by compass.line.collectAsStateWithLifecycle()
    TodayScreen(ui, contentPadding, reselect, onTick = vm::tick, onUndo = vm::undo, pills = pills, links = links, onSendNow = vm::sendNow, compassLine = line)
}

/** Today, native (NATIVE-PLAN Phase 2): the web's 3 at a time, a ring tick with ding, confetti and fold, undo. */
@Composable
fun TodayScreen(
    ui: TodayUiState,
    contentPadding: PaddingValues,
    reselect: Int = 0,
    onTick: (String) -> Unit = {},
    onUndo: (String) -> Unit = {},
    today: LocalDate = LocalDate.now(TASHKENT),
    /** The rituals' entry points (Phase 5): Empty your head · Close the day · the weekly review when due. */
    pills: org.pagel.ai.ui.rituals.RitualPills? = null,
    links: org.pagel.ai.ui.rituals.RitualLinks = org.pagel.ai.ui.rituals.RitualLinks(),
    onSendNow: () -> Unit = {},
    /** The Compass's quiet line (Phase 6a): his chosen goal's first words, only when he turned it on. */
    compassLine: String? = null,
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val toasts = LocalToasts.current
    val openTask = LocalTaskOpener.current
    val reduce = remember { reducedMotion(ctx) }
    val closed = pills?.shutTonight == true
    val bursts = remember { mutableStateListOf<Burst>() }
    var nextBurst by remember { mutableLongStateOf(0L) }
    var overlayAt by remember { mutableStateOf(Offset.Zero) }
    var overlayWidth by remember { mutableStateOf(0f) }
    // The next task, said once after a tick ("Next: Call the bank · 10 min?"), gone after 8 s.
    var nudge by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(nudge) { if (nudge != null) { delay(8_000); nudge = null } }
    val list = rememberLazyListState()
    // Rows seen so far: a row that appears later is the next task being dealt, and slides in.
    val seen = remember { mutableSetOf<String>() }
    SideEffect { seen += ui.rows.map(TodayRow::id) }

    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }

    fun finish(row: TodayRow, ringCentre: Offset) {
        // Respond first (rules/respond-first-save-after): feel, sound and picture now, the save goes behind.
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        val app = ctx.applicationContext
        val cleared = ui.open == 1 && !row.done
        // The run going up (checker #11): three or more in a row is a bigger burst and a third, higher note. The day
        // cleared is the shell's (DayClear): one full-screen burst from the centre and the chord, from any screen.
        val big = Combo.app.next(System.currentTimeMillis()) >= 3
        if (!cleared) scope.launch(Dispatchers.Default) { if (big) Ding.combo(app) else Ding.play(app) }
        bursts += Burst(nextBurst++, ringCentre - overlayAt, power = if (big) 1 else 0)
        val inRow = Combo.app.tick(System.currentTimeMillis())
        nudge = if (cleared) null else nudgeText(ui.rows.firstOrNull { !it.done && it.id != row.id }?.title)
        onTick(row.id)
        toasts.show(if (cleared) "Day cleared ✨" else comboText(inRow), action = "Undo") { Combo.app.undo(); onUndo(row.id) }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { overlayAt = it.positionInRoot(); overlayWidth = it.size.width.toFloat() }) {
        // One gap between all blocks (spacing audit #19: 16 under the header, 12 under the bar, 8 under the pills).
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(Space.headerToCard)) {
            if (compassLine != null) item(key = "compass", contentType = "compass") { org.pagel.ai.ui.compass.CompassTodayLine(compassLine) }
            item(key = "head", contentType = "head") {
                Header(ui, today, closed = closed, onSweep = if (pills != null) links.sweep else null) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSendNow() }
            }
            // Boss 2026-10-28: "I closed the day … I should have an empty Today, so I can sleep well". After Close the
            // day, Today is one calm card and a folded peek at tomorrow; no rows, no bar, no leftover habits.
            if (closed) {
                if (pills != null && pills.any) item(key = "rituals", contentType = "pills") {
                    org.pagel.ai.ui.rituals.RitualPillsRow(pills, links, Modifier.padding(horizontal = Space.gutter))
                }
                item(key = "closed", contentType = "note") { DayClosedCard(ui.doneToday, ui.tomorrow) }
                return@LazyColumn
            }
            // The day's progress grows with each tick (a game's level bar), with the next task's nudge under it.
            if (!ui.loading && ui.progress.total > 0) item(key = "progress", contentType = "progress") {
                DayProgressBar(ui.progress, nudge, Modifier.padding(horizontal = Space.gutter))
            }
            // One row, only when it has something to say: Close the day in the evening, the review while due (checker P5 #4).
            if (pills != null && pills.any) item(key = "rituals", contentType = "pills") {
                org.pagel.ai.ui.rituals.RitualPillsRow(pills, links, Modifier.animateItem().padding(horizontal = Space.gutter))
            }
            when {
                ui.loading -> item(key = "loading", contentType = "note") {
                    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(3, Modifier.padding(vertical = 6.dp)) }
                }
                // Also after a sync took the finished rows off Today: tasks were done today and none is open.
                ui.allDone || (ui.rows.isEmpty() && ui.open == 0 && ui.doneToday > 0) -> item(key = "done", contentType = "note") {
                    DayClearedCard(ui.doneToday, ui.clearedStreak)
                }
                ui.rows.isEmpty() -> item(key = "empty", contentType = "note") {
                    Note("Nothing picked yet", "Star up to 3 tasks on pagel.uz and they show here.")
                }
                else -> item(key = "list", contentType = "list") {
                    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            for ((i, row) in ui.rows.withIndex()) key(row.id) {
                                if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                                TaskRow(row, today, isNew = seen.isNotEmpty() && row.id !in seen, reduce = reduce, onOpen = { openTask(row.id) }) { centre -> finish(row, centre) }
                            }
                            if (ui.waiting > 0) {
                                // "+2" waiting: one capsule, not a sentence (Boss: one icon beats many words).
                                Text("+${ui.waiting}", style = Glass.NumberText, color = Glass.PrimaryInk,
                                    modifier = Modifier.padding(start = 54.dp, top = 4.dp, bottom = 10.dp).background(Glass.PrimarySoft, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 3.dp)
                                        .semantics { contentDescription = "${ui.waiting} more after these" })
                            }
                        }
                    }
                }
            }
        }
        for (b in bursts) key(b.id) { ConfettiBurst(b, onEnd = { bursts.remove(b) }, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Header(ui: TodayUiState, today: LocalDate, closed: Boolean = false, onSweep: (() -> Unit)? = null, onSendNow: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f).padding(start = Space.textEdge, end = Space.textEdge, top = Space.headerTop)) {
        Text(today.format(HEAD_DATE), style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        Text("Today", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
        // One counting language on Today: "12 done · 4 left" lives in the bar; the header names the day only.
        val count = if (closed) "Closed for tonight" else "${ui.open} left"
        // Only "Offline" is worth a word (PHASE-BRIEF: no "Syncing"); the waiting chip already says what waits.
        Text(if (ui.status != "Offline") count else "$count · Offline", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
    }
    // Round B (A): what waits to reach pagel.uz is never invisible; a tap sends it now. In the header's row (taller than
    // the chip), never a list item: the list never moves when it comes or goes (spacing audit #3).
    // The chip keeps the 16 dp edge the left side keeps (checker #10: it sat 4 dp from the edge).
    if (ui.outbox > 0) WaitingChip(ui.outbox, Modifier.padding(end = if (onSweep == null) Space.lg else Space.xs), onClick = onSendNow)
    // Empty your head: a 48 dp sparkle beside the title, any time of day (Phase 5).
    if (onSweep != null) org.pagel.ai.ui.glass.GlassIconButton(TabIcons.Sparkles, "Empty your head", Modifier.padding(end = 12.dp), tint = Glass.Primary, onClick = onSweep)
    }
}

/** "↑ 2 waiting": a small glass chip while changes wait for pagel.uz; a tap sends them now. */
@Composable
internal fun WaitingChip(n: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier.heightIn(min = 48.dp).pressScale(press).clickable(press, indication = null, role = Role.Button, onClickLabel = "Send now", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$n waiting to send · Send now" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(16.dp)).border(1.dp, Glass.Aurora1.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("↑", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk)
            Spacer(Modifier.width(6.dp))
            Text("$n waiting", style = MaterialTheme.typography.labelLarge, color = Glass.Ink, maxLines = 1)
        }
    }
}

/** "2 of 5 ✓" and a mint bar that grows with each tick (spring), with the next task's nudge under it while it shows. */
@Composable
internal fun DayProgressBar(p: DayProgress, nudge: String?, modifier: Modifier = Modifier) {
    val grown by androidx.compose.animation.core.animateFloatAsState(p.fraction, spring(dampingRatio = 0.8f, stiffness = 300f), label = "progress")
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "${p.done} of ${p.total} done today" }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(8.dp).background(Glass.Ink.copy(alpha = 0.07f), RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth(grown.coerceIn(0f, 1f)).height(8.dp).background(Brush.horizontalGradient(listOf(Glass.Aurora1, Glass.Primary)), RoundedCornerShape(4.dp)))
            }
            Spacer(Modifier.width(10.dp))
            // slot: tabular figures in a fixed least width, so "9 of 10 ✓" and "1 of 6 ✓" never change the bar's length (#22).
            Text(p.text, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = Glass.PrimaryInk, maxLines = 1, softWrap = false,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 128.dp))
        }
        // slot: the nudge's line is always laid out (empty and clear when there is none), so the tasks under it never
        // drop 22 dp after a tick and rise 8 s later (spacing audit #4).
        val shown by androidx.compose.animation.core.animateFloatAsState(if (nudge != null) 1f else 0f, label = "nudge")
        var last by remember { mutableStateOf("") }
        if (nudge != null) last = nudge
        Text(last, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, minLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).graphicsLayer { alpha = shown })
    }
}

/** The whole day done: a big glass card, "Day cleared ✨", how many, and the streak of cleared days. */
@Composable
internal fun DayClearedCard(done: Int, streak: Int) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✨", style = MaterialTheme.typography.headlineLarge)
            Text("Day cleared", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            Text(if (done == 1) "1 task done today" else "$done tasks done today", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
            if (streak >= 1) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (streak == 1) "🔥 First cleared day" else "🔥 $streak days cleared in a row", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk,
                    modifier = Modifier.background(Glass.PrimarySoft, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Add the next one with +.", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Note(title: String, line: String) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
        }
    }
}

/**
 * One task: the ring, the title, then when + project. Ticked: the ring fills and pops, the tick draws itself, and
 * after 0.85 s the row folds in 0.38 s (the web's "finish moment"). A newly dealt row slides up into the free slot.
 */
@Composable
private fun TaskRow(row: TodayRow, today: LocalDate, isNew: Boolean, reduce: Boolean, onOpen: () -> Unit, onTick: (Offset) -> Unit) {
    val fold = remember { Animatable(1f) }
    val enter = remember { Animatable(if (isNew && !reduce) 0f else 1f) }
    LaunchedEffect(Unit) { if (enter.value < 1f) enter.animateTo(1f, tween(460, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.15f))) }
    LaunchedEffect(row.done) {
        if (!row.done) fold.snapTo(1f)
        else if (!reduce) { delay(850); fold.animateTo(0f, tween(380, easing = FastOutSlowInEasing)) }
    }
    val titleColor by animateColorAsState(if (row.done) Glass.Muted else Glass.Ink, tween(300), label = "title")

    Row(
        Modifier.fillMaxWidth()
            // Height and fade are read in the layout and draw phases only: the fold never recomposes the list.
            .layout { m, c -> val p = m.measure(c); layout(p.width, (p.height * fold.value).roundToInt()) { p.place(0, 0) } }
            .clipToBounds()
            .graphicsLayer { alpha = fold.value * enter.value; translationY = (1f - enter.value) * 14.dp.toPx() }
            .padding(start = 6.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TickRing(row.priority, row.done, row.title, onTick)
        // The row opens the task panel (the ring stays the tick), pressed the moment the finger lands (audit #6).
        val open = remember { MutableInteractionSource() }
        Column(
            Modifier.weight(1f).pressOnTouch()
                .clickable(open, indication = null, enabled = !row.done, role = Role.Button, onClickLabel = "Open") { onOpen() }
                .padding(top = 12.dp, bottom = 10.dp),
        ) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Meta(row, today)
        }
    }
}

@Composable
private fun Meta(row: TodayRow, today: LocalDate) {
    // The web Habits card's meta line, 12.5 sp, in the body face (mono only on numbers, checker 2026-10-28 #6).
    val small = MaterialTheme.typography.labelSmall
    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        dueLabel(row.due, today)?.let { d ->
            val c = when (d.tone) { DueTone.LATE -> Glass.Risk; DueTone.TODAY -> Glass.Primary; else -> Glass.Muted }
            androidx.compose.material3.Icon(TabIcons.Calendar, contentDescription = null, tint = c, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(d.text, style = small, color = c)
            Spacer(Modifier.width(10.dp))
        }
        row.every?.let {
            // The same words as the project page and the panel ("Weekly", "Every Tue, Thu"), not the raw "↻ week".
            androidx.compose.material3.Icon(TabIcons.Habits, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(org.pagel.ai.ui.glass.Repeat.label(it), style = small, color = Glass.Muted, maxLines = 1)
            Spacer(Modifier.width(10.dp))
        }
        ProjectTile(row.project, row.projectColor, row.projectTitle, size = 16.dp)
        Spacer(Modifier.width(5.dp))
        Text(shortName(row.project, row.projectTitle), style = small, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The tick ring: 22 dp drawn, 48 dp to touch. Its centre goes to [onTick] so the confetti starts from it. */
@Composable
internal fun TickRing(priority: Int, done: Boolean, title: String, onTick: (Offset) -> Unit) {
    val flag = FLAGS.getOrNull(priority)
    val press = remember { MutableInteractionSource() }
    var centre by remember { mutableStateOf(Offset.Zero) }
    val pop = remember { Animatable(1f) }
    val draw = remember { Animatable(if (done) 1f else 0f) }
    LaunchedEffect(done) {
        if (!done) { draw.snapTo(0f); return@LaunchedEffect }
        launch { pop.animateTo(1.28f, tween(170, easing = EaseOutStrong)); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
        delay(80)
        draw.animateTo(1f, tween(300, easing = EaseOutStrong))
    }
    Box(
        Modifier.size(48.dp)
            .onGloballyPositioned { centre = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
            .pressScale(press)
            .clickable(press, indication = null, enabled = !done, role = Role.Checkbox) { onTick(centre) }
            .semantics { contentDescription = "Done: $title" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
            val r = size.minDimension / 2
            val line = 1.5.dp.toPx()
            // Done = bright mint with an ink tick, as Habits, Completed and the panel draw it (no 4th green).
            if (done) drawCircle(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), r)
            else {
                if (flag != null) drawCircle(flag.copy(alpha = 0.08f), r)
                drawCircle(flag ?: RingIdle, r - line / 2, style = Stroke(line))
            }
            // The tick (M5.5 10.5 l3 3 6-7 on a 20-unit box), drawn up to [draw] of its length.
            val u = size.minDimension / 20f
            val a = Offset(5.5f * u, 10.5f * u); val b = Offset(8.5f * u, 13.5f * u); val c = Offset(14.5f * u, 6.5f * u)
            val first = (b - a).getDistance(); val total = first + (c - b).getDistance()
            val d = draw.value * total
            val w = 2.2f * u
            if (d > 0f) drawLine(Glass.Ink, a, if (d <= first) a + (b - a) * (d / first) else b, w, StrokeCap.Round)
            if (d > first) drawLine(Glass.Ink, b, b + (c - b) * ((d - first) / (total - first)), w, StrokeCap.Round)
        }
    }
}

/**
 * The closed day: "Day closed 🌙 · Nothing needs you tonight", what was done, and tomorrow's stars folded into one line
 * ("Tomorrow · 3", a tap to peek). Boss 2026-10-28: an empty Today after Close the day, so he can sleep well.
 */
@Composable
internal fun DayClosedCard(done: Int, tomorrow: List<TodayRow>) {
    var peek by remember { mutableStateOf(false) }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🌙", style = MaterialTheme.typography.headlineLarge)
            Text("Day closed", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Space.xs))
            Text("Nothing needs you tonight", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
            if (done > 0) {
                Spacer(Modifier.height(Space.md))
                Text("$done done today ✓", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk,
                    modifier = Modifier.background(Glass.PrimarySoft, CircleShape).padding(horizontal = Space.lg, vertical = Space.sm))
            }
            if (tomorrow.isNotEmpty()) {
                Spacer(Modifier.height(Space.lg))
                val press = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Space.touch).pressScale(press)
                        .clickable(press, indication = null, role = Role.Button, onClickLabel = if (peek) "Fold" else "Peek") { peek = !peek }
                        .semantics(mergeDescendants = true) { contentDescription = "Tomorrow · ${tomorrow.size}" + if (peek) ", shown" else ", folded" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(TabIcons.Sunrise, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.sm))
                    Text("Tomorrow · ${tomorrow.size}", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.weight(1f))
                    Icon(if (peek) TabIcons.ChevronDown else TabIcons.ChevronRight, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp))
                }
                if (peek) for (t in tomorrow) Text("☆  ${t.title}", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(start = Space.xxl, top = Space.xs))
            }
        }
    }
}
