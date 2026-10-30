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

// ── Core round 4 · the week Monday to Sunday, the drag read at the drop ──────────────────────────────────────────────

/** The Monday of [d]'s week (ISO, Monday first): Boss 2026-10-29, "Week shows Monday to Sunday, not today + 6". */
fun weekStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

/**
 * The days timeline page [page] shows (0 = the one holding [today]): Week = Monday to Sunday, one week a page;
 * 3 days = three days a page from today; Day = one day a page.
 */
fun timelineDays(view: CalView, today: LocalDate, page: Int): List<LocalDate> {
    val (start, n) = when (view) {
        CalView.WEEK -> weekStart(today).plusWeeks(page.toLong()) to 7
        CalView.THREE -> today.plusDays(3L * page) to 3
        else -> today.plusDays(page.toLong()) to 1
    }
    return (0 until n).map { start.plusDays(it.toLong()) }
}

/** The timeline page that holds [day] (the inverse of [timelineDays]). */
fun timelinePageOf(view: CalView, today: LocalDate, day: LocalDate): Int = when (view) {
    CalView.WEEK -> Math.floorDiv(java.time.temporal.ChronoUnit.DAYS.between(weekStart(today), weekStart(day)), 7L).toInt()
    CalView.THREE -> Math.floorDiv(java.time.temporal.ChronoUnit.DAYS.between(today, day), 3L).toInt()
    else -> java.time.temporal.ChronoUnit.DAYS.between(today, day).toInt()
}

/**
 * The timeline's columns where they are now, in one space (the screen's): [left]/[top] = 00:00 of the first column,
 * [width] = all columns, [days] = the page shown now. Read when it is needed, never kept from the lift.
 */
data class Grid(val left: Float, val top: Float, val width: Float, val hourPx: Float, val days: List<LocalDate>)

/** A day column and a 15-minute slot on it. */
data class Slot(val day: LocalDate, val minute: Int)

/**
 * A block held on the timeline (core round 4): only the lift is kept (which block, how far below its top the finger
 * held it, a move or a resize of its end). Where it lands is read from [Grid] as it is at that moment: round 0.3.33's
 * drop used a target taken at the lift, stale after a swipe or a scroll.
 */
class BlockDrag(val block: Block, val resize: Boolean, private val grab: Float) {
    /** The slot under [x], with the block's top (a move) or its end (a resize) at [y]; a finger beside the grid counts for the edge column. */
    fun slot(g: Grid, x: Float, y: Float): Slot? {
        if (g.width <= 0f || g.days.isEmpty() || g.hourPx <= 0f) return null
        val col = ((x - g.left) / (g.width / g.days.size)).toInt().coerceIn(0, g.days.size - 1)
        val minutes = (y - grab - g.top) / g.hourPx * 60f
        return Slot(g.days[col], if (resize) snapEnd(minutes) else snap15(minutes))
    }

    /** The due the drop makes, or null when nothing changes (it went back where it was). */
    fun dueAt(g: Grid, x: Float, y: Float): String? {
        val s = slot(g, x, y) ?: return null
        val due = if (resize) resizedDue(block.task.due, block.start, s.minute) else droppedDue(block.task.due, s.day, s.minute)
        return due.takeIf { it != block.task.due }
    }

    companion object {
        /** Lifted at [y] (the screen's), the block's top or end then at [edgeY]. */
        fun lift(block: Block, resize: Boolean, y: Float, edgeY: Float) = BlockDrag(block, resize, y - edgeY)
    }
}

/** An end snapped to the 15-minute grid, as late as 23:55 (pagel.uz keeps a range inside its day). */
fun snapEnd(minutes: Float): Int = minOf((Math.round(minutes / 15f) * 15).coerceAtLeast(15), 23 * 60 + 55)

/** A block's end dragged to [end]: it stays at least 15 minutes after [start]; the due becomes a range on its own day. */
fun resizedDue(due: String?, start: Int, end: Int): String {
    val day = due.orEmpty().take(10)
    val e = end.coerceIn(start + 15, 23 * 60 + 55)
    return "$day ${hhmm(start)}-${hhmm(e)}"
}

/** The block under a long-press: its time holds [minute] and its lane holds [laneX] (0 to 1 across the column). */
fun blockAt(blocks: List<Block>, minute: Float, laneX: Float, minMinutes: Int): Block? = blocks.lastOrNull {
    minute >= it.start && minute < maxOf(it.end, it.start + minMinutes) && laneX >= it.lane.toFloat() / it.lanes && laneX < (it.lane + 1f) / it.lanes
}

/** The month grid's day under [x], [y] (the grid's own space), read at the drop; null off the grid. */
fun monthDayAt(x: Float, y: Float, width: Float, height: Float, weeks: List<List<LocalDate>>): LocalDate? {
    if (width <= 0f || height <= 0f || weeks.isEmpty() || x < 0f || y < 0f || x >= width || y >= height) return null
    val row = (y / (height / weeks.size)).toInt().coerceIn(0, weeks.size - 1)
    val col = (x / (width / 7f)).toInt().coerceIn(0, 6)
    return weeks[row][col]
}

/**
 * One day of the week grid (Boss 2026-10-29: "habits are NOT in the all-day row"): the timed blocks (a daily habit only
 * in the Day view, never draggable) and the untimed tasks for the All-day line; habits without a time stay on Habits.
 */
fun gridDay(tasks: List<TaskEntity>, dayView: Boolean): DayLayout {
    val l = layoutDay(tasks)
    val blocks = if (dayView) l.blocks else lanes(l.blocks.filter { it.task.every != "day" }.map { it.copy(lane = 0, lanes = 1) })
    return DayLayout(blocks, l.allDay.filter { it.every.isNullOrBlank() }, l.dailyHabits + l.blocks.count { it.task.every == "day" && !dayView })
}
