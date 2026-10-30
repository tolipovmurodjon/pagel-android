package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.upcoming.BlockDrag
import org.pagel.ai.data.upcoming.CalView
import org.pagel.ai.data.upcoming.Grid
import org.pagel.ai.data.upcoming.Slot
import org.pagel.ai.data.upcoming.blockAt
import org.pagel.ai.data.upcoming.blockOf
import org.pagel.ai.data.upcoming.gridDay
import org.pagel.ai.data.upcoming.monthDayAt
import org.pagel.ai.data.upcoming.resizedDue
import org.pagel.ai.data.upcoming.snapEnd
import org.pagel.ai.data.upcoming.timelineDays
import org.pagel.ai.data.upcoming.timelinePageOf
import org.pagel.ai.data.upcoming.weekStart
import org.pagel.ai.data.upcoming.droppedDue
import org.pagel.ai.data.upcoming.layoutDay
import org.pagel.ai.data.upcoming.monthWeeks
import org.pagel.ai.data.upcoming.pageStart
import org.pagel.ai.data.upcoming.snap15
import org.pagel.ai.data.upcoming.tasksOn
import java.time.LocalDate
import java.time.YearMonth

/** Round C: the calendar's numbers (blocks, lanes, drops, the month grid, the strip's pages). */
class CalendarLogicTest {
    private val day = LocalDate.of(2026, 10, 28)
    private fun task(id: String, due: String?, every: String? = null) = TaskEntity(
        id = id, project = "pagel", phase = "Now", sort = 1.0, text = id, title = id, status = "open", priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
    )

    @Test fun aRangeIsATallBlockAndATimeIsHalfAnHour() {
        assertEquals(600 to 720, blockOf(task("a", "2026-10-28 10:00-12:00"))!!.let { it.start to it.end })
        assertEquals(900 to 930, blockOf(task("b", "2026-10-28 15:00"))!!.let { it.start to it.end })
        assertNull(blockOf(task("c", "2026-10-28")))
    }

    @Test fun overlapsShareTheWidthAndUntimedGoToAllDay() {
        val l = layoutDay(listOf(task("a", "2026-10-28 10:00-12:00"), task("b", "2026-10-28 11:00"), task("c", "2026-10-28 13:00"),
            task("d", "2026-10-28"), task("h", "2026-10-28", "day")))
        assertEquals(listOf("a" to (0 to 2), "b" to (1 to 2), "c" to (0 to 1)), l.blocks.map { it.task.id to (it.lane to it.lanes) })
        assertEquals(listOf("d"), l.allDay.map { it.id })
        assertEquals(1, l.dailyHabits)
    }

    @Test fun aDropKeepsARangesLengthInsideItsDay() {
        assertEquals("2026-10-30 14:00-16:00", droppedDue("2026-10-28 10:00-12:00", day.plusDays(2), 840))
        assertEquals("2026-10-28 22:30-23:55", droppedDue("2026-10-28 10:00-12:00", day, 1350))
        assertEquals("2026-10-29 09:15", droppedDue("2026-10-28 15:00", day.plusDays(1), 555))
        assertEquals("2026-10-29 09:00", droppedDue("2026-10-28", day.plusDays(1), 540))
        assertEquals(555, snap15(560f))
    }

    /** Boss 2026-10-29: "Week shows the whole week from Monday to Sunday, not the current day + 6". */
    @Test fun theWeekIsMondayToSundayAndPagesByWeek() {
        val thu = LocalDate.of(2026, 10, 29)
        assertEquals(LocalDate.of(2026, 10, 26), weekStart(thu))
        assertEquals((26..31).map { LocalDate.of(2026, 10, it) } + LocalDate.of(2026, 11, 1), timelineDays(CalView.WEEK, thu, 0))
        assertEquals(LocalDate.of(2026, 11, 2), timelineDays(CalView.WEEK, thu, 1).first())
        assertEquals(LocalDate.of(2026, 10, 25), timelineDays(CalView.WEEK, thu, -1).last())
        // Sunday 1 November is still this week; Monday 2 November is the next page; the page of a day is the inverse.
        assertEquals(0, timelinePageOf(CalView.WEEK, thu, LocalDate.of(2026, 11, 1)))
        assertEquals(1, timelinePageOf(CalView.WEEK, thu, LocalDate.of(2026, 11, 2)))
        assertEquals(-1, timelinePageOf(CalView.WEEK, thu, LocalDate.of(2026, 10, 25)))
        assertEquals(listOf(thu.plusDays(3), thu.plusDays(4), thu.plusDays(5)), timelineDays(CalView.THREE, thu, 1))
        assertEquals(-1, timelinePageOf(CalView.THREE, thu, thu.minusDays(1)))
        assertEquals(listOf(thu.plusDays(2)), timelineDays(CalView.DAY, thu, 2))
    }

    @Test fun snapsAreFifteenMinutesAndAnEndStaysInsideItsDay() {
        assertEquals(600, snap15(607f))
        assertEquals(615, snap15(608f))
        assertEquals(0, snap15(-40f))
        assertEquals(23 * 60 + 45, snap15(24 * 60f))
        assertEquals(23 * 60 + 55, snapEnd(24 * 60f))
        assertEquals(15, snapEnd(0f))
        assertEquals("2026-10-28 10:00-11:30", resizedDue("2026-10-28 10:00", 600, 690))
        // Never shorter than 15 minutes.
        assertEquals("2026-10-28 10:00-10:15", resizedDue("2026-10-28 10:00-12:00", 600, 560))
    }

