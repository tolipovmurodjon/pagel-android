package org.pagel.ai.ui.habits

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.db.HabitFreezeEntity
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.HabitLook
import org.pagel.ai.data.habits.habitBadges
import org.pagel.ai.data.habits.Mark
import org.pagel.ai.data.habits.chainBest
import org.pagel.ai.data.habits.chainStreak
import org.pagel.ai.data.habits.completion
import org.pagel.ai.data.habits.isNamoz
import org.pagel.ai.data.habits.minutesOf
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.monthMarks
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.HabitIcons
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.task.LocalTaskOpener
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The numbers a detail page and History show: one habit's or all habits' (the reference's three stat cards). */
data class HabitStats(val streak: Int, val best: Int, val finished: Int, val thisWeek: Int, val rateDone: Int, val rateOf: Int) {
    val rate get() = if (rateOf == 0) 0 else rateDone * 100 / rateOf
}

fun statsOf(habits: List<TaskEntity>, ticks: List<HabitTickEntity>, freezes: List<HabitFreezeEntity>, today: LocalDate): HabitStats {
    val byId = habits.associateBy { it.id }
    val doneOf = ticks.filter { it.n >= (byId[it.taskId]?.times ?: 1).coerceAtLeast(1) && it.taskId in byId }.groupBy({ it.taskId }, { it.day }).mapValues { it.value.toSet() }
    val frozen = freezes.groupBy({ it.taskId }, { it.day }).mapValues { it.value.toSet() }
    val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    var done = 0
    var of = 0
    for (h in habits) { val (n, o) = completion(h, doneOf[h.id].orEmpty(), frozen[h.id].orEmpty(), today.minusDays(29), today); done += n; of += o }
    return HabitStats(
        streak = habits.maxOfOrNull { chainStreak(it, doneOf[it.id].orEmpty(), frozen[it.id].orEmpty(), today) } ?: 0,
        best = habits.maxOfOrNull { chainBest(it, doneOf[it.id].orEmpty(), frozen[it.id].orEmpty(), today) } ?: 0,
        finished = doneOf.values.sumOf { it.size },
        thisWeek = doneOf.values.sumOf { days -> days.count { it >= monday.toString() && it <= today.toString() } },
        rateDone = done, rateOf = of,
    )
}

/**
 * Three gradient stat cards side by side that snap, one at a time, as the sideways row stops (Boss's reference: Current
 * streak + Best, Habit finished + This week, Complete rate + n/n). Each card is a 2-stop OKLCH gradient in a tile
 * colour (never red), the number in the number face.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StatCards(s: HabitStats) {
    val state = rememberLazyListState()
    LazyRow(
        state = state, flingBehavior = rememberSnapFlingBehavior(state), contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        item(key = "streak") { StatCard("blue", "Current streak", "${s.streak}", "Best ${s.best}") }
        item(key = "finished") { StatCard("violet", "Habit finished", "${s.finished}", "This week ${s.thisWeek}") }
        item(key = "rate") { StatCard("amber", "Complete rate", "${s.rate}%", "${s.rateDone}/${s.rateOf} in 30 days") }
    }
}

@Composable
private fun StatCard(colour: String, label: String, value: String, sub: String) {
    val look = cardColours(colour, 1f)
    val shape = RoundedCornerShape(Space.xxl)
    Column(
        Modifier.width(176.dp).heightIn(min = 148.dp).clip(shape)
            .background(Brush.linearGradient(listOf(look.a, look.b), start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset.Infinite))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), endY = 160f))
            .semantics(mergeDescendants = true) { contentDescription = "$label $value, $sub" }
            .padding(Space.lg),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = look.content.copy(alpha = 0.9f), minLines = 2, maxLines = 2)
        Spacer(Modifier.height(Space.sm))
        Text(value, style = Glass.NumberText.copy(fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold), color = look.content, maxLines = 1, softWrap = false)
        Spacer(Modifier.height(Space.xs))
        Text(sub, style = MaterialTheme.typography.labelMedium, color = look.content.copy(alpha = 0.85f), maxLines = 2)
    }
}

/** What the month shows: its title, its days with marks and each day's (done, due) count. */
private data class MonthShown(val title: String, val days: List<Pair<LocalDate, Mark>>, val counts: List<Pair<Int, Int>>?)

/**
 * A month as a chain calendar, Monday first. Swipe it sideways or use the arrows: the new month slides in from the side
 * it comes from on an ease-out curve (a sideways drag moves months only by 70 dp or a flick, never by a tap's wobble).
 * With [onSelect] a tap on a day that can hold a tick logs it, or takes it back (Boss 2026-10-31: a missed day can be
 * logged and marked); the chain re-joins by itself.
 */
@Composable
internal fun MonthChain(
    title: String, days: List<Pair<LocalDate, Mark>>, today: LocalDate, onPrev: () -> Unit, onNext: (() -> Unit)?, counts: List<Pair<Int, Int>>? = null,
    onSelect: ((LocalDate) -> Unit)? = null,
) {
    var dir by remember { mutableIntStateOf(1) }
    val reduce = org.pagel.ai.ui.today.reducedMotion(LocalContext.current)
    val haptics = LocalHapticFeedback.current
    val prev by rememberUpdatedState(onPrev)
    val next by rememberUpdatedState(onNext)
    fun back() { dir = -1; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); prev() }
    fun forward() { if (next != null) { dir = 1; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); next?.invoke() } }
    Pane(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter).testTag("month-chain").pointerInput(Unit) {
            var dx = 0f
            detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx > 70f) back() else if (dx < -70f) forward() }) { change, d -> change.consume(); dx += d }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.md, horizontal = Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(TabIcons.ChevronLeft, "Month before", size = Space.touch, plain = true, onClick = ::back)
                Text(title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.weight(1f).semantics { heading() },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (onNext != null) GlassIconButton(TabIcons.ChevronRight, "Month after", size = Space.touch, plain = true, onClick = ::forward) else Spacer(Modifier.width(Space.touch))
            }
            Row(Modifier.fillMaxWidth()) {
                val big = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
                for (d in DayOfWeek.entries) Text(d.getDisplayName(if (big) TextStyle.NARROW else TextStyle.SHORT, Locale.UK).uppercase(), style = MaterialTheme.typography.labelSmall, color = Glass.Muted,
                    maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.xs))
            AnimatedContent(
                targetState = MonthShown(title, days, counts), contentKey = { it.title },
                transitionSpec = {
                    if (reduce) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    else (fadeIn(tween(200, easing = EASE_OUT)) + slideInHorizontally(tween(240, easing = EASE_OUT)) { dir * it / 4 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(200, easing = EASE_OUT)) { -dir * it / 4 })
                },
                label = "month",
            ) { shown ->
                val marks = shown.days.toMap()
                val tappable: ((LocalDate) -> Unit)? = onSelect?.let { pick ->
                    { d -> if (marks[d].let { it != null && it != Mark.OFF && it != Mark.BEFORE && it != Mark.FUTURE }) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); pick(d) } }
                }
                Column {
                    // Week rows; the first starts at its weekday, so the chain runs Monday to Sunday on each row.
                    val lead = shown.days.firstOrNull()?.first?.dayOfWeek?.value?.minus(1) ?: 0
                    var at = 0
                    var first = true
                    while (at < shown.days.size) {
                        val slots = if (first) 7 - lead else 7
                        val row = shown.days.subList(at, minOf(shown.days.size, at + slots))
                        ChainRow(row, today, null, tappable, circle = 34.dp, slots = 7, startSlot = if (first) lead else 0, counts = shown.counts?.subList(at, at + row.size))
                        at += slots
                        first = false
                    }
                }
            }
        }
    }
}

