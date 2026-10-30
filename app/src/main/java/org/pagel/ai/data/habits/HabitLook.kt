package org.pagel.ai.data.habits

import org.pagel.ai.data.db.HabitFreezeEntity
import org.pagel.ai.data.db.HabitPrefEntity
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.ui.glass.Repeat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * A habit's own look and its anchor (Boss 2026-10-29), kept in the habit_prefs row the phone already has:
 * `emoji` = "coffee" or "coffee/amber" (the emoji key, then one of the ten tile colours); `after` = another habit's id,
 * or "moment:☕ Coffee" for a daily moment (BJ Fogg's anchor: after I pour my coffee, I …). One parser, used everywhere.
 * A real column for each (and their sync to pagel.uz) waits for Room v10 (see START HERE v18.2).
 */
object HabitLook {
    const val MOMENT = "moment:"

    /** The daily moments offered before the habits in the "After" picker. */
    val MOMENTS = listOf("☕ Coffee", "🕌 Fajr", "🪥 Brushing teeth", "🍽 Lunch", "🛏 Getting into bed")

    /** The ten tile colours (brain rules/gradient-project-icons, the projects' own palette). */
    val COLOURS = listOf("mint", "teal", "sky", "blue", "violet", "pink", "red", "coral", "amber", "slate")

    fun emojiOf(raw: String?): String? = raw?.substringBefore('/')?.trim()?.takeIf(String::isNotEmpty)
    fun colourOf(raw: String?): String? = raw?.substringAfter('/', "")?.trim()?.takeIf { it in COLOURS }
    fun look(emoji: String?, colour: String?): String? {
        val e = emoji?.trim().orEmpty()
        val c = colour?.takeIf { it in COLOURS }
        return when {
            c == null -> e.ifEmpty { null }
            else -> "$e/$c"
        }
    }

    /** The anchor as stored: a habit id, or a moment's words. */
    sealed interface Anchor {
        data class Habit(val id: String) : Anchor
        data class Moment(val text: String) : Anchor
    }

    fun anchorOf(raw: String?): Anchor? = when {
        raw.isNullOrBlank() -> null
        raw.startsWith(MOMENT) -> raw.removePrefix(MOMENT).trim().takeIf(String::isNotEmpty)?.let(Anchor::Moment)
        else -> Anchor.Habit(raw)
    }

    fun store(a: Anchor?): String? = when (a) {
        null -> null
        is Anchor.Habit -> a.id
        is Anchor.Moment -> MOMENT + a.text.trim().take(40)
    }

    /**
     * Habits [self] may come after: real habits only (a day, weekdays or set weekdays; never a weekly/monthly task, never
     * a prayer), never itself, never one whose own chain of "after" already leads back to [self] (no loops).
     */
    fun anchorChoices(self: String, tasks: Collection<TaskEntity>, prefs: Map<String, HabitPrefEntity>): List<TaskEntity> =
        tasks.filter { it.id != self && isDailyHabit(it) && !leadsTo(it.id, self, prefs) }

    /** Whether following [from]'s "after" links reaches [target] (a loop if [target] then followed [from]). */
    fun leadsTo(from: String, target: String, prefs: Map<String, HabitPrefEntity>): Boolean {
        var at: String? = from
        val seen = mutableSetOf<String>()
        while (at != null && seen.add(at)) {
            if (at == target) return true
            at = (anchorOf(prefs[at]?.after) as? Anchor.Habit)?.id
        }
        return false
    }

    /**
     * Rows in stacking order (note 4): each habit right under the habit it follows when both are on the same page, with
     * its depth (0 = leads). A link to a habit not on the page is shown as a plain row; every row shows exactly once.
     */
    fun <T> stackOrder(items: List<T>, id: (T) -> String, prefs: Map<String, HabitPrefEntity>): List<Pair<T, Int>> {
        val here = items.map(id).toSet()
        val parent = items.associate { t -> id(t) to (anchorOf(prefs[id(t)]?.after) as? Anchor.Habit)?.id?.takeIf { it in here && it != id(t) } }
        val kids = items.groupBy { parent[id(it)] }
        val out = mutableListOf<Pair<T, Int>>()
        val seen = mutableSetOf<String>()
        fun walk(t: T, d: Int) {
            if (!seen.add(id(t))) return
            out += t to d
            kids[id(t)].orEmpty().forEach { walk(it, d + 1) }
        }
        items.filter { parent[id(it)] == null }.forEach { walk(it, 0) }
        items.forEach { walk(it, 0) }
        return out
    }

    /** A habit the Habits tab shows as a card: every day, weekdays or set weekdays; not a prayer. */
    fun isDailyHabit(t: TaskEntity): Boolean {
        val e = t.every.orEmpty()
        return !isNamoz(t) && (e == "day" || e == "weekday" || Repeat.daysOf(e).isNotEmpty())
    }

    /** "After ☕ coffee → 🧘 Floor routine": the recipe line under a habit's name; null without an anchor. */
    fun recipe(anchor: Anchor?, anchorTitle: String?, selfGlyph: String, selfTitle: String): String? {
        val first = when (anchor) {
            null -> return null
            is Anchor.Moment -> anchor.text.lowercase().replaceFirstChar { it.uppercase() }.let { m ->
                // "☕ Coffee" -> "☕ coffee": the moment reads as part of the sentence.
                val sp = m.indexOf(' ')
                if (sp > 0) m.substring(0, sp + 1) + m.substring(sp + 1).lowercase() else m
            }
            is Anchor.Habit -> anchorTitle ?: return null
        }
        return "After $first → $selfGlyph $selfTitle".trim()
    }
}

/** One week of a habit: its Monday, days done, days it was due (from its start), and each day's mark. */
data class WeekBar(val monday: LocalDate, val done: Int, val target: Int, val days: List<Pair<LocalDate, Mark>>) {
    val share: Float get() = if (target == 0) 0f else done.toFloat() / target
}

/** The habit page's numbers: up to 12 weeks from its start (never a week before it existed), streak, best streak. */
data class HabitWeeks(val bars: List<WeekBar>, val streak: Int, val best: Int, val daysTracked: Int) {
    val thisWeek: WeekBar? get() = bars.lastOrNull()
    val average: Int get() = bars.filter { it.target > 0 }.let { b -> if (b.isEmpty()) 0 else (b.sumOf { it.share.toDouble() } / b.size * 100).toInt() }
    val bestWeek: Int get() = ((bars.filter { it.target > 0 }.maxOfOrNull { it.share } ?: 0f) * 100).toInt()
    /** Fewer than 2 weeks: the page shows "Stats show up after a week" instead of a chart. */
    val tooNew: Boolean get() = bars.size < 2
}

fun habitWeeks(t: TaskEntity, ticks: List<HabitTickEntity>, freezes: List<HabitFreezeEntity>, today: LocalDate, weeks: Int = 12): HabitWeeks {
    val start = startsOf(listOf(t), ticks, today)[t.id] ?: today
    val taps = ticks.filter { it.taskId == t.id }.associate { it.day to it.n }
    val frozen = freezes.filter { it.taskId == t.id }.map { it.day }.toSet()
    val done = taps.filter { it.value >= t.times.coerceAtLeast(1) }.keys
    val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val firstMonday = maxOf(start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), thisMonday.minusWeeks((weeks - 1).toLong()))
    val bars = generateSequence(firstMonday) { it.plusWeeks(1) }.takeWhile { !it.isAfter(thisMonday) }.map { mon ->
        val days = (0L..6L).map { mon.plusDays(it) }
        val counted = days.filter { !it.isBefore(start) && repeatsOn(t, it) }
        val marks = days.map { d ->
            d to if (d.isBefore(start)) Mark.BEFORE else markOf(t, d, today, taps[d.toString()] ?: 0, d.toString() in frozen)
        }
        WeekBar(mon, counted.count { it.toString() in done }, counted.size, marks)
    }.toList()
    return HabitWeeks(
        bars, chainStreak(t, done, frozen, today), chainBest(t, done, frozen, today),
        (ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceAtLeast(1),
    )
}
