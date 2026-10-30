package org.pagel.ai.ui.task

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassChip
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.today.BigTick
import org.pagel.ai.ui.today.Ding
import org.pagel.ai.ui.today.PrimaryPill
import org.pagel.ai.ui.today.QuietButton
import org.pagel.ai.ui.today.TWO_MINUTE_START
import org.pagel.ai.ui.today.clockText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Divider = Glass.Ink.copy(alpha = 0.07f)

// ── The panel's two new rows ───────────────────────────────────────────────────────────────────────────────────────

/** "✨ Make it tiny" (or "Tiny steps · 4" once written) and "I'm stuck": the starting tools, in the task panel. */
@Composable
internal fun StartRows(micro: Int, onTiny: () -> Unit, onStuck: () -> Unit) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(vertical = Space.xs)) {
            ToolRow(TabIcons.Sparkles, if (micro > 0) "Tiny steps · $micro" else "Make it tiny", if (micro > 0) "Focus" else "Funny small steps", onTiny)
            HorizontalDivider(Modifier.padding(start = Space.xxl + Space.xxl), color = Divider,
                thickness = 1.dp)
            ToolRow(TabIcons.CircleHelp, "I'm stuck", "4 quick fixes", onStuck)
        }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, label: String, hint: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(start = Space.lg, end = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(Space.md))
        // The hint sits under the words, so a large font wraps them instead of cutting either.
        Column(Modifier.weight(1f).padding(vertical = Space.sm)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
        }
        Spacer(Modifier.width(Space.xs))
        Icon(TabIcons.ChevronRight, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SheetHead(title: String, sub: String? = null) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.padding(top = Space.sm).semantics { heading() })
    if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs))
}

// ── Make it tiny ───────────────────────────────────────────────────────────────────────────────────────────────────

/** The spice slider's three stops (goblin.tools Magic ToDo): a hard day gets smaller steps. */
private val SPICE = listOf("🌶" to "A few steps", "🌶🌶" to "Small steps", "🌶🌶🌶" to "Tiny tiny steps")

