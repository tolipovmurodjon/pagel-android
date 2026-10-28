package org.pagel.ai.ui.pair

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale

@Composable
fun PairRoute(vm: PairViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    PairScreen(ui, vm::onCode, vm::onServer, vm::connect)
}

/**
 * "Connect this phone": no password on the phone, one code from pagel.uz → Phones (NATIVE-PLAN Phase 1). Six boxes
 * (iOS one-time-code style), paste, the sixth digit sends; the button fills while pagel.uz is asked, a check draws
 * itself when it says yes, a wrong code shakes the boxes once (Boss 2026-10-28: "the progress bar is trash").
 */
@Composable
fun PairScreen(ui: PairUiState, onCode: (String) -> Unit, onServer: (String) -> Unit, onConnect: () -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    // The keyboard opens on the code at once: one look at pagel.uz, six digits, done.
    val focus = remember { FocusRequester() }
    // One haptic per outcome, on the same frame as the change it reports (Apple HIG playing haptics).
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(ui.shake, ui.timedOut) { if (ui.error != null) haptics.performHapticFeedback(HapticFeedbackType.Reject) }
    LaunchedEffect(ui.step) { if (ui.step == PairStep.DONE) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AuroraBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("PAGEL", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink)
            Spacer(Modifier.height(6.dp))
            Text("Connect this phone to pagel.uz.", style = MaterialTheme.typography.bodyLarge, color = Glass.Muted)
            Spacer(Modifier.height(24.dp))
            Pane {
                Column(Modifier.padding(20.dp)) {
                    Text("On pagel.uz open More → Phones and tap Connect phone. Type the 6 digits here.", style = MaterialTheme.typography.bodyMedium, color = Glass.Ink)
                    Spacer(Modifier.height(16.dp))
                    CodeBoxes(ui, focus, onCode, onConnect)
                    Spacer(Modifier.height(16.dp))
                    ConnectButton(ui, onConnect)
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (advanced) "Hide server" else "Server", color = Glass.Muted)
            }
            if (advanced) {
                OutlinedTextField(ui.server, onServer, label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
        }
    }
}

/**
 * The code as six boxes over one hidden field (so the keyboard, paste and the system's code autofill all work): each
 * digit in its own box in the body font, the next box ringed mint. A wrong code shakes the row once. "Paste" takes
 * the first six digits on the clipboard.
 */
@Composable
private fun CodeBoxes(ui: PairUiState, focus: FocusRequester, onCode: (String) -> Unit, onConnect: () -> Unit) {
    val shake = remember { Animatable(0f) }
    var focused by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(ui.shake) {
        if (ui.shake == 0) return@LaunchedEffect
        shake.animateTo(0f, keyframes { durationMillis = 360; 0f at 0; -12f at 50; 12f at 110; -9f at 170; 7f at 230; -3f at 290; 0f at 360 })
    }
    Column {
        BasicTextField(
            value = ui.code,
            onValueChange = onCode,
            enabled = !ui.busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onConnect() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .graphicsLayer { translationX = shake.value.dp.toPx() }
                .semantics { contentDescription = "Code, ${ui.code.length} of 6 digits" },
            decorationBox = { inner ->
                Box {
                    // The real field stays in the tree (keyboard, paste, autofill) but draws nothing: the boxes show the code.
                    Box(Modifier.size(1.dp).graphicsLayer { alpha = 0f }) { inner() }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in 0 until 6) {
                            val digit = ui.code.getOrNull(i)
                            val next = focused && !ui.busy && i == ui.code.length
                            val shape = RoundedCornerShape(12.dp)
                            Box(
                                Modifier.weight(1f).heightIn(min = 56.dp)
                                    .background(Color.White.copy(alpha = 0.82f), shape)
                                    .border(if (next) 1.75.dp else 1.dp, if (ui.error != null && ui.code.isEmpty()) Glass.Risk.copy(alpha = 0.6f) else if (next) Glass.Primary else Glass.Ink.copy(alpha = 0.14f), shape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (digit != null) Text(digit.toString(), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold), color = Glass.Ink)
                                else if (next) Caret()
                            }
                        }
                    }
                }
            },
        )
        // slot: Paste and the error share one reserved line (54 dp): the block above and Connect below never move when
        // the 6th digit hides Paste or an error comes (spacing audit #5). What went wrong has a mark as well as the colour.
        Box(Modifier.fillMaxWidth().heightIn(min = 54.dp), contentAlignment = Alignment.CenterEnd) {
            if (ui.error != null) Text("⚠  " + ui.error, color = Glass.Risk, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
            else {
            val pasteOn = !ui.busy && ui.code.length < 6
            val press = remember { MutableInteractionSource() }
            Row(
                Modifier.padding(top = 6.dp).heightIn(min = 48.dp).graphicsLayer { alpha = if (pasteOn) 1f else 0f }.pressScale(press)
                    .clickable(press, indication = null, enabled = pasteOn, role = Role.Button) {
                        scope.launch {
                            val text = runCatching { clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
                            val digits = text.filter(Char::isDigit).take(6)
                            if (digits.isNotEmpty()) onCode(digits)
                        }
                    }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TabIcons.Copy, contentDescription = null, tint = Glass.PrimaryInk, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste", style = MaterialTheme.typography.labelLarge, color = Glass.PrimaryInk)
            }
            }
        }
    }
}

/** The next box's caret, blinking as a text field's does. */
@Composable
private fun Caret() {
    val blink by rememberInfiniteTransition(label = "caret").animateFloat(1f, 0f, infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse), label = "blink")
    Box(Modifier.width(2.dp).height(26.dp).graphicsLayer { alpha = blink }.background(Glass.Primary))
}

