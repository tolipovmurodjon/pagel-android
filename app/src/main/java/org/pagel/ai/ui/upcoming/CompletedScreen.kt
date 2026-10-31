package org.pagel.ai.ui.upcoming

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.motion.blackhole.BlackHoleOverlay
import org.pagel.ai.ui.motion.blackhole.BlackHolePrewarm
import org.pagel.ai.ui.motion.blackhole.BlackHoleRun
import org.pagel.ai.ui.motion.blackhole.BlackHoleTiming
import org.pagel.ai.ui.motion.blackhole.PieceRect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.upcoming.DoneGroup
import org.pagel.ai.data.upcoming.doneTimeOf
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressOnTouch
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.projects.shortName
import org.pagel.ai.ui.task.LocalTaskOpener
import org.pagel.ai.ui.today.headline

@Composable
fun CompletedRoute(contentPadding: PaddingValues, reselect: Int, vm: CompletedViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    CompletedScreen(ui, contentPadding, reselect, onReopen = vm::reopen, onClearDone = { vm.clearDone() })
}

/**
 * Completed, native (Phase 4, the web's /completed, grouped by day as Things' Logbook): Today, Yesterday, weekday
 * names, then dates, each task with the time it was done and its project tile. A tap opens its panel (read only for a
 * done task); the filled check opens it again (pagel.uz's undone), Room first, with Undo.
 */
@Composable
fun CompletedScreen(ui: CompletedUi, contentPadding: PaddingValues, reselect: Int = 0, onReopen: (String) -> Unit = {}, onClearDone: () -> Unit = {}) {
    val list = rememberLazyListState()
    val openTask = LocalTaskOpener.current
    LaunchedEffect(reselect) { if (reselect > 0) list.animateScrollToItem(0) }
    // "Clear done" (black-hole brief; v2: the hole opens out of the camera): the list is recorded into a layer as it draws, so a tap can snapshot it before
    // the rows leave Room; the black hole then plays over that snapshot while the data is already gone.
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var hole by remember { mutableStateOf<BlackHoleRun?>(null) }
    // Laboratory → Black hole → "Use in the app" (on by default): off, and the rows just leave.
    val holeOn by remember { org.pagel.ai.ui.lab.Experiments.isOn(context, org.pagel.ai.ui.lab.Experiments.BLACK_HOLE) }.collectAsStateWithLifecycle(initialValue = true)
    // Undo mid-run: the effect fades out in 120 ms (never cut in one frame) while the rows come back underneath.
    var holeStop by remember { mutableStateOf(false) }
    LaunchedEffect(ui.clearUndone) { if (ui.clearUndone > 0 && hole != null) holeStop = true }
    val clear: () -> Unit = {
        // Undispatched: the snapshot starts in the tap's own frame, before Room can redraw the list without the rows.
        // The data never waits longer than SNAPSHOT_WAIT_MS for it; without a snapshot the rows just leave.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val info = list.layoutInfo
            val pieces = donePieces(info, with(density) { STRIP.toPx() })
            val shot = withTimeoutOrNull(SNAPSHOT_WAIT_MS) { runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull() }
            onClearDone()
            holeStop = false
            if (holeOn && shot != null && pieces.isNotEmpty()) hole = BlackHoleRun(System.nanoTime(), shot, pieces, BlackHoleTiming.ClearAll)
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
            state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "head", contentType = "head") {
                Row(Modifier.fillMaxWidth().padding(start = Space.textEdge, end = Space.md, top = Space.md), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("The proof that the list moves", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                        Text("Completed", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
                        Text(if (ui.count == 1) "1 task done" else "${ui.count} tasks done", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                    }
                    if (!ui.loading && ui.groups.isNotEmpty()) ClearDoneButton(clear)
                }
            }
        when {
            ui.loading -> item(key = "loading", contentType = "note") {
                Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(3, Modifier.padding(vertical = 6.dp)) }
            }
            ui.allClear -> item(key = "clear", contentType = "note") {
                Pane(Modifier.fillMaxWidth().padding(horizontal = Space.lg)) {
                    Column(Modifier.padding(Space.xl)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("All clear", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                            Spacer(Modifier.width(Space.sm))
                            Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(Space.xl))
                        }
                        Text("The finished tasks are cleared. Undo brings them back.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
                    }
                }
            }
            ui.groups.isEmpty() -> item(key = "none", contentType = "note") {
                Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Nothing finished yet", style = MaterialTheme.typography.titleMedium, color = Glass.Ink)
                        Text("Tick a task and it shows here, by day.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
                    }
                }
            }
            else -> for (g in ui.groups) item(key = "day-${g.day}", contentType = "day") {
                DayPane(g, ui, onOpen = openTask, onReopen = onReopen, modifier = Modifier.animateItem())
            }
        }
        }
        BlackHoleOverlay(hole, onEnd = { hole = null; holeStop = false }, Modifier.matchParentSize(), stop = holeStop)
        // The ring shader is built and drawn once now, so the first Clear done opens without a compile hitch.
        BlackHolePrewarm()
    }
}

