package org.pagel.ai.ui.inbox

import org.pagel.ai.ui.glass.Space
import androidx.compose.foundation.layout.wrapContentHeight
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
        Choice.DROP -> ChoiceLook(TabIcons.Trash, "Drop", "git keeps it", Color(0xFF1F2937),
            "Nothing to do, nothing to keep. Most saves end here, and that is fine. Undo for 5 s.", "Memes, jokes, ads, \"10 AI tools\" lists you will never open.")
        Choice.SOMEDAY -> ChoiceLook(TabIcons.Moon, "Someday", "maybe, with no date", Color(0xFF4F5FA8),
            "Not now, maybe never. One tap files it where the weekly review looks; it has no date and no count.", "\"Learn to weld\" → Someday. Bring it back when the time is right.")
        Choice.WAITING -> ChoiceLook(TabIcons.UserClock, "Waiting", "someone else has the ball", Color(0xFF0E7490),
            "Someone else must act first. Say who, and PAGEL asks you again on the check day (3 days on).", "\"Aziz sends the invoice\" → Waiting for Aziz, check Thursday.")
        Choice.PROJECT -> ChoiceLook(TabIcons.Folder, "Project", "more than one step", Color(0xFF166534),
            "It takes more than one step. Name the project, then write its very first step.", "\"Launch the newsletter\" → project Newsletter, first step \"Pick the tool\".")
        Choice.CALENDAR -> ChoiceLook(TabIcons.Calendar, "Calendar", "tied to a day or time", Color(0xFF8A3FB0),
            "It must happen on a day or at a time. Pick the date, and the wheel for the hour.", "\"Grant round closes 15 Nov\" → 15 Nov, 09:00.")
    }

/**
 * The GTD question's two groups (Boss 2026-10-30): "Can I do something about it?" No → Drop · Someday · Note · Learn;
 * Yes → Do now · Action · Project · Waiting · Calendar. Five slots a row (No's fifth stays empty), so the choices sit
 * in a grid and a card never shifts them. Later, the put-off, is a small text link under the rows.
 */
val NO_GROUP = listOf(Choice.DROP, Choice.SOMEDAY, Choice.NOTE, Choice.KEEP)
val YES_GROUP = listOf(Choice.NOW, Choice.ACT, Choice.PROJECT, Choice.WAITING, Choice.CALENDAR)

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

/**
 * The four swipes (Boss 2026-10-28: "swipe up it should shuffle, drop swipe down, left learn, right action"). A hold
 * is the fifth gesture (Do now), in [Deck].
 */
enum class Swipe {
    SHUFFLE, DROP, LEARN, ACTION;

    /** The choice a swipe makes; null = shuffle (back into the deck, nothing sent). Learn is a Note when today's keeps are full. */
    fun choice(full: Boolean): Choice? = when (this) {
        SHUFFLE -> null
        DROP -> Choice.DROP
        LEARN -> if (full) Choice.NOTE else Choice.KEEP
        ACTION -> Choice.ACT
    }

    companion object {
        /** px/s: a flick this fast decides even before the line. */
        const val FLING = 1800f

        /** The swipe a card dragged by ([dx], [dy]) px makes: vertical past [line], sideways past 30 % of [width]. */
        fun at(dx: Float, dy: Float, width: Float, line: Float): Swipe? = when {
            abs(dy) > abs(dx) && dy < -line -> SHUFFLE
            abs(dy) > abs(dx) && dy > line -> DROP
            dx > width * 0.3f -> ACTION
            dx < -width * 0.3f -> LEARN
            else -> null
        }

        /** The swipe a flick at ([vx], [vy]) px/s makes. */
        fun flung(vx: Float, vy: Float): Swipe? = when {
            abs(vy) > abs(vx) && vy < -FLING -> SHUFFLE
            abs(vy) > abs(vx) && vy > FLING -> DROP
            vx > FLING -> ACTION
            vx < -FLING -> LEARN
            else -> null
        }
    }
}

/** Shuffle's own colour (indigo, 6:1 on white): no choice owns it, so the stamp never reads as Later or Action. */
val ShuffleColor = Color(0xFF3B5BDB)

/** A swipe's stamp: its icon, word and colour (Learn and Note borrow their choice's). */
fun Swipe.look(full: Boolean): Triple<ImageVector, String, Color> = when (this) {
    Swipe.SHUFFLE -> Triple(TabIcons.Shuffle, "Shuffle", ShuffleColor)
    else -> choice(full)!!.look.let { Triple(it.icon, it.label, it.color) }
}

/**
 * The post's caption without the claim it starts with: LinkedIn's claim is the caption's first line, so the card said
 * it twice (Boss 2026-10-28). Empty when nothing but the claim is left.
 */