/** "Make it tiny" in a half sheet: the spice, the AI's steps, then Start → Focus mode. */
@Composable
fun TinySheet(taskId: String, onClose: () -> Unit, onFocus: (String) -> Unit) {
    val vm: StartViewModel = hiltViewModel(key = "start-$taskId")
    LaunchedEffect(taskId) { vm.bind(taskId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (ui.loading) return
    var spice by rememberSaveable(taskId) { mutableIntStateOf(2) }
    GlassSheet({ vm.clearProposal(); onClose() }) {
        TinyContent(ui, spice, onSpice = { spice = it }, onWrite = { vm.propose(spice) },
            onStart = { steps -> vm.keepTiny(steps) { onClose(); onFocus(taskId) } },
            onFocusSaved = { onClose(); onFocus(taskId) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TinyContent(ui: StartUi, spice: Int, onSpice: (Int) -> Unit = {}, onWrite: () -> Unit = {}, onStart: (List<String>) -> Unit = {}, onFocusSaved: () -> Unit = {}) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.gutter, end = Space.gutter, bottom = SheetBottom)) {
        SheetHead("Make it tiny", ui.title)
        Spacer(Modifier.height(Space.lg))
        // The spice: three stops in one capsule row, the chosen one rimmed mint (never a hidden setting).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SPICE.forEachIndexed { i, (chili, words) ->
                val on = spice == i + 1
                val press = remember { MutableInteractionSource() }
                Column(
                    Modifier.weight(1f).heightIn(min = 64.dp).pressScale(press)
                        .background(Color.White.copy(alpha = if (on) 0.92f else 0.66f), RoundedCornerShape(18.dp))
                        .border(if (on) 1.5.dp else 1.dp, if (on) SolidColor(Glass.Primary) else SolidColor(Glass.Ink.copy(alpha = 0.10f)), RoundedCornerShape(18.dp))
                        .clickable(press, indication = null, role = Role.RadioButton) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSpice(i + 1) }
                        .semantics(mergeDescendants = true) { contentDescription = "Spice ${i + 1}: $words"; selected = on }
                        .padding(vertical = Space.sm, horizontal = Space.xs),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text(chili, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(words, style = MaterialTheme.typography.labelSmall, color = if (on) Glass.Ink else Glass.Muted, maxLines = 2, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(Space.lg))
        when (val t = ui.tiny) {
            Tiny.Idle -> {
                if (ui.micro.isNotEmpty()) {
                    StepList(ui.micro)
                    Spacer(Modifier.height(Space.lg))
                    PrimaryPill("Start focus · ${ui.micro.size} steps", "Start focus", onFocusSaved)
                    QuietButton("Write new ones", onWrite)
                } else PrimaryPill("Write tiny steps", "Write tiny steps", onWrite)
            }
            // Never a spinner while the AI writes (core round 4): the steps' own lines, waiting to fill.
            Tiny.Writing -> Pane(Modifier.fillMaxWidth()) { SkeletonRows(4, Modifier.padding(vertical = Space.sm)) }
            is Tiny.Ready -> {
                StepList(t.steps)
                Spacer(Modifier.height(Space.lg))
                PrimaryPill("Start · step 1 of ${t.steps.size}", "Start focus", { onStart(t.steps) })
                QuietButton("Again", onWrite)
            }
            Tiny.Offline -> { Quiet("Needs internet"); QuietButton("Try again", onWrite) }
            is Tiny.Failed -> { Quiet(t.message); QuietButton("Try again", onWrite) }
        }
    }
}

@Composable
private fun Quiet(line: String) {
    Text(line, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.md), textAlign = TextAlign.Center)
}

@Composable
private fun StepList(steps: List<String>) {
    Pane(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.paneInset, vertical = Space.sm)) {
            steps.forEachIndexed { i, s -> key(i, s) {
                Row(Modifier.fillMaxWidth().padding(vertical = Space.sm), verticalAlignment = Alignment.Top) {
                    Text("${i + 1}", style = Glass.NumberText, color = Glass.PrimaryInk, modifier = Modifier.width(Space.xxl).padding(top = Space.xs))
                    Text(s, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink)
                }
            } }
        }
    }
}

// ── I'm stuck ──────────────────────────────────────────────────────────────────────────────────────────────────────

/** I'm stuck: four plain reasons, each a fix in one tap. */
@Composable
fun StuckSheet(taskId: String, onClose: () -> Unit, onTiny: () -> Unit, onOpenProject: (String) -> Unit) {
    val vm: StartViewModel = hiltViewModel(key = "start-$taskId")
    LaunchedEffect(taskId) { vm.bind(taskId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (ui.loading) return
    var reason by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var startedAt by rememberSaveable(taskId) { mutableLongStateOf(0L) }
    GlassSheet(onClose) {
        StuckContent(
            ui, StuckReason.entries.firstOrNull { it.word == reason }, startedAt,
            onReason = { r -> reason = r.word; vm.stuck(r); if (r == StuckReason.DONT_WANT) startedAt = System.currentTimeMillis() },
            onBack = { reason = null; startedAt = 0L },
            onTiny = { onClose(); onTiny() },
            onProject = { vm.turnIntoProject { name -> onClose(); onOpenProject(name) } },
            onDoneWhen = { vm.doneWhen(it); onClose() },
            onWaiting = { who, day, words -> vm.waitingFor(who, day, words).also { if (it == null) onClose() } },
            onFinish = { vm.finishTask(); onClose() },
            onClose = onClose,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StuckContent(
    ui: StartUi, reason: StuckReason?, startedAt: Long = 0L, today: LocalDate = LocalDate.now(org.pagel.ai.data.tasks.TaskRepository.TASHKENT),
    clock: () -> Long = System::currentTimeMillis,
    onReason: (StuckReason) -> Unit = {}, onBack: () -> Unit = {}, onTiny: () -> Unit = {}, onProject: () -> Unit = {},
    onDoneWhen: (String) -> Unit = {}, onWaiting: (String, String, String) -> String? = { _, _, _ -> null }, onFinish: () -> Unit = {}, onClose: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.gutter, end = Space.gutter, bottom = SheetBottom)) {
        when (reason) {
            null -> {
                SheetHead("I'm stuck", ui.title)
                Spacer(Modifier.height(Space.md))
                Pane(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = Space.xs)) {
                        StuckReason.entries.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = Space.lg), color = Divider,
                                thickness = 1.dp)
                            val press = remember { MutableInteractionSource() }
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press)
                                    .clickable(press, indication = null, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onReason(r) }
                                    .semantics(mergeDescendants = true) { contentDescription = "Stuck: ${r.label}" }
                                    .padding(horizontal = Space.lg, vertical = Space.md),
                            ) {
                                Text(r.label, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2)
                                Text(r.fix, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            StuckReason.TOO_BIG -> {
                SheetHead("Too big", "Shrink it until starting is silly-easy.")
                Spacer(Modifier.height(Space.lg))
                PrimaryPill("✨ Make it tiny", "Make it tiny", onTiny)
                Spacer(Modifier.height(Space.sm))
                OutlinePill("Turn into project", onProject)
                QuietButton("Back", onBack)
            }
            StuckReason.NOT_CLEAR -> {
                SheetHead("Not clear", "Write what done looks like. One line.")
                Spacer(Modifier.height(Space.lg))
                var line by rememberSaveable(ui.id) { mutableStateOf(ui.doneWhen) }
                Field(line, "Done when …", "Done when") { line = it }
                Spacer(Modifier.height(Space.lg))
                PrimaryPill("Save the finish line", "Save the finish line") { if (line.isNotBlank()) onDoneWhen(line.trim()) }
                QuietButton("Back", onBack)
            }
            StuckReason.DONT_WANT -> TwoMinutes(ui.title, startedAt, clock, onFinish, onClose)
            StuckReason.WAITING -> {
                SheetHead("Waiting on someone", "It leaves Today and comes back on the day to check.")
                Spacer(Modifier.height(Space.lg))
                var who by rememberSaveable(ui.id) { mutableStateOf("") }
                var pick by rememberSaveable(ui.id) { mutableIntStateOf(0) }
                var bad by remember { mutableStateOf<String?>(null) }
                Field(who, "Who? (Aziz, the bank…)", "Waiting for whom") { who = it; bad = null }
                Spacer(Modifier.height(Space.md))
                val days = checkDays(today)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.chipGap)) {
                    days.forEachIndexed { i, (label, _) ->
                        GlassChip(TabIcons.Calendar, label, set = pick == i, checked = pick == i, modifier = Modifier.semantics { selected = pick == i }) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); pick = i
                        }
                    }
                }
                // slot: the message line is always laid out, so the button never jumps (RULES.md layout 1).
                Text(bad.orEmpty(), style = MaterialTheme.typography.labelSmall, color = Glass.Amber, maxLines = 1, minLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Space.sm))
                Spacer(Modifier.height(Space.sm))
                PrimaryPill("Save · Waiting for", "Save waiting for") {
                    val (label, day) = days[pick]
                    bad = onWaiting(who, day.toString(), label.lowercase(Locale.UK))
                    if (bad != null) haptics.performHapticFeedback(HapticFeedbackType.Reject)
                }
                QuietButton("Back", onBack)
            }
        }
    }
}

