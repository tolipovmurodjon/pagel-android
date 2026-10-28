package org.pagel.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.tasks.NotDoing
import org.pagel.ai.ui.add.AddPlan
import org.pagel.ai.ui.add.Picks
import org.pagel.ai.ui.add.planOf
import org.pagel.ai.ui.add.whereOf
import org.pagel.ai.ui.glass.DatePick
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.TimeWheelMath
import org.pagel.ai.ui.projects.projectGroups
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * The task system's small rules, each checked against what the web's own code gives (lib/repeat.ts, pickers.tsx
 * parseTime, not-doing-check.ts, add-task.tsx): the phone and pagel.uz must agree word for word.
 */
class TaskLogicTest {
    @Test fun repeatLabelsAndToggles() {
        assertEquals("Daily", Repeat.label("day"))
        assertEquals("Weekdays", Repeat.label("weekday"))
        assertEquals("Every Tue, Thu, Sun", Repeat.label("sun,tue,thu"))
        assertEquals("Mondays", Repeat.label("monday"))
        assertEquals("tue,thu,sun", Repeat.daySet(listOf(0, 2, 4)))
        assertEquals("weekday", Repeat.toggle("mon,tue,wed,thu", 5))
        assertEquals("day", Repeat.toggle("weekday", 6).let { Repeat.toggle(it, 0) })
        assertNull(Repeat.toggle("tue", 2))
        assertEquals("mon,tue,wed,thu", Repeat.toggle("weekday", 5))
        assertEquals(listOf(1, 2, 3, 4, 5), Repeat.litDays("weekday"))
    }

    @Test fun timesAreTypedAnyWay() {
        assertEquals("09:05", DatePick.parseTime("9:05"))
        assertEquals("09:05", DatePick.parseTime("905"))
        assertEquals("14:45", DatePick.parseTime("2:45pm"))
        assertEquals("00:00", DatePick.parseTime("12am"))
        assertEquals("09:30", DatePick.parseTime("9.30"))
        assertNull(DatePick.parseTime("25:00"))
        assertNull(DatePick.parseTime("soon"))
        assertEquals(48, DatePick.SLOTS.size)
        assertEquals("23:30", DatePick.SLOTS.last())
    }

    @Test fun quickDaysAndTheMonthGrid() {
        val tue = LocalDate.of(2026, 10, 27)
        val q = DatePick.quickDays(tue)
        assertEquals(listOf("2026-10-27", "2026-10-28", "2026-10-31", "2026-11-02"), q.map { it.day.toString() })
        // On a Saturday "This weekend" is the next one, as the web's `|| 7`.
        assertEquals("2026-11-07", DatePick.quickDays(LocalDate.of(2026, 10, 31))[2].day.toString())
        val cells = DatePick.cells(YearMonth.of(2026, 10))
        assertEquals(42, cells.size)
        assertEquals("2026-09-28", cells.first().toString()) // October 2026 starts on a Thursday; weeks start Monday
        assertEquals("Today 15:00", DatePick.dueText("2026-10-27 15:00", tue))
        assertEquals("Tomorrow", DatePick.dueText("2026-10-28", tue))
        assertEquals("30 Oct", DatePick.dueText("2026-10-30", tue))
        assertEquals("Date", DatePick.dueText(null, tue))
        assertEquals("29 October 2026", DatePick.spoken(LocalDate.of(2026, 10, 29)))
    }

    @Test fun anExcuseIsNotAReason() {
        assertNotNull(NotDoing.check("too busy"))
        assertNotNull(NotDoing.check("I am just too tired lately"))
        assertNotNull(NotDoing.check("vaqtim yo'q hozir bu ishga"))
        assertNotNull(NotDoing.check("мне лень это делать сейчас"))
        assertNull(NotDoing.check("Waiting on the paid pod run"))
    }

    @Test fun picksBeatTheTypedLineAndProjectsAddIntoThePlan() {
        val now = LocalDateTime.of(2026, 10, 27, 10, 0)
        val typed = planOf("Call the bank tomorrow 3pm p2", "me", listOf("me"), now)
        assertEquals("2026-10-28 15:00", typed.due)
        assertEquals(1, typed.priority)
        val picked = planOf("Call the bank tomorrow 3pm p2", "me", listOf("me"), now, Picks(dueSet = true, due = "2026-10-30", priority = 0))
        assertEquals("2026-10-30", picked.due)
        assertEquals(0, picked.priority)
        assertEquals("2026-10-27", planOf("Weigh in", "me", listOf("me"), now).due)
        assertNull("from a project: no date unless one is given", planOf("Weigh in", "me", listOf("me"), now, toToday = false).due)
        val today = now.toLocalDate()
        assertEquals("Today", whereOf(AddPlan("x", "me", 2, "2026-10-27", null, false), today))
        assertEquals("Upcoming, Fri 30 Oct", whereOf(AddPlan("x", "me", 2, "2026-10-30", null, false), today))
        assertEquals("Habits, Weekly", whereOf(AddPlan("x", "me", 2, "2026-10-27", "week", false), today))
        assertEquals("in the plan", whereOf(AddPlan("x", "me", 2, null, null, false), today))
        // Today deals 3 at a time: with 3 showing, a new one waits (the web's add-task.tsx words).
        assertEquals("Today, in line", whereOf(AddPlan("x", "me", 2, "2026-10-27", null, false), today, inLine = true))
        assertEquals("Upcoming, Fri 30 Oct", whereOf(AddPlan("x", "me", 2, "2026-10-30", null, false), today, inLine = true))
    }

