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

@Composable
internal fun Chip(text: String, on: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val press = remember { MutableInteractionSource() }
    // A Box centres the word in the 48 dp target (a Text with heightIn sat on its top edge).
    Box(
        modifier.heightIn(min = if (onClick != null) Space.touch else 32.dp).clip(CircleShape)
            .background(if (on) Glass.PrimarySoft else Color.White.copy(alpha = 0.7f))
            .border(1.dp, if (on) Glass.Primary else Glass.Ink.copy(alpha = 0.08f), CircleShape)
            .then(if (onClick != null) Modifier.pressScale(press).clickable(press, indication = null, role = Role.RadioButton, onClick = onClick).semantics { selected = on } else Modifier)
            .padding(horizontal = Space.md, vertical = Space.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = if (on) Glass.PrimaryInk else Glass.Ink, maxLines = 1)
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
            for ((i, name) in listOf("Calendar", "All habits", "Badges").withIndex()) Chip(name, on = tab == i, modifier = Modifier.weight(1f)) { tab = i }
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


