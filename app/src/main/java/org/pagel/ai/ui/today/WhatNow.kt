package org.pagel.ai.ui.today

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.LocalToasts
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.SheetBottom
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale

/** What now's sheet: shown while its stage is not CLOSED. The tick plays the finish moment, then the sheet closes. */
@Composable
fun WhatNowSheet(
    ui: WhatNowUi, onEnergy: (Energy) -> Unit, onStart: () -> Unit, onNotThis: () -> Unit,
    onFinish: () -> String?, onUndo: (String) -> Unit, onClose: () -> Unit,
) {
    if (ui.stage == WhatNowUi.Stage.CLOSED) return
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val toasts = LocalToasts.current
    var finishing by remember { mutableStateOf(false) }
    GlassSheet(onClose) {
        WhatNowContent(ui, finishing = finishing, onEnergy = onEnergy, onStart = onStart, onNotThis = onNotThis, onClose = onClose, onFinish = {
            // The finish moment: Confirm haptic, the ding, the ring pops and draws its tick; then the sheet folds away.
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            scope.launch(Dispatchers.Default) { Ding.play(ctx.applicationContext) }
            finishing = true
            scope.launch {
                delay(TodayViewModel.FOLD_MS)
                val inRow = Combo.app.tick(System.currentTimeMillis())
                val id = onFinish()
                finishing = false
                if (id != null) toasts.show(comboText(inRow), action = "Undo") { Combo.app.undo(); onUndo(id) }
            }
        })
    }
}

/** The sheet's body, one stage at a time (screenshot tests draw it without the sheet). */
@Composable
fun WhatNowContent(
    ui: WhatNowUi, finishing: Boolean = false, clock: () -> Long = System::currentTimeMillis,
    onEnergy: (Energy) -> Unit = {}, onStart: () -> Unit = {}, onNotThis: () -> Unit = {}, onFinish: () -> Unit = {}, onClose: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, bottom = SheetBottom)) {
        when (ui.stage) {
            WhatNowUi.Stage.ENERGY -> EnergyAsk(onEnergy)
            WhatNowUi.Stage.PICK -> ui.pick?.let { PickCard(it, ui.shown, onStart, onNotThis) }
            WhatNowUi.Stage.TIMER -> ui.pick?.let { RunningStart(it, ui.startedAt, ui.minutes, finishing, clock, onFinish) }
            WhatNowUi.Stage.NONE -> NothingFits(onClose)
            WhatNowUi.Stage.CLOSED -> {}
        }
    }
}

@Composable
private fun SheetTitle(text: String, sub: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.padding(top = Space.sm).semantics { heading() })
    if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, modifier = Modifier.padding(top = Space.xs))
}

/** 🔋 low / ok / high, asked once a day: the pick follows it. */
@Composable
private fun EnergyAsk(onEnergy: (Energy) -> Unit) {
    val haptics = LocalHapticFeedback.current
    SheetTitle("How's your energy?", "Once a day. It picks the right size of task.")
    Spacer(Modifier.height(Space.lg))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        for (e in Energy.entries) {
            val press = remember { MutableInteractionSource() }
            Column(
                Modifier.weight(1f).heightIn(min = 88.dp).pressScale(press)
                    .background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(20.dp))
                    .border(1.dp, Glass.Aurora1.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                    .clickable(press, indication = null, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onEnergy(e) }
                    .semantics(mergeDescendants = true) { contentDescription = "Energy: ${e.label}" }
                    .padding(vertical = Space.md),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(when (e) { Energy.LOW -> "🪫"; Energy.OK -> "🔋"; Energy.HIGH -> "⚡" }, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(Space.xs))
                Text(e.label, style = MaterialTheme.typography.labelLarge, color = Glass.Ink, maxLines = 1)
            }
        }
    }
}

