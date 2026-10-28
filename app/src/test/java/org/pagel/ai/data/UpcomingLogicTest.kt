package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.upcoming.completedGroups
import org.pagel.ai.data.upcoming.doneDayLabel
import org.pagel.ai.data.upcoming.doneCount
import org.pagel.ai.data.upcoming.doneDayOf
import org.pagel.ai.data.upcoming.doneTimeOf
import org.pagel.ai.data.upcoming.movedTo
import org.pagel.ai.data.upcoming.plannedLine
import org.pagel.ai.data.upcoming.stripDayAt
import org.pagel.ai.data.upcoming.upcomingView
import java.time.LocalDate
import java.time.OffsetDateTime

/** Phase 4's pure rules: Upcoming's days (the web's views.ts), Completed by Tashkent day, the drop's date and target. */
class UpcomingLogicTest {
    private val today = LocalDate.parse("2026-10-28") // a Wednesday

    private fun task(id: String, due: String?, every: String? = null, priority: Int = 2, status: String = "open", doneAt: String? = null) = TaskEntity(
        id = id, project = "me", phase = "Now", sort = 1.0, text = id, title = null, status = status, priority = priority, star = false,
        due = due, every = every, doneWhen = null, doneAt = doneAt, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
    )

    @Test fun upcomingIsOverdueThenTheNextSevenDaysWithEachHabitOnEveryDayItFallsOn() {
        val v = upcomingView(
            listOf(
                task("late2", "2026-10-25"), task("late1", "2026-10-27 09:00"),
                task("today-p2", "2026-10-28 10:00"), task("today-p0", "2026-10-28 18:00", priority = 0),
                task("sat", "2026-10-31"), task("far", "2026-11-20"),
                task("gym", "2026-10-29 18:00", every = "tue,thu,sun"),
                task("undated", null),
            ),
            today,
        )
        assertEquals(listOf("late2", "late1"), v.overdue.map { it.id })
        assertEquals((0..6).map { today.plusDays(it.toLong()) }, v.days.map { it.day })
        // The same day: priority first, then the time (the web's byUrgency).
        assertEquals(listOf("today-p0", "today-p2"), v.days[0].tasks.map { it.id })
        assertEquals(listOf("gym"), v.days[1].tasks.map { it.id }) // Thu
        assertEquals(listOf("sat"), v.days[3].tasks.map { it.id })
        assertEquals(listOf("gym"), v.days[4].tasks.map { it.id }) // Sun
        assertEquals(emptyList<String>(), v.days[5].tasks.map { it.id }) // Mon
        assertEquals(listOf("gym"), v.days[6].tasks.map { it.id }) // Tue
    }

    @Test fun aDropKeepsTheTime() {
        assertEquals("2026-10-31 10:00", movedTo("2026-10-29 10:00", LocalDate.parse("2026-10-31")))
        assertEquals("2026-10-31", movedTo("2026-10-29", LocalDate.parse("2026-10-31")))
        assertEquals("2026-10-28", movedTo(null, today))
    }

    @Test fun theStripDayUnderTheFinger() {
        // 7 cells over 350 px: 50 px each; the strip is 60 px tall, with 20 px of slop above and below.
        assertEquals(0, stripDayAt(10f, 30f, 350f, 60f, 7, 20f))
        assertEquals(2, stripDayAt(120f, 30f, 350f, 60f, 7, 20f))
        assertEquals(6, stripDayAt(349f, 30f, 350f, 60f, 7, 20f))
        // A thumb a little above or below still counts; further away, or off the sides, does not.
        assertEquals(3, stripDayAt(160f, -15f, 350f, 60f, 7, 20f))
        assertEquals(3, stripDayAt(160f, 75f, 350f, 60f, 7, 20f))
        assertNull(stripDayAt(160f, -25f, 350f, 60f, 7, 20f))
        assertNull(stripDayAt(160f, 90f, 350f, 60f, 7, 20f))
        assertNull(stripDayAt(-1f, 30f, 350f, 60f, 7, 20f))
        assertNull(stripDayAt(350f, 30f, 350f, 60f, 7, 20f))
        assertNull(stripDayAt(10f, 30f, 0f, 60f, 7, 20f))
    }