fun captionWithoutClaim(claim: String, text: String): String {
    val c = claim.lowercase().filter(Char::isLetterOrDigit)
    if (c.length < 12) return text
    var matched = 0
    var i = 0
    while (i < text.length && matched < c.length) {
        val ch = text[i]
        if (ch.isLetterOrDigit()) {
            if (ch.lowercaseChar() != c[matched]) return text
            matched++
        }
        i++
    }
    val rest = text.substring(i).trimStart { !it.isLetterOrDigit() && it != '@' && it != '#' }
    return if (rest.count(Char::isLetterOrDigit) < 12) "" else rest
}

/** What the screen can ask of the Inbox. */
data class InboxActions(
    val choose: (InboxCard, Choice, Throw) -> Unit = { _, _, _ -> },
    /** Swipe up: back into the deck at a random later spot. False = it cannot (the last card), so it springs back. */
    val shuffle: (InboxCard, Throw) -> Boolean = { _, _ -> false },
    val openForm: (InboxCard, Choice) -> Unit = { _, _ -> },
    val leaveDone: (Long) -> Unit = {},
    val help: () -> Unit = {},
    val readAll: (InboxCard) -> Unit = {},
    /** "Sorted today (N)": today's decisions and where each went (round D item 2). */
    val sorted: () -> Unit = {},
    /** The bulk tidy-up of the old saves (Inbox clarify). */
    val tidy: () -> Unit = {},
    /** Long-press a choice: the same choice, with last time's settings, on this card. */
    val repeat: (InboxCard, Choice) -> Unit = { _, _ -> },
)

@Composable
fun InboxRoute(
    contentPadding: PaddingValues, reselect: Int, onOpen: (InboxOpen) -> Unit = {}, onSorted: () -> Unit = {}, vm: InboxViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sortedToday by vm.sortedToday.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.open.collect(onOpen) }
    val leaving by vm.leaving.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val pictures by vm.picturesShown.collectAsStateWithLifecycle()
    val cheer by vm.cheer.collectAsStateWithLifecycle()
    val tidy by vm.tidy.collectAsStateWithLifecycle()
    val bulk by vm.bulk.collectAsStateWithLifecycle()
    var guide by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<InboxCard?>(null) }
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) {
        vm.onScreen(true)
        onStopOrDispose { vm.onScreen(false) }
    }
    InboxScreen(
        ui, leaving, pictures, contentPadding, reselect = reselect, cheer = cheer, sortedCount = sortedToday.size, bulk = bulk,
        actions = InboxActions(choose = vm::choose, shuffle = vm::shuffle, openForm = vm::openForm, leaveDone = vm::leaveDone, help = { guide = true }, readAll = { reading = it }, sorted = onSorted, tidy = vm::openTidy, repeat = vm::repeat),
    )
    tidy?.let { t -> TidySheet(t, onSomeday = { vm.tidyAll(Choice.SOMEDAY) }, onDrop = { vm.tidyAll(Choice.DROP) }, onDismiss = vm::closeTidy, onRetry = vm::openTidy) }
    form?.let { f -> ChoiceFormSheet(f, ui, onEdit = vm::editForm, onSubmit = vm::submit, onDismiss = vm::closeForm, withPicture = pictures[f.card.file] != null) }
    if (guide) GuideSheet { guide = false }
    reading?.let { c -> ReadAllSheet(c) { reading = null } }
}