/** Boss's ease-out for a thing arriving (emil-design-eng): fast start, soft landing. */
private val EASE_OUT = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

@Composable
internal fun Chip(
    text: String, on: Boolean = false, modifier: Modifier = Modifier, icon: ImageVector? = null, iconTint: Color = Glass.Muted,
    description: String? = null, onClick: (() -> Unit)? = null,
) {
    val press = remember { MutableInteractionSource() }
    // One pill height for every chip, tappable or not (checker 3 #10: the bell chip stood taller than its row): the pill
    // is 36 dp, and the 48 dp touch target is layout space around it, the same for every chip in a row.
    Row(
        modifier.minimumInteractiveComponentSize().heightIn(min = 36.dp).clip(CircleShape)
            .background(if (on) Glass.PrimarySoft else Color.White.copy(alpha = 0.7f))
            .border(1.dp, if (on) Glass.Primary else Glass.Ink.copy(alpha = 0.08f), CircleShape)
            .then(
                when {
                    onClick != null -> Modifier.pressScale(press).clickable(press, indication = null, role = Role.RadioButton, onClick = onClick)
                        .semantics { selected = on; if (description != null) contentDescription = description }
                    description != null -> Modifier.semantics(mergeDescendants = true) { contentDescription = description }
                    else -> Modifier
                },
            )
            .padding(horizontal = Space.md, vertical = Space.xs),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Space.xs))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = if (on) Glass.PrimaryInk else Glass.Ink, maxLines = 1)
    }
}

