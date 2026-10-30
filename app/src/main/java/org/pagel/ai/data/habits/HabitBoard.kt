package org.pagel.ai.data.habits

import org.pagel.ai.data.db.HabitFreezeEntity
import org.pagel.ai.data.db.HabitPrefEntity
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.Repeat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * Habits 2.0 (round D, Boss: "cleaner, better, like a chain"): one habit on one day, as a mark. DONE and FROZEN hold the
 * chain; MISSED breaks it; OFF is a day the habit does not repeat on (the chain passes over it); BEFORE is a day a
 * namoz habit did not count yet (before 1 November); FUTURE has not come.
 */
enum class Mark { DONE, PARTIAL, FROZEN, MISSED, OFF, TODAY, FUTURE, BEFORE }

private fun Mark.holds() = this == Mark.DONE || this == Mark.FROZEN

/** The web's day number (getDay()): Sunday 0 … Saturday 6. */
private fun js(d: LocalDate) = d.dayOfWeek.value % 7

/**
 * Whether a habit is due on [d]: every day, Mon-Fri or a set of days by the weekday; a week, month or "N days" repeat
 * only on the dates its rule gives from its date on (the web's datesBetween), so a weekly habit is not on every day.
 */
fun repeatsOn(t: TaskEntity, d: LocalDate): Boolean {
    val every = t.every.orEmpty()
    return when {
        every == "day" || every.isEmpty() -> true
        every == "weekday" -> js(d) in 1..5
        Repeat.daysOf(every).isNotEmpty() -> js(d) in Repeat.daysOf(every)
        else -> t.due?.takeIf { it.length >= 10 }?.let { due -> d.toString() in Repeat.datesBetween(due, every, d.toString(), d.toString()) } ?: false
    }
}

/**
 * The first day a habit counts in the all-habits chain (checker 2026-10-29 #1: a habit added today turned every past
 * full day into a partial one). Its first tick or its own date, whichever is earlier; a habit with neither starts today.
 */
fun startsOf(habits: List<TaskEntity>, ticks: List<HabitTickEntity>, today: LocalDate): Map<String, LocalDate> {
    val first = ticks.groupBy { it.taskId }.mapValues { (_, l) -> l.minOf { it.day } }
    return habits.associate { t ->
        val dated = t.due?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val ticked = first[t.id]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        t.id to (listOfNotNull(dated, ticked).minOrNull() ?: today)
    }
}

/** Due on [d] and already started then (no start known = always). */
fun dueOn(t: TaskEntity, d: LocalDate, starts: Map<String, LocalDate>): Boolean =
    repeatsOn(t, d) && starts[t.id]?.let { !d.isBefore(it) } != false

fun isNamoz(t: TaskEntity) = t.habitGroup == Namoz.GROUP || (Namoz.prayerOf(t.title ?: t.text) != null && t.every == "day")

/** One habit on [day], from its taps that day and whether that day was frozen. */
fun markOf(t: TaskEntity, day: LocalDate, today: LocalDate, taps: Int, frozen: Boolean): Mark {
    val ymd = day.toString()
    return when {
        isNamoz(t) && !Namoz.counts(ymd) -> Mark.BEFORE
        !repeatsOn(t, day) -> Mark.OFF
        taps >= t.times.coerceAtLeast(1) -> Mark.DONE
        day.isAfter(today) -> Mark.FUTURE
        frozen -> Mark.FROZEN
        taps > 0 -> Mark.PARTIAL
        day == today -> Mark.TODAY
        else -> Mark.MISSED
    }
}

/**
 * Which cells join to the next: a held day links to the next held day when only OFF days lie between (a Tue/Thu/Sun
 * habit's chain runs over Wednesday). Index i is true when a bar runs from cell i to cell i+1.
 */
fun links(marks: List<Mark>): List<Boolean> = marks.indices.map { i ->
    if (i == marks.lastIndex) return@map false
    // A bar from i to i+1 when some held day at or before i and some held day after i meet over OFF days only.
    val left = (i downTo 0).firstOrNull { marks[it] != Mark.OFF }?.let { marks[it].holds() } ?: false
    val right = (i + 1..marks.lastIndex).firstOrNull { marks[it] != Mark.OFF }?.let { marks[it].holds() } ?: false
    left && right
}

