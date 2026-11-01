package org.pagel.ai.ui.habits

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.HabitLook
import org.pagel.ai.data.habits.HabitWeeks
import org.pagel.ai.data.habits.Mark
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.habitWeeks
import org.pagel.ai.data.habits.minutesOf
import org.pagel.ai.data.habits.monthMarks
import org.pagel.ai.data.habits.partAt
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * One habit (Boss 2026-10-29: "when opened, it is mainly about stats; cleaner"): the header (its own colour tile, the
 * name, the recipe line, the chips) → one stats card (a 3-number strip over a 12-week chart) → the month's chain. Every
 * action and every setting is in the task panel (Boss 2026-10-31: one editor), which ⋮, the tile and the bell chip open.
 */
@Composable
fun HabitDetailRoute(id: String, contentPadding: PaddingValues, onBack: () -> Unit, onOpenTimer: (() -> Unit)? = null, vm: HabitsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val t = ui.byId[id]
    val ctx = LocalContext.current
    val openTask = LocalTaskOpener.current
    if (t == null) { Box(Modifier.fillMaxSize().padding(contentPadding)); return }
    val today = LocalDate.parse(ui.today)
    HabitDetail(
        t, ui, contentPadding, onBack = onBack, onToggleDay = { vm.toggleDay(id, it) }, onOpenTimer = onOpenTimer,
        onShare = {
            val s = statsOf(listOf(t), ui.ticks, ui.freezes, today)
            val text = "${titleOf(t)}: ${s.streak} in a row (best ${s.best}), ${s.rate}% of the last 30 days."
            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }
        },
        onSettings = { openTask(id) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitDetail(
    t: TaskEntity, ui: HabitsUi, contentPadding: PaddingValues, onBack: () -> Unit = {}, onShare: () -> Unit = {},
    onSettings: () -> Unit = {}, onToggleDay: ((LocalDate) -> Unit)? = null, onOpenTimer: (() -> Unit)? = null,
) {
    val today = runCatching { LocalDate.parse(ui.today) }.getOrElse { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    val pref = ui.prefs[t.id]
    val title = titleOf(t)
    val emoji = Namoz.prayerOf(title)?.emoji ?: HabitIcons.keyFor(title, pref?.emoji)
    val weeks = remember(t, ui.ticks, ui.freezes, today) { habitWeeks(t, ui.ticks, ui.freezes, today) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TabIcons.ArrowLeft, "Back", size = Space.touch, plain = true, onClick = onBack)
            Spacer(Modifier.weight(1f))
            GlassIconButton(TabIcons.ArrowUpRight, "Share", size = Space.touch, plain = true, onClick = onShare)
            GlassIconButton(TabIcons.More, "Edit habit", size = Space.touch, plain = true, onClick = onSettings)
        }
        Row(Modifier.padding(horizontal = Space.textEdge), verticalAlignment = Alignment.CenterVertically) {
            // Its own colour, set into the title row; a tap opens the task panel, where the icon and colour are changed.
            val press = remember { MutableInteractionSource() }
            Box(Modifier.pressScale(press).clickable(press, indication = null, role = Role.Button, onClickLabel = "Edit habit", onClick = onSettings)) {
                EmojiTile(emoji, 40.dp, colour = HabitLook.colourOf(pref?.emoji))
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                // The whole name, however long: it grows, never "…" (checker 3 #9).
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, modifier = Modifier.semantics { heading() })
                recipeOf(t, ui)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        // Every chip has one height (Chip: a 36 dp pill in a 48 dp slot), so the slots alone space the rows.
        FlowRow(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            val time = t.due?.drop(11)?.trim()?.take(5)?.takeIf(String::isNotEmpty)
            Chip(time?.let { runCatching { partAt(LocalTime.parse(it)).label }.getOrNull() } ?: "Anytime")
            Chip(Repeat.label(t.every.orEmpty()))
            // The quick glance at the reminder, honest about on/off; a tap opens the task panel. Off is a grey
            // outline bell, never the bell-off emoji (its slash is red, checker 3 #2).
            if (time != null) {
                val on = t.id in ui.reminders
                Chip(
                    time, icon = if (on) TabIcons.BellRing else TabIcons.BellOff, iconTint = if (on) Glass.Primary else Glass.Muted,
                    description = if (on) "Reminder on · $time" else "Reminder off · $time", onClick = onSettings,
                )
            }
            minutesOf(title, pref?.minutes)?.let { Chip("$it min") }
            if (onOpenTimer != null && minutesOf(title, pref?.minutes) != null) Chip("Timer", icon = TabIcons.Clock, iconTint = Glass.Primary, description = "Open timer") { onOpenTimer() }
            if (t.times > 1) Chip("${t.times}× a day")
        }
        StatCards(statsOf(listOf(t), ui.ticks, ui.freezes, today))
        StatsCard(weeks)
        val marks = monthMarks(emptyList(), ui.ticks, ui.freezes, month, today, t)
        val taps = ui.ticks.filter { it.taskId == t.id }.associate { it.day to it.n }
        MonthChain(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)), marks, today, onPrev = { month = month.minusMonths(1) },
            onNext = if (month < YearMonth.from(today)) ({ month = month.plusMonths(1) }) else null,
            counts = marks.map { (d, _) -> (taps[d.toString()] ?: 0) to t.times.coerceAtLeast(1) }, onSelect = onToggleDay)
    }
}

