package org.pagel.ai.data.upcoming

import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.ui.glass.Repeat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** pagel.uz counts its days in Tashkent (views.ts dayOf); done_at is UTC, so 23:30 UTC is already tomorrow there. */
val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")

/** One day of Upcoming: its date and the open tasks on it (a habit on every day it falls on, as the web). */
data class UpcomingDay(val day: LocalDate, val tasks: List<TaskEntity>)

/** The web's /upcoming: overdue on top (oldest first), then the next [days] days from today. */
data class UpcomingView(val overdue: List<TaskEntity>, val days: List<UpcomingDay>)

private val byUrgency = compareBy<TaskEntity>({ it.priority }, { it.due.orEmpty() })

/** views.ts `upcoming` + the overdue filter of upcoming/page.tsx, over Room's open dated tasks. */
fun upcomingView(open: List<TaskEntity>, today: LocalDate, days: Int = 7): UpcomingView {
    val dated = open.filter { it.status == "open" && (it.due?.length ?: 0) >= 10 }
    val t = today.toString()
    val overdue = dated.filter { it.due!!.take(10) < t }.sortedBy { it.due }
    val list = (0 until days).map { i ->
        val day = today.plusDays(i.toLong())
        val d = day.toString()
        UpcomingDay(day, dated.filter { task ->
            val every = task.every?.takeIf(String::isNotBlank)
            if (every != null) Repeat.datesBetween(task.due!!, every, d, d).isNotEmpty() else task.due!!.take(10) == d
        }.sortedWith(byUrgency))
    }
    return UpcomingView(overdue, list)
}

/**
 * A day's rows and its folded daily habits. Today and Tomorrow ([index] 0, 1) show everything; a later day folds its
 * daily habits into one quiet line (Boss 2026-10-28: the same five habits on every day was a wall). A habit on chosen
 * days (Gym tue,thu,sun; weigh-in on Mondays) stays a row: that day is what it says.
 */
fun foldDaily(tasks: List<TaskEntity>, index: Int): Pair<List<TaskEntity>, List<TaskEntity>> =
    if (index <= 1) tasks to emptyList() else tasks.partition { it.every != "day" }

/** "5 daily habits", the folded line's words. */
fun dailyLine(n: Int): String = if (n == 1) "1 daily habit" else "$n daily habits"

/**
 * Upcoming's count line: the tasks in it, each once ("5 tasks · 2 late"). A habit is not counted: it shows on every day
 * it falls on, so counting rows said "21 planned" for 4 tasks (checker P4 #4); the web shows no count at all.
 */
fun plannedLine(view: UpcomingView): String {
    val tasks = (view.overdue + view.days.flatMap { it.tasks }).filter { it.every.isNullOrBlank() }.mapTo(HashSet()) { it.id }.size
    val late = view.overdue.count { it.every.isNullOrBlank() }
    val head = if (tasks == 1) "1 task" else "$tasks tasks"
    return if (late > 0) "$head · $late late" else head
}

/**
 * Completed's "N tasks done". pagel.uz's [total] (GET /api/v1/completed) counts every finished task, as the web does;
 * Room holds only some of them. Changes after that answer ([room] now vs [roomAtTotal] then: a tick, an untick) move
 * it by the same amount. With no total yet (offline, an older pagel.uz) it is what Room has.
 */
fun doneCount(room: Int, total: Int?, roomAtTotal: Int): Int = if (total == null) room else maxOf(room, total + room - roomAtTotal)

/** A task dropped on [day]: its time stays ("2026-10-29 10:00" on Saturday → "2026-10-31 10:00"). */
fun movedTo(due: String?, day: LocalDate): String = day.toString() + (due?.drop(10)?.takeIf(String::isNotBlank) ?: "")

/**
 * The week strip's day under the finger. [x], [y] are in the strip's own space; a finger [slop] above or below the strip
 * still counts (a thumb covers the cell it aims at). Null off the strip.
 */
fun stripDayAt(x: Float, y: Float, width: Float, height: Float, days: Int, slop: Float): Int? {
    if (width <= 0f || days <= 0 || x < 0f || x >= width || y < -slop || y > height + slop) return null
    return (x / (width / days)).toInt().coerceIn(0, days - 1)
}

/** The Tashkent day a task was finished; done_at is an ISO instant (sync.ts), an older plain date is read as it is. */
fun doneDayOf(doneAt: String): LocalDate? =
    runCatching { Instant.parse(doneAt).atZone(TASHKENT).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(doneAt.take(10)) }.getOrNull()

private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

/** When it was finished, Tashkent time ("14:32"); empty when done_at has no time. */
fun doneTimeOf(doneAt: String): String = runCatching { Instant.parse(doneAt).atZone(TASHKENT).format(HM) }.getOrDefault("")

private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.UK)
private val DATE = DateTimeFormatter.ofPattern("d MMMM", Locale.UK)
private val DATE_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)

/** Today, Yesterday, the weekday inside the last week, then the date (with its year when it is another year). */
fun doneDayLabel(day: LocalDate, today: LocalDate): String = when {
    day == today -> "Today"
    day == today.minusDays(1) -> "Yesterday"
    day > today.minusDays(7) && day < today -> day.format(WEEKDAY)
    day.year == today.year -> day.format(DATE)
    else -> day.format(DATE_YEAR)
}

/** A day of Completed: its label and its tasks, newest first. */
data class DoneGroup(val day: LocalDate, val label: String, val tasks: List<TaskEntity>)

/** Finished tasks grouped by the Tashkent day they were finished, newest day first (the web's /completed, by day). */
fun completedGroups(done: List<TaskEntity>, today: LocalDate): List<DoneGroup> =
    done.mapNotNull { t -> t.doneAt?.let(::doneDayOf)?.let { it to t } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap(compareByDescending { it })
        .map { (day, tasks) -> DoneGroup(day, doneDayLabel(day, today), tasks.sortedByDescending { it.doneAt }) }
