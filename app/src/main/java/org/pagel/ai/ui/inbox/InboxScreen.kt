package org.pagel.ai.ui.inbox

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.pagel.ai.data.inbox.Choice
import org.pagel.ai.ui.glass.EaseOutStrong
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.ProjectTile
import org.pagel.ai.ui.glass.SkeletonRows
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TouchMin
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.today.Burst
import org.pagel.ai.ui.today.ConfettiBurst
import org.pagel.ai.ui.today.Ding
import org.pagel.ai.ui.today.reducedMotion
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

// ── The six choices: the web's words, keys and colours (learn-client.tsx CHOICES, globals.css [data-kind]) ──────────

/** One choice as the screen shows it. Colours carry white words at ≥ 4.5:1 (Do now darkened from the web's #eb8909). */
data class ChoiceLook(val icon: ImageVector, val label: String, val hint: String, val color: Color, val whenText: String, val eg: String)

val Choice.look: ChoiceLook
    get() = when (this) {
        Choice.ACT -> ChoiceLook(TabIcons.ListTodo, "Action", "a next step in a plan", Color(0xFF1C7E69),
            "It asks you to DO something. Write the very next physical step: a verb and a thing.", "\"Apply to the grant\" → \"Open the form and fill the first page\" in Humopedia.")
        Choice.NOTE -> ChoiceLook(TabIcons.StickyNote, "Note", "one line to remember", Color(0xFF1C7E69),
            "Only one fact is worth keeping: a person, an account, a tool, a price.", "A drone-maker reel → \"@ixtirochi_bola: Uzbek maker, FPV drones with ESP\".")
        Choice.KEEP -> ChoiceLook(TabIcons.BookOpen, "Learn", "in your words, then recall", Color(0xFF7C4DFF),
            "An idea you want in your head. Say it in your own words; PAGEL asks you about it later. 5 a day.", "\"Quizzes beat rereading\" → \"every lesson ends with 3 questions\".")
        Choice.NOW -> ChoiceLook(TabIcons.Zap, "Do now", "under 2 minutes", Color(0xFFB25E00),
            "It takes under 2 minutes. Do it right now, then it is gone.", "Follow an account, reply \"thanks\", star a repo.")
        Choice.LATER -> ChoiceLook(TabIcons.Clock, "Later", "once", Color(0xFF6B7280),
            "Not now, but not sure. Goes to the back once; next time you must decide.", "A long video you cannot watch tonight.")
        Choice.DROP -> ChoiceLook(TabIcons.Trash, "Drop", "git keeps it", Color(0xFFD1453B),
            "Nothing to do, nothing to keep. Most saves end here, and that is fine. Undo for 5 s.", "Memes, jokes, ads, \"10 AI tools\" lists you will never open.")
    }

/** The web's chip order (A, N, K, 2, L, D). */
private val ORDER = listOf(Choice.ACT, Choice.NOTE, Choice.KEEP, Choice.NOW, Choice.LATER, Choice.DROP)

/** Each choice's own feel under the finger (Apple HIG playing haptics: one per action, matched to its meaning). */
fun Choice.haptic(): HapticFeedbackType = when (this) {
    Choice.DROP -> HapticFeedbackType.Reject
    Choice.LATER -> HapticFeedbackType.SegmentTick
    Choice.NOTE -> HapticFeedbackType.ToggleOn
    else -> HapticFeedbackType.Confirm
}

/** Where a swiped card was let go and how fast it was moving (px, px/s): it keeps flying that way. */
data class Throw(val x: Float = 0f, val y: Float = 0f, val vx: Float = 0f, val vy: Float = 0f) {
    val swiped get() = x != 0f || y != 0f

    companion object {
        val None = Throw()
    }
}

/** What the screen can ask of the Inbox. */
data class InboxActions(
    val choose: (InboxCard, Choice, Throw) -> Unit = { _, _, _ -> },
    val openForm: (InboxCard, Choice) -> Unit = { _, _ -> },
    val leaveDone: (Long) -> Unit = {},
    val help: () -> Unit = {},
    val readAll: (InboxCard) -> Unit = {},
)

