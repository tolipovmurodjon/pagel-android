package org.pagel.ai.data.habits

import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.ui.glass.Repeat
import java.time.LocalDate

/** The web's parts of the day (views.ts HabitPart) with its icons (habits/page.tsx ICON). */
enum class Part(val label: String, val icon: String) {
    MORNING("Morning", "🌅"), AFTERNOON("Afternoon", "☀️"), EVENING("Evening", "🌙"), ANYTIME("Anytime", "🔁");

    companion object {
        /** Before 12 is Morning, before 17 Afternoon, later Evening; no time is Anytime (views.ts habitsView). */
        fun of(due: String?): Part {
            val hm = due?.drop(11)?.trim().orEmpty()
            return when {
                hm.isEmpty() -> ANYTIME
                hm < "12:00" -> MORNING
                hm < "17:00" -> AFTERNOON
                else -> EVENING
            }
        }
    }
}

/** One day of a habit's last week (round B: "a habit tracker fully when missed"). */
enum class Dot { DONE, PARTIAL, MISSED, OFF, TODAY }

/**
 * One habit as the screen draws it: [taps] today (of its times), its last 7 days oldest first, the best run, and its
 * [slot] (the date it shows for: a daily habit missed yesterday shows today's slot, never a red date).
 */
data class HabitItem(
    val task: TaskEntity,
    val streak: Int,
    val taps: Int = 0,
    val week: List<Dot> = emptyList(),
    val best: Int = 0,
    val slot: String? = task.due,
) {
    val times: Int get() = task.times.coerceAtLeast(1)
}

data class HabitsDay(
    val parts: List<Pair<Part, List<HabitItem>>>,
    val done: List<HabitItem>,
    val coming: List<TaskEntity>,
    val doneCount: Int,
    val left: Int,
) {
    val total get() = doneCount + left
    val allDone get() = left == 0 && doneCount > 0
    val best get() = done.maxOfOrNull { it.streak } ?: 0
}

/** The web's day number (getDay()): Sunday 0 … Saturday 6. */
private fun jsDay(d: LocalDate) = d.dayOfWeek.value % 7

/**
 * The scheduled days in a row a habit was done (the web's streakOf, Duolingo's rule): only the days it repeats on
 * count, and today not done yet does not break it. Week- and month-long repeats have no day streak (null).
 */
fun streakOf(every: String, days: Set<String>, today: String): Int? {
    val on = when (every) {
        "day" -> (0..6).toList()
        "weekday" -> (1..5).toList()
        else -> Repeat.daysOf(every)
    }
    if (on.isEmpty()) return null
    var n = 0
    var d = LocalDate.parse(today)
    repeat(400) {
        if (jsDay(d) in on) {
            val ymd = d.toString()
            if (ymd in days) n++ else if (ymd != today) return n
        }
        d = d.minusDays(1)
    }
    return n
}

/** The web's repeat days for a streak: every day, Mon-Fri, or a set of days; none for week/month repeats. */
private fun daysOn(every: String): List<Int> = when (every) {
    "day" -> (0..6).toList()
    "weekday" -> (1..5).toList()
    else -> Repeat.daysOf(every)
}

/** The longest run of scheduled days done, back to 400 days (the panel's and the card's "best"). */
fun bestStreak(every: String, days: Set<String>, today: String): Int {
    val on = daysOn(every)
    if (on.isEmpty()) return 0
    var best = 0
    var run = 0
    var d = LocalDate.parse(today).minusDays(399)
    val end = LocalDate.parse(today)
    while (!d.isAfter(end)) {
        if (jsDay(d) in on) { if (d.toString() in days) { run++; best = maxOf(best, run) } else if (d != end) run = 0 }
        d = d.plusDays(1)
    }
    return best
}

/**
 * Where a habit shows on [today]: its own date, or for a habit whose date passed, its first scheduled day from today on
 * (a daily one: today; Tue/Thu/Sun missed on Tue: Thursday). A miss breaks the streak; it never makes the row red.
 */
fun slotOf(t: TaskEntity, today: String): String? {
    val due = t.due?.takeIf { it.length >= 10 } ?: return t.due
    val every = t.every?.takeIf(String::isNotBlank) ?: return due
    return if (due.take(10) < today) Repeat.nextDue(due, every, today) else due
}

fun habitsDay(habits: List<TaskEntity>, ticks: List<HabitTickEntity>, today: String, finishing: Set<String> = emptySet()): HabitsDay {
    val taps = ticks.associate { "${it.day} ${it.taskId}" to it.n }
    val byId = habits.associateBy { it.id }
    // A day counts once the habit reached its times that day (1 for most).
    val doneDays = ticks.filter { it.n >= (byId[it.taskId]?.times ?: 1).coerceAtLeast(1) }.groupBy({ it.taskId }, { it.day }).mapValues { it.value.toSet() }
    val doneToday = habits.filter { today in doneDays[it.id].orEmpty() }.map { it.id }.toSet()
    val week = (6 downTo 0).map { LocalDate.parse(today).minusDays(it.toLong()) }
    fun item(t: TaskEntity): HabitItem {
        val done = doneDays[t.id].orEmpty()
        val on = daysOn(t.every.orEmpty())
        val dots = week.map { d ->
            val ymd = d.toString()
            when {
                ymd in done -> Dot.DONE
                (taps["$ymd ${t.id}"] ?: 0) > 0 -> Dot.PARTIAL
                ymd == today -> Dot.TODAY
                jsDay(d) in on -> Dot.MISSED
                else -> Dot.OFF
            }
        }
        return HabitItem(t, streakOf(t.every.orEmpty(), done, today) ?: 0, taps["$today ${t.id}"] ?: 0, dots, bestStreak(t.every.orEmpty(), done, today), slotOf(t, today))
    }
    val slots = habits.associate { it.id to slotOf(it, today) }
    val now = habits.filter { it.id in finishing || (it.id !in doneToday && (slots[it.id].isNullOrBlank() || slots[it.id]!!.take(10) <= today)) }
    val byTime = compareBy<TaskEntity>({ it.due?.drop(11).orEmpty() }, { it.priority })
    val parts = Part.entries.map { p -> p to now.filter { Part.of(it.due) == p }.sortedWith(byTime).map(::item) }.filter { it.second.isNotEmpty() }
    val done = habits.filter { it.id in doneToday && it.id !in finishing }.sortedWith(byTime).map(::item)
    // A habit whose ring is still filling is not "coming up" yet, though its date already moved (checker #5). Coming
    // up shows each habit at its slot (a missed Tue/Thu/Sun habit: its next day, not the missed one).
    val coming = habits.filter { !slots[it.id].isNullOrBlank() && slots[it.id]!!.take(10) > today && it.id !in doneToday && it.id !in finishing }
        .map { it.copy(due = slots[it.id]) }.sortedBy { it.due }
    // A habit whose ring is filling counts as done at the tap: Room's habits and its ticks are two reads, and the
    // header said "0 of 3" in the frame between them (checker 2026-10-28 #5, walk 35).
    return HabitsDay(parts, done, coming, doneCount = habits.count { it.id in doneToday || it.id in finishing }, left = now.count { it.id !in doneToday && it.id !in finishing })
}
