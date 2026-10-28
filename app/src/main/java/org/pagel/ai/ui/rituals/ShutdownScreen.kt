package org.pagel.ai.ui.rituals

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.rituals.Move
import org.pagel.ai.data.rituals.Rituals
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TimeWheel
import org.pagel.ai.ui.glass.TouchMin
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.habits.Switch
import org.pagel.ai.ui.projects.shortName
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FLAGS = listOf(Color(0xFFD1453B), Color(0xFFEB8909), Color(0xFF246FE0))
private val HEAD_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

@Composable
fun ShutdownRoute(contentPadding: PaddingValues, onToday: () -> Unit = {}, vm: ShutdownViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var timeSheet by remember { mutableStateOf(false) }
    // Notifications off = the reminder never rings: say so as a row Boss can tap (as Habits does, checker P5 #8).
    var notify by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        notify = org.pagel.ai.ui.habits.notificationsOn(ctx)
        onPauseOrDispose {}
    }
    // Notifications are asked for at first use (the reminder switched on), never at launch, never in a loop.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notify = org.pagel.ai.ui.habits.notificationsOn(ctx) }
    fun evening(on: Boolean) {
        vm.setEvening(on)
        if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    ShutdownScreen(
        ui, contentPadding, onChoose = vm::choose, onBack = vm::back, onPick = vm::togglePick, onClose = vm::close,
        onEvening = ::evening, onTime = { timeSheet = true }, onToday = onToday, notify = notify,
        onFixNotify = {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
        },
    )
    if (timeSheet) {
        GlassSheet({ timeSheet = false }) {
            Text("Evening reminder", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { TimeWheel(ui.eveningAt, onChange = vm::setEveningAt, modifier = Modifier.padding(vertical = 8.dp)) }
            Hint("\"Close the day\" at ${ui.eveningAt}, unless it is closed already.", modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
            Spacer(Modifier.size(SheetBottom))
        }
    }
}

/**
 * Close the day, native (Phase 5, the web's /shutdown): today's open stars one at a time (Keep · Later · Drop),
 * then up to 3 more picked for tomorrow, then the day is closed: the changes go to pagel.uz with the shutdown event.
 * The evening reminder (20:45, his day close, unless Boss sets a time) brings him here.
 */
@Composable
fun ShutdownScreen(
    ui: ShutdownUi,
    contentPadding: PaddingValues,
    onChoose: (String, Move) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    onPick: (String) -> Unit = {},
    onClose: () -> Unit = {},
    onEvening: (Boolean) -> Unit = {},
    onTime: () -> Unit = {},
    onToday: () -> Unit = {},
    notify: Boolean = true,
    onFixNotify: () -> Unit = {},
    // The evening it closes: at 01:30 that is still yesterday (checker P5 #10).
    today: LocalDate = Rituals.ritualDate(System.currentTimeMillis()),
) {
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head", contentType = "head") {
            RitualHead(today.format(HEAD_DATE), "Close the day", when {
                ui.done -> null
                ui.shutTonight -> "Closed tonight ✓ · you can close it again"
                else -> "Five minutes. Then nothing needs you tonight."
            })
        }
        when {
            ui.loading -> item(key = "loading", contentType = "pane") { Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(3) } }
            ui.done -> item(key = "done", contentType = "pane") {
                Column {
                    RitualPane("Day closed ✓") {
                        Text("Tomorrow is written down: ${ui.tomorrow} ${if (ui.tomorrow == 1) "star" else "stars"}. Nothing else needs you tonight.",
                            style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
                    }
                    Spacer(Modifier.size(12.dp))
                    ButtonRow { MintButton("Go to Today", TabIcons.Today, Modifier.weight(1f), onClick = onToday) }
                }
            }
            ui.deciding -> item(key = "cards", contentType = "cards") {
                AnimatedContent(
                    targetState = ui.step,
                    transitionSpec = {
                        val forward = targetState > initialState
                        (slideInHorizontally(spring(dampingRatio = 1f, stiffness = 500f)) { if (forward) it / 3 else -it / 3 } + fadeIn())
                            .togetherWith(slideOutHorizontally(spring(dampingRatio = 1f, stiffness = 600f)) { if (forward) -it / 3 else it / 3 } + fadeOut())
                    },
                    label = "star",
                ) { step ->
                    val t = ui.stars.getOrNull(step)
                    if (t != null) StarCard(t, step, ui.stars.size, onBack = onBack) { onChoose(t.id, it) }
                }
            }
            else -> {
                item(key = "picks", contentType = "pane") {
                    RitualPane("Anything else for tomorrow?") {
                        Hint(if (ui.stars.isEmpty()) "No open stars: everything starred is done ✓. Today shows them 3 at a time." else "Today shows them 3 at a time.")
                        Spacer(Modifier.size(6.dp))
                        if (ui.candidates.isEmpty()) Hint("Nothing waiting in your projects' NOW phases.")
                        ui.candidates.forEachIndexed { i, t ->
                            if (i > 0) HorizontalDivider(thickness = 1.dp, color = Glass.Ink.copy(alpha = 0.07f))
                            PickRow(t, on = t.id in ui.picks) { onPick(t.id) }
                        }
                    }
                }
                item(key = "close", contentType = "buttons") {
                    Column {
                        Hint("${ui.tomorrow} ${if (ui.tomorrow == 1) "star" else "stars"} for tomorrow", modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                        ButtonRow {
                            if (ui.stars.isNotEmpty()) QuietButton("Back", TabIcons.ChevronLeft, Modifier.weight(1f), onClick = onBack)
                            MintButton("Close", TabIcons.Moon, Modifier.weight(1f), description = "Finish: close the day", onClick = onClose)
                        }
                    }
                }
            }
        }
        item(key = "evening", contentType = "pane") { EveningRow(ui.eveningOn, ui.eveningAt, notify, onEvening, onTime, onFixNotify) }
    }
}

/** One open star, big, with its three choices; "n of N" and Back above it. */
@Composable
private fun StarCard(t: RitualTask, step: Int, total: Int, onBack: () -> Unit, onChoose: (Move) -> Unit) {
    Column {
        // Boss note 2026-10-28 ("spacing is trash"): the row is always 48 dp (the Back slot is kept empty on star 1) and
        // Space.headerToCard above the card, so the card never jumps between stars and never touches the counter.
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${step + 1} of $total", style = MaterialTheme.typography.labelSmall, color = Glass.Ink, modifier = Modifier.semantics { contentDescription = "Star ${step + 1} of $total" })
            Spacer(Modifier.width(8.dp))
            Text("Today's open stars", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, modifier = Modifier.weight(1f))
            if (step > 0) org.pagel.ai.ui.glass.GlassIconButton(TabIcons.ChevronLeft, "Back one star", plain = true, onClick = onBack)
            else Spacer(Modifier.size(48.dp))
        }
        Spacer(Modifier.height(org.pagel.ai.ui.glass.Space.headerToCard))
        Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(horizontal = org.pagel.ai.ui.glass.Space.paneInset, vertical = org.pagel.ai.ui.glass.Space.xl)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProjectTile(t.project, t.projectColor, t.projectTitle, size = 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(shortName(t.project, t.projectTitle), style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.weight(1f))
                    FLAGS.getOrNull(t.priority)?.let { Icon(TabIcons.FlagFilled, contentDescription = "P${t.priority}", tint = it, modifier = Modifier.size(16.dp)) }
                }
                // slot: the title always takes 2-3 lines, so Keep / Later / Drop stay at one height from star to star
                // (spacing audit #10, a sibling of Boss's "spacing is trash").
                Text(t.title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, minLines = 2, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = org.pagel.ai.ui.glass.Space.md, bottom = org.pagel.ai.ui.glass.Space.xl))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoveButton(Move.KEEP, TabIcons.Sun, Modifier.weight(1f), t.title, onChoose)
                    MoveButton(Move.LATER, TabIcons.Hourglass, Modifier.weight(1f), t.title, onChoose)
                    MoveButton(Move.DROP, TabIcons.Trash, Modifier.weight(1f), t.title, onChoose)
                }
            }
        }
    }
}

