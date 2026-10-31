package org.pagel.ai.ui.habits

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.pagel.ai.data.habits.minutesOf
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.HabitIcons
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SheetTitle
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale

/** One timer colour: a top and a bottom, deep enough for white words. Ours; never red. */
internal class TimerShade(val name: String, val top: Color, val bottom: Color)

internal val TIMER_PALETTE = listOf(
    TimerShade("Ocean", Color(0xFF1E5A99), Color(0xFF0A2447)),
    TimerShade("Indigo", Color(0xFF4B4BA8), Color(0xFF171650)),
    TimerShade("Teal", Color(0xFF117A72), Color(0xFF053A37)),
    TimerShade("Forest", Color(0xFF3C7A45), Color(0xFF14361C)),
    TimerShade("Plum", Color(0xFF7B4390), Color(0xFF2E1641)),
    TimerShade("Amber", Color(0xFF9A5C0C), Color(0xFF4B2A04)),
    TimerShade("Graphite", Color(0xFF454C5A), Color(0xFF161A21)),
)

/** The palette page nearest a habit's own tile colour (first open only; after that its own swipe is kept). */
internal fun timerColourFor(tile: String?): Int = when (tile) {
    "blue" -> 0
    "violet" -> 1
    "teal" -> 2
    "mint" -> 3
    "pink" -> 4
    "amber" -> 5
    "slate" -> 6
    else -> 0
}

/**
 * The full-screen timer of a habit with minutes (Boss's reference: a colour, one big ring, huge time, a white Start pill,
 * "swipe to change the colour"). It is never required: a habit is ticked from its card. It runs the same clock as the
 * rest of the app, so it survives leaving the screen. Start/Pause, Finish (counts the habit done with whatever time was
 * spent), a bell sheet (ring at the goal, three chimes made here) and a sound sheet (six real CC0 ambient recordings).
 */