/** A day pane is cut into strips this tall: each strip flies as its own piece. */
private val STRIP = 72.dp
private const val SNAPSHOT_WAIT_MS = 150L

/** The visible day panes, cut into strips, in the list's own px (the snapshot's coordinates). */
internal fun donePieces(info: LazyListLayoutInfo, stripPx: Float): List<PieceRect> {
    val shift = -info.viewportStartOffset
    val height = info.viewportSize.height.toFloat()
    val width = info.viewportSize.width.toFloat()
    return info.visibleItemsInfo.filter { it.contentType == "day" }.flatMap { item ->
        val top = max(0f, (item.offset + shift).toFloat())
        val bottom = min(height, (item.offset + shift + item.size).toFloat())
        if (bottom - top < 1f) return@flatMap emptyList()
        val n = max(1, ceil((bottom - top) / stripPx).toInt())
        val h = (bottom - top) / n
        (0 until n).map { k -> PieceRect(0f, top + k * h, width, top + (k + 1) * h) }
    }
}

/** "Clear done": a mint pill with the bin; the whole 48 dp is the touch box. */
@Composable
private fun ClearDoneButton(onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.heightIn(min = Space.touch).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Clear done" },
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.background(Glass.PrimarySoft, RoundedCornerShape(Space.lg)).padding(horizontal = Space.md, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Icon(TabIcons.Trash, contentDescription = null, tint = Glass.PrimaryInk, modifier = Modifier.size(Space.lg))
            Spacer(Modifier.width(Space.xs))
            Text("Clear done", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, maxLines = 1)
        }
    }
}

@Composable
private fun DayPane(g: DoneGroup, ui: CompletedUi, onOpen: (String) -> Unit, onReopen: (String) -> Unit, modifier: Modifier) {
    Pane(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(bottom = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.label, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.weight(1f).semantics { heading() })
                Text("${g.tasks.size}", style = Glass.NumberText, color = Glass.Muted, modifier = Modifier.semantics { contentDescription = "${g.tasks.size} done" })
            }
            for ((i, t) in g.tasks.withIndex()) key(t.id) {
                val leaving = t.id in ui.reopening
                // Unticked: the check empties first, then the row folds away; it never vanishes in one frame (checker P4 #5).
                AnimatedVisibility(
                    visible = !leaving,
                    enter = EnterTransition.None,
                    exit = shrinkVertically(tween(420, delayMillis = 200, easing = FastOutSlowInEasing)) + fadeOut(tween(320, delayMillis = 200)),
                ) {
                    Column {
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                        DoneRow(t, ui, leaving, onOpen, onReopen)
                    }
                }
            }
        }
    }
}

/** One done task: the filled check (tap = open it again), the title, then the time it was done · project. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DoneRow(t: TaskEntity, ui: CompletedUi, leaving: Boolean, onOpen: (String) -> Unit, onReopen: (String) -> Unit) {
    val title = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)
    val haptics = LocalHapticFeedback.current
    // The disc drains back into an empty ring (no bounce: a tap has no momentum).
    val fill by animateFloatAsState(if (leaving) 0f else 1f, spring(dampingRatio = 1f, stiffness = 500f), label = "undone")
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp), verticalAlignment = Alignment.Top) {
        val press = remember { MutableInteractionSource() }
        Box(
            Modifier.size(48.dp).pressScale(press)
                .clickable(press, indication = null, enabled = !leaving, role = Role.Checkbox) { haptics.performHapticFeedback(HapticFeedbackType.ToggleOff); onReopen(t.id) }
                .semantics { contentDescription = "Undo: $title"; stateDescription = if (leaving) "Not done" else "Done" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).border(1.5.dp, Glass.Ink.copy(alpha = 0.30f * (1f - fill)), CircleShape))
            // Bright mint with an ink tick: done, without a 4th green (Boss's colour theory).
            Box(
                Modifier.size(22.dp).graphicsLayer { scaleX = fill; scaleY = fill; alpha = fill }
                    .background(Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TabIcons.Check, contentDescription = null, tint = Glass.Ink, modifier = Modifier.size(14.dp))
            }
        }
        val open = remember { MutableInteractionSource() }
        Column(
            Modifier.weight(1f).pressOnTouch().clickable(open, indication = null, role = Role.Button, onClickLabel = "Open") { onOpen(t.id) }
                .padding(top = 12.dp, bottom = 10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Glass.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val small = MaterialTheme.typography.labelSmall
            val icon = with(LocalDensity.current) { 13.sp.toDp() }
            FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val time = t.doneAt?.let(::doneTimeOf).orEmpty()
                if (time.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(TabIcons.Clock, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(icon))
                    Spacer(Modifier.width(4.dp))
                    Text(time, style = small, color = Glass.Muted, maxLines = 1)
                }
                val p = ui.projects[t.project]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProjectTile(t.project, p?.color, p?.title ?: t.project, size = 16.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(shortName(t.project, p?.title ?: t.project), style = small, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
