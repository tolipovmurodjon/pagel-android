package org.pagel.ai.ui.habits

import android.content.Intent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import org.pagel.ai.data.habits.Achievement
import org.pagel.ai.data.habits.Mark
import org.pagel.ai.data.habits.achievements
import org.pagel.ai.data.habits.chainBest
import org.pagel.ai.data.habits.chainStreak
import org.pagel.ai.data.habits.completion
import org.pagel.ai.data.habits.isNamoz
import org.pagel.ai.data.habits.minutesOf
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.monthMarks
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.Glass
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

/** Three glass stat cards side by side, scrolling sideways at 1.5x (the number in the number face). */
@Composable
internal fun StatCards(s: HabitStats) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        StatCard("fire", "Streak", "${s.streak}", "Best ${s.best}")
        StatCard("check", "Finished", "${s.finished}", "This week ${s.thisWeek}")
        StatCard("target", "Completion", "${s.rate}%", "${s.rateDone} of ${s.rateOf} · 30 days")
    }
}

@Composable
private fun StatCard(emoji: String, label: String, value: String, sub: String) {
    Pane(Modifier.width(156.dp).semantics(mergeDescendants = true) { contentDescription = "$label $value, $sub" }) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiTile(emoji, 28.dp)
                Spacer(Modifier.width(Space.sm))
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(Space.md))
            Text(value, style = Glass.NumberText.copy(fontSize = 34.sp, lineHeight = 38.sp), color = Glass.Ink, maxLines = 1)
            Spacer(Modifier.height(Space.xs))
            Text(sub, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A month as a chain calendar, Monday first, with ‹ › to step months. */
@Composable
internal fun MonthChain(
    title: String, days: List<Pair<LocalDate, Mark>>, today: LocalDate, onPrev: () -> Unit, onNext: (() -> Unit)?, counts: List<Pair<Int, Int>>? = null,
) {
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.md, horizontal = Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(TabIcons.ChevronLeft, "Month before", size = Space.touch, plain = true, onClick = onPrev)
                Text(title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.weight(1f).semantics { heading() },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (onNext != null) GlassIconButton(TabIcons.ChevronRight, "Month after", size = Space.touch, plain = true, onClick = onNext) else Spacer(Modifier.width(Space.touch))
            }
            Row(Modifier.fillMaxWidth()) {
                for (d in DayOfWeek.entries) Text(d.getDisplayName(TextStyle.SHORT, Locale.UK).uppercase(), style = MaterialTheme.typography.labelSmall, color = Glass.Muted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.xs))
            // Week rows; the first starts at its weekday, so the chain runs Monday to Sunday on each row.
            val lead = days.firstOrNull()?.first?.dayOfWeek?.value?.minus(1) ?: 0
            var at = 0
            var first = true
            while (at < days.size) {
                val slots = if (first) 7 - lead else 7
                val row = days.subList(at, minOf(days.size, at + slots))
                ChainRow(row, today, null, null, circle = 34.dp, slots = 7, startSlot = if (first) lead else 0, counts = counts?.subList(at, at + row.size))
                at += slots
                first = false
            }
        }
    }
}