/** A choice: an icon over its word, 64 dp tall. Keep is the bright one (keeping is the usual answer). */
@Composable
private fun MoveButton(move: Move, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, title: String, onChoose: (Move) -> Unit) {
    val press = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(16.dp)
    val surface = when (move) {
        Move.KEEP -> Modifier.background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Glass.Aurora2, Glass.Aurora1)), shape)
        else -> Modifier.background(Color.White.copy(alpha = 0.72f), shape)
    }
    Column(
        modifier.heightIn(min = 64.dp).pressScale(press).then(surface)
            .clickable(press, indication = null, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onChoose(move) }
            .semantics(mergeDescendants = true) { contentDescription = "${move.label}: $title" }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (move == Move.DROP) Glass.Risk else Glass.Ink, modifier = Modifier.size(20.dp))
        Text(move.label, style = MaterialTheme.typography.labelLarge, color = if (move == Move.DROP) Glass.Risk else Glass.Ink, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A candidate for tomorrow: its tile and words, and a star that fills when picked (faded once 3 are picked). */
@Composable
private fun PickRow(t: RitualTask, on: Boolean, onToggle: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Checkbox) { haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onToggle() }
            .semantics(mergeDescendants = true) { toggleableState = ToggleableState(on); contentDescription = "Pick: ${t.title}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectTile(t.project, t.projectColor, t.projectTitle, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(t.title, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        Box(Modifier.size(TouchMin), contentAlignment = Alignment.Center) {
            Icon(if (on) TabIcons.StarFilled else TabIcons.Star, contentDescription = null, tint = if (on) Color(0xFFC2410C) else Glass.Ink.copy(alpha = 0.55f), modifier = Modifier.size(22.dp))
        }
    }
}