// ── History ─────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** History (Boss's reference): Calendar · All habits · Achievements, with the three stat cards for all habits. */
@Composable
fun HabitHistoryRoute(contentPadding: PaddingValues, onBack: () -> Unit, onOpenHabit: (String) -> Unit, vm: HabitsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    HabitHistory(ui, contentPadding, onBack, onOpenHabit)
}

@Composable
fun HabitHistory(ui: HabitsUi, contentPadding: PaddingValues, onBack: () -> Unit = {}, onOpenHabit: (String) -> Unit = {}, startTab: Int = 0) {
    val today = runCatching { LocalDate.parse(ui.today) }.getOrElse { LocalDate.now() }
    var tab by remember { mutableStateOf(startTab) }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var namoz by remember { mutableStateOf(false) }
    val all = ui.byId.values.toList()
    val (prayers, habits) = all.partition(::isNamoz)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TabIcons.ArrowLeft, "Back", size = Space.touch, plain = true, onClick = onBack)
            Text("History", style = MaterialTheme.typography.headlineMedium, color = Glass.Ink, modifier = Modifier.padding(start = Space.xs).semantics { heading() })
        }
        HistoryTabs(listOf("Calendar", "All habits", "Achievements"), tab) { tab = it }
        if (tab != 2) StatCards(statsOf(habits, ui.ticks, ui.freezes, today))
        when (tab) {
            0 -> {
                if (prayers.isNotEmpty()) Row(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Chip("Habits", on = !namoz) { namoz = false }
                    Chip("Namoz", on = namoz) { namoz = true }
                }
                val marks = monthMarks(if (namoz) prayers else habits, ui.ticks, ui.freezes, month, today, null).map { (d, m) ->
                    // Namoz counts from 1 November: earlier days are blank, never missed.
                    d to if (namoz && !Namoz.counts(d.toString())) Mark.BEFORE else m
                }
                val taps = ui.ticks.associate { "${it.day} ${it.taskId}" to it.n }
                val frozen = ui.freezes.groupBy({ it.taskId }, { it.day }).mapValues { it.value.toSet() }
                MonthChain(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)), marks, today, onPrev = { month = month.minusMonths(1) },
                    onNext = if (month < YearMonth.from(today)) ({ month = month.plusMonths(1) }) else null,
                    counts = (if (namoz) prayers else habits).let { list ->
                        val starts = org.pagel.ai.data.habits.startsOf(list, ui.ticks, today)
                        marks.map { (d, _) -> org.pagel.ai.data.habits.heldOf(list, taps, frozen, d, starts) }
                    })
            }
            1 -> Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
                Column(Modifier.padding(vertical = Space.sm)) {
                    for (h in habits + prayers) {
                        val s = statsOf(listOf(h), ui.ticks, ui.freezes, today)
                        val press = remember(h.id) { MutableInteractionSource() }
                        val title = titleOf(h)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press).clickable(press, indication = null, role = Role.Button) { onOpenHabit(h.id) }
                                .padding(horizontal = Space.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EmojiTile(Namoz.prayerOf(title)?.emoji ?: HabitIcons.keyFor(title, ui.prefs[h.id]?.emoji), 36.dp, colour = HabitLook.colourOf(ui.prefs[h.id]?.emoji))
                            Spacer(Modifier.width(Space.md))
                            Text(title, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(TabIcons.Flame, null, tint = Glass.Muted, modifier = Modifier.size(14.dp))
                            Text("${s.streak}  ${s.rate}%", style = Glass.NumberText, color = Glass.Muted, modifier = Modifier.padding(start = Space.xs))
                        }
                    }
                }
            }
            else -> AchievementsPage(habitBadges(all, ui.ticks, ui.freezes, today))
        }
    }
}

/** Plain words with a mint underline (Boss's reference): they scroll sideways when a large font needs the room. */
@Composable
private fun HistoryTabs(names: List<String>, at: Int, onPick: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
        for ((i, name) in names.withIndex()) {
            val on = i == at
            val press = remember { MutableInteractionSource() }
            Column(
                Modifier.heightIn(min = Space.touch).pressScale(press)
                    .selectable(on, press, indication = null, role = Role.Tab) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPick(i) }
                    .semantics { contentDescription = name },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium), color = if (on) Glass.Ink else Glass.Muted, maxLines = 1, softWrap = false)
                Spacer(Modifier.height(Space.xs))
                Box(Modifier.size(width = 22.dp, height = 3.dp).background(if (on) Glass.Primary else Color.Transparent, CircleShape))
            }
        }
    }
}
