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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
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
fun TodayRoute(contentPadding: PaddingValues, reselect: Int, vm: TodayViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    TodayScreen(ui, contentPadding, reselect, onTick = vm::tick, onUndo = vm::undo)
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
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val toasts = LocalToasts.current
    val openTask = LocalTaskOpener.current
    val reduce = remember { reducedMotion(ctx) }
    val bursts = remember { mutableStateListOf<Burst>() }
    var nextBurst by remember { mutableLongStateOf(0L) }
    var overlayAt by remember { mutableStateOf(Offset.Zero) }
    val list = rememberLazyListState()
    // Rows seen so far: a row that appears later is the next task being dealt, and slides in.
    val seen = remember { mutableSetOf<String>() }
    SideEffect { seen += ui.rows.map(TodayRow::id) }

    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }

    fun finish(row: TodayRow, ringCentre: Offset) {
        // Respond first (rules/respond-first-save-after): feel, sound and picture now, the save goes behind.
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch(Dispatchers.Default) { Ding.play() }
        if (!reduce) bursts += Burst(nextBurst++, ringCentre - overlayAt)
        onTick(row.id)
        toasts.show("Done", action = "Undo") { onUndo(row.id) }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { overlayAt = it.positionInRoot() }) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "head", contentType = "head") { Header(ui, today) }
            when {
                ui.loading -> item(key = "loading", contentType = "note") {
                    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(3, Modifier.padding(vertical = 6.dp)) }
                }
                ui.allDone -> item(key = "done", contentType = "note") {
                    Note("All done for today ✓", "Pick the next ones on pagel.uz; they show here in a second.")
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
private fun Header(ui: TodayUiState, today: LocalDate) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)) {
        Text(today.format(HEAD_DATE), style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        Text("Today", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
        val count = when {
            ui.open == 0 -> "0 today"
            else -> "${ui.open} today"
        }
        Text(if (ui.status.isEmpty()) count else "$count · ${ui.status}", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
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