/** "After coffee → Floor routine", from the habit's anchor; null without one. */
internal fun recipeOf(t: TaskEntity, ui: HabitsUi): String? {
    val anchor = HabitLook.anchorOf(ui.prefs[t.id]?.after)
    val anchorTitle = (anchor as? HabitLook.Anchor.Habit)?.let { a -> ui.byId[a.id] }?.let(::titleOf)
    return HabitLook.recipe(anchor, anchorTitle, titleOf(t))
}

// ── Stats ───────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * One compact glass card under the three gradient stat cards (Boss 2026-10-29: the six "0 / 0" week rows were "trash"):
 * a 12-week bar chart in the glass chart kit's style
 * (thin rounded bars, radius min(6, 0.3 × width), mono 11 sp labels, no grid). Never a week before the habit started;
 * under 2 weeks it says when the chart comes. Tap a bar: that week's days in the line above the chart.
 */
@Composable
internal fun StatsCard(w: HabitWeeks) {
    var picked by remember(w) { mutableStateOf<Int?>(null) }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).testTag("habit-stats")) {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.md)) {
            // One line above the chart, always there (no jump): the picked week's days, the empty state, or the summary.
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
            val line = when {
                w.tooNew -> "Stats show up after a week · ${(7 - w.daysTracked).coerceAtLeast(1)} ${if (7 - w.daysTracked == 1) "day" else "days"} to go"
                picked != null -> w.bars[picked!!].let { b ->
                    b.monday.format(fmt) + ": " + b.days.joinToString(" ") { (d, m) ->
                        d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.UK) + when (m) { Mark.DONE, Mark.FROZEN -> "✓"; Mark.MISSED -> "·"; else -> "" }
                    }
                }
                else -> "Average ${w.average} % · Best week ${w.bestWeek} %"
            }
            Text(line, style = Glass.NumberText.copy(fontSize = 12.sp), color = Glass.Muted, maxLines = 2,
                modifier = Modifier.padding(horizontal = Space.lg).heightIn(min = 32.dp))
            Spacer(Modifier.height(Space.sm))
            WeekBars(w, picked) { picked = if (picked == it) null else it }
        }
    }
}

/** The bars: 12 slots, the oldest left; done share in mint, the rest a soft track, this week stronger + its label. */
@Composable
private fun WeekBars(w: HabitWeeks, picked: Int?, onPick: (Int) -> Unit) {
    val slots = 12
    val start = slots - w.bars.size
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.lg)) {
        Canvas(
            Modifier.fillMaxWidth().height(96.dp).testTag("habit-weeks")
                .semantics { contentDescription = "${w.bars.size} weeks, average ${w.average} %" }
                .pointerInput(w) {
                    detectTapGestures { p -> val i = (p.x / (size.width.toFloat() / slots)).toInt() - start; if (i in w.bars.indices) onPick(i) }
                },
        ) {
            val cell = size.width / slots
            val bw = minOf(12.dp.toPx(), cell * 0.45f)
            val r = minOf(6.dp.toPx(), bw * 0.3f)
            val top = 2.dp.toPx()
            val bottom = size.height - 16.dp.toPx()
            val h = bottom - top
            for ((k, b) in w.bars.withIndex()) {
                val x = (start + k + 0.5f) * cell - bw / 2
                val now = k == w.bars.lastIndex
                // This week: a dot under its bar, not a word that wraps at a large font (checker: a stray "W").
                if (now) drawCircle(Glass.Primary, 2.5.dp.toPx(), Offset(x + bw / 2, size.height - 6.dp.toPx()))
                drawRoundRect(Glass.Ink.copy(alpha = 0.06f), Offset(x, top), Size(bw, h), CornerRadius(r))
                val fh = h * b.share.coerceIn(0f, 1f)
                if (fh > 0f) drawRoundRect(
                    if (now || k == picked) Glass.Primary else Glass.Primary.copy(alpha = 0.62f),
                    Offset(x, bottom - fh), Size(bw, fh), CornerRadius(r),
                )
            }
        }
    }
}