/** The run of scheduled days held up to [today] (today not done yet does not break it); frozen days hold, count 0. */
fun chainStreak(t: TaskEntity, done: Set<String>, frozen: Set<String>, today: LocalDate): Int {
    var n = 0
    var d = today
    repeat(400) {
        val ymd = d.toString()
        if (isNamoz(t) && !Namoz.counts(ymd)) return n
        if (repeatsOn(t, d)) {
            when {
                ymd in done -> n++
                ymd in frozen || d == today -> Unit
                else -> return n
            }
        }
        d = d.minusDays(1)
    }
    return n
}

/** The longest run, the same rule, over the last 400 days. */
fun chainBest(t: TaskEntity, done: Set<String>, frozen: Set<String>, today: LocalDate): Int {
    var best = 0
    var run = 0
    var d = today.minusDays(399)
    while (!d.isAfter(today)) {
        val ymd = d.toString()
        if (repeatsOn(t, d) && !(isNamoz(t) && !Namoz.counts(ymd))) {
            when {
                ymd in done -> { run++; best = maxOf(best, run) }
                ymd in frozen || d == today -> Unit
                else -> run = 0
            }
        }
        d = d.plusDays(1)
    }
    return best
}

/**
 * "Never miss twice" (Atomic Habits): the last day it was due before today was missed (not done, not frozen) and
 * today is due and not done yet. Never for namoz (Boss: no shaming, a missed prayer is only an empty link).
 */
fun neverMissTwice(t: TaskEntity, done: Set<String>, frozen: Set<String>, today: LocalDate): Boolean {
    if (isNamoz(t) || today.toString() in done || !repeatsOn(t, today)) return false
    var d = today.minusDays(1)
    repeat(14) {
        if (repeatsOn(t, d)) {
            val ymd = d.toString()
            // A habit never done before that day is new, not missed.
            return ymd !in done && ymd !in frozen && done.any { it < ymd }
        }
        d = d.minusDays(1)
    }
    return false
}

/** Done of due days between [from] and [to] (frozen days count neither way); a habit's first done day starts it. */
fun completion(t: TaskEntity, done: Set<String>, frozen: Set<String>, from: LocalDate, to: LocalDate): Pair<Int, Int> {
    val first = done.minOrNull()?.let(LocalDate::parse) ?: return 0 to 0
    var d = maxOf(from, first)
    var n = 0
    var of = 0
    while (!d.isAfter(to)) {
        val ymd = d.toString()
        if (repeatsOn(t, d) && ymd !in frozen && !(isNamoz(t) && !Namoz.counts(ymd))) { of++; if (ymd in done) n++ }
        d = d.plusDays(1)
    }
    return n to of
}