/**
 * The Inbox, one save at a time (the web's /learn on the phone): the card in the middle, and a dock pinned above the
 * tab bar with the AI's pick as the big button and the other choices beside it, so one thumb reaches all (Boss
 * 2026-10-28: "move that nav, action etc part lower so easy to use on hand"). Swipe up shuffles, down drops, left
 * learns, right makes an Action; hold for Do now. Every choice answers at once with its own motion, sound and haptic;
 * the save goes behind. The ring counts the day's 20. A short screen (landscape) scrolls with the dock under the card.
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
    sortedCount: Int = 0,
    /** A bulk tidy-up on its way: its quiet "120 of 412" line takes the counts' place. */
    bulk: Bulk? = null,
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
        scope.launch(Dispatchers.Default) { Ding.play(ctx.applicationContext) }
        bursts += Burst(nextBurst++, ringAt - rootAt)
    }

    val tall = LocalConfiguration.current.screenHeightDp >= 480
    // The shell's bottom padding ends 8 dp above the glass bar's top; 4 more puts the dock 12 dp clear of it (0.8.1
    // drew the choice buttons behind the bar, only their tops showing).
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootAt = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize().then(if (tall) Modifier else Modifier.verticalScroll(scroll)).padding(contentPadding).padding(bottom = if (tall) DockGap else 12.dp)) {
            Header(ui, onHelp = actions.help, onRing = { ringAt = it }, sortedCount = sortedCount, onSorted = actions.sorted, onTidy = actions.tidy, bulk = bulk)
            // Tall: the middle takes what the header and the dock leave, and the card sits centred in it.
            val middle = if (tall) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth()
            when {
                ui.loading -> Box(middle, contentAlignment = Alignment.Center) {
                    Pane(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { SkeletonRows(4, Modifier.padding(vertical = 10.dp)) }
                }
                ui.cards.isEmpty() && leaving == null -> Box(middle, contentAlignment = Alignment.Center) { Empty(ui) }
                else -> {
                    androidx.compose.foundation.layout.BoxWithConstraints(middle, contentAlignment = Alignment.Center) {
                        // 12 dp of air round the card (was 16: the Later line under the grid took 4 of the card's room).
                        val room = if (tall && constraints.hasBoundedHeight) maxHeight - Space.md else 560.dp
                        Deck(ui, leaving, pictures, actions, reduce, peek, room, Modifier.fillMaxSize())
                    }
                    ui.cards.firstOrNull()?.let { Dock(it, ui, actions) }
                }
            }
        }
        for (b in bursts) key(b.id) { ConfettiBurst(b, onEnd = { bursts.remove(b) }, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Header(ui: InboxUi, onHelp: () -> Unit, onRing: (Offset) -> Unit, sortedCount: Int = 0, onSorted: () -> Unit = {}, onTidy: () -> Unit = {}, bulk: Bulk? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // With no card here, a count would sit over "Inbox zero" (checker 2026-10-28 #8): the empty card says it.
                // After the goal: done for today, never the pile ("900 more", checker 2026-10-30 #9).
                if (ui.cards.isNotEmpty()) FitText(headerNote(ui, LocalDensity.current.fontScale >= 1.3f), MaterialTheme.typography.labelSmall, Glass.Muted, minSize = 9.sp)
                // Never "Inbo / x": a word that does not fit gets a smaller size, not a break.
                FitText("Inbox", MaterialTheme.typography.headlineLarge, Glass.Ink, minSize = 18.sp, modifier = Modifier.semantics { heading() })
            }
            // At a large font the flame gives its room to the title: "Inbo / x" and a three-line "Done for today" at 1.5x
            // on the 320 dp screen (walk 59, run 41089220966).
            if (ui.streak > 0 && LocalDensity.current.fontScale < 1.3f) Streak(ui.streak)
            GlassIconButton(TabIcons.CalendarRange, "Tidy old saves", plain = true, onClick = onTidy)
            GlassIconButton(TabIcons.CircleHelp, "How sorting works", plain = true, onClick = onHelp)
            GoalRing(ui.sorted, ui.goal, Modifier.onGloballyPositioned { onRing(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) })
        }
        // The day's goal leads; the learned habit (5) follows (learn-client.tsx today-count). The counts get the whole
        // width and keep their words together, so "2/5 learned" never breaks after the number (checker #7).
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A bulk tidy-up on its way says how far it is in the same place, quietly (no spinner).
            Text(bulk?.let(::bulkLine) ?: countsLine(ui), style = MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = Modifier.weight(1f))
            // Always there, so the deck never moves when the first decision lands (RULES.md Layout 1).
            val press = remember { MutableInteractionSource() }
            Text(
                "Sorted today ($sortedCount)", style = MaterialTheme.typography.labelMedium, color = Glass.PrimaryInk, maxLines = 1,
                modifier = Modifier.heightIn(min = Space.touch).pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onSorted)
                    .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = Space.sm),
            )
        }
    }
}

/** The line over the title: "Today's 20", or "Done today ✓" after the goal; a short "Done ✓" at a large font so it stays one line. */
private fun headerNote(ui: InboxUi, big: Boolean) = if (ui.sorted < ui.goal) "Today's ${ui.goal}" else if (big) "Done ✓" else "Done today ✓"

/**
 * One text that shrinks until it fits its box, so a word is never cut with "…" or broken across lines. It lays out at
 * the style's size, then steps down 8 % at a time (down to [minSize]) while the layout still overflows.
 */
@Composable
private fun FitText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1, minSize: TextUnit = 10.sp) {
    var size by remember(text, style) { mutableStateOf(style.fontSize) }
    Text(
        text, style = style.copy(fontSize = size), color = color, maxLines = maxLines, softWrap = maxLines > 1, overflow = TextOverflow.Clip,
        onTextLayout = {
            val wide = maxLines == 1 && it.multiParagraph.intrinsics.maxIntrinsicWidth > it.size.width + 1f
            if ((it.hasVisualOverflow || wide) && size.value * 0.92f >= minSize.value) size = (size.value * 0.92f).sp
        },
        modifier = modifier,
    )
}

