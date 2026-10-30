package org.pagel.ai.ui.habits

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
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
import org.pagel.ai.data.habits.Part
import org.pagel.ai.data.habits.habitWeeks
import org.pagel.ai.data.habits.isNamoz
import org.pagel.ai.data.habits.minutesOf
import org.pagel.ai.data.habits.monthMarks
import org.pagel.ai.data.habits.partAt
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassIconButton
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.SheetLabel
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TileColors
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.task.LocalTaskOpener
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Where the ⋮ sheet opens: at its top (Look) or at the reminder (the bell chip). */
enum class SheetAt { TOP, REMINDER, LOOK }

/**
 * One habit (Boss 2026-10-29: "when opened, it is mainly about stats; cleaner"): the header (its own colour tile, the
 * name, the recipe line, the chips) → one stats card (a 3-number strip over a 12-week chart) → the month's chain. Every
 * action and every setting sits behind ⋮ (and the tile, and the bell chip, which open the same sheet).
 */
@Composable
fun HabitDetailRoute(id: String, contentPadding: PaddingValues, onBack: () -> Unit, vm: HabitsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val t = ui.byId[id]
    val ctx = LocalContext.current
    val openTask = LocalTaskOpener.current
    var sheet by remember { mutableStateOf<SheetAt?>(null) }
    val grants = rememberReminderGrants(vm)
    if (t == null) { Box(Modifier.fillMaxSize().padding(contentPadding)); return }
    val today = LocalDate.parse(ui.today)
    HabitDetail(
        t, ui, contentPadding, onBack = onBack, onEdit = { openTask(id) },
        onShare = {
            val s = statsOf(listOf(t), ui.ticks, ui.freezes, today)
            val text = "${titleOf(t)}: ${s.streak} in a row (best ${s.best}), ${s.rate}% of the last 30 days."
            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }
        },
        onSettings = { sheet = it },
    )
    sheet?.let { at ->
        GlassSheet(onDismiss = { sheet = null }, full = true) {
            HabitSettings(
                t, ui, at,
                reminder = {
                    ReminderContent(
                        t, on = id in ui.reminders, exact = grants.exact, onToggle = { grants.remind(id, it) }, onTime = { vm.setTime(id, it) },
                        notify = grants.notify, onFixNotify = grants.fixNotify, onFixExact = grants.fixExact,
                    )
                },
                onEmoji = { vm.setEmoji(id, it) }, onColour = { vm.setColour(id, it) }, onRename = { vm.rename(id, it) },
                onNote = { vm.setIdentity(id, it) }, onPart = { vm.setPart(id, it) }, onDays = { vm.setDays(id, it) },
                onAnchor = { vm.setAnchor(id, it) },
                onTimer = { vm.toggleTimer(id, it) }, onResetTimer = { vm.resetTimer(id) }, onDone = { vm.tickOn(id, today) },
                onFreeze = { vm.freeze(id, onToday = true) }, onUnfreeze = { vm.unfreeze(id, onToday = true) }, onUntick = { vm.untick(id, onToday = true) },
                onDelete = { vm.delete(id) { sheet = null; onBack() } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitDetail(
    t: TaskEntity, ui: HabitsUi, contentPadding: PaddingValues, onBack: () -> Unit = {}, onEdit: () -> Unit = {}, onShare: () -> Unit = {},
    onSettings: (SheetAt) -> Unit = {},
) {
    val today = runCatching { LocalDate.parse(ui.today) }.getOrElse { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    val pref = ui.prefs[t.id]
    val title = titleOf(t)
    val emoji = Namoz.prayerOf(title)?.emoji ?: Emoji.keyFor(title, pref?.emoji)
    val weeks = remember(t, ui.ticks, ui.freezes, today) { habitWeeks(t, ui.ticks, ui.freezes, today) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.paneGap)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(TabIcons.ArrowLeft, "Back", size = Space.touch, plain = true, onClick = onBack)
            Spacer(Modifier.weight(1f))
            GlassIconButton(TabIcons.StickyNote, "Edit", size = Space.touch, plain = true, onClick = onEdit)
            GlassIconButton(TabIcons.ArrowUpRight, "Share", size = Space.touch, plain = true, onClick = onShare)
            GlassIconButton(TabIcons.More, "Habit settings", size = Space.touch, plain = true) { onSettings(SheetAt.TOP) }
        }
        Row(Modifier.padding(horizontal = Space.textEdge), verticalAlignment = Alignment.CenterVertically) {
            // Its own colour, set into the title row; a tap opens the sheet at Look (emoji, colour, name).
            val press = remember { MutableInteractionSource() }
            Box(Modifier.pressScale(press).clickable(press, indication = null, role = Role.Button, onClickLabel = "Change emoji and colour") { onSettings(SheetAt.LOOK) }) {
                EmojiTile(emoji, 40.dp, colour = HabitLook.colourOf(pref?.emoji))
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Glass.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                recipeOf(t, ui)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Glass.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        FlowRow(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            val time = t.due?.drop(11)?.trim()?.take(5)?.takeIf(String::isNotEmpty)
            Chip(time?.let { runCatching { partAt(LocalTime.parse(it)).label }.getOrNull() } ?: "Anytime")
            Chip(Repeat.label(t.every.orEmpty()))
            // The quick glance at the reminder, honest about on/off; a tap opens the sheet at Reminder.
            if (time != null) Chip(if (t.id in ui.reminders) "🔔 $time" else "🔕 $time") { onSettings(SheetAt.REMINDER) }
            minutesOf(title, pref?.minutes)?.let { Chip("$it min") }
            if (t.times > 1) Chip("${t.times}× a day")
        }
        StatsCard(weeks)
        val marks = monthMarks(emptyList(), ui.ticks, ui.freezes, month, today, t)
        val taps = ui.ticks.filter { it.taskId == t.id }.associate { it.day to it.n }
        MonthChain(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)), marks, today, onPrev = { month = month.minusMonths(1) },
            onNext = if (month < YearMonth.from(today)) ({ month = month.plusMonths(1) }) else null,
            counts = marks.map { (d, _) -> (taps[d.toString()] ?: 0) to t.times.coerceAtLeast(1) })
    }
}

/** "After ☕ coffee → 🧘 Floor routine", from the habit's anchor; null without one. */
internal fun recipeOf(t: TaskEntity, ui: HabitsUi): String? {
    val anchor = HabitLook.anchorOf(ui.prefs[t.id]?.after)
    val anchorTitle = (anchor as? HabitLook.Anchor.Habit)?.let { a -> ui.byId[a.id] }?.let { a ->
        Emoji.glyph(Emoji.keyFor(titleOf(a), ui.prefs[a.id]?.emoji)) + " " + titleOf(a)
    }
    return HabitLook.recipe(anchor, anchorTitle, Emoji.glyph(Emoji.keyFor(titleOf(t), ui.prefs[t.id]?.emoji)), titleOf(t))
}

// ── Stats ───────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * One compact glass card (Boss 2026-10-29: the six "0 / 0" week rows and the three stretched cards were "trash"): a
 * strip of three numbers (🔥 Streak · ✓ This week · 🎯 Best) over a 12-week bar chart in the glass chart kit's style
 * (thin rounded bars, radius min(6, 0.3 × width), mono 11 sp labels, no grid). Never a week before the habit started;
 * under 2 weeks it says when the chart comes. Tap a bar: that week's days in the line above the chart.
 */
@Composable
internal fun StatsCard(w: HabitWeeks) {
    var picked by remember(w) { mutableStateOf<Int?>(null) }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).testTag("habit-stats")) {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.md)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                StatColumn("🔥 Streak", "${w.streak}", Modifier.weight(1f))
                Divider()
                StatColumn("✓ Week", w.thisWeek?.let { "${it.done}/${it.target}" } ?: "0/0", Modifier.weight(1f))
                Divider()
                StatColumn("🎯 Best", "${w.best}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.md))
            // One line above the chart, always there (no jump): the picked week's days, the empty state, or the summary.
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
            val line = when {
                w.tooNew -> "Stats show up after a week · ${(7 - w.daysTracked).coerceAtLeast(1)} ${if (7 - w.daysTracked == 1) "day" else "days"} to go"
                picked != null -> w.bars[picked!!].let { b ->
                    b.monday.format(fmt) + ": " + b.days.joinToString(" ") { (d, m) ->
                        d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.UK) + when (m) { Mark.DONE -> "✓"; Mark.FROZEN -> "❄"; Mark.MISSED -> "·"; else -> "" }
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

@Composable
private fun StatColumn(label: String, value: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1, softWrap = false)
        Spacer(Modifier.height(Space.xs))
        Text(value, style = Glass.NumberText.copy(fontSize = 22.sp), color = Glass.Ink, maxLines = 1)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(32.dp).background(Glass.Ink.copy(alpha = 0.10f)))
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

// ── The ⋮ sheet ─────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Everything a habit can change, grouped (Boss 2026-10-29): Look (emoji, colour, name, note) · Schedule (time of day,
 * days, reminder) · Routine (after: a moment or another habit) · Actions (the clock, done, freeze, take back, delete).
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HabitSettings(
    t: TaskEntity, ui: HabitsUi, at: SheetAt, reminder: @Composable () -> Unit,
    onEmoji: (String?) -> Unit = {}, onColour: (String?) -> Unit = {}, onRename: (String) -> Unit = {}, onNote: (String?) -> Unit = {},
    onPart: (Part) -> Unit = {}, onDays: (Set<Int>) -> Unit = {}, onAnchor: (HabitLook.Anchor?) -> Unit = {},
    onTimer: (Int) -> Unit = {}, onResetTimer: () -> Unit = {}, onDone: () -> Unit = {}, onFreeze: () -> Unit = {}, onUnfreeze: () -> Unit = {},
    onUntick: () -> Unit = {}, onDelete: () -> Unit = {},
) {
    val pref = ui.prefs[t.id]
    val title = titleOf(t)
    val emoji = Namoz.prayerOf(title)?.emoji ?: Emoji.keyFor(title, pref?.emoji)
    val colour = HabitLook.colourOf(pref?.emoji)
    val toReminder = remember { BringIntoViewRequester() }
    LaunchedEffect(at) { if (at == SheetAt.REMINDER) toReminder.bringIntoView() }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        // ── Look: Schedule and Actions come first; the 40-emoji grid and the colours open behind the tile.
        var lookOpen by remember(t.id, at) { mutableStateOf(at == SheetAt.LOOK) }
        Row(Modifier.padding(horizontal = Space.xl, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            val tilePress = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(tilePress).clickable(tilePress, indication = null, role = Role.Button, onClickLabel = if (lookOpen) "Close emoji and colour" else "Change emoji and colour") { lookOpen = !lookOpen }
                    .testTag("look-tile"),
            ) { EmojiTile(emoji, 56.dp, colour = colour) }
            Spacer(Modifier.width(Space.md))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!isNamoz(t)) {
            SheetLabel("Look", Modifier.padding(start = Space.xl))
            var name by remember(t.id) { mutableStateOf(title) }
            OutlinedTextField(name, { name = it.take(80); }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter), label = { Text("Name") }, singleLine = true)
            if (name.trim() != title && name.isNotBlank()) Chip("Save name", on = true, modifier = Modifier.padding(start = Space.gutter)) { onRename(name) }
            if (lookOpen) LookPicker(emoji, colour, onEmoji, onColour)
            var note by remember(t.id) { mutableStateOf(pref?.identity.orEmpty()) }
            OutlinedTextField(note, { note = it.take(120) }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter), label = { Text("Who you are becoming") },
                placeholder = { Text("I'm someone who trains 3× a week") }, maxLines = 3)
            if (note.trim() != pref?.identity.orEmpty()) Chip("Save note", on = true, modifier = Modifier.padding(start = Space.gutter)) { onNote(note.ifBlank { null }) }
        }
        // ── Schedule
        SheetLabel("Schedule", Modifier.padding(start = Space.xl, top = Space.md))
        if (!isNamoz(t)) {
            val time = t.due?.drop(11)?.trim()?.take(5)?.takeIf(String::isNotEmpty)
            val part = time?.let { runCatching { partAt(LocalTime.parse(it)) }.getOrNull() } ?: Part.ANYTIME
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                for (p in listOf(Part.MORNING, Part.AFTERNOON, Part.EVENING, Part.ANYTIME)) Chip(p.label, on = p == part, modifier = Modifier.weight(1f)) { onPart(p) }
            }
            val days = Repeat.daysOf(t.every.orEmpty()).toSet().ifEmpty { if (t.every == "day") (0..6).toSet() else if (t.every == "weekday") (1..5).toSet() else emptySet() }
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                // Monday first, as the strip.
                for (d in listOf(1, 2, 3, 4, 5, 6, 0)) {
                    val on = d in days
                    val letter = java.time.DayOfWeek.of(if (d == 0) 7 else d).getDisplayName(TextStyle.NARROW, Locale.UK)
                    Chip(letter, on = on, modifier = Modifier.weight(1f)) { onDays(if (on) days - d else days + d) }
                }
            }
        }
        Box(Modifier.bringIntoViewRequester(toReminder)) { reminder() }
        // ── Routine (habit stacking: after a moment or a habit)
        if (!isNamoz(t)) {
            SheetLabel("Routine", Modifier.padding(start = Space.xl, top = Space.md))
            AnchorPicker(t, ui, onAnchor)
        }
        // ── Actions (today)
        SheetLabel("Actions", Modifier.padding(start = Space.xl, top = Space.md))
        val card = ui.board?.let { it.cards + it.namoz }?.firstOrNull { it.task.id == t.id }
        Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
            Column {
                val minutes = card?.minutes
                if (card != null && minutes != null && !card.done && card.mark != Mark.FROZEN) {
                    val timer = ui.timers[t.id]
                    MenuRow(TabIcons.Clock, when {
                        timer?.running == true -> "Pause the clock · " + progressText(card, timer, ui.now)
                        timer != null -> "Go on · " + progressText(card, timer, ui.now)
                        else -> "Start $minutes min"
                    }) { onTimer(minutes) }
                    if (timer != null) MenuRow(TabIcons.Refresh, "Reset the clock", onResetTimer)
                    MenuRow(TabIcons.Check, "Done without the clock", onDone)
                }
                if (card != null && !card.done && !isNamoz(t)) {
                    if (card.mark == Mark.FROZEN) MenuRow(TabIcons.Undo, "Unfreeze today", onUnfreeze)
                    else MenuRow(TabIcons.Sparkles, "Freeze today · ${ui.board?.freezesLeft ?: 0} left this month", onFreeze)
                }
                if (card != null && (card.done || card.taps > 0)) MenuRow(TabIcons.Undo, "Take back today's tick", onUntick)
                MenuRow(TabIcons.Trash, "Delete this habit", onDelete)
            }
        }
    }
}

