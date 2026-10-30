package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pagel.ai.data.api.KpiDayDto
import org.pagel.ai.data.api.KpiWeekReply
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.projects.WeekCounts
import org.pagel.ai.data.projects.daysTakenOf
import org.pagel.ai.data.projects.weekCounts
import org.pagel.ai.ui.projects.finishSummary
import org.pagel.ai.ui.projects.serverLine
import java.time.Instant

/** "Your week" counted on the phone, and the finish moment's summary (core round: (e) + (f)). */
class ProjectWeekTest {
    // Thursday 29 Oct 2026, 12:00 in Tashkent: the week is Fri 23 .. Thu 29.
    private val now = Instant.parse("2026-10-29T07:00:00Z").toEpochMilli()

    private fun task(id: String, project: String, doneAt: String?, status: String = "done", every: String? = null, times: Int = 1) =
        TaskEntity(id, project, "Now", 0.0, "Task $id", null, status, 2, false, null, every, null, doneAt, "", 1, lastModified = 0, times = times)

    @Test fun weekCountsTasksProjectsAndKeptHabitDays() {
        val done = listOf(
            task("a", "games", "2026-10-29T05:00:00Z"),
            task("b", "games", "2026-10-24T10:00:00Z"),
            task("c", "inbox", "2026-10-25T10:00:00Z"), // counts as done, but Inbox is not a project
            task("d", "pagel", "2026-10-22T20:00:00Z"), // 01:00 on the 23rd in Tashkent: inside the week
            task("e", "pagel", "2026-10-22T10:00:00Z"), // 15:00 on the 22nd: one day too old
            task("f", "games", "2026-10-28T10:00:00Z", every = "daily"), // a habit is not a task
            task("g", "games", null, status = "open"),
        )
        val habits = listOf(task("h1", "health", null, "open", "daily"), task("h2", "health", null, "open", "daily", times = 2))
        val ticks = listOf(
            HabitTickEntity("2026-10-29", "h1", 1), // kept
            HabitTickEntity("2026-10-28", "h2", 1), // 1 of 2 taps: not kept yet
            HabitTickEntity("2026-10-27", "h2", 2), // kept
            HabitTickEntity("2026-10-20", "h1", 1), // last week
            HabitTickEntity("2026-10-29", "gone", 1), // a habit that no longer exists
        )
        assertEquals(WeekCounts(done = 4, projectsMoved = 2, habitsKept = 2), weekCounts(done, habits, ticks, now))
    }

    @Test fun emptyWeekIsZeroes() = assertEquals(WeekCounts(0, 0, 0), weekCounts(emptyList(), emptyList(), emptyList(), now))

    @Test fun daysTakenCountsFromTheFirstFinishedTaskToToday() {
        assertEquals(10, daysTakenOf(listOf(task("a", "p", "2026-10-27T10:00:00Z"), task("b", "p", "2026-10-20T10:00:00Z"), task("c", "p", null, "open")), now))
        assertEquals(1, daysTakenOf(listOf(task("a", "p", "2026-10-29T03:00:00Z")), now))
        assertNull(daysTakenOf(listOf(task("a", "p", null, "open")), now))
    }

    @Test fun finishSummaryReadsPlainly() {
        assertEquals("8 tasks done over 12 days.", finishSummary(8, 12))
        assertEquals("1 task done in 1 day.", finishSummary(1, 1))
        assertEquals("3 tasks done.", finishSummary(3, null))
    }

    @Test fun serverLineIsOneQuietLine() {
        val w = KpiWeekReply(days = listOf(KpiDayDto("2026-10-29", firstTickMs = 80_000)), projectsFinished = 2, daysOpened = 5)
        assertEquals("2 closed · opened 5/7 days · first tick 1:20", serverLine(w))
        assertEquals("0 closed · opened 0/7 days", serverLine(KpiWeekReply()))
    }
}