/** "Old saves to Someday · 120 of 412" (or "dropping"), and "waiting for pagel.uz" while it cannot reach it. */
fun bulkLine(b: Bulk): String {
    val nb = " "
    val what = if (b.choice == Choice.SOMEDAY) "Old saves to Someday" else "Dropping old saves"
    return "$what · ${b.done}${nb}of${nb}${b.total}" + if (b.stalled) " · waiting for pagel.uz" else ""
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
            .background(Color(0xFFB45309), CircleShape).padding(start = 7.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
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

/** Added to the shell's bottom padding (bar top − 8 dp), so the dock ends 12 dp above the tab bar. */
val DockGap = 4.dp

/** How long a still finger holds the card before Do now fires; the ring shows from [HOLD_SHOW_MS] (a tap never flashes it). */
const val HOLD_MS = 500L
private const val HOLD_SHOW_MS = 110L

/**
 * The cards and their gestures. The whole space between the header and the dock takes the swipe, not only the card
 * (Boss 2026-10-28: "I have to put my finger at the top of the screen to swipe"): a thumb anywhere in it moves the top
 * card, which follows with a slight tilt, stamps the swipe it will make (icon, word, colour), ticks once at the line,
 * and either leaves (past the line or flung) or springs back with the finger's speed. Up shuffles, down drops, left
 * learns, right makes an Action. A still finger on the card for [HOLD_MS] is Do now: a ring fills around it (a tick
 * when it shows, Confirm when it fires); moving or lifting first does nothing. The next card is the top one the moment
 * the swipe commits (the view model hides the leaving card at once); only the screen's first card rises in.
 */
@Composable
private fun Deck(ui: InboxUi, leaving: Leaving?, pictures: Map<String, Bitmap?>, actions: InboxActions, reduce: Boolean, peek: Offset, room: Dp, modifier: Modifier) {
    val top = ui.cards.firstOrNull()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val line = with(LocalDensity.current) { 110.dp.toPx() }
    var width by remember { mutableFloatStateOf(1000f) }
    val offset = remember(top?.file) { Animatable(peek, Offset.VectorConverter) }
    val enter = remember { Animatable(if (reduce || peek != Offset.Zero) 1f else 0f) }
    var holdAt by remember { mutableStateOf<Offset?>(null) }
    val holdP = remember { Animatable(0f) }
    val places = remember { arrayOfNulls<LayoutCoordinates>(2) }
    var releasedAt by remember { mutableLongStateOf(0L) }
    val home = spring<Offset>(dampingRatio = 0.62f, stiffness = 380f)
    val stamp by remember(offset) { derivedStateOf { Swipe.at(offset.value.x, offset.value.y, width, line) } }
    fun progress() = min(1f, hypot(offset.value.x, offset.value.y) / (width * 0.3f))

    val release by rememberUpdatedState { s: Swipe, at: Offset, v: Velocity ->
        val card = ui.cards.firstOrNull()
        val thrown = Throw(at.x, at.y, v.x, v.y)
        if (card == null) false else when (val c = s.choice(ui.full)) {
            null -> actions.shuffle(card, thrown)
            Choice.DROP -> { actions.choose(card, c, thrown); true }
            else -> { actions.openForm(card, c); false }
        }
    }
    val hold by rememberUpdatedState { ui.cards.firstOrNull()?.let { actions.choose(it, Choice.NOW, Throw.None) } }

    LaunchedEffect(Unit) { if (enter.value < 1f) enter.animateTo(1f, tween(320, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f))) }
    // Release to the next card on screen, measured on the frame that draws it (the walkthrough prints these lines).
    LaunchedEffect(top?.file) {
        if (releasedAt == 0L) return@LaunchedEffect
        withFrameNanos { }
        android.util.Log.i("PagelInbox", "next card in ${SystemClock.uptimeMillis() - releasedAt} ms")
        releasedAt = 0L
    }

    val gestures = if (top == null) Modifier else Modifier
        .pointerInput(top.file) {
            // The hold watches the same touches as the drag without taking them, on the card only.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val area = places[0]
                val card = places[1]?.takeIf { it.isAttached }
                if (area != null && card != null && !area.localBoundingBoxOf(card).contains(down.position)) return@awaitEachGesture
                val slop = viewConfiguration.touchSlop
                val timer = scope.launch {
                    delay(HOLD_SHOW_MS)
                    holdAt = down.position
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    holdP.snapTo(0f)
                    holdP.animateTo(1f, tween((HOLD_MS - HOLD_SHOW_MS).toInt(), easing = LinearEasing))
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    holdAt = null
                    holdP.snapTo(0f)
                    hold()
                }
                while (timer.isActive) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed || (c.position - down.position).getDistance() > slop) break
                }
                if (timer.isActive) {
                    timer.cancel()
                    holdAt = null
                    scope.launch { holdP.snapTo(0f) }
                }
            }
        }
        .pointerInput(top.file) {
            val tracker = VelocityTracker()
            var armed: Swipe? = null
            detectDragGestures(
                onDragStart = { tracker.resetTracking(); armed = null },
                onDragCancel = { scope.launch { offset.animateTo(Offset.Zero, home) } },
                onDragEnd = {
                    val v = tracker.calculateVelocity()
                    val o = offset.value
                    val s = Swipe.at(o.x, o.y, width, line) ?: Swipe.flung(v.x, v.y)
                    if (s != null) releasedAt = SystemClock.uptimeMillis()
                    if (s == null || !release(s, o, v)) {
                        releasedAt = 0L
                        scope.launch { offset.animateTo(Offset.Zero, home, initialVelocity = Offset(v.x, v.y)) }
                    }
                },
            ) { change, amount ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                val next = offset.value + amount
                scope.launch { offset.snapTo(next) }
                val s = Swipe.at(next.x, next.y, width, line)
                if (s != armed) { if (s != null) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate); armed = s }
            }
        }

    Box(modifier.onPlaced { places[0] = it }.then(gestures), contentAlignment = Alignment.Center) {
        if (top != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (ui.cards.size > 1) {
                    // The next card peeks out under the top one: a stack, so Boss sees there is more. It shows where the
                    // next save is from and its claim, faded, and grows into place as the top card is dragged away.
                    val next = ui.cards[1]
                    Pane(Modifier.matchParentSize().graphicsLayer {
                        val g = progress()
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
                    Box(
                        Modifier.onPlaced { places[1] = it }.onSizeChanged { width = it.width.toFloat() }.graphicsLayer {
                            translationX = offset.value.x
                            translationY = offset.value.y + (1f - enter.value) * 18.dp.toPx()
                            rotationZ = offset.value.x / width * 9f
                            // The card gives a little under a held finger (a press, not a lift).
                            val s = (0.97f + 0.03f * enter.value) * (1f - 0.025f * holdP.value)
                            scaleX = s; scaleY = s
                            alpha = enter.value
                        },
                    ) {
                        CardBody(top, pictures, actions, room)
                        stamp?.let {
                            Stamp(it, ui.full, Modifier.align(when (it) {
                                Swipe.ACTION -> Alignment.TopStart
                                Swipe.LEARN -> Alignment.TopEnd
                                Swipe.SHUFFLE -> Alignment.BottomCenter
                                Swipe.DROP -> Alignment.TopCenter
                            }))
                        }
                    }
                }
            }
        }
        leaving?.let { l -> key(l.id) { Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { LeavingCard(l, pictures, reduce, room) { actions.leaveDone(l.id) } } } }
        holdAt?.let { at -> HoldRing(at) { holdP.value } }
    }
}