@Composable
fun InboxRoute(contentPadding: PaddingValues, reselect: Int, vm: InboxViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val leaving by vm.leaving.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val pictures by vm.picturesShown.collectAsStateWithLifecycle()
    val cheer by vm.cheer.collectAsStateWithLifecycle()
    var guide by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<InboxCard?>(null) }
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) {
        vm.onScreen(true)
        onStopOrDispose { vm.onScreen(false) }
    }
    InboxScreen(
        ui, leaving, pictures, contentPadding, reselect = reselect, cheer = cheer,
        actions = InboxActions(choose = vm::choose, openForm = vm::openForm, leaveDone = vm::leaveDone, help = { guide = true }, readAll = { reading = it }),
    )
    form?.let { f -> ChoiceFormSheet(f, ui, onEdit = vm::editForm, onSubmit = vm::submit, onDismiss = vm::closeForm, withPicture = pictures[f.card.file] != null) }
    if (guide) GuideSheet { guide = false }
    reading?.let { c -> ReadAllSheet(c) { reading = null } }
}

/**
 * The Inbox, one save at a time (the web's /learn on the phone): the post's picture large, the AI's pick as the big
 * button, the rest as chips. Swipe right takes the AI's pick, left drops, up puts off once. Every choice answers at
 * once with its own motion, sound and haptic; the save goes behind. The ring counts the day's 20.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InboxScreen(
    ui: InboxUi,
    leaving: Leaving?,
    pictures: Map<String, Bitmap?>,
    contentPadding: PaddingValues,
    actions: InboxActions = InboxActions(),
    reselect: Int = 0,
    cheer: Int = 0,
    /** Where the top card starts (screenshot tests draw it mid-swipe). */
    peek: Offset = Offset.Zero,
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reduce = remember { reducedMotion(ctx) }
    val scroll = rememberScrollState()
    val bursts = remember { mutableStateListOf<Burst>() }
    var nextBurst by remember { mutableLongStateOf(0L) }
    var ringAt by remember { mutableStateOf(Offset.Zero) }
    var rootAt by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(reselect) { if (reselect > 0) scroll.animateScrollTo(0) }
    // Each choice: its haptic and its sound the moment it is made (rules/respond-first-save-after).
    LaunchedEffect(leaving?.id) {
        val l = leaving ?: return@LaunchedEffect
        haptics.performHapticFeedback(l.choice.haptic())
        scope.launch(Dispatchers.Default) { ChoiceSound.play(l.choice) }
    }
    // A keep's milestone or the day's 20: the finish moment from the ring (Today's ding and confetti).
    LaunchedEffect(cheer) {
        if (cheer == 0) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch(Dispatchers.Default) { Ding.play() }
        if (!reduce) bursts += Burst(nextBurst++, ringAt - rootAt)
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootAt = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(contentPadding).padding(bottom = 12.dp)) {
            Header(ui, onHelp = actions.help, onRing = { ringAt = it })
            when {
                ui.loading -> Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(4, Modifier.padding(vertical = 10.dp)) }
                ui.cards.isEmpty() && leaving == null -> Empty(ui)
                else -> Deck(ui, leaving, pictures, actions, reduce, peek)
            }
        }
        for (b in bursts) key(b.id) { ConfettiBurst(b, onEnd = { bursts.remove(b) }, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Header(ui: InboxUi, onHelp: () -> Unit, onRing: (Offset) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // With no card here, "N left" would sit over "Inbox zero" (checker 2026-10-28 #8): the empty card says it.
                if (ui.cards.isNotEmpty()) Text("${ui.left} left", style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                Text("Inbox", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            }
            if (ui.streak > 0) Streak(ui.streak)
            GlassIconButton(TabIcons.CircleHelp, "How sorting works", plain = true, onClick = onHelp)
            GoalRing(ui.sorted, ui.goal, Modifier.onGloballyPositioned { onRing(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) })
        }
        // The day's goal leads; the learned habit (5) follows (learn-client.tsx today-count). The counts get the whole
        // width and keep their words together, so "2/5 learned" never breaks after the number (checker #7).
        Text(countsLine(ui), style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
    }
}

/** "7/20 sorted today · 2/5 learned", with no-break spaces inside each count. */
fun countsLine(ui: InboxUi): String {
    val nb = "\u00A0"
    val learned = if (ui.kept < 5) "${ui.kept}/5${nb}learned" else "${ui.kept}${nb}learned${nb}✓"
    return "${ui.sorted}/${ui.goal}${nb}sorted${nb}today · $learned"
}

/** Sorted since the screen opened, the web's flame pill. Solid deep orange so the white number keeps 5:1. */
@Composable
private fun Streak(n: Int) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(n) { pop.snapTo(0.6f); pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 600f)) }
    Row(
        Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .background(Color(0xFFC2410C), CircleShape).padding(start = 7.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$n sorted since you opened the Inbox" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TabIcons.Flame, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(3.dp))
        Text("$n", style = Glass.NumberText.copy(fontSize = 13.sp), color = Color.White)
    }
}

