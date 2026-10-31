package org.pagel.ai.ui.habits

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.pagel.ai.data.habits.minutesOf
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TileColors
import org.pagel.ai.ui.glass.pressScale

/** The five colours the timer swipes through (ours, never red): each is a tile colour taken deep enough for white words. */
internal val TIMER_COLOURS = listOf("blue", "violet", "teal", "mint", "slate")

internal fun timerBackground(page: Int): Pair<Color, Color> {
    val (a, b) = TileColors.stops("", TIMER_COLOURS[page.coerceIn(0, TIMER_COLOURS.lastIndex)])
    return lerp(a, Color.Black, 0.40f) to lerp(b, Color.Black, 0.50f)
}

/**
 * The full-screen timer of a habit with minutes (Boss's reference: a colour, one big ring, huge time, a white Start pill,
 * "swipe to change the colour"). It runs the same clock as the card's play ring, so either can start it and the other
 * shows it. The bell: ring at the goal, with one of three soft sounds made here. Ambient music is not in this build.
 */
@Composable
fun HabitTimerScreen(id: String, onBack: () -> Unit, vm: HabitsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val t = ui.byId[id]
    if (t == null) { Box(Modifier.fillMaxSize().background(Color(0xFF1E3A8A))); return }
    val card = ui.board?.let { it.cards + it.namoz }?.firstOrNull { it.task.id == id }
    val title = titleOf(t)
    val minutes = card?.minutes ?: minutesOf(title, ui.prefs[id]?.minutes) ?: 10
    val look = ui.timerLooks[id] ?: TimerLook()
    val timer = ui.timers[id]
    val finished = card?.finished == true
    val latest by rememberUpdatedState(ui)
    val bell by rememberUpdatedState(look)
    val haptics = LocalHapticFeedback.current
    // The goal is reached when a clock that was running is gone and the habit is ticked (a Reset leaves it unticked).
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(timer) {
        if (timer?.running == true) wasRunning = true
        else if (timer == null && wasRunning) {
            wasRunning = false
            delay(400)
            if (id in latest.finishing || latest.board?.let { b -> (b.cards + b.namoz).any { it.task.id == id && it.finished } } == true) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                if (bell.bell) HabitChime.play(bell.sound)
            }
        }
    }
    val start = TIMER_COLOURS.indexOf(card?.colour ?: tileOf(card?.emoji ?: "")).coerceAtLeast(0)
    HabitTimerView(
        title = title, minutes = minutes, timer = timer, now = ui.now, finished = finished, look = look, startColour = start,
        onStartPause = { vm.toggleTimer(id, minutes) }, onReset = { vm.resetTimer(id) }, onBack = onBack,
        onLook = { vm.setTimerLook(id, it) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HabitTimerView(
    title: String, minutes: Int, timer: HabitTimer?, now: Long, finished: Boolean, look: TimerLook, startColour: Int,
    onStartPause: () -> Unit, onReset: () -> Unit, onBack: () -> Unit, onLook: (TimerLook) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val reduce = org.pagel.ai.ui.today.reducedMotion(LocalContext.current)
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf(false) }
    val page0 = if (look.colour in TIMER_COLOURS.indices) look.colour else startColour
    val pager = rememberPagerState(initialPage = page0) { TIMER_COLOURS.size }
    val current by rememberUpdatedState(look)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect { p ->
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onLook(current.copy(colour = p))
        }
    }
    // The status bar's icons go white over the colour and come back with the page.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controls = window?.let { WindowCompat.getInsetsController(it, view) }
        val was = controls?.isAppearanceLightStatusBars
        controls?.isAppearanceLightStatusBars = false
        onDispose { if (was != null) controls?.isAppearanceLightStatusBars = was }
    }

    val total = minutes * 60_000L
    val spent = (timer?.spent(now) ?: 0L).coerceIn(0L, total)
    val running = timer?.running == true
    val shown = if (finished && timer == null) 1f else spent.toFloat() / total
    val sweep by animateFloatAsState(shown, if (reduce) snap() else tween(900, easing = LinearEasing), label = "ring")
    val left = ((total - spent + 999) / 1000).toInt()

    Box(Modifier.fillMaxSize().testTag("habit-timer")) {
        // The colour is the pager: a swipe anywhere changes it, 1:1 with the finger, and a flick lands on a colour.
        HorizontalPager(pager, Modifier.fillMaxSize()) { p ->
            val (a, b) = timerBackground(p)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(a, b))))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(TabIcons.ArrowLeft, "Back", size = Space.touch, plain = true, tint = Color.White, onClick = onBack)
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 2,
                    modifier = Modifier.weight(1f).padding(horizontal = Space.xs).semantics { heading() })
                GlassIconButton(if (look.bell) TabIcons.BellRing else TabIcons.BellOff, "Timer sound", size = Space.touch, plain = true, tint = Color.White) { sheet = true }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(272.dp).semantics(mergeDescendants = true) { contentDescription = "Time left %d:%02d".format(left / 60, left % 60) }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(272.dp)) {
                    val w = 22.dp.toPx()
                    drawCircle(Color.White.copy(alpha = 0.18f), radius = size.minDimension / 2 - w / 2, style = Stroke(w))
                    if (sweep > 0f) drawArc(
                        Color.White, -90f, 360f * sweep, false, topLeft = Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
                    )
                }
                // The digits live inside the ring, so they follow the ring's size, not the font scale (a 1.5x font would cross the stroke).
                val big = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toSp() }
                val line = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toSp() }
                if (finished && timer == null) Text("Done", style = Glass.NumberText.copy(fontSize = big * 0.85f, lineHeight = line, fontWeight = FontWeight.Bold), color = Color.White)
                else Text(
                    "%02d:%02d".format(left / 60, left % 60), style = Glass.NumberText.copy(fontSize = big, lineHeight = line, fontWeight = FontWeight.Bold),
                    color = Color.White, maxLines = 1, softWrap = false,
                )
            }
            Spacer(Modifier.height(Space.xxxl))
            val press = remember { MutableInteractionSource() }
            val label = when { finished && timer == null -> "Done"; running -> "Pause"; spent > 0 -> "Resume"; else -> "Start" }
            Row(
                Modifier.width(232.dp).heightIn(min = 64.dp).pressScale(press).background(Color.White, CircleShape)
                    .clickable(press, indication = null, enabled = !(finished && timer == null), role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onStartPause()
                    }
                    .semantics { contentDescription = "$label timer" }.testTag("timer-start"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val u = size.minDimension / 20f
                    if (running) {
                        drawRect(Glass.Ink, Offset(4f * u, 3f * u), androidx.compose.ui.geometry.Size(4.5f * u, 14f * u))
                        drawRect(Glass.Ink, Offset(11.5f * u, 3f * u), androidx.compose.ui.geometry.Size(4.5f * u, 14f * u))
                    } else if (finished && timer == null) {
                        drawLine(Glass.Ink, Offset(4f * u, 10.5f * u), Offset(8f * u, 14.5f * u), 2.6f * u, StrokeCap.Round)
                        drawLine(Glass.Ink, Offset(8f * u, 14.5f * u), Offset(16f * u, 5.5f * u), 2.6f * u, StrokeCap.Round)
                    } else {
                        drawPath(Path().apply { moveTo(5f * u, 3f * u); lineTo(17f * u, 10f * u); lineTo(5f * u, 17f * u); close() }, Glass.Ink)
                    }
                }
                Spacer(Modifier.width(Space.md))
                Text(label.uppercase(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = Glass.Ink, maxLines = 1)
            }
            Spacer(Modifier.height(Space.md))
            // Reset keeps its slot (no jump when a clock exists).
            val canReset = timer != null
            Text(
                "Reset", style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.alpha(if (canReset) 1f else 0f).heightIn(min = Space.touch)
                    .clickable(enabled = canReset, role = Role.Button) { onReset() }.padding(horizontal = Space.lg).wrapContentHeight(Alignment.CenterVertically),
            )
            Spacer(Modifier.weight(1f))
            Text("SWIPE TO CHANGE THE COLOUR", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                for (i in TIMER_COLOURS.indices) {
                    val on = pager.currentPage == i
                    val w by animateDpAsState(if (on) 26.dp else 8.dp, if (reduce) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), label = "dot")
                    Box(Modifier.size(width = w, height = 8.dp).background(Color.White.copy(alpha = if (on) 1f else 0.45f), CircleShape))
                }
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
    if (sheet) GlassSheet(onDismiss = { sheet = false }) {
        SheetTitle("Timer sound", null)
        Column(Modifier.padding(horizontal = Space.gutter)) {
            SoundRow("Ring at the goal", look.bell, role = Role.Switch) { onLook(look.copy(bell = !look.bell)) }
            for ((i, name) in HabitChime.names.withIndex()) {
                SoundRow(name, look.sound == i && look.bell, role = Role.RadioButton) {
                    onLook(look.copy(sound = i, bell = true))
                    scope.launch { HabitChime.play(i) }
                }
            }
        }
        Spacer(Modifier.height(SheetBottom))
    }
}

@Composable
private fun SoundRow(label: String, on: Boolean, role: Role, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press).clickable(press, indication = null, role = role, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = if (on) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
        if (role == Role.Switch) Switch(on)
        else if (on) androidx.compose.material3.Icon(TabIcons.Check, null, tint = Glass.Primary, modifier = Modifier.size(20.dp))
    }
}