/** Do now's ring around a held finger: it fills in [HOLD_MS], with Do now's mark above the finger so the thumb never hides it. */
@Composable
private fun BoxScope.HoldRing(at: Offset, progress: () -> Float) {
    val color = Choice.NOW.look.color
    Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
        val r = 34.dp.toPx()
        val w = 4.dp.toPx()
        drawCircle(color.copy(alpha = 0.12f), r, at)
        drawCircle(Color.White.copy(alpha = 0.85f), r, at, style = Stroke(w))
        drawArc(color, -90f, 360f * progress(), false, topLeft = at - Offset(r, r), size = Size(2 * r, 2 * r), style = Stroke(w, cap = StrokeCap.Round))
    }
    Box(
        Modifier.align(Alignment.TopStart).offset { IntOffset((at.x - 15.dp.toPx()).toInt(), (at.y - 74.dp.toPx()).toInt()) }
            .size(30.dp).background(color, CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) { Icon(TabIcons.Zap, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
}

/** The swipe a drag will make, stamped on the card (a dating app's LIKE/NOPE, in PAGEL's words): icon, word, colour. */
@Composable
private fun Stamp(swipe: Swipe, full: Boolean, modifier: Modifier) {
    val (icon, label, color) = swipe.look(full)
    Row(
        modifier.padding(18.dp).graphicsLayer { rotationZ = when (swipe) { Swipe.LEARN -> 10f; Swipe.ACTION -> -10f; else -> 0f } }
            .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(12.dp)).border(2.5.dp, color, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp), color = color)
    }
}

/**
 * A card leaving with its choice's motion (globals.css "Inbox game feel") in 200 ms: Action flies up-right into the
 * plan, Note folds small like a sticky note, Learn turns over like a page, Do now zaps and pops, Later slides to the
 * back, Drop tips and falls. A swiped card keeps flying the way it was thrown. Not drawn at all with reduced motion.
 */