/**
 * The day's 20 as a ring (the web's goal bar, round, as Apple's activity rings): a mint arc on a faint track that
 * springs forward with each choice; at the goal it closes, fills soft mint and shows a tick.
 */
@Composable
fun GoalRing(sorted: Int, goal: Int, modifier: Modifier = Modifier) {
    val target = if (goal <= 0) 0f else min(1f, sorted.toFloat() / goal)
    val p by animateFloatAsState(target, spring(dampingRatio = 0.8f, stiffness = 220f), label = "ring")
    val done = goal > 0 && sorted >= goal
    val scale = LocalDensity.current.fontScale
    Box(modifier.size(58.dp).semantics { contentDescription = "$sorted of $goal sorted today" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(50.dp)) {
            val w = 5.dp.toPx()
            if (done) drawCircle(Glass.PrimarySoft, size.minDimension / 2 - w)
            drawCircle(Glass.Ink.copy(alpha = 0.08f), size.minDimension / 2 - w / 2, style = Stroke(w))
            if (p > 0f) drawArc(Glass.Primary, -90f, 360f * p, false, topLeft = Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        if (done) Icon(TabIcons.Check, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(22.dp))
        // Numbers stop growing at 1.2× font so "18/20" stays inside the ring (as the tab words do).
        else Text("$sorted/$goal", style = Glass.NumberText.copy(fontSize = (11f * min(scale, 1.2f) / scale).sp), color = Glass.Ink, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun Empty(ui: InboxUi) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Saves still on pagel.uz but none here (offline after the saved deck ran out): say so, no spinner.
            val waiting = ui.left > 0
            Box(Modifier.size(72.dp).background(Glass.PrimarySoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (waiting) TabIcons.CloudOff else TabIcons.InboxZero, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(if (waiting) "${ui.left} more when online" else "Inbox zero", style = MaterialTheme.typography.headlineSmall, color = Glass.Ink)
            Spacer(Modifier.height(4.dp))
            Text(if (ui.kept > 0) "${ui.kept} learned today." else "New saves land here.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
        }
    }
}

// ── The deck ────────────────────────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Deck(ui: InboxUi, leaving: Leaving?, pictures: Map<String, Bitmap?>, actions: InboxActions, reduce: Boolean, peek: Offset) {
    val top = ui.cards.firstOrNull()
    // How far the top card is dragged (0..1): the card behind grows into its place as the top one leaves.
    var drag by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.TopCenter) {
        if (top != null) {
            Box {
                if (ui.cards.size > 1) {
                    // The next card peeks out under the top one: a stack, so Boss sees there is more.
                    // It shows where the next save is from and its claim, faded, so a moving top card never uncovers a blank slab.
                    val next = ui.cards[1]
                    Pane(Modifier.matchParentSize().graphicsLayer {
                        val g = drag.coerceIn(0f, 1f)
                        scaleX = 0.94f + 0.06f * g; scaleY = 0.94f + 0.06f * g
                        translationY = (1f - g) * 14.dp.toPx(); alpha = 0.55f + 0.45f * g
                    }.clearAndSetSemantics {}) {
                        Column(Modifier.padding(18.dp).graphicsLayer { alpha = 0.55f }) {
                            SitePill(next.site, next.siteName)
                            Spacer(Modifier.height(10.dp))
                            Text(next.claim, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                key(top.file) {
                    SwipeCard(top, ui, peek, reduce, onDrag = { drag = it }, onRelease = { choice, at, v ->
                        drag = 0f
                        if (choice == Choice.LATER || choice == Choice.DROP) { actions.choose(top, choice, Throw(at.x, at.y, v.x, v.y)); true }
                        else { actions.openForm(top, choice); false }
                    }) { CardBody(top, pictures, actions) }
                }
            }
        }
        leaving?.let { l -> key(l.id) { LeavingCard(l, pictures, reduce) { actions.leaveDone(l.id) } } }
    }
    if (top != null) {
        Spacer(Modifier.height(14.dp))
        Choose(top, ui, actions)
    }
}

/**
 * The top card under the finger: it follows the drag with a slight tilt, shows the choice it will make as a stamp,
 * ticks once when it crosses the line, and either leaves (released past the line or flung) or springs back.
 * Right takes the AI's pick (Learn when it has none), left drops, up puts off once.
 */
@Composable
private fun SwipeCard(card: InboxCard, ui: InboxUi, peek: Offset, reduce: Boolean, onDrag: (Float) -> Unit, onRelease: (Choice, Offset, androidx.compose.ui.unit.Velocity) -> Boolean, content: @Composable () -> Unit) {
    val offset = remember { Animatable(peek, Offset.VectorConverter) }
    val enter = remember { Animatable(if (reduce || peek != Offset.Zero) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var width by remember { mutableFloatStateOf(1000f) }
    val upLine = with(LocalDensity.current) { 110.dp.toPx() }
    val right = card.guess ?: if (ui.full) Choice.NOTE else Choice.KEEP

    fun choiceAt(o: Offset): Choice? = when {
        o.y < -upLine && abs(o.y) > abs(o.x) && !card.putOff -> Choice.LATER
        o.x > width * 0.3f -> right
        o.x < -width * 0.3f -> Choice.DROP
        else -> null
    }
    LaunchedEffect(Unit) { if (enter.value < 1f) enter.animateTo(1f, tween(320, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f))) }
    LaunchedEffect(peek) { onDrag(min(1f, hypot(peek.x, peek.y) / (width * 0.3f))) }
    val stamp by remember { derivedStateOf { choiceAt(offset.value) } }

    Box(
        Modifier
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(card.file) {
                val tracker = VelocityTracker()
                var armed: Choice? = null
                detectDragGestures(
                    onDragStart = { tracker.resetTracking(); armed = null },
                    onDragCancel = { scope.launch { offset.animateTo(Offset.Zero, spring(dampingRatio = 0.62f, stiffness = 380f)) }; onDrag(0f) },
                    onDragEnd = {
                        val v = tracker.calculateVelocity()
                        val o = offset.value
                        val flung = when {
                            v.y < -1800f && abs(v.y) > abs(v.x) && !card.putOff -> Choice.LATER
                            v.x > 1800f -> right
                            v.x < -1800f -> Choice.DROP
                            else -> null
                        }
                        val c = choiceAt(o) ?: flung
                        if (c == null || !onRelease(c, o, v)) scope.launch { offset.animateTo(Offset.Zero, spring(dampingRatio = 0.62f, stiffness = 380f)) }
                        if (c == null) onDrag(0f)
                    },
                ) { change, amount ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val next = offset.value + amount
                    scope.launch { offset.snapTo(next) }
                    onDrag(min(1f, hypot(next.x, next.y) / (width * 0.3f)))
                    val c = choiceAt(next)
                    if (c != armed) { if (c != null) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate); armed = c }
                }
            }
            .graphicsLayer {
                translationX = offset.value.x
                translationY = offset.value.y + (1f - enter.value) * 18.dp.toPx()
                rotationZ = offset.value.x / width * 9f
                val s = 0.97f + 0.03f * enter.value
                scaleX = s; scaleY = s
                alpha = enter.value
            },
    ) {
        content()
        stamp?.let { Stamp(it, Modifier.align(if (it == Choice.DROP) Alignment.TopEnd else if (it == Choice.LATER) Alignment.BottomCenter else Alignment.TopStart)) }
    }
}

/** The choice a swipe will make, stamped on the card while it is dragged (a dating app's LIKE/NOPE, in PAGEL's words). */
@Composable
private fun Stamp(choice: Choice, modifier: Modifier) {
    val look = choice.look
    Row(
        modifier.padding(18.dp).graphicsLayer { rotationZ = if (choice == Choice.DROP) 10f else if (choice == Choice.LATER) 0f else -10f }
            .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(12.dp)).border(2.5.dp, look.color, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(look.icon, contentDescription = null, tint = look.color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(look.label.uppercase(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp), color = look.color)
    }
}

/**
 * A card leaving with its choice's motion (globals.css "Inbox game feel"): Action flies up-right into the plan, Note
 * folds small like a sticky note, Learn turns over like a page, Do now zaps and pops, Later slides to the back, Drop
 * tips and falls. A swiped card keeps flying the way it was thrown. Not drawn at all with reduced motion.
 */
@Composable
private fun LeavingCard(l: Leaving, pictures: Map<String, Bitmap?>, reduce: Boolean, onEnd: () -> Unit) {
    if (reduce) { LaunchedEffect(l.id) { onEnd() }; return }
    if (l.thrown.swiped) { ThrownCard(l, pictures, onEnd); return }
    val p = remember { Animatable(0f) }
    LaunchedEffect(l.id) { p.animateTo(1f, tween(380, easing = CubicBezierEasing(0.2f, 0.6f, 0.35f, 1f))); onEnd() }
    Box(
        Modifier.clearAndSetSemantics {}.graphicsLayer {
            val t = p.value
            val w = size.width; val h = size.height
            alpha = 1f - t
            when (l.choice) {
                Choice.ACT -> { translationX = 0.6f * w * t; translationY = -0.4f * h * t; scaleX = 1f - 0.6f * t; scaleY = scaleX; rotationZ = 6f * t }
                Choice.NOTE -> { translationX = 0.45f * w * t; translationY = -0.55f * h * t; scaleX = 1f - 0.85f * t; scaleY = scaleX; rotationZ = -12f * t }
                Choice.KEEP -> { rotationY = 85f * t; scaleX = 1f - 0.1f * t; scaleY = scaleX; cameraDistance = 12f * density }
                Choice.NOW -> { translationX = if (t < 0.45f) 6.dp.toPx() * kotlin.math.sin(t * 40f) else 0f; scaleX = 1f + 0.15f * t; scaleY = scaleX }
                Choice.LATER -> { translationY = 0.4f * h * t; scaleX = 1f - 0.08f * t; scaleY = scaleX }
                Choice.DROP -> { translationY = 0.7f * h * t; rotationZ = 10f * t; scaleX = 1f - 0.1f * t; scaleY = scaleX }
            }
        },
    ) { CardBody(l.card, pictures, InboxActions()) }
}

/**
 * A swiped card leaving: it starts where the finger let go and keeps the finger's speed and direction (apple-design
 * velocity handoff) on a critically damped spring, so a hard fling leaves fast and a slow push drifts off.
 */
@Composable
private fun ThrownCard(l: Leaving, pictures: Map<String, Bitmap?>, onEnd: () -> Unit) {
    val t = l.thrown
    val start = Offset(t.x, t.y)
    val pos = remember { Animatable(start, Offset.VectorConverter) }
    val far = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() * 1.4f }
    LaunchedEffect(l.id) {
        val speed = hypot(t.vx, t.vy)
        val len = hypot(t.x, t.y).coerceAtLeast(1f)
        val dir = if (speed > 300f) Offset(t.vx / speed, t.vy / speed) else Offset(t.x / len, t.y / len)
        pos.animateTo(start + dir * far, spring(dampingRatio = 1f, stiffness = 260f), initialVelocity = Offset(t.vx, t.vy))
        onEnd()
    }
    Box(
        Modifier.clearAndSetSemantics {}.graphicsLayer {
            val gone = ((pos.value - start).getDistance() / far).coerceIn(0f, 1f)
            translationX = pos.value.x
            translationY = pos.value.y
            rotationZ = pos.value.x / size.width.coerceAtLeast(1f) * 9f
            alpha = 1f - gone
        },
    ) { CardBody(l.card, pictures, InboxActions()) }
}

/** One save: where it is from, the AI's one-line claim, why it matters, the picture large, the post, its link. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardBody(card: InboxCard, pictures: Map<String, Bitmap?>, actions: InboxActions) {
    val uri = LocalUriHandler.current
    // The choice buttons stay above the tab bar without a scroll: the picture gives up height on short screens.
    // The AI's pick and the row of choices stay above the tab bar without a scroll; the picture takes what is left.
    // Measured on the 360x780 shots: the card without a picture, the pick and the row need ~660 dp with the bar. Larger
    // text takes height too (checker 2026-10-28 #1: at 1.5x the pick fell off): ~320 dp per font step. Below 96 dp the
    // picture hides; with one the caption keeps 2 lines, at 1.3x+ one.
    val screenH = LocalConfiguration.current.screenHeightDp
    val scale = LocalDensity.current.fontScale
    val budget = screenH - 660 - ((scale - 1f) * 320f).toInt()
    val pictureH = if (budget < 96 || !card.hasPicture) 0.dp else budget.coerceAtMost(220).dp
    val captionLines = when { scale >= 1.3f -> 1; screenH < 720 || pictureH > 0.dp -> 2; else -> 3 }
    var cut by remember(card.file) { mutableStateOf(false) }
    Pane(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SitePill(card.site, card.siteName)
                card.author?.let { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                card.posted?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Glass.Muted) }
                if (card.project != "all") Row(verticalAlignment = Alignment.CenterVertically) {
                    ProjectTile(card.project, card.projectColor, card.projectTitle ?: card.project, size = 16.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(card.projectTitle ?: card.project.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = Glass.Muted)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(card.claim, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, maxLines = 5, overflow = TextOverflow.Ellipsis)
            card.why?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Medium), color = Glass.Primary)
            }
            if (card.hasPicture && pictureH > 0.dp) {
                val bmp = pictures[card.file]
                if (card.file !in pictures) {
                    Spacer(Modifier.height(12.dp))
                    PictureSkeleton(pictureH)
                } else if (bmp != null) {
                    Spacer(Modifier.height(12.dp))
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(img, contentDescription = "The post's picture", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(pictureH).clip(RoundedCornerShape(16.dp)))
                }
            }
            if (card.text.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    card.text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF3B4256), maxLines = captionLines, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { cut = it.hasVisualOverflow },
                    modifier = Modifier.drawBehind { drawLine(Glass.Ink.copy(alpha = 0.10f), Offset(0f, 0f), Offset(0f, size.height), 3.dp.toPx()) }.padding(start = 12.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Whenever the caption is cut, there is a way to read it all (checker #6: "..." with no link).
                if (cut) LinkWord("Read all", null) { actions.readAll(card) }
                Spacer(Modifier.weight(1f))
                card.link?.let { l -> LinkWord("Open the post", TabIcons.ArrowUpRight) { runCatching { uri.openUri(l) } } }
            }
        }
    }
}

@Composable
private fun PictureSkeleton(height: androidx.compose.ui.unit.Dp) {
    val a by androidx.compose.animation.core.rememberInfiniteTransition(label = "pic").animateFloat(
        0.45f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "breathe",
    )
    Box(Modifier.fillMaxWidth().height(height).graphicsLayer { alpha = a }.background(Glass.Ink.copy(alpha = 0.07f), RoundedCornerShape(16.dp)).clearAndSetSemantics {})
}

/** A small text link with a 48 dp touch box (the web's .ib-link). */
@Composable
private fun LinkWord(text: String, icon: ImageVector?, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.heightIn(min = TouchMin).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), color = Glass.Muted)
        icon?.let { Spacer(Modifier.width(3.dp)); Icon(it, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(14.dp)) }
    }
}

/** Where the save came from, as a small coloured mark (white on ≥ 4.5:1: Instagram's gradient from its darker half). */
@Composable
private fun SitePill(site: Site, name: String) {
    val bg = when (site) {
        Site.INSTAGRAM -> Brush.linearGradient(listOf(Color(0xFFC13584), Color(0xFF833AB4)))
        Site.X -> SolidColor(Color(0xFF111111))
        Site.LINKEDIN -> SolidColor(Color(0xFF0A66C2))
        else -> SolidColor(Glass.Muted)
    }
    Row(Modifier.background(bg, CircleShape).padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        when (site) {
            Site.INSTAGRAM -> Icon(TabIcons.Instagram, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            // X's own mark, not a close cross (checker 2026-10-28 #9).
            Site.X -> Icon(TabIcons.XLogo, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            Site.LINKEDIN -> Text("in", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFF0A66C2),
                modifier = Modifier.background(Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
            else -> Icon(TabIcons.Globe, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── The choices under the card ──────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choose(card: InboxCard, ui: InboxUi, actions: InboxActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val guess = card.guess
        if (guess != null) GuessButton(card, guess, ui) { actions.choose(card, guess, Throw.None) }
        else AiReading(ui.reading)
        Spacer(Modifier.height(8.dp))
        // One row of icons, each with its word under it (checker #5: as chips, three of six sat under the tab bar).
        Row(Modifier.fillMaxWidth()) {
            for (c in ORDER) if (c != guess) {
                val off = (c == Choice.KEEP && ui.full) || (c == Choice.LATER && card.putOff)
                ChoiceIcon(c, enabled = !off, modifier = Modifier.weight(1f)) { actions.choose(card, c, Throw.None) }
            }
        }
    }
}

/**
 * The AI's pick, the big button (the web's Enter): the choice's colour, its icon, "AI's pick" and what it does. With
 * today's keeps full, a Learn becomes a Note and says why, as on the web.
 */
@Composable
private fun GuessButton(card: InboxCard, guess: Choice, ui: InboxUi, onClick: () -> Unit) {
    val look = guess.look
    val press = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    // The hint alone, in full white (the .92 white measured 4.36:1 on Learn's light end); "AI's pick" is the sparkle.
    val sub = if (guess != card.pick) "${ui.cap}/${ui.cap} learned · one line" else look.hint
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).pressScale(press)
            .background(Brush.linearGradient(listOf(look.color, lerp(look.color, Color.Black, 0.18f))), shape)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)), shape)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "AI pick: ${look.label}" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(look.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(look.label, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.width(6.dp))
                Icon(TabIcons.Sparkles, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Swipe right does the same: the arrow says so without words.
        Icon(TabIcons.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

/**
 * One of the other choices: a white glass disc with the choice's icon in its colour and its word under it (it shrinks
 * to fit at large font, never cut); the whole column is one target of at least 48 dp.
 */
@Composable
private fun ChoiceIcon(choice: Choice, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val look = choice.look
    val press = remember { MutableInteractionSource() }
    val scale = LocalDensity.current.fontScale
    Column(
        modifier.heightIn(min = TouchMin).pressScale(press).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clickable(press, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(42.dp).background(Color.White.copy(alpha = 0.72f), CircleShape)
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Glass.Aurora1.copy(alpha = 0.45f))), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(look.icon, contentDescription = null, tint = look.color, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.height(3.dp))
        androidx.compose.foundation.text.BasicText(
            look.label,
            style = MaterialTheme.typography.labelSmall.copy(color = Glass.Ink.copy(alpha = 0.86f), textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            maxLines = 1,
            softWrap = false,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = (8f / scale).sp, maxFontSize = (12.5f * minOf(scale, 1.2f) / scale).sp, stepSize = (0.25f / scale).sp),
        )
    }
}

/**
 * No pick yet: a white glass pill with a mint rim and a sparkle that breathes while pagel.uz's AI reads (it was a grey
 * dashed box that read as a disabled empty slot, checker 2026-10-28 #9). Still when nothing is asked yet.
 */
@Composable
private fun AiReading(reading: Boolean) {
    val breath = if (reading) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "ai")
        t.animateFloat(0.45f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "breath")
    } else null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(Color.White.copy(alpha = 0.78f), CircleShape)
            .border(1.25.dp, Brush.linearGradient(listOf(Color.White, Glass.Aurora1, Color.White)), CircleShape)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TabIcons.Sparkles, contentDescription = null, tint = Glass.Primary, modifier = Modifier.size(18.dp).graphicsLayer { alpha = breath?.value ?: 1f })
        Spacer(Modifier.width(10.dp))
        Text(if (reading) "AI is reading this one" else "Not read yet · pick below", style = MaterialTheme.typography.bodyMedium, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