    @Test fun completedGroupsByTheTashkentDayWithAnExplicitOffset() {
        val utc = { s: String -> OffsetDateTime.parse(s).toInstant().toString() }
        // 23:59 Tashkent on the 27th is 18:59 UTC; 00:01 Tashkent on the 28th is 19:01 UTC on the 27th.
        val lateTuesday = utc("2026-10-27T23:59:00+05:00")
        val earlyWednesday = utc("2026-10-28T00:01:00+05:00")
        assertEquals(LocalDate.parse("2026-10-27"), doneDayOf(lateTuesday))
        assertEquals(LocalDate.parse("2026-10-28"), doneDayOf(earlyWednesday))
        assertEquals("00:01", doneTimeOf(earlyWednesday))
        val groups = completedGroups(
            listOf(
                task("tue", null, status = "done", doneAt = lateTuesday),
                task("wed1", null, status = "done", doneAt = earlyWednesday),
                task("wed2", null, status = "done", doneAt = utc("2026-10-28T14:32:00+05:00")),
                task("mon", null, status = "done", doneAt = utc("2026-10-26T09:00:00+05:00")),
                task("old", null, status = "done", doneAt = utc("2026-10-02T09:00:00+05:00")),
                task("last-year", null, status = "done", doneAt = utc("2025-12-30T09:00:00+05:00")),
            ),
            today,
        )
        assertEquals(listOf("Today", "Yesterday", "Monday", "2 October", "30 December 2025"), groups.map { it.label })
        // Newest first inside a day.
        assertEquals(listOf("wed2", "wed1"), groups[0].tasks.map { it.id })
    }

    @Test fun dayLabelsAreTodayYesterdayWeekdaysThenDates() {
        assertEquals("Today", doneDayLabel(today, today))
        assertEquals("Yesterday", doneDayLabel(today.minusDays(1), today))
        assertEquals("Thursday", doneDayLabel(today.minusDays(6), today))
        assertEquals("21 October", doneDayLabel(today.minusDays(7), today))
    }

    /** Checker P4 #4: "21 planned" for 4 tasks. Each task once; a habit on every day it falls on is not counted. */
    @Test fun theCountLineCountsEachTaskOnceAndNoHabit() {
        val v = upcomingView(
            listOf(
                task("late", "2026-10-26"), task("late-habit", "2026-10-26 07:30", every = "day"),
                task("thu", "2026-10-29"), task("sat", "2026-10-31 10:00"),
                task("gym", "2026-10-29 18:00", every = "tue,thu,sun"), task("water", "2026-10-28", every = "day"),
            ),
            today,
        )
        assertEquals("3 tasks · 1 late", plannedLine(v))
        assertEquals("1 task", plannedLine(upcomingView(listOf(task("thu", "2026-10-29"), task("gym", "2026-10-29", every = "day")), today)))
        assertEquals("0 tasks", plannedLine(upcomingView(emptyList(), today)))
    }

    /** Checker P4 #9: pagel.uz's total wins; a tick or an untick here moves it by one; no total = what Room holds. */
    @Test fun theDoneCountIsPagelUzsTotalMovedByWhatChangedSince() {
        assertEquals(3, doneCount(room = 3, total = null, roomAtTotal = 0))
        assertEquals(7, doneCount(room = 3, total = 7, roomAtTotal = 3))
        assertEquals(6, doneCount(room = 2, total = 7, roomAtTotal = 3))
        assertEquals(8, doneCount(room = 4, total = 7, roomAtTotal = 3))
        // Never fewer than the rows on screen.
        assertEquals(5, doneCount(room = 5, total = 1, roomAtTotal = 3))
    }
}