/** The days to check back: tomorrow, in 3 days, next Monday. */
internal fun checkDays(today: LocalDate): List<Pair<String, LocalDate>> {
    val monday = today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY))
    return listOf("Tomorrow" to today.plusDays(1), "In 3 days" to today.plusDays(3), "Mon " + monday.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)) to monday)
}

/** Don't want to: just 2 minutes (the 2-minute rule); stopping after is allowed, ticking is better. */
@Composable
private fun TwoMinutes(title: String, startedAt: Long, clock: () -> Long, onFinish: () -> Unit, onClose: () -> Unit) {
    val endsAt = startedAt + TWO_MINUTE_START * 60_000L
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(endsAt) { while (now < endsAt) { delay(1_000); now = clock() } }
    val left = (endsAt - now) / 1000
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SheetHead("Just 2 minutes", "Start, and you may stop when it rings.")
        Text(clockText(left), style = Glass.NumberText.copy(fontSize = 56.sp, lineHeight = 64.sp), color = Glass.Ink,
            modifier = Modifier.padding(vertical = Space.md).semantics { contentDescription = "${clockText(left)} left" })
        Text(if (left > 0) title else "2 minutes done. Keep going, or stop here.", style = MaterialTheme.typography.titleMedium, color = Glass.Ink,
            maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.lg))
        BigTick(title, false, onFinish)
        QuietButton("Stop for now", onClose)
    }
}

@Composable
private fun Field(value: String, hint: String, said: String, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(Color.White.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
            .border(1.dp, SolidColor(Glass.Ink.copy(alpha = 0.10f)), RoundedCornerShape(16.dp))
            .padding(horizontal = Space.lg, vertical = Space.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value, onValueChange = { onChange(it.replace('\n', ' ')) }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Glass.Ink), cursorBrush = SolidColor(Glass.Primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = said },
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = Glass.Muted); inner() } },
        )
    }
}

@Composable
private fun OutlinePill(label: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press)
            .background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(26.dp))
            .border(1.dp, SolidColor(Glass.Primary), RoundedCornerShape(26.dp))
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk, maxLines = 1) }
}

// ── Focus mode ─────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Focus mode: one micro-step at a time on a full-screen glass card, a big tick (a tiny pop + haptic each), "2 of 5",
 * and the task's finish moment at the last one. Skip and I'm stuck are always there.
 */