/** The evening reminder: one switch and its time (a tap opens Apple's wheel). Only on this phone. */
@Composable
private fun EveningRow(on: Boolean, at: String, notify: Boolean, onToggle: (Boolean) -> Unit, onTime: () -> Unit, onFixNotify: () -> Unit) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val press = remember { MutableInteractionSource() }
        val haptics = LocalHapticFeedback.current
        // Two rows, so at 1.5x the words keep the width (one row broke "Every evening, unless…" into five lines).
        Column(Modifier.fillMaxWidth().padding(start = org.pagel.ai.ui.glass.Space.paneInset, end = org.pagel.ai.ui.glass.Space.md, top = org.pagel.ai.ui.glass.Space.xs, bottom = org.pagel.ai.ui.glass.Space.md)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (on) TabIcons.BellRing else TabIcons.Bell, contentDescription = null, tint = if (on) Glass.Primary else Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Evening reminder", style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, modifier = Modifier.weight(1f))
            Box(
                Modifier.heightIn(min = TouchMin).pressScale(press)
                    .clickable(press, indication = null, role = Role.Switch) { haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); onToggle(!on) }
                    .semantics { toggleableState = ToggleableState(on); stateDescription = if (on) "On" else "Off"; contentDescription = "Evening reminder" },
                contentAlignment = Alignment.Center,
            ) { Switch(on) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            // slot: two lines whether on or off, so the pane keeps its size on the toggle (spacing audit #10).
            Hint(if (on) "Every evening, unless the day is closed" else "Off · tap the switch to turn it on", modifier = Modifier.weight(1f), lines = 2)
            Spacer(Modifier.width(8.dp))
            val timePress = remember { MutableInteractionSource() }
            Box(
                Modifier.heightIn(min = TouchMin).pressScale(timePress)
                    .clickable(timePress, indication = null, role = Role.Button, onClickLabel = "Change the time") { onTime() }
                    .semantics(mergeDescendants = true) { contentDescription = "Reminder time $at" },
                contentAlignment = Alignment.Center,
            ) {
                Text(at, style = Glass.NumberText.copy(fontSize = MaterialTheme.typography.labelLarge.fontSize), color = Glass.PrimaryInk,
                    modifier = Modifier.background(Glass.PrimarySoft, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        if (on && !notify) org.pagel.ai.ui.habits.FixRow(TabIcons.Bell, "Notifications off", "Turn on", Glass.Risk, onFixNotify)
        }
    }
}
