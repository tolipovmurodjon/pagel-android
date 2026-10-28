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

/** One habit as the screen draws it. */
data class HabitItem(val task: TaskEntity, val streak: Int)

/**
 * What /habits shows on [today]: the habits due today or late by part of the day, the ones done today with their
 * flames, the ones coming up, and the day's progress. A habit being ticked ([finishing]) keeps its row while its ring
 * fills, then moves to "Done today".
 */
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

fun habitsDay(habits: List<TaskEntity>, ticks: List<HabitTickEntity>, today: String, finishing: Set<String> = emptySet()): HabitsDay {
    val daysOf = ticks.groupBy({ it.taskId }, { it.day }).mapValues { it.value.toSet() }
    val doneToday = ticks.filter { it.day == today }.map { it.taskId }.toSet()
    fun item(t: TaskEntity) = HabitItem(t, streakOf(t.every.orEmpty(), daysOf[t.id].orEmpty(), today) ?: 0)
    val now = habits.filter { it.id in finishing || (it.id !in doneToday && (it.due.isNullOrBlank() || it.due.take(10) <= today)) }
    val byTime = compareBy<TaskEntity>({ it.due?.drop(11).orEmpty() }, { it.priority })
    val parts = Part.entries.map { p -> p to now.filter { Part.of(it.due) == p }.sortedWith(byTime).map(::item) }.filter { it.second.isNotEmpty() }
    val done = habits.filter { it.id in doneToday && it.id !in finishing }.sortedWith(byTime).map(::item)
    val coming = habits.filter { !it.due.isNullOrBlank() && it.due.take(10) > today && it.id !in doneToday }.sortedBy { it.due }
    return HabitsDay(parts, done, coming, doneCount = habits.count { it.id in doneToday }, left = now.count { it.id !in doneToday })
}