/**
 * One habit (round D; Boss's reference, in our own look): back, edit, share; the big title; chips for its part,
 * repeat, time and length; three stat cards; the month's chain; the last weeks as rows; his identity line; the emoji
 * he picks; the habit it follows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitDetailRoute(id: String, contentPadding: PaddingValues, onBack: () -> Unit, vm: HabitsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val t = ui.byId[id]
    val ctx = LocalContext.current
    val openTask = LocalTaskOpener.current
    var bell by remember { mutableStateOf(false) }
    val grants = rememberReminderGrants(vm)
    if (t == null) { Box(Modifier.fillMaxSize().padding(contentPadding)); return }
    HabitDetail(
        t, ui, contentPadding, onBack = onBack, onEdit = { openTask(id) },
        onShare = {
            val s = statsOf(listOf(t), ui.ticks, ui.freezes, LocalDate.parse(ui.today))
            val text = "${titleOf(t)}: ${s.streak} in a row (best ${s.best}), ${s.rate}% of the last 30 days."
            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }
        },
        onEmoji = { vm.setEmoji(id, it) }, onAfter = { vm.setAfter(id, it) }, onIdentity = { vm.setIdentity(id, it) },
        onReminder = { bell = true }, onTick = vm::tick, onUntick = vm::untick, onTimer = vm::toggleTimer, onResetTimer = vm::resetTimer,
        onFreeze = vm::freeze, onUnfreeze = vm::unfreeze,
    )
    if (bell) ReminderSheet(
        t, on = id in ui.reminders, exact = grants.exact, notify = grants.notify, onToggle = { grants.remind(id, it) }, onTime = { vm.setTime(id, it) },
        onFixNotify = grants.fixNotify, onFixExact = grants.fixExact,
    ) { bell = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitDetail(
    t: TaskEntity, ui: HabitsUi, contentPadding: PaddingValues, onBack: () -> Unit = {}, onEdit: () -> Unit = {}, onShare: () -> Unit = {},
    onEmoji: (String?) -> Unit = {}, onAfter: (String?) -> Unit = {}, onIdentity: (String?) -> Unit = {},
    onReminder: () -> Unit = {}, onTick: (String) -> Unit = {}, onUntick: (String) -> Unit = {}, onTimer: (String, Int) -> Unit = { _, _ -> },
    onResetTimer: (String) -> Unit = {}, onFreeze: (String) -> Unit = {}, onUnfreeze: (String) -> Unit = {},
) {
    val today = runCatching { LocalDate.parse(ui.today) }.getOrElse { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var identityOpen by remember { mutableStateOf(false) }
    val pref = ui.prefs[t.id]
    val title = titleOf(t)
    val emoji = Namoz.prayerOf(title)?.emoji ?: Emoji.keyFor(title, pref?.emoji)
    val stats = statsOf(listOf(t), ui.ticks, ui.freezes, today)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TabIcons.ArrowLeft, "Back", size = Space.touch, plain = true, onClick = onBack)
            Spacer(Modifier.weight(1f))
            GlassIconButton(TabIcons.StickyNote, "Edit", size = Space.touch, plain = true, onClick = onEdit)
            GlassIconButton(TabIcons.ArrowUpRight, "Share", size = Space.touch, plain = true, onClick = onShare)
        }
        Row(Modifier.padding(horizontal = Space.textEdge), verticalAlignment = Alignment.CenterVertically) {
            EmojiTile(emoji, 56.dp)
            Spacer(Modifier.width(Space.md))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Glass.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        }
        FlowRow(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            val time = t.due?.drop(11)?.trim()?.take(5)?.takeIf(String::isNotEmpty)
            val part = time?.let { runCatching { org.pagel.ai.data.habits.partAt(java.time.LocalTime.parse(it)).label }.getOrNull() } ?: "Anytime"
            Chip(part)
            Chip(Repeat.label(t.every.orEmpty()))
            if (time != null) Chip("🔔 $time")
            minutesOf(title, pref?.minutes)?.let { Chip("$it min") }
            if (t.times > 1) Chip("${t.times}× a day")
        }
        // The identity line (Atomic Habits): who Boss is becoming, in his own words.
        val press = remember { MutableInteractionSource() }
        Text(
            pref?.identity?.let { "“$it”" } ?: "Write who you are becoming…", style = MaterialTheme.typography.bodyLarge,
            color = if (pref?.identity != null) Glass.Ink else Glass.Muted,
            modifier = Modifier.padding(horizontal = Space.textEdge).heightIn(min = Space.touch).pressScale(press)
                .clickable(press, indication = null, role = Role.Button) { identityOpen = true },
        )
        TodayActions(t, ui, onReminder, onTick, onUntick, onTimer, onResetTimer, onFreeze, onUnfreeze)
        StatCards(stats)
        // This habit's own chain (the Habits tab's top bar is the all-habits one): a count habit's partial day shows taps / times.
        val marks = monthMarks(emptyList(), ui.ticks, ui.freezes, month, today, t)
        val taps = ui.ticks.filter { it.taskId == t.id }.associate { it.day to it.n }
        MonthChain(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)), marks, today, onPrev = { month = month.minusMonths(1) },
            onNext = if (month < YearMonth.from(today)) ({ month = month.plusMonths(1) }) else null,
            counts = marks.map { (d, _) -> (taps[d.toString()] ?: 0) to t.times.coerceAtLeast(1) })
        WeekRows(t, ui, today)
        if (!isNamoz(t)) {
            Section("Emoji")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                for (k in Emoji.all.keys) {
                    val on = k == emoji
                    val p = remember { MutableInteractionSource() }
                    Box(
                        Modifier.size(Space.touch).clip(RoundedCornerShape(14.dp)).background(if (on) Glass.PrimarySoft else Color.White.copy(alpha = 0.5f))
                            .border(if (on) 1.5.dp else 0.dp, if (on) Glass.Primary else Color.Transparent, RoundedCornerShape(14.dp))
                            .pressScale(p).clickable(p, indication = null, role = Role.RadioButton) { onEmoji(k) }
                            .semantics { contentDescription = "Emoji $k"; selected = on },
                        contentAlignment = Alignment.Center,
                    ) { Image(painterResource(Emoji.res(k)), null, Modifier.size(32.dp)) }
                }
            }
            val others = ui.byId.values.filter { it.id != t.id && !isNamoz(it) }
            if (others.isNotEmpty()) {
                Section("After (habit stacking)")
                FlowRow(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Chip("None", on = pref?.after == null) { onAfter(null) }
                    for (o in others) Chip(titleOf(o).take(28), on = pref?.after == o.id) { onAfter(o.id) }
                }
            }
        }
    }
    if (identityOpen) IdentitySheet(pref?.identity.orEmpty(), onSave = { onIdentity(it); identityOpen = false }) { identityOpen = false }
}

/**
 * What used to crowd the row (Boss 2026-10-29: "move the reminder thing etc. to the habit's own page"): the reminder,
 * the clock of a habit with minutes, a streak freeze for today, taking today's tick back. One quiet glass list.
 */