@Composable
fun HabitTimerScreen(id: String, onBack: () -> Unit, vm: HabitsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val t = ui.byId[id]
    if (t == null) { Box(Modifier.fillMaxSize().background(TIMER_PALETTE[0].bottom)); return }
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
    HabitTimerView(
        title = title, minutes = minutes, timer = timer, now = ui.now, finished = finished, look = look,
        startColour = timerColourFor(card?.colour ?: tileOf(card?.emoji ?: "")),
        onStartPause = { vm.toggleTimer(id, minutes) }, onReset = { vm.resetTimer(id) }, onFinish = { vm.tick(id) }, onBack = onBack,
        onLook = { vm.setTimerLook(id, it) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HabitTimerView(
    title: String, minutes: Int, timer: HabitTimer?, now: Long, finished: Boolean, look: TimerLook, startColour: Int,
    onStartPause: () -> Unit, onReset: () -> Unit, onFinish: () -> Unit, onBack: () -> Unit, onLook: (TimerLook) -> Unit,
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val reduce = org.pagel.ai.ui.today.reducedMotion(ctx)
    val scope = rememberCoroutineScope()
    var bellSheet by remember { mutableStateOf(false) }
    var soundSheet by remember { mutableStateOf(false) }
    val page0 = if (look.colour in TIMER_PALETTE.indices) look.colour else startColour.coerceIn(0, TIMER_PALETTE.lastIndex)
    val pager = rememberPagerState(initialPage = page0) { TIMER_PALETTE.size }
    val current by rememberUpdatedState(look)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect { p ->
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onLook(current.copy(colour = p))
        }
    }

    val total = minutes * 60_000L
    val spent = (timer?.spent(now) ?: 0L).coerceIn(0L, total)
    val running = timer?.running == true
    val done = finished && timer == null

    // The status bar's icons go white over the colour and come back with the page; the screen stays on while a clock runs.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controls = window?.let { WindowCompat.getInsetsController(it, view) }
        val was = controls?.isAppearanceLightStatusBars
        controls?.isAppearanceLightStatusBars = false
        onDispose { if (was != null) controls?.isAppearanceLightStatusBars = was }
    }
    DisposableEffect(view, running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    // Ambient sound: plays while the clock runs, or while the sound sheet is open (a preview); 400 ms fades; stops on leaving.
    val player = remember { AmbientPlayer(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val wantSound = look.ambient >= 0 && (running || soundSheet)
    LaunchedEffect(wantSound, look.ambient) { if (wantSound) player.play(scope, look.ambient, current.volume) else player.pause(scope) }

    // The ring is drawn from the frame clock while a clock runs (read in the draw phase only), else from the saved time.
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, timer?.startedAt, timer?.spentMs, total, done) {
        progress.floatValue = if (done) 1f else spent.toFloat() / total
        val began = timer?.startedAt
        if (running && began != null && !reduce) {
            val base = timer?.spentMs ?: 0L
            while (isActive) {
                withFrameNanos { }
                progress.floatValue = (base + (System.currentTimeMillis() - began)).coerceIn(0L, total).toFloat() / total
            }
        }
    }
    val left = ((total - spent + 999) / 1000).toInt()
    val ring = (LocalConfiguration.current.screenHeightDp * 0.34f).dp.coerceIn(200.dp, 272.dp)

    Box(Modifier.fillMaxSize().testTag("habit-timer")) {
        // The colour moves with the finger: between two pages it is the blend of both, so a swipe never jumps.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val pos = pager.currentPage + pager.currentPageOffsetFraction
                val i = kotlin.math.floor(pos).toInt().coerceIn(0, TIMER_PALETTE.lastIndex)
                val j = (i + 1).coerceAtMost(TIMER_PALETTE.lastIndex)
                val f = (pos - i).coerceIn(0f, 1f)
                drawRect(Brush.verticalGradient(listOf(lerp(TIMER_PALETTE[i].top, TIMER_PALETTE[j].top, f), lerp(TIMER_PALETTE[i].bottom, TIMER_PALETTE[j].bottom, f))))
            },
        )
        // A swipe anywhere changes the colour (the pages are empty: only the gesture lives here).
        HorizontalPager(pager, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(TabIcons.ArrowLeft, "Back", size = Space.touch, plain = true, tint = Color.White, onClick = onBack)
                Text(
                    title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 2,
                    modifier = Modifier.weight(1f).padding(horizontal = Space.xs).semantics { heading() },
                )
                GlassIconButton(TabIcons.Volume, "Sounds", size = Space.touch, plain = true, tint = Color.White) { soundSheet = true }
                GlassIconButton(if (look.bell) TabIcons.BellRing else TabIcons.BellOff, "Timer sound", size = Space.touch, plain = true, tint = Color.White) { bellSheet = true }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(ring).semantics(mergeDescendants = true) { contentDescription = "Time left %d:%02d".format(left / 60, left % 60) },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(ring)) {
                    val w = size.minDimension * 0.08f
                    drawCircle(Color.White.copy(alpha = 0.18f), radius = size.minDimension / 2 - w / 2, style = Stroke(w))
                    val sweep = progress.floatValue
                    if (sweep > 0f) drawArc(
                        Color.White, -90f, 360f * sweep, false, topLeft = Offset(w / 2, w / 2),
                        size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
                    )
                }
                // The digits live inside the ring, so they follow its size, not the font scale; a mono face never changes width.
                val big = with(LocalDensity.current) { (ring * 0.206f).toSp() }
                val line = with(LocalDensity.current) { (ring * 0.221f).toSp() }
                if (done) Text("Done", style = Glass.NumberText.copy(fontSize = big * 0.85f, lineHeight = line, fontWeight = FontWeight.Bold), color = Color.White)
                else Text(
                    "%02d:%02d".format(left / 60, left % 60), style = Glass.NumberText.copy(fontSize = big, lineHeight = line, fontWeight = FontWeight.Bold),
                    color = Color.White, maxLines = 1, softWrap = false,
                )
            }
            Spacer(Modifier.height(Space.xxl))
            val press = remember { MutableInteractionSource() }
            val label = when {
                done -> "Done"
                running -> "Pause"
                spent > 0 -> "Resume"
                else -> "Start"
            }
            Row(
                Modifier.width(232.dp).heightIn(min = 64.dp).pressScale(press).background(Color.White, CircleShape)
                    .clickable(press, indication = null, enabled = !done, role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onStartPause()
                    }
                    .semantics { contentDescription = "$label timer" }.testTag("timer-start"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val u = size.minDimension / 20f
                    when {
                        done -> {
                            drawLine(Glass.Ink, Offset(4f * u, 10.5f * u), Offset(8f * u, 14.5f * u), 2.6f * u, StrokeCap.Round)
                            drawLine(Glass.Ink, Offset(8f * u, 14.5f * u), Offset(16f * u, 5.5f * u), 2.6f * u, StrokeCap.Round)
                        }
                        running -> {
                            drawRect(Glass.Ink, Offset(4f * u, 3f * u), Size(4.5f * u, 14f * u))
                            drawRect(Glass.Ink, Offset(11.5f * u, 3f * u), Size(4.5f * u, 14f * u))
                        }
                        else -> drawPath(Path().apply { moveTo(5f * u, 3f * u); lineTo(17f * u, 10f * u); lineTo(5f * u, 17f * u); close() }, Glass.Ink)
                    }
                }
                Spacer(Modifier.width(Space.md))
                Text(label.uppercase(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = Glass.Ink, maxLines = 1)
            }
            Spacer(Modifier.height(Space.md))
            // Finish is always there: it counts the habit done now with whatever time was spent (4 of 10 minutes still counts).
            val finishPress = remember { MutableInteractionSource() }
            Row(
                Modifier.width(232.dp).heightIn(min = Space.row).alpha(if (done) 0f else 1f).pressScale(finishPress).clip(CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .clickable(finishPress, indication = null, enabled = !done, role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm); onFinish()
                    }
                    .semantics { contentDescription = "Finish" }.testTag("timer-finish"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(TabIcons.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.sm))
                Text("Finish", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1)
            }
            // Reset keeps its slot (no jump when a clock exists).
            val canReset = timer != null
            Text(
                "Reset", style = MaterialTheme.typography.labelLarge, color = Color.White,
                modifier = Modifier.alpha(if (canReset) 1f else 0f).heightIn(min = Space.touch)
                    .clickable(enabled = canReset, role = Role.Button) { onReset() }.padding(horizontal = Space.lg).wrapContentHeight(Alignment.CenterVertically),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "SWIPE TO CHANGE THE COLOUR", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                for (i in TIMER_PALETTE.indices) {
                    val on = pager.currentPage == i
                    val w by animateDpAsState(if (on) 26.dp else 8.dp, if (reduce) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), label = "dot")
                    Box(Modifier.size(width = w, height = 8.dp).background(Color.White.copy(alpha = if (on) 1f else 0.45f), CircleShape))
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
    }
    if (bellSheet) GlassSheet(onDismiss = { bellSheet = false }) {
        BellSheetContent(look, onLook = onLook, onPreview = { scope.launch { HabitChime.play(it) } })
        Spacer(Modifier.height(SheetBottom))
    }
    if (soundSheet) GlassSheet(onDismiss = { soundSheet = false }) {
        AmbientSheetContent(
            look, playing = wantSound, onPick = { onLook(look.copy(ambient = it)) },
            onVolume = { player.setVolume(it) }, onVolumeDone = { onLook(look.copy(volume = it)) },
        )
        Spacer(Modifier.height(SheetBottom))
    }
}

/** The bell sheet: ring at the goal on or off, and which of the three soft chimes (each plays when picked). */
@Composable
internal fun BellSheetContent(look: TimerLook, onLook: (TimerLook) -> Unit, onPreview: (Int) -> Unit) {
    SheetTitle("Ring at the goal", null)
    Column(Modifier.padding(horizontal = Space.gutter)) {
        SoundRow("Ring at the goal", null, look.bell, role = Role.Switch) { onLook(look.copy(bell = !look.bell)) }
        for ((i, name) in HabitChime.names.withIndex()) {
            SoundRow(name, TabIcons.BellRing, look.sound == i && look.bell, role = Role.RadioButton) {
                onLook(look.copy(sound = i, bell = true))
                onPreview(i)
            }
        }
    }
}

/**
 * The sound sheet (Boss's reference, in our glass style): Silence and six real recordings, each with its line icon, a check
 * on the chosen one and three moving bars while it plays (a sign of life, not a level meter), and a volume slider.
 */
@Composable
internal fun AmbientSheetContent(look: TimerLook, playing: Boolean, onPick: (Int) -> Unit, onVolume: (Float) -> Unit, onVolumeDone: (Float) -> Unit) {
    SheetTitle("Sounds", null)
    Column(Modifier.padding(horizontal = Space.gutter)) {
        SoundRow("Silence", TabIcons.BellOff, look.ambient < 0, role = Role.RadioButton) { onPick(-1) }
        for ((i, s) in AmbientSounds.all.withIndex()) {
            SoundRow(s.label, null, look.ambient == i, role = Role.RadioButton, iconName = s.icon, bars = look.ambient == i && playing) { onPick(i) }
        }
        var vol by remember(look.volume) { mutableFloatStateOf(look.volume) }
        Row(Modifier.fillMaxWidth().heightIn(min = Space.row), verticalAlignment = Alignment.CenterVertically) {
            Icon(TabIcons.Volume, null, tint = Glass.Muted, modifier = Modifier.size(22.dp))
            Slider(
                value = vol, onValueChange = { vol = it; onVolume(it) }, onValueChangeFinished = { onVolumeDone(vol) },
                modifier = Modifier.weight(1f).padding(start = Space.md).semantics { contentDescription = "Volume" },
                colors = SliderDefaults.colors(thumbColor = Glass.Primary, activeTrackColor = Glass.Primary, inactiveTrackColor = Glass.Ink.copy(alpha = 0.12f)),
            )
        }
    }
}

@Composable
private fun SoundRow(
    label: String, icon: ImageVector?, on: Boolean, role: Role, iconName: String? = null, bars: Boolean = false, onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press).clickable(press, indication = null, role = role, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = if (on) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val v = icon ?: iconName?.let { HabitIcons.vector(it) }
        if (v != null) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Glass.PrimarySoft else Glass.Ink.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(v, null, tint = if (on) Glass.PrimaryInk else Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(Space.md))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
        if (role == Role.Switch) Switch(on)
        else {
            if (bars) LevelBars()
            if (on) Icon(TabIcons.Check, null, tint = Glass.Primary, modifier = Modifier.padding(start = Space.sm).size(20.dp))
        }
    }
}

/** Three bars that move while a sound plays. */
@Composable
private fun LevelBars() {
    val t = rememberInfiniteTransition(label = "bars")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(680, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(440, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Canvas(Modifier.size(width = 20.dp, height = 16.dp)) {
        val w = size.width / 5f
        for ((k, h) in listOf(a, b, c).withIndex()) {
            val bar = size.height * h
            drawRoundRect(Glass.Primary, Offset(w * (2 * k), size.height - bar), Size(w, bar), CornerRadius(w / 2))
        }
    }
}
