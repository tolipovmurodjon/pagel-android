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
import org.pagel.ai.data.upcoming.UpcomingDay
import org.pagel.ai.data.upcoming.plannedLine
import org.pagel.ai.data.upcoming.stripDayAt
import org.pagel.ai.ui.glass.Glass
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
import java.time.LocalDate
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
    UpcomingScreen(ui, contentPadding, reselect, onMove = vm::reschedule, onMoveAll = vm::moveAllToToday, onFinish = vm::finish)
}

/** A row being dragged to another day: where the finger holds it, where it started, the strip day under the finger. */
@Stable
private class Drag {
    var id by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var shown by mutableStateOf(false)
    var hovered by mutableStateOf<Int?>(null)
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
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val openTask = LocalTaskOpener.current
    val list = rememberLazyListState()
    val drag = remember { Drag() }
    var rootAt by remember { mutableStateOf(Offset.Zero) }
    // The strip's place on screen, read when a drag needs it (one node, outside the list).
    val strip = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val slop = with(LocalDensity.current) { 28.dp.toPx() }
    val days by rememberUpdatedState(ui.view.days)
    val moveTo by rememberUpdatedState(onMove)
    val overdueFirst = if (ui.view.overdue.isNotEmpty()) 1 else 0

    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    // One tick for each day the finger crosses (Apple's picker detents), none when it leaves the strip.
    LaunchedEffect(drag.hovered) { if (drag.hovered != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    val over = drag.hovered != null
    LaunchedEffect(over) { drag.lift.animateTo(if (over) 1f else 0f, spring(dampingRatio = 1f, stiffness = 600f)) }
    // "You are here": the day at the top of the list gets a thin ink ring on the strip (today is marked already).
    val atTop by remember {
        derivedStateOf {
            val k = list.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith("day-") == true }?.key as? String
            days.indexOfFirst { "day-${it.day}" == k }.takeIf { it > 0 }
        }
    }

    fun dayUnder(finger: Offset): Int? {
        val c = strip[0]?.takeIf { it.isAttached } ?: return null
        val at = c.positionInRoot()
        return stripDayAt(finger.x - at.x, finger.y - at.y, c.size.width.toFloat(), c.size.height.toFloat(), days.size, slop)
    }
    fun cellCentre(i: Int): Offset? {
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
                val i = if (cancelled) null else drag.hovered
                drag.hovered = null
                val target = i?.let(::cellCentre)
                if (id != null && i != null && target != null && i in days.indices) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    moveTo(id, days[i].day)
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

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootAt = it.positionInRoot(); drag.screenWidth = it.size.width.toFloat() }) {
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            // Only the strip is pinned (it is the drop target); the title scrolls with the list, so landscape and 1.5x
            // keep room for the days (checker P4 #3: title + strip took ~180 dp).
            WeekStrip(
                ui.view.days, ui.today, drag.hovered, atTop,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).onGloballyPositioned { strip[0] = it },
            ) { i -> scope.launch { list.animateScrollToItem(if (i == 0) 0 else 1 + overdueFirst + i) } } // today = the top, Overdue included
            // Under the pinned strip the scrolled list melts away (a 20 dp fade) instead of a hard cut row (Boss
            // 2026-10-28: "05:30 Daily Me" peeked out clipped under the strip). Only once scrolled: at rest the title is whole.
            val fade by animateFloatAsState(if (list.canScrollBackward) 1f else 0f, label = "fade")
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f)
                    .graphicsLayer { compositingStrategy = if (fade > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
                    .drawWithContent {
                        drawContent()
                        if (fade > 0f) drawRect(
                            Brush.verticalGradient(0f to Color.Black.copy(alpha = 1f - fade), 1f to Color.Black, startY = 0f, endY = 20.dp.toPx()),
                            size = Size(size.width, 20.dp.toPx()), blendMode = BlendMode.DstIn,
                        )
                    },
                state = list,
                contentPadding = PaddingValues(top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "head", contentType = "head") { Header(ui) }
                when {
                    ui.loading -> item(key = "loading", contentType = "note") {
                        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(3, Modifier.padding(vertical = 6.dp)) }
                    }
                    else -> {
                        if (ui.view.overdue.isNotEmpty()) item(key = "overdue", contentType = "overdue") {
                            OverduePane(ui, drag, handlers, onMoveAll = { onMoveAll(ui.view.overdue.map(TaskEntity::id)) }, onOpen = openTask, onFinish = onFinish,
                                modifier = Modifier.animateItem())
                        }
                        for ((i, d) in ui.view.days.withIndex()) item(key = "day-${d.day}", contentType = "day") {
                            DayPane(d, i, ui, drag, handlers, onOpen = openTask, onFinish = onFinish, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
        if (drag.shown) Ghost(drag, rootAt)
    }
}

/** What a row needs to start, move and end a drag; the screen owns the state. */
private class DragHandlers(
    val start: (TaskEntity, Offset, Offset, IntSize) -> Unit,
    val move: (Offset) -> Unit,
    val end: (cancelled: Boolean) -> Unit,
)

private fun titleOf(t: TaskEntity) = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)

@Composable
private fun Header(ui: UpcomingUi) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)) {
        Text("Next 7 days", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        Text("Upcoming", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
        Text(plannedLine(ui.view), style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
    }
}

/**
 * The week strip: the weekday, the date in a circle, a dot when the day has tasks. Today is bright mint with an ink
 * number; the day under a dragged row fills ink and grows, so the drop target is never in doubt.
 */
@Composable
private fun WeekStrip(days: List<UpcomingDay>, today: LocalDate, hovered: Int?, shown: Int?, modifier: Modifier, onTap: (Int) -> Unit) {
    Pane(modifier.fillMaxWidth(), radius = 22.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
            for ((i, d) in days.withIndex()) key(d.day) {
                val isToday = d.day == today
                val over = hovered == i
                // No bounce: hovering has no momentum (apple §4; checker P4 #10 had .6).
                val grow by animateFloatAsState(if (over) 1.16f else 1f, spring(dampingRatio = 1f, stiffness = 700f), label = "cell")
                val press = remember { MutableInteractionSource() }
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp).pressScale(press)
                        .clickable(press, indication = null, role = Role.Button, onClickLabel = "Show") { onTap(i) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = d.day.format(SPOKEN) + when (d.tasks.size) { 0 -> ""; 1 -> ", 1 task"; else -> ", ${d.tasks.size} tasks" }
                            selected = isToday
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(d.day.format(LETTER), style = MaterialTheme.typography.labelSmall, color = if (isToday) Glass.PrimaryInk else Glass.Muted, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.size(34.dp).graphicsLayer { scaleX = grow; scaleY = grow }
                            .then(
                                when {
                                    over -> Modifier.background(Glass.Ink, CircleShape)
                                    isToday -> Modifier.background(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), CircleShape)
                                    shown == i -> Modifier.border(1.25.dp, Glass.Ink, CircleShape)
                                    else -> Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            d.day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            color = if (over) Color.White else Glass.Ink,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(5.dp).background(if (d.tasks.isNotEmpty()) Glass.Primary else Color.Transparent, CircleShape))
                }
            }
        }
    }
}

/** A pane's title and its one action; at large font the action wraps under the title ("Overd/ue" at 1.5x shared a row). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PaneTitle(text: String, color: Color = Glass.Ink, trailing: (@Composable () -> Unit)? = null) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 8.dp),
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
        val time = t.due?.drop(11)?.trim()?.takeIf(String::isNotEmpty)
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