@Composable
private fun TodayActions(
    t: TaskEntity, ui: HabitsUi, onReminder: () -> Unit, onTick: (String) -> Unit, onUntick: (String) -> Unit, onTimer: (String, Int) -> Unit,
    onResetTimer: (String) -> Unit, onFreeze: (String) -> Unit, onUnfreeze: (String) -> Unit,
) {
    val card = ui.board?.let { it.cards + it.namoz }?.firstOrNull { it.task.id == t.id }
    val time = t.due?.drop(11)?.trim()?.take(5)?.takeIf(String::isNotEmpty)
    val on = t.id in ui.reminders
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column {
            MenuRow(if (on) TabIcons.BellRing else TabIcons.Bell, "Reminder · " + if (on) "${time ?: "on"}" else "off", onReminder)
            if (card != null && card.minutes != null && !card.done && card.mark != Mark.FROZEN) {
                val timer = ui.timers[t.id]
                val label = when {
                    timer?.running == true -> "Pause the clock · " + progressText(card, timer, ui.now)
                    timer != null -> "Go on · " + progressText(card, timer, ui.now)
                    else -> "Start ${card.minutes} min"
                }
                MenuRow(TabIcons.Clock, label) { onTimer(t.id, card.minutes) }
                if (timer != null) MenuRow(TabIcons.Refresh, "Reset the clock") { onResetTimer(t.id) }
                MenuRow(TabIcons.Check, "Done without the clock") { onTick(t.id) }
            }
            if (card != null && !card.done && !isNamoz(t)) {
                val left = ui.board?.freezesLeft ?: 0
                if (card.mark == Mark.FROZEN) MenuRow(TabIcons.Undo, "Unfreeze today") { onUnfreeze(t.id) }
                else MenuRow(TabIcons.Sparkles, "Freeze today · $left left this month") { onFreeze(t.id) }
            }
            if (card != null && (card.done || card.taps > 0)) MenuRow(TabIcons.Undo, "Take back today's tick") { onUntick(t.id) }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.padding(start = Space.textEdge, top = Space.sm).semantics { heading() })
}

@Composable
internal fun Chip(text: String, on: Boolean = false, onClick: (() -> Unit)? = null) {
    val press = remember { MutableInteractionSource() }
    // A Box centres the word in the 48 dp target (a Text with heightIn sat on its top edge).
    Box(
        Modifier.heightIn(min = if (onClick != null) Space.touch else 32.dp).clip(CircleShape)
            .background(if (on) Glass.PrimarySoft else Color.White.copy(alpha = 0.7f))
            .border(1.dp, if (on) Glass.Primary else Glass.Ink.copy(alpha = 0.08f), CircleShape)
            .then(if (onClick != null) Modifier.pressScale(press).clickable(press, indication = null, role = Role.RadioButton, onClick = onClick).semantics { selected = on } else Modifier)
            .padding(horizontal = Space.md, vertical = Space.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = if (on) Glass.PrimaryInk else Glass.Ink, maxLines = 1)
    }
}