@Composable
private fun LeavingCard(l: Leaving, pictures: Map<String, Bitmap?>, reduce: Boolean, room: Dp, onEnd: () -> Unit) {
    if (reduce) { LaunchedEffect(l.id) { onEnd() }; return }
    if (l.thrown.swiped) { ThrownCard(l, pictures, room, onEnd); return }
    val p = remember { Animatable(0f) }
    LaunchedEffect(l.id) { p.animateTo(1f, tween(200, easing = CubicBezierEasing(0.2f, 0.6f, 0.35f, 1f))); onEnd() }
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
                Choice.SOMEDAY -> { translationY = -0.35f * h * t; scaleX = 1f - 0.2f * t; scaleY = scaleX }
                Choice.WAITING -> { translationY = 0.4f * h * t; scaleX = 1f - 0.08f * t; scaleY = scaleX }
                Choice.PROJECT, Choice.CALENDAR -> { translationX = 0.6f * w * t; translationY = -0.4f * h * t; scaleX = 1f - 0.6f * t; scaleY = scaleX; rotationZ = 6f * t }
            }
        },
    ) { CardBody(l.card, pictures, InboxActions(), room) }
}

/**
 * A swiped card leaving: it starts where the finger let go and keeps the finger's speed and direction (apple-design
 * velocity handoff) on a stiff critically damped spring: gone in ~200 ms (Boss 2026-10-28: "slow to show the next one").
 */
@Composable
private fun ThrownCard(l: Leaving, pictures: Map<String, Bitmap?>, room: Dp, onEnd: () -> Unit) {
    val t = l.thrown
    val start = Offset(t.x, t.y)
    val pos = remember { Animatable(start, Offset.VectorConverter) }
    val far = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() * 1.2f }
    LaunchedEffect(l.id) {
        val speed = hypot(t.vx, t.vy)
        val len = hypot(t.x, t.y).coerceAtLeast(1f)
        val dir = if (speed > 300f) Offset(t.vx / speed, t.vy / speed) else Offset(t.x / len, t.y / len)
        withTimeoutOrNull(220) { pos.animateTo(start + dir * far, spring(dampingRatio = 1f, stiffness = 1_500f), initialVelocity = Offset(t.vx, t.vy)) }
        onEnd()
    }
    Box(
        Modifier.clearAndSetSemantics {}.graphicsLayer {
            val gone = ((pos.value - start).getDistance() / (far * 0.6f)).coerceIn(0f, 1f)
            translationX = pos.value.x
            translationY = pos.value.y
            rotationZ = pos.value.x / size.width.coerceAtLeast(1f) * 9f
            alpha = 1f - gone
        },
    ) { CardBody(l.card, pictures, InboxActions(), room) }
}

/**
 * One save: where it is from, the AI's one-line claim, why it matters, the picture, the post, its link. The card is at
 * most [room] tall (the space between the header and the dock); the picture takes what the words leave, up to 220 dp,
 * and hides below 56 dp rather than show a sliver. The caption leaves out the claim it starts with (LinkedIn's does).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardBody(card: InboxCard, pictures: Map<String, Bitmap?>, actions: InboxActions, room: Dp) {
    val uri = LocalUriHandler.current
    val scale = LocalDensity.current.fontScale
    val caption = remember(card.file, card.claim, card.text) { captionWithoutClaim(card.claim, card.text) }
    val showPicture = card.hasPicture && (card.file !in pictures || pictures[card.file] != null)
    val captionLines = when { scale >= 1.3f || room < 380.dp -> 1; showPicture -> 2; else -> 3 }
    // A short phone (the 320x640 test screen): the claim and the links first; the caption waits behind "Read all".
    val tight = room < 300.dp
    var cut by remember(card.file) { mutableStateOf(false) }
    Pane(Modifier.fillMaxWidth().heightIn(max = room)) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
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
            // Takes what the rest of the card leaves, shrinking by words until it all fits: the pane clips what overflows it, and a
            // clipped claim ("Quizzes beat rereading:" and no more, walk 78) is a cut text.
            FitText(card.claim, MaterialTheme.typography.headlineSmall, Glass.Ink, maxLines = 8, minSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
            card.why?.let {
                Spacer(Modifier.height(4.dp))
                FitText(it, MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Medium), Glass.Primary, maxLines = if (tight) 2 else 3, minSize = 11.sp)
            }
            if (showPicture) {
                val bmp = pictures[card.file]
                val slot = Modifier.weight(1f, fill = false).pictureSlot()
                if (bmp == null) PictureSkeleton(slot)
                else {
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(img, contentDescription = "The post's picture", contentScale = ContentScale.Crop, modifier = slot.clip(RoundedCornerShape(16.dp)))
                }
            }
            if (caption.isNotBlank() && !tight) {
                Spacer(Modifier.height(12.dp))
                Text(
                    caption, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF3B4256), maxLines = captionLines, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { cut = it.hasVisualOverflow },
                    modifier = Modifier.drawBehind { drawLine(Glass.Ink.copy(alpha = 0.10f), Offset(0f, 0f), Offset(0f, size.height), 3.dp.toPx()) }.padding(start = 12.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Whenever the caption is cut, there is a way to read it all (checker #6: "..." with no link).
                if (cut || (tight && caption.isNotBlank())) LinkWord("Read all", null) { actions.readAll(card) }
                Spacer(Modifier.weight(1f))
                card.link?.let { l -> LinkWord("Open the post", TabIcons.ArrowUpRight) { runCatching { uri.openUri(l) } } }
            }
        }
    }
}

/**
 * The picture's place: 12 dp under the words, as tall as the card has room for up to 220 dp, nothing below 56 dp (a
 * strip of picture says nothing and pushes the words).
 */
