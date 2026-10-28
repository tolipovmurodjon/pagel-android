package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.upcoming.blockOf
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