/** One task for right now: why (its project), its tiny first step, Start · 10 min, Not this. */
@Composable
private fun PickCard(p: NowPick, shown: Int, onStart: () -> Unit, onNotThis: () -> Unit) {
    SheetTitle("What now?")
    Spacer(Modifier.height(Space.md))
    Pane(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.paneInset, vertical = Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProjectTile(p.project, p.projectColor, p.why, size = 20.dp)
                Spacer(Modifier.width(Space.sm))
                Text("For ${p.why}", style = MaterialTheme.typography.labelLarge, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("$shown/$MAX_PICKS", style = Glass.NumberText, color = Glass.Muted, modifier = Modifier.semantics { contentDescription = "Pick $shown of $MAX_PICKS" })
            }
            Spacer(Modifier.height(Space.sm))
            Text(p.title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.md))
            Row(verticalAlignment = Alignment.Top) {
                Icon(TabIcons.Zap, contentDescription = null, tint = Glass.Primary, modifier = Modifier.padding(top = Space.xs).size(16.dp))
                Spacer(Modifier.width(Space.sm))
                Text("First: ${p.first}", style = MaterialTheme.typography.bodyMedium, color = Glass.PrimaryInk, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Spacer(Modifier.height(Space.lg))
    PrimaryPill("Start · $START_MINUTES min", "Start $START_MINUTES minutes: ${p.title}", onStart)
    Spacer(Modifier.height(Space.xs))
    QuietButton(if (shown >= MAX_PICKS) "Not this · last one" else "Not this", onNotThis)
}

/** The running start: the clock, the first step, one big tick that ends in the finish moment. */
@Composable
private fun RunningStart(p: NowPick, startedAt: Long, minutes: Int, finishing: Boolean, clock: () -> Long, onFinish: () -> Unit) {
    val endsAt = startedAt + minutes * 60_000L
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(endsAt) { while (now < endsAt) { delay(1_000); now = clock() } }
    val left = (endsAt - now) / 1000
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (left > 0) "Just this, for $minutes min" else "$minutes min in · tick it or keep going", style = MaterialTheme.typography.labelLarge, color = Glass.Muted,
            modifier = Modifier.padding(top = Space.sm), textAlign = TextAlign.Center)
        Text(clockText(left), style = Glass.NumberText.copy(fontSize = 56.sp, lineHeight = 64.sp), color = Glass.Ink,
            modifier = Modifier.padding(vertical = Space.sm).semantics { contentDescription = "${clockText(left)} left" })
        Text(p.title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text("First: ${p.first}", style = MaterialTheme.typography.bodyMedium, color = Glass.PrimaryInk, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.xs))
        Spacer(Modifier.height(Space.xl))
        BigTick(p.title, finishing, onFinish)
        Spacer(Modifier.height(Space.sm))
    }
}

/** A 72 dp mint ring with a check: the tick of a focus card or a running start. Done pops it and fills it. */
@Composable
internal fun BigTick(title: String, done: Boolean, onTick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(done) { if (done) { pop.animateTo(1.18f, tween(160)); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) } }
    Box(
        Modifier.size(88.dp).pressScale(press)
            .clickable(press, indication = null, enabled = !done, role = Role.Checkbox, onClick = onTick)
            .semantics { contentDescription = "Done: $title" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(72.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                .then(if (done) Modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Glass.Aurora2, Glass.Aurora1)), CircleShape)
                else Modifier.background(Color.White.copy(alpha = 0.86f), CircleShape).border(2.dp, Glass.Primary, CircleShape)),
            contentAlignment = Alignment.Center,
        ) { Icon(TabIcons.Check, contentDescription = null, tint = if (done) Glass.Ink else Glass.Primary, modifier = Modifier.size(36.dp)) }
    }
}

@Composable
private fun NothingFits(onClose: () -> Unit) {
    SheetTitle("Nothing else fits right now", "Take 5 minutes. Water, a stretch, then ask again.")
    Spacer(Modifier.height(Space.lg))
    PrimaryPill("OK", "Close", onClose)
}

/** The one filled mint pill of a sheet (its main action). */
@Composable
internal fun PrimaryPill(label: String, said: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press)
            .background(Glass.Primary, RoundedCornerShape(26.dp))
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = said },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1) }
}

/** A quiet word button (Not this, Skip). */
@Composable
internal fun QuietButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxWidth().heightIn(min = Space.touch).pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = Glass.Muted, maxLines = 1) }
}