/** The last 6 weeks as rows: the dates, a bar of done days, and "4 / 7". */
@Composable
private fun WeekRows(t: TaskEntity, ui: HabitsUi, today: LocalDate) {
    val done = ui.ticks.filter { it.taskId == t.id && it.n >= t.times.coerceAtLeast(1) }.map { it.day }.toSet()
    val frozen = ui.freezes.filter { it.taskId == t.id }.map { it.day }.toSet()
    val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    Section("Statistics")
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column(Modifier.padding(vertical = Space.sm)) {
            val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            for (w in 0L until 6L) {
                val from = monday.minusWeeks(w)
                val to = minOf(from.plusDays(6), today)
                val (n, of) = completion(t, done, frozen, from, to)
                Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).padding(horizontal = Space.lg), verticalAlignment = Alignment.CenterVertically) {
                    Text("${from.format(fmt)} – ${from.plusDays(6).format(fmt)}", style = MaterialTheme.typography.bodyMedium, color = Glass.Ink, modifier = Modifier.width(132.dp), maxLines = 1)
                    Box(Modifier.weight(1f).height(6.dp).background(Glass.Ink.copy(alpha = 0.07f), CircleShape)) {
                        if (of > 0) Box(Modifier.fillMaxWidth(n.toFloat() / of).height(6.dp).background(Brush.horizontalGradient(listOf(Glass.Aurora1, Glass.Primary)), CircleShape))
                    }
                    Text("$n / $of", style = Glass.NumberText, color = Glass.Muted, modifier = Modifier.padding(start = Space.md).width(44.dp))
                }
            }
        }
    }
}

@Composable
private fun IdentitySheet(now: String, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(now) }
    org.pagel.ai.ui.glass.GlassSheet(onDismiss) {
        org.pagel.ai.ui.glass.SheetTitle("Who you are becoming", null) { onSave(text) }
        androidx.compose.material3.OutlinedTextField(
            text, { text = it.take(120) }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
            placeholder = { Text("I'm someone who trains 3× a week") }, singleLine = false, maxLines = 3,
        )
        Spacer(Modifier.height(org.pagel.ai.ui.glass.SheetBottom))
    }
}

// ── History ─────────────────────────────────────────────────────────────────────────────────────────────────────────

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
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            for ((i, name) in listOf("Calendar", "All habits", "Achievements").withIndex()) Chip(name, on = tab == i) { tab = i }
        }
        StatCards(statsOf(habits, ui.ticks, ui.freezes, today))
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
                    counts = marks.map { (d, _) -> org.pagel.ai.data.habits.heldOf(if (namoz) prayers else habits, taps, frozen, d) })
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
                            EmojiTile(Namoz.prayerOf(title)?.emoji ?: Emoji.keyFor(title, ui.prefs[h.id]?.emoji), 36.dp)
                            Spacer(Modifier.width(Space.md))
                            Text(title, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("🔥${s.streak}  ${s.rate}%", style = Glass.NumberText, color = Glass.Muted)
                        }
                    }
                }
            }
            else -> {
                val list = achievements(all, ui.ticks, ui.freezes, today)
                Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    for (row in list.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        for (a in row) Badge(a, Modifier.weight(1f))
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(a: Achievement, modifier: Modifier) {
    Pane(modifier.semantics(mergeDescendants = true) { contentDescription = "${a.title}, ${if (a.earned) "earned" else "not yet"}" }) {
        Column(Modifier.fillMaxWidth().padding(Space.lg), horizontalAlignment = Alignment.CenterHorizontally) {
            EmojiTile(a.emoji, 52.dp, quiet = !a.earned)
            Spacer(Modifier.height(Space.sm))
            Text(a.title, style = MaterialTheme.typography.titleSmall, color = if (a.earned) Glass.Ink else Glass.Muted, maxLines = 1)
            Text(a.line, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, minLines = 2, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}