/**
 * The one button: mint, "Connect". While pagel.uz is asked it fills left to right with a soft light passing over it,
 * and its words say what is happening ("Connecting…", after 4 s "Reaching pagel.uz…"); a yes draws a check; after a
 * timeout it says "Try again". Never a spinner (PHASE-BRIEF).
 */
@Composable
private fun ConnectButton(ui: PairUiState, onConnect: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val fill = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    LaunchedEffect(ui.step) {
        when (ui.step) {
            PairStep.TYPING -> { fill.snapTo(0f); check.snapTo(0f) }
            // Most of the way in the time pagel.uz usually takes, then a slow creep: it never claims to be done.
            PairStep.CONNECTING -> if (fill.value < 0.6f) fill.animateTo(0.6f, tween(1_200, easing = FastOutSlowInEasing))
            PairStep.SLOW -> fill.animateTo(0.92f, tween(26_000, easing = LinearEasing))
            PairStep.DONE -> { fill.animateTo(1f, spring(dampingRatio = 1f, stiffness = 600f)); check.animateTo(1f, tween(360, easing = FastOutSlowInEasing)) }
        }
    }
    val working = ui.step == PairStep.CONNECTING || ui.step == PairStep.SLOW
    val shimmer = if (working) {
        rememberInfiniteTransition(label = "shimmer").animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "x")
    } else null
    val label = when {
        ui.step == PairStep.DONE -> "Connected"
        ui.step == PairStep.SLOW -> "Reaching pagel.uz…"
        ui.step == PairStep.CONNECTING -> "Connecting…"
        ui.timedOut -> "Try again"
        else -> "Connect"
    }
    val enabled = ui.canConnect
    val live = enabled || ui.busy
    val shape = RoundedCornerShape(28.dp)
    // Waiting for digits: soft mint with deep-mint words (never a grey slab); ready or asking: mint with white words.
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(press).clip(shape)
            .background(if (live) Glass.Primary else Glass.PrimarySoft)
            .clickable(press, indication = null, enabled = enabled, role = Role.Button, onClick = onConnect),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            // Asking: a deeper mint grows from the left under the words (white keeps ≥ 5:1 on both).
            if (ui.busy && fill.value > 0f) drawRect(Glass.PrimaryInk, size = size.copy(width = size.width * fill.value))
            shimmer?.let { s ->
                val x = size.width * s.value
                val band = size.width * 0.35f
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent), startX = x - band, endX = x + band))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ui.step == PairStep.DONE) {
                Canvas(Modifier.size(20.dp)) {
                    val p = Path().apply { moveTo(size.width * 0.18f, size.height * 0.54f); lineTo(size.width * 0.42f, size.height * 0.76f); lineTo(size.width * 0.84f, size.height * 0.28f) }
                    val m = PathMeasure().apply { setPath(p, false) }
                    val part = Path()
                    m.getSegment(0f, m.length * check.value, part, true)
                    drawPath(part, Color.White, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (live) Color.White else Glass.PrimaryInk, textAlign = TextAlign.Center)
        }
    }
}