    /**
     * Round 0.3.33's trap: a drop target taken at the lift is stale. The block is lifted on this week's grid, then the
     * week pages on and the grid scrolls; the drop must land where the finger is on the grid as it is at the drop.
     */
    @Test fun theDropIsReadFromTheGridAtTheDropNotAtTheLift() {
        val hour = 52f
        val wk = timelineDays(CalView.WEEK, day, 0)
        val lifted = Grid(left = 36f, top = 100f, width = 280f, hourPx = hour, days = wk)
        val block = blockOf(task("a", "2026-11-01 10:00"))!!
        // The finger holds the block 13 px below its top (10:00 on the lifted grid).
        val drag = BlockDrag.lift(block, resize = false, y = lifted.top + 10 * hour + 13f, edgeY = lifted.top + 10 * hour)
        val thursdayX = 36f + 40f * 3 + 20f
        // Next week now, and the grid scrolled 300 px up: Thursday of THAT week at 14:30.
        val now = Grid(left = 36f, top = -200f, width = 280f, hourPx = hour, days = timelineDays(CalView.WEEK, day, 1))
        val y = now.top + 14.5f * hour + 13f
        assertEquals(Slot(LocalDate.of(2026, 11, 5), 870), drag.slot(now, thursdayX, y))
        assertEquals("2026-11-05 14:30", drag.dueAt(now, thursdayX, y))
        // Read on the lifted grid instead, the same finger means another day and time: that was the bug.
        assertEquals(Slot(LocalDate.of(2026, 10, 29), 525), drag.slot(lifted, thursdayX, y))
        // Back where it was: nothing to send.
        assertNull(drag.dueAt(lifted, 36f + 40f * 6 + 5f, lifted.top + 10 * hour + 13f))
        // A finger beside the columns counts for the edge column.
        assertEquals(LocalDate.of(2026, 11, 8), drag.slot(now, 400f, y)!!.day)
    }

    @Test fun aResizeMovesOnlyTheEndAndAHabitIsFoundButTheCallerRefusesIt() {
        val hour = 52f
        val g = Grid(left = 0f, top = 0f, width = 70f, hourPx = hour, days = listOf(day))
        val block = blockOf(task("r", "2026-10-28 10:00-11:00"))!!
        val drag = BlockDrag.lift(block, resize = true, y = 11 * hour - 4f, edgeY = 11 * hour)
        assertEquals("2026-10-28 10:00-12:30", drag.dueAt(g, 30f, 12.5f * hour - 4f))
        val gym = blockOf(task("g", "2026-10-28 18:00", "tue,thu,sun"))!!
        assertEquals(true, blockAt(listOf(block, gym), 18 * 60 + 10f, 0.5f, 25)!!.habit)
        assertEquals("r", blockAt(listOf(block, gym), 630f, 0.5f, 25)!!.task.id)
        assertNull(blockAt(listOf(block, gym), 700f, 0.5f, 25))
    }

    @Test fun theMonthGridFindsTheDayUnderTheFingerAtTheDrop() {
        val weeks = monthWeeks(YearMonth.of(2026, 10))
        assertEquals(LocalDate.of(2026, 9, 28), monthDayAt(1f, 1f, 350f, 360f, weeks))
        // The fourth row (19-25 Oct), the fourth column (Thursday 22).
        assertEquals(LocalDate.of(2026, 10, 22), monthDayAt(50f * 3 + 25f, 60f * 3 + 30f, 350f, 360f, weeks))
        assertNull(monthDayAt(-1f, 10f, 350f, 360f, weeks))
        assertNull(monthDayAt(10f, 360f, 350f, 360f, weeks))
    }

    @Test fun habitsStayOutOfTheAllDayLineAndDailyOnesOutOfTheWeek() {
        val tasks = listOf(task("t", "2026-10-28"), task("h", "2026-10-28", "day"), task("w", "2026-10-28 07:00", "day"), task("g", "2026-10-28 18:00", "wed"))
        val week = gridDay(tasks, dayView = false)
        assertEquals(listOf("t"), week.allDay.map { it.id })
        assertEquals(listOf("g"), week.blocks.map { it.task.id })
        val dayV = gridDay(tasks, dayView = true)
        assertEquals(listOf("w", "g"), dayV.blocks.map { it.task.id })
    }

    @Test fun habitsFallOnTheirDaysAndTheMonthHasSixWeeks() {
        val gym = task("g", "2026-10-27 18:00", "tue,thu,sun")
        assertEquals(listOf("g"), tasksOn(listOf(gym), LocalDate.of(2026, 10, 29)).map { it.id })
        assertEquals(emptyList<String>(), tasksOn(listOf(gym), day).map { it.id })
        val w = monthWeeks(YearMonth.of(2026, 10))
        assertEquals(6, w.size)
        assertEquals(LocalDate.of(2026, 9, 28), w[0][0])
        assertEquals(day.plusDays(7), pageStart(day, 1))
    }
}