@Composable
fun FocusSheet(taskId: String, onClose: () -> Unit, onOpenProject: (String) -> Unit) {
    val vm: StartViewModel = hiltViewModel(key = "start-$taskId")
    LaunchedEffect(taskId) { vm.bind(taskId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (ui.loading) return
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var index by rememberSaveable(taskId) { mutableIntStateOf(0) }
    var popping by remember { mutableStateOf(false) }
    var stuck by rememberSaveable(taskId) { mutableStateOf(false) }
    var tiny by rememberSaveable(taskId) { mutableStateOf(false) }
    // Skip on the last step asks "Done?" instead of closing (checker round 2 #9): Done plays the finish moment.
    var asking by rememberSaveable(taskId) { mutableStateOf(false) }
    /** The tick's pop + haptic + ding; on the last step (or a "Done" after Skip) it is the task's finish moment. */
    fun tick(countsStep: Boolean) {
        if (popping) return
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (countsStep) vm.microDone()
        popping = true
        val last = !countsStep || index >= ui.micro.lastIndex
        scope.launch(Dispatchers.Default) { Ding.play(ctx.applicationContext) }
        scope.launch {
            delay(if (last) org.pagel.ai.ui.today.TodayViewModel.FOLD_MS else 420L)
            popping = false
            if (last) { asking = false; vm.finishTask(); onClose() } else index++
        }
    }
    GlassSheet(onClose, full = true) {
        FocusContent(ui, index, popping, asking = asking, onTick = { tick(countsStep = true) },
            onSkip = { if (index < ui.micro.lastIndex) index++ else asking = true }, onStuck = { stuck = true }, onClose = onClose,
            onDone = { tick(countsStep = false) }, onNotYet = { asking = false; onClose() })
    }
    if (stuck) StuckSheet(taskId, onClose = { stuck = false }, onTiny = { tiny = true }, onOpenProject = { onClose(); onOpenProject(it) })
    if (tiny) TinySheet(taskId, onClose = { tiny = false }, onFocus = { index = 0 })
}

@Composable
fun FocusContent(
    ui: StartUi, index: Int, popping: Boolean = false, asking: Boolean = false, onTick: () -> Unit = {}, onSkip: () -> Unit = {}, onStuck: () -> Unit = {},
    onClose: () -> Unit = {}, onDone: () -> Unit = {}, onNotYet: () -> Unit = {},
) {
    val steps = ui.micro
    val i = index.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth().fillMaxHeight().padding(start = Space.gutter, end = Space.gutter, bottom = SheetBottom), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.title, style = MaterialTheme.typography.labelLarge, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            org.pagel.ai.ui.glass.GlassIconButton(TabIcons.Close, "Close focus", size = 36.dp, onClick = onClose)
        }
        // "2 of 5" and a line that fills step by step.
        Text(if (steps.isEmpty()) "" else "${i + 1} of ${steps.size}", style = Glass.NumberText.copy(fontSize = 16.sp, lineHeight = 20.sp), color = Glass.PrimaryInk,
            modifier = Modifier.padding(top = Space.sm).semantics { contentDescription = "Step ${i + 1} of ${steps.size}" })
        Box(Modifier.fillMaxWidth().padding(top = Space.sm)
            .height(6.dp).background(Divider, RoundedCornerShape(3.dp))) {
            val f = if (steps.isEmpty()) 0f else (i + if (popping) 1 else 0).toFloat() / steps.size
            Box(Modifier.fillMaxWidth(f).height(6.dp).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Glass.Aurora1, Glass.Primary)), RoundedCornerShape(3.dp)))
        }
        // The card's top stays put from step to step (RULES.md layout 1): a fixed gap above it, the free space below.
        Spacer(Modifier.height(Space.xxxl))
        Pane(Modifier.fillMaxWidth().testTag("focus-card"), radius = 28.dp) {
            Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(horizontal = Space.xxl, vertical = Space.xxxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(steps.getOrElse(i) { "No tiny steps yet" }, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp),
                    color = Glass.Ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(Space.xxl))
                if (steps.isNotEmpty()) BigTick(steps[i], popping, onTick)
            }
        }
        Spacer(Modifier.weight(1f))
        if (asking) {
            // Skipped the last step: is the task done? Done = the finish moment; Not yet = out of focus, the task stays open.
            Text("Done?", style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.padding(bottom = Space.sm).semantics { heading() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PrimaryPill("Done", "Done: ${ui.title}", onDone) }
                QuietButton("Not yet", onNotYet, Modifier.weight(1f))
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            QuietButton("Skip", onSkip, Modifier.weight(1f))
            QuietButton("I'm stuck", onStuck, Modifier.weight(1f))
        }
    }
}
