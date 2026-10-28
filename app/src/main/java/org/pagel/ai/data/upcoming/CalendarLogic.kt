package org.pagel.ai.data.upcoming

import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.ui.glass.Repeat
import java.time.LocalDate
import java.time.YearMonth

/**
 * The calendar's numbers (round C, Boss: "week calendar view like Google's, many view formats, colouring"): which tasks
 * fall on a day, where a timed task sits on the 24 h timeline, side-by-side lanes for overlaps, and the due a drop makes.
 * Plain logic, tested by itself (CalendarLogicTest).
 */

/** The views, in the switch's order (Google Calendar on Android: Schedule, Day, 3 days, Week, Month). */
enum class CalView(val label: String, val days: Int) { SCHEDULE("List", 7), DAY("Day", 1), THREE("3 days", 3), WEEK("Week", 7), MONTH("Month", 0) }

/** A timed task on the timeline: minutes from midnight, [lane] of [lanes] side by side, [habit] = not draggable. */
data class Block(val task: TaskEntity, val start: Int, val end: Int, val habit: Boolean, val lane: Int = 0, val lanes: Int = 1)

/** "10:00" → 600; null when it is not a 24 h time. */
fun minutesOf(hhmm: String): Int? {
    val m = Regex("""^(\d{2}):(\d{2})$""").matchEntire(hhmm.trim()) ?: return null
    val (h, mi) = m.destructured
    return (h.toInt() * 60 + mi.toInt()).takeIf { h.toInt() < 24 && mi.toInt() < 60 }
}

fun hhmm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/** A time with no end is drawn 30 minutes long (Google draws an event without an end the same way). */
const val DEFAULT_BLOCK_MIN = 30

/** Where [t] sits on the timeline: its range ("10:00-12:00"), or its time + 30 min; null when it has no time. */
fun blockOf(t: TaskEntity): Block? {
    val time = t.due?.drop(11)?.trim().orEmpty()
    val start = minutesOf(time.take(5)) ?: return null
    val end = time.substringAfter('-', "").let(::minutesOf)?.takeIf { it > start } ?: minOf(start + DEFAULT_BLOCK_MIN, 24 * 60)
    return Block(t, start, end, habit = !t.every.isNullOrBlank())
}

/** The open tasks on [day]: a dated task on its date, a habit on each day its repeat falls on (as Upcoming). */
fun tasksOn(open: List<TaskEntity>, day: LocalDate): List<TaskEntity> {
    val d = day.toString()
    return open.filter { t ->
        if (t.status != "open" || (t.due?.length ?: 0) < 10) return@filter false
        val every = t.every?.takeIf(String::isNotBlank)
        if (every != null) Repeat.datesBetween(t.due!!, every, d, d).isNotEmpty() else t.due!!.take(10) == d
    }.sortedWith(compareBy({ it.due?.drop(11).orEmpty() }, { it.priority }))
}

/**
 * One day split for the timeline: timed blocks with their lanes (overlapping blocks share the width, as Google), the
 * untimed tasks for the "All day" row, and the daily habits without a time, folded to one count.
 */
data class DayLayout(val blocks: List<Block>, val allDay: List<TaskEntity>, val dailyHabits: Int)

fun layoutDay(tasks: List<TaskEntity>): DayLayout {
    val timed = tasks.mapNotNull(::blockOf).sortedWith(compareBy({ it.start }, { -it.end }))
    val untimed = tasks.filter { blockOf(it) == null }
    val (daily, rest) = untimed.partition { it.every == "day" }
    return DayLayout(lanes(timed), rest, daily.size)
}

/** Side-by-side lanes: blocks that overlap in time share a cluster; each gets the first free lane. */
fun lanes(sorted: List<Block>): List<Block> {
    val out = ArrayList<Block>(sorted.size)
    var cluster = ArrayList<Block>()
    var clusterEnd = -1
    fun flush() {
        val n = (cluster.maxOfOrNull { it.lane } ?: 0) + 1
        cluster.forEach { out += it.copy(lanes = n) }
        cluster = ArrayList()
    }
    for (b in sorted) {
        if (b.start >= clusterEnd && cluster.isNotEmpty()) { flush(); clusterEnd = -1 }
        val busy = cluster.filter { it.end > b.start }.map { it.lane }.toSet()
        val lane = generateSequence(0) { it + 1 }.first { it !in busy }
        cluster += b.copy(lane = lane)
        clusterEnd = maxOf(clusterEnd, b.end)
    }
    if (cluster.isNotEmpty()) flush()
    return out
}

/** Minutes snapped to the 15-minute grid a drag moves on. */
fun snap15(minutes: Float): Int = ((minutes / 15f).let { Math.round(it) } * 15).coerceIn(0, 24 * 60 - 15)

/**
 * The due a block dropped on [day] at [start] makes: a range keeps its length ("10:00-12:00" dropped at 14:00 =
 * "14:00-16:00"), never past 23:55 (a range stays inside its day, as pagel.uz checks); a single time moves.
 */
fun droppedDue(due: String?, day: LocalDate, start: Int): String {
    val time = due?.drop(11)?.trim().orEmpty()
    val s = minutesOf(time.take(5))
    val e = time.substringAfter('-', "").let(::minutesOf)
    val at = start.coerceIn(0, 23 * 60 + 45)
    if (s == null || e == null || e <= s) return "$day ${hhmm(at)}"
    val end = minOf(at + (e - s), 23 * 60 + 55)
    return if (end > at) "$day ${hhmm(at)}-${hhmm(end)}" else "$day ${hhmm(at)}"
}

/** The month grid's six weeks (Monday first), as the date sheet's. */
fun monthWeeks(m: YearMonth): List<List<LocalDate>> {
    val first = m.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    return (0 until 6).map { w -> (0 until 7).map { start.plusDays((w * 7 + it).toLong()) } }
}

/** The strip's page [page] (0 = this one): 7 days from today + 7 × page, so page 0 is today and the next 6 (Upcoming). */
fun pageStart(today: LocalDate, page: Int): LocalDate = today.plusDays(7L * page)