/** A timed habit's minutes: Boss's own number, else the title's ("ten minutes", "5-min", "the five-minute routine"). */
fun minutesOf(title: String, chosen: Int? = null): Int? {
    if (chosen != null && chosen > 0) return chosen
    val words = mapOf("one" to 1, "two" to 2, "three" to 3, "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "besh" to 5, "o'n" to 10)
    val m = Regex("""(\d{1,3}|[a-z']+)[\s-]*(min|minute|minutes|daqiqa)\b""", RegexOption.IGNORE_CASE).find(title) ?: return null
    val n = m.groupValues[1].lowercase()
    return n.toIntOrNull() ?: words[n]
}

/** Parts of the day as the pills show them; a habit with no time shows in every part. */
fun partAt(time: LocalTime): Part = when {
    time.hour < 12 -> Part.MORNING
    time.hour < 17 -> Part.AFTERNOON
    else -> Part.EVENING
}

const val FREEZES_A_MONTH = 2

/** Freezes left in [month]: two a month across all habits, Duolingo's rule. */
fun freezesLeft(freezes: List<HabitFreezeEntity>, month: YearMonth): Int =
    (FREEZES_A_MONTH - freezes.count { it.day.startsWith(month.toString()) }).coerceAtLeast(0)

/** How full a day was across the habits it had (non-namoz): the week strip's and the calendar's chain. */
enum class Clear { ALL, SOME, NONE, EMPTY, FUTURE }

data class HabitCard(
    val task: TaskEntity,
    val title: String,
    val emoji: String,
    val part: Part,
    val taps: Int,
    val mark: Mark,
    val streak: Int,
    val best: Int,
    val minutes: Int?,
    val neverMissTwice: Boolean,
    /** The habit this one follows ("after ☕ Coffee"), by its title. */
    val after: String?,
    val time: String?,
    val window: Namoz.Window? = null,
    val identity: String? = null,
    /** The tile colour Boss picked (one of HabitLook.COLOURS), else null = by what it is about. */
    val colour: String? = null,
) {
    val times: Int get() = task.times.coerceAtLeast(1)
    val done: Boolean get() = mark == Mark.DONE
}

/**
 * One day of the all-habits chain on top of the Habits tab (Boss 2026-10-29: "when I complete ALL habits, it marks that
 * day"): [held] of [due] habits done or frozen that day; the strip fills a day only at held == due.
 */
data class StripDay(val date: LocalDate, val clear: Clear, val frozen: Boolean = false, val held: Int = 0, val due: Int = 0) {
    val progress: Float get() = if (due == 0) 0f else held.toFloat() / due
}

data class HabitBoard(
    val today: LocalDate,
    val selected: LocalDate,
    val week: List<StripDay>,
    val cards: List<HabitCard>,
    val namoz: List<HabitCard>,
    val freezesLeft: Int,
    /** The Sunday before this week was full too: the bar runs in from the strip's left edge. */
    val chainIn: Boolean = false,
    /** The next Monday is full too: the bar runs out at the right edge. */
    val chainOut: Boolean = false,
) {
    fun inPart(p: Part) = cards.filter { it.part == p || it.part == Part.ANYTIME }
    val doneCount get() = cards.count { it.done }
    val total get() = cards.size
    val namozDone get() = namoz.count { it.done }
}

private fun titleOf(t: TaskEntity) = t.title?.takeIf(String::isNotBlank) ?: t.text.lineSequence().first().trim().removePrefix("#").trim()

private fun ticksByDay(ticks: List<HabitTickEntity>) = ticks.associate { "${it.day} ${it.taskId}" to it.n }

/**
 * The Habits tab on [selected] (today, or a day Boss tapped in the week strip): every habit due that day as a card, the
 * five prayers apart as the Namoz group, and the selected week as the chain strip.
 */
fun habitBoard(
    habits: List<TaskEntity>,
    ticks: List<HabitTickEntity>,
    prefs: List<HabitPrefEntity>,
    freezes: List<HabitFreezeEntity>,
    today: LocalDate,
    selected: LocalDate = today,
    namozWindows: (LocalDate) -> List<Namoz.Window> = Namoz::windows,
): HabitBoard {
    val taps = ticksByDay(ticks)
    val pref = prefs.associateBy { it.taskId }
    val frozenOf = freezes.groupBy({ it.taskId }, { it.day }).mapValues { it.value.toSet() }
    val byId = habits.associateBy { it.id }
    val doneOf = ticks.filter { it.n >= (byId[it.taskId]?.times ?: 1).coerceAtLeast(1) }.groupBy({ it.taskId }, { it.day }).mapValues { it.value.toSet() }
    val (prayers, others) = habits.partition(::isNamoz)
    val windows by lazy { namozWindows(selected) }
    val starts by lazy { startsOf(habits.filterNot(::isNamoz), ticks, today) }

    fun card(t: TaskEntity): HabitCard {
        val title = titleOf(t)
        val p = pref[t.id]
        val done = doneOf[t.id].orEmpty()
        val frozen = frozenOf[t.id].orEmpty()
        val n = taps["$selected ${t.id}"] ?: 0
        val time = t.due?.drop(11)?.trim()?.take(5)?.takeIf(String::isNotEmpty)
        val window = Namoz.prayerOf(title)?.let { pr -> windows.firstOrNull { it.prayer == pr } }
        val part = when {
            window != null -> partAt(window.from)
            time != null -> runCatching { partAt(LocalTime.parse(time)) }.getOrDefault(Part.ANYTIME)
            else -> Part.ANYTIME
        }
        return HabitCard(
            task = t, title = title, emoji = window?.prayer?.emoji ?: Emoji.keyFor(title, p?.emoji), part = part, taps = n,
            mark = markOf(t, selected, today, n, selected.toString() in frozen),
            streak = chainStreak(t, done, frozen, today), best = chainBest(t, done, frozen, today),
            minutes = if (window != null) null else minutesOf(title, p?.minutes),
            neverMissTwice = selected == today && neverMissTwice(t, done, frozen, today),
            after = p?.after?.let { byId[it] }?.let(::titleOf), time = time, window = window, identity = p?.identity,
            colour = HabitLook.colourOf(p?.emoji),
        )
    }

    val dueThatDay = others.filter { repeatsOn(it, selected) }
    val order = compareBy<HabitCard>({ it.part.ordinal }, { it.time ?: "99" }, { it.task.priority })
    // A habit stacked after another sits right under it.
    val cards = stack(dueThatDay.map(::card).sortedWith(order))
    val namoz = prayers.map(::card).sortedBy { it.window?.prayer?.ordinal ?: 9 }

    val monday = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun stripDay(d: LocalDate): StripDay {
        val (held, due) = heldOf(others, taps, frozenOf, d, starts)
        return StripDay(d, clearOf(others, taps, frozenOf, d, today, starts), others.any { d.toString() in frozenOf[it.id].orEmpty() }, held, due)
    }
    val week = (0L..6L).map { i -> stripDay(monday.plusDays(i)) }
    // The chain runs off the strip's edge when it goes on into the week before or after (checker #9, the ref's 26).
    val chainIn = week.first().clear == Clear.ALL && stripDay(monday.minusDays(1)).clear == Clear.ALL
    val chainOut = week.last().clear == Clear.ALL && stripDay(monday.plusDays(7)).clear == Clear.ALL
    return HabitBoard(today, selected, week, cards, namoz, freezesLeft(freezes, YearMonth.from(today)), chainIn, chainOut)
}

/** Puts each habit with an `after` right under the habit it follows (one level; a loop keeps the plain order). */
private fun stack(cards: List<HabitCard>): List<HabitCard> {
    val followers = cards.filter { it.after != null && cards.any { c -> c.title == it.after && c !== it } }
    if (followers.isEmpty()) return cards
    val out = cards.filter { it !in followers }.toMutableList()
    for (f in followers) {
        val at = out.indexOfFirst { it.title == f.after }
        if (at < 0) out += f else out.add(at + 1, f)
    }
    return out
}

/** How many of the habits due on [d] were held (done or frozen), and how many were due. */
fun heldOf(
    habits: List<TaskEntity>, taps: Map<String, Int>, frozen: Map<String, Set<String>>, d: LocalDate, starts: Map<String, LocalDate> = emptyMap(),
): Pair<Int, Int> {
    val due = habits.filter { dueOn(it, d, starts) }
    return due.count { (taps["$d ${it.id}"] ?: 0) >= it.times.coerceAtLeast(1) || d.toString() in frozen[it.id].orEmpty() } to due.size
}

/** A day across [habits]: every due one held = ALL; some = SOME; none = NONE; nothing due = EMPTY; ahead = FUTURE. */
fun clearOf(
    habits: List<TaskEntity>, taps: Map<String, Int>, frozen: Map<String, Set<String>>, d: LocalDate, today: LocalDate,
    starts: Map<String, LocalDate> = emptyMap(),
): Clear {
    if (d.isAfter(today)) return Clear.FUTURE
    val due = habits.filter { dueOn(it, d, starts) }
    if (due.isEmpty()) return Clear.EMPTY
    val held = due.count { (taps["$d ${it.id}"] ?: 0) >= it.times.coerceAtLeast(1) || d.toString() in frozen[it.id].orEmpty() }
    return when {
        held == due.size -> Clear.ALL
        held > 0 || due.any { (taps["$d ${it.id}"] ?: 0) > 0 } -> Clear.SOME
        else -> Clear.NONE
    }
}

/** A month as the chain calendar draws it, for one habit (or all habits when [habit] is null), Monday first. */
fun monthMarks(
    habits: List<TaskEntity>, ticks: List<HabitTickEntity>, freezes: List<HabitFreezeEntity>, month: YearMonth, today: LocalDate, habit: TaskEntity?,
): List<Pair<LocalDate, Mark>> {
    val taps = ticksByDay(ticks)
    val frozen = freezes.groupBy({ it.taskId }, { it.day }).mapValues { it.value.toSet() }
    val starts = startsOf(habit?.let { listOf(it) } ?: habits, ticks, today)
    return (1..month.lengthOfMonth()).map { month.atDay(it) }.map { d ->
        d to if (habit != null) {
            val m = markOf(habit, d, today, taps["$d ${habit.id}"] ?: 0, d.toString() in frozen[habit.id].orEmpty())
            if (m != Mark.DONE && starts[habit.id]?.let { d.isBefore(it) } == true) Mark.BEFORE else m
        }
        else when (clearOf(habits, taps, frozen, d, today, starts)) {
            Clear.ALL -> Mark.DONE
            Clear.SOME -> Mark.PARTIAL
            Clear.NONE -> if (d == today) Mark.TODAY else Mark.MISSED
            Clear.EMPTY -> Mark.OFF
            Clear.FUTURE -> Mark.FUTURE
        }
    }
}

/** A badge on the Achievements page: earned or locked, with its own emoji. */
data class Achievement(val key: String, val emoji: String, val title: String, val line: String, val earned: Boolean)

/** The badges (Boss's reference: "streak 7/30/100, first week cleared…"), from the habits' bests and ticks. */
fun achievements(habits: List<TaskEntity>, ticks: List<HabitTickEntity>, freezes: List<HabitFreezeEntity>, today: LocalDate): List<Achievement> {
    val byId = habits.associateBy { it.id }
    val done = ticks.filter { it.n >= (byId[it.taskId]?.times ?: 1).coerceAtLeast(1) }
    val doneOf = done.groupBy({ it.taskId }, { it.day }).mapValues { it.value.toSet() }
    val frozen = freezes.groupBy({ it.taskId }, { it.day }).mapValues { it.value.toSet() }
    val best = habits.maxOfOrNull { chainBest(it, doneOf[it.id].orEmpty(), frozen[it.id].orEmpty(), today) } ?: 0
    val taps = ticksByDay(ticks)
    val others = habits.filterNot(::isNamoz)
    val starts = startsOf(others, ticks, today)
    val clearedDays = (0L..400L).map { today.minusDays(it) }.filter { clearOf(others, taps, frozen, it, today, starts) == Clear.ALL }.map { it.toString() }.toSet()
    val clearedWeek = (0L..400L).map { today.minusDays(it) }.any { end -> (0L..6L).all { (end.minusDays(it)).toString() in clearedDays } }
    val namozDays = habits.filter(::isNamoz).let { p -> if (p.size < 5) emptySet() else (0L..60L).map { today.minusDays(it) }.filter { d -> p.all { d.toString() in doneOf[it.id].orEmpty() } }.toSet() }
    return listOf(
        Achievement("first", "seedling", "First tick", "Tick any habit once", done.isNotEmpty()),
        Achievement("streak3", "fire", "3 in a row", "A 3-day chain", best >= 3),
        Achievement("streak7", "link", "A week's chain", "A 7-day chain", best >= 7),
        Achievement("cleared", "party", "Day cleared", "Every habit of a day", clearedDays.isNotEmpty()),
        Achievement("week", "trophy", "Week cleared", "7 cleared days in a row", clearedWeek),
        Achievement("streak30", "rocket", "30 in a row", "A 30-day chain", best >= 30),
        Achievement("hundred", "target", "100 ticks", "100 done days in all", done.size >= 100),
        Achievement("freeze", "ice", "Chain held", "A freeze kept a streak", freezes.isNotEmpty()),
        Achievement("namoz", "mosque", "Five in a day", "All five prayers on one day", namozDays.isNotEmpty()),
        Achievement("streak100", "tree", "100 in a row", "A 100-day chain", best >= 100),
    )
}