/** The emoji search, the grid and the colours: opened by the tile, so the sheet starts on Schedule and Actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookPicker(emoji: String, colour: String?, onEmoji: (String?) -> Unit, onColour: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            var find by remember { mutableStateOf("") }
            OutlinedTextField(find, { find = it.take(20) }, Modifier.fillMaxWidth().padding(horizontal = Space.gutter), label = { Text("Find an emoji") }, singleLine = true)
            FlowRow(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                for (k in Emoji.all.keys.filter { find.isBlank() || it.contains(find.trim().lowercase()) }) {
                    val on = k == emoji
                    val p = remember(k) { MutableInteractionSource() }
                    Box(
                        Modifier.size(Space.touch).clip(RoundedCornerShape(14.dp)).background(if (on) Glass.PrimarySoft else Color.White.copy(alpha = 0.5f))
                            .border(if (on) 1.5.dp else 0.dp, if (on) Glass.Primary else Color.Transparent, RoundedCornerShape(14.dp))
                            .pressScale(p).clickable(p, indication = null, role = Role.RadioButton) { onEmoji(k) }
                            .semantics { contentDescription = "Emoji $k"; selected = on },
                        contentAlignment = Alignment.Center,
                    ) { Image(painterResource(Emoji.res(k)), null, Modifier.size(32.dp)) }
                }
            }
            FlowRow(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                for (c in HabitLook.COLOURS) {
                    val (a, b) = TileColors.stops("", c)
                    val on = c == colour
                    val p = remember(c) { MutableInteractionSource() }
                    Box(
                        Modifier.size(Space.touch).pressScale(p).clickable(p, indication = null, role = Role.RadioButton) { onColour(c) }
                            .semantics { contentDescription = "Colour $c"; selected = on },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(a, b)))
                            .border(if (on) 2.5.dp else 1.dp, if (on) Glass.Ink else Color.White.copy(alpha = 0.7f), CircleShape))
                    }
                }
            }
    }
}

/**
 * "After": None, the daily moments (☕ Coffee, 🕌 Fajr, …, or his own words), then real habits only, by part of the day,
 * each with its colour tile, emoji and full name (2 lines, never cut mid-word). Itself and loops are never offered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnchorPicker(t: TaskEntity, ui: HabitsUi, onAnchor: (HabitLook.Anchor?) -> Unit) {
    val now = HabitLook.anchorOf(ui.prefs[t.id]?.after)
    var open by remember { mutableStateOf(false) }
    Pane(Modifier.fillMaxWidth().padding(horizontal = Space.gutter)) {
        Column {
            val label = when (now) {
                null -> "None"
                is HabitLook.Anchor.Moment -> now.text
                is HabitLook.Anchor.Habit -> ui.byId[now.id]?.let(::titleOf) ?: "None"
            }
            MenuRow(TabIcons.CornerDownLeft, "After · $label") { open = !open }
            if (!open) return@Column
            AnchorRow("None", null, null, now == null) { onAnchor(null) }
            for (m in HabitLook.MOMENTS) AnchorRow(m, null, null, (now as? HabitLook.Anchor.Moment)?.text == m) { onAnchor(HabitLook.Anchor.Moment(m)) }
            var own by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.md), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(own, { own = it.take(40) }, Modifier.weight(1f), label = { Text("✏️ Your own moment") }, singleLine = true)
                if (own.isNotBlank()) Chip("Use", on = true, modifier = Modifier.padding(start = Space.sm)) { onAnchor(HabitLook.Anchor.Moment("✏️ " + own.trim())) }
            }
            val choices = HabitLook.anchorChoices(t.id, ui.byId.values, ui.prefs)
            for (p in listOf(Part.MORNING, Part.AFTERNOON, Part.EVENING, Part.ANYTIME)) {
                val inPart = choices.filter { c -> (c.due?.drop(11)?.trim()?.take(5)?.takeIf(String::isNotEmpty)?.let { runCatching { partAt(LocalTime.parse(it)) }.getOrNull() } ?: Part.ANYTIME) == p }
                if (inPart.isEmpty()) continue
                Text(p.label, style = MaterialTheme.typography.labelMedium, color = Glass.Muted, modifier = Modifier.padding(start = Space.lg, top = Space.sm))
                for (c in inPart) {
                    val e = Emoji.keyFor(titleOf(c), ui.prefs[c.id]?.emoji)
                    AnchorRow(titleOf(c), e, HabitLook.colourOf(ui.prefs[c.id]?.emoji), (now as? HabitLook.Anchor.Habit)?.id == c.id) { onAnchor(HabitLook.Anchor.Habit(c.id)) }
                }
            }
        }
    }
}

@Composable
private fun AnchorRow(text: String, emoji: String?, colour: String?, on: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.row).pressScale(press).clickable(press, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "After: $text"; selected = on }.padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (emoji != null) { EmojiTile(emoji, 32.dp, colour = colour); Spacer(Modifier.width(Space.md)) }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Glass.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (on) Icon(TabIcons.Check, null, tint = Glass.Primary, modifier = Modifier.size(20.dp))
    }
}