private fun Modifier.pictureSlot(): Modifier = layout { m, c ->
    val gap = 12.dp.roundToPx()
    val h = minOf(c.maxHeight, 220.dp.roundToPx() + gap)
    if (c.maxHeight != Constraints.Infinity && h < 56.dp.roundToPx() + gap) {
        layout(0, 0) {}
    } else {
        val p = m.measure(Constraints.fixed(c.maxWidth, h - gap))
        layout(p.width, h) { p.place(0, gap) }
    }
}

@Composable
private fun PictureSkeleton(modifier: Modifier) {
    val a by androidx.compose.animation.core.rememberInfiniteTransition(label = "pic").animateFloat(
        0.45f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "breathe",
    )
    Box(modifier.graphicsLayer { alpha = a }.background(Glass.Ink.copy(alpha = 0.07f), RoundedCornerShape(16.dp)).clearAndSetSemantics {})
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
            Site.X -> Icon(TabIcons.XLogo, contentDescription = "X", tint = Color.White, modifier = Modifier.size(12.dp))
            Site.LINKEDIN -> Text("in", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFF0A66C2),
                modifier = Modifier.background(Color.White, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
            else -> Icon(TabIcons.Globe, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        // X's logo is its name: "X X" said it twice.
        if (site != Site.X) {
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── The choices under the card ──────────────────────────────────────────────────────────────────────────────────────

/**
 * The dock, pinned just above the tab bar (Boss 2026-10-28, the thumb zone): the AI's pick as the big button, the
 * GTD question, its two rows of choices, and under them the Later link. One thumb reaches all of it without moving the
 * hand. A long-press on a choice repeats it with last time's settings (checker 2026-10-30 #7); one line says so until
 * Boss has used it once.
 */
@Composable
private fun Dock(card: InboxCard, ui: InboxUi, actions: InboxActions) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(DOCK_PREFS, android.content.Context.MODE_PRIVATE) }
    var tipUsed by remember { mutableStateOf(runCatching { prefs.getBoolean(HOLD_TIP_USED, false) }.getOrDefault(false)) }
    val repeat = { c: Choice ->
        if (!tipUsed) { tipUsed = true; runCatching { prefs.edit().putBoolean(HOLD_TIP_USED, true).apply() } }
        actions.repeat(card, c)
    }
    // The Later row under the grid is paid for by the grid itself (no top gap, no padding round each choice), so the
    // card keeps the room its picture needs at 360x780.
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val guess = card.guess
        if (guess != null) GuessButton(card, guess, ui) { actions.choose(card, guess, Throw.None) }
        else AiReading(ui.reading)
        Text(
            "Can I do something about it?", style = MaterialTheme.typography.labelMedium, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = Space.xs).semantics { heading() },
        )
        // Two rows of icons, each with its word under it (checker #5: as chips, three of six sat under the tab bar).
        // slot: always in the same places; the AI's pick is marked where it stands (spacing audit #2: the icons moved
        // from card to card when the pick was taken out of the row). The No/Yes column is as wide as its words need
        // (checker 2026-10-30 #3: a fixed 28 dp cut "Yes" at 1.5x), and every word in the grid has one size: the largest
        // that lets the longest fit its slot, so Someday and Calendar never shrink alone.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val (answerWidth, labelSize) = dockSizes(maxWidth)
            Column(Modifier.fillMaxWidth()) {
                for ((answer, group) in listOf("No" to NO_GROUP, "Yes" to YES_GROUP)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(answer, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, softWrap = false,
                            modifier = Modifier.widthIn(min = answerWidth).semantics { contentDescription = "If $answer" })
                        for (slot in 0 until DOCK_SLOTS) {
                            val c = group.getOrNull(slot)
                            if (c == null) Spacer(Modifier.weight(1f))
                            else ChoiceIcon(c, enabled = !(c == Choice.KEEP && ui.full), picked = c == guess, labelSize = labelSize, modifier = Modifier.weight(1f),
                                onLongClick = { repeat(c) }) { actions.choose(card, c, Throw.None) }
                        }
                    }
                }
            }
        }
        // Later, the put-off once: a small text link under the rows (checker 2026-10-30 #9), with the long-press tip
        // beside it until Boss has used it.
        Row(Modifier.fillMaxWidth().heightIn(min = DOCK_LINK_ROW), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (tipUsed) "" else "Hold = repeat", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 2,
                modifier = Modifier.weight(1f).padding(start = Space.xs),
            )
            val press = remember { MutableInteractionSource() }
            val laterOff = card.putOff
            Row(
                Modifier.heightIn(min = DOCK_LINK_ROW).pressScale(press).graphicsLayer { alpha = if (laterOff) 0.4f else 1f }
                    .clickable(press, indication = null, enabled = !laterOff, role = Role.Button) { actions.choose(card, Choice.LATER, Throw.None) }
                    .padding(horizontal = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TabIcons.Clock, contentDescription = null, tint = Glass.Muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Space.xs))
                Text(
                    Choice.LATER.look.label, style = MaterialTheme.typography.labelSmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                    color = Glass.Muted, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

/** The dock's grid: five slots a row, No's fifth left empty. */
private const val DOCK_SLOTS = 5
/**
 * The Later link's row: one small line. Compose widens a small target's touch area to 48 dp where nothing else is under
 * the finger (the dock's bottom edge), so it stays a fair thumb target without taking the card's room.
 */
private val DOCK_LINK_ROW = 20.dp
private const val DOCK_PREFS = "pagel-inbox"
private const val HOLD_TIP_USED = "hold_tip_used"

/**
 * The No/Yes column's width (the wider word, measured) and the one word size for the whole grid: the largest step from
 * 12.5 sp (growing to 1.2x with the font) down to 8 sp at which every choice's word fits its slot.
 */
@Composable
private fun dockSizes(width: Dp): Pair<Dp, androidx.compose.ui.unit.TextUnit> {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val small = MaterialTheme.typography.labelSmall
    return remember(width, density) {
        val scale = density.fontScale
        val answerPx = listOf("No", "Yes").maxOf { measurer.measure(it, small, maxLines = 1, softWrap = false).size.width }
        val answer = with(density) { maxOf(Space.xxl, answerPx.toDp() + Space.xs) }
        val slotPx = with(density) { ((width - answer) / DOCK_SLOTS).toPx() } - with(density) { Space.xs.toPx() }
        val words = (NO_GROUP + YES_GROUP).map { it.look.label }
        val max = 12.5f * minOf(scale, 1.2f) / scale
        val min = 8f / scale
        var size = max
        while (size > min && words.any { measurer.measure(it, small.copy(fontSize = size.sp), maxLines = 1, softWrap = false).size.width > slotPx }) size -= 0.25f / scale
        answer to size.coerceAtLeast(min).sp
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
    // One row tall (64 dp, Boss 2026-10-28: the pill read as a slab); it grows only when 1.3x+ text needs two lines.
    Row(
        Modifier.fillMaxWidth().then(if (LocalDensity.current.fontScale <= 1.2f) Modifier.height(64.dp) else Modifier.heightIn(min = 64.dp)).pressScale(press)
            .background(Brush.linearGradient(listOf(look.color, lerp(look.color, Color.Black, 0.18f))), shape)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)), shape)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "AI pick: ${look.label}" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            FitText(sub, MaterialTheme.typography.labelSmall, Color.White, maxLines = 2, minSize = 9.sp)
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

/**
 * One of the other choices: a white glass disc with the choice's icon in its colour and its word under it, at the
 * grid's one word size (never cut); the whole column is one target of at least 48 dp. A long-press repeats it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChoiceIcon(
    choice: Choice, enabled: Boolean, modifier: Modifier, picked: Boolean = false, labelSize: androidx.compose.ui.unit.TextUnit = 12.5.sp,
    onLongClick: () -> Unit = {}, onClick: () -> Unit,
) {
    val look = choice.look
    val press = remember { MutableInteractionSource() }
    Column(
        // combinedClickable gives the long-press its own haptic.
        modifier.heightIn(min = TouchMin).pressScale(press).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .combinedClickable(press, indication = null, enabled = enabled, role = Role.Button, onLongClickLabel = "Repeat ${look.label} as last time",
                onLongClick = onLongClick, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 30 dp discs (were 32): with no padding round each choice, the grid pays for the Later line under it.
        Box(
            Modifier.size(30.dp).background(if (picked) look.color.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.72f), CircleShape)
                .border(if (picked) 2.dp else 1.dp, if (picked) androidx.compose.ui.graphics.SolidColor(look.color) else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Glass.Aurora1.copy(alpha = 0.45f))), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(look.icon, contentDescription = null, tint = look.color, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.height(2.dp))
        androidx.compose.foundation.text.BasicText(
            look.label,
            style = MaterialTheme.typography.labelSmall.copy(color = Glass.Ink.copy(alpha = 0.86f), fontSize = labelSize, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            maxLines = 1,
            softWrap = false,
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
        // slot: the same 64 dp as the AI's pick (audit #2: 48 vs 64 moved the deck 16 dp).
        Modifier.fillMaxWidth().then(if (LocalDensity.current.fontScale <= 1.2f) Modifier.height(64.dp) else Modifier.heightIn(min = 64.dp))
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