    private fun t(id: String, phase: String, sort: Int, now: Boolean, status: String = "open") = TaskEntity(
        id = id, project = "p", phase = phase, sort = 0.0, text = "Task $id", title = null, status = status, priority = 2, star = false,
        due = null, every = null, doneWhen = null, doneAt = null, updatedAt = "", serverVersion = 1, lastModified = 0, phaseSort = sort, phaseNow = now,
    )

    @Test fun projectGroupsPutNowFirstThenPlanOrder() {
        // Rows arrive as PagelDao.openTasks orders them: NOW first, then by phase order.
        val g = projectGroups(listOf(t("c", "Phase 2", 1, true), t("a", "Phase 1", 0, false), t("b", "Phase 1", 0, false), t("d", "Phase 3", 2, false)))
        assertEquals(listOf("Phase 2" to true, "Phase 1" to false, "Phase 3" to false), g.map { it.title to it.now })
        assertEquals(listOf("a", "b"), g[1].rows.map { it.id })
        // An older server sends no phase order: phases keep the order they first appear in.
        val old = projectGroups(listOf(t("a", "Later", 0, false), t("b", "Now", 0, false), t("c", "Later", 0, false)))
        assertEquals(listOf("Later", "Now"), old.map { it.title })
        assertEquals(listOf("a", "c"), old[0].rows.map { it.id })
    }

    @Test fun projectGroupsKeepTheDoneOnesAndFileNewTasksUnderNow() {
        // In PagelDao.projectTasks order: NOW first, then by phase order.
        val g = projectGroups(listOf(
            t("a", "Build", 1, true), t("b", "Build", 1, true, status = "done"),
            t("d", "Plan", 0, false, status = "done"), t("phone_1", "", 0, false), t("c", "Ship", 2, false),
        ))
        assertEquals(listOf("Build", "Plan", "Ship"), g.map { it.title })
        // NOW: its open task, then the one just added on the phone (no phase until pagel.uz files it); done ones apart.
        assertEquals(listOf("a", "phone_1"), g[0].rows.map { it.id })
        assertEquals(listOf("b"), g[0].done.map { it.id })
        // A phase with only done tasks is a finished one: nothing open, its done list kept.
        assertEquals(emptyList<String>(), g[1].rows.map { it.id })
        assertEquals(listOf("d"), g[1].done.map { it.id })
        // No NOW phase yet (a new project): the phone's task gets a pane of its own.
        val fresh = projectGroups(listOf(t("phone_2", "", 0, false)))
        assertEquals(listOf("Tasks" to true), fresh.map { it.title to it.now })
    }

    @Test fun theTimeWheelTurnsRoundAndSnapsToFives() {
        assertEquals(15 to 6, TimeWheelMath.split("15:32")) // nearest 5: :30
        assertEquals(15 to 11, TimeWheelMath.split("15:58")) // stays in its hour: :55
        assertEquals(7 to 1, TimeWheelMath.split("07:07"))
        assertEquals(9 to 0, TimeWheelMath.split("")) // no time yet: the wheel opens on 09:00
        assertEquals("09:05", TimeWheelMath.value(9, 1))
        assertEquals("23:55", TimeWheelMath.value(23, 11))
        // Each column is a long loop that starts in its middle: past 23 comes 00, before 00 comes 23.
        val h = TimeWheelMath.start(23, TimeWheelMath.HOURS)
        assertEquals(23, TimeWheelMath.rowOf(h, TimeWheelMath.HOURS))
        assertEquals(0, TimeWheelMath.rowOf(h + 1, TimeWheelMath.HOURS))
        assertEquals(23, TimeWheelMath.rowOf(TimeWheelMath.start(0, TimeWheelMath.HOURS) - 1, TimeWheelMath.HOURS))
        assertEquals(11, TimeWheelMath.rowOf(-1, TimeWheelMath.MINUTES))
    }

    @Test fun monthlyOnChosenDaysMatchesTheServer() {
        // The server's grammar (dashboard lib/repeat.ts, d8be98c): "month 1,15", "month 15,last".
        assertEquals("Monthly · 1, 15, Last", Repeat.label("month 1,15,last"))
        assertEquals(listOf(1, 15, Repeat.LAST), Repeat.monthDays("month 1,15,last"))
        assertEquals("month 1,15", Repeat.toggleMonthDay("month", 15).let { Repeat.toggleMonthDay(it, 1) })
        assertEquals("month 15,last", Repeat.toggleMonthDay("month 15", Repeat.LAST))
        assertEquals("month", Repeat.toggleMonthDay("month 15", 15)) // none left: plain monthly, on its date
        assertEquals(true, Repeat.isMonthly("month 1,15"))
        assertEquals(false, Repeat.isMonthly("month 32"))
        assertEquals(emptyList<Int>(), Repeat.monthDays("month"))
    }
}
