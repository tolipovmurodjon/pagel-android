package org.pagel.ai.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.data.api.HabitMeasureDto
import org.pagel.ai.data.api.HabitMeasuresResponse
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.StepEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.ui.shell.OpenRequests
import org.pagel.ai.widget.CaloriesGlance
import org.pagel.ai.widget.CaloriesSnapshot
import org.pagel.ai.widget.Fit
import org.pagel.ai.widget.WidgetRevisions
import org.pagel.ai.widget.fitOf
import org.pagel.ai.widget.habitsGlance
import org.pagel.ai.widget.namozGlance
import org.pagel.ai.widget.nextTaskGlance
import org.pagel.ai.widget.todayGlance
import org.pagel.ai.widget.twelfthsOf
import java.time.LocalDate
import java.time.LocalTime

/** Phase 8: every widget's model from fake rows (no Android), the way the widgets and the updater build them. */
class WidgetModelsTest {
    private fun task(
        id: String, text: String, due: String? = null, every: String? = null, status: String = "open", times: Int = 1, project: String = "pagel",
    ) = TaskEntity(
        id = id, project = project, phase = "Now", sort = 1.0, text = text, title = null, status = status, priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "2026-10-29T05:00:00Z", serverVersion = 1, lastModified = 0, times = times,
    )

    private val projects = listOf(ProjectEntity(name = "pagel", title = "PAGEL", color = null, sort = 0, openCount = 3))

    @Test fun todaySaysDoneAndLeftInTodaysOwnWords() {
        val g = todayGlance(listOf(task("a", "Call the bank"), task("b", "Pay rent", status = "done")), projects, doneToday = 12, today = LocalDate.parse("2026-10-29"))
        assertEquals("12 done · 1 left", g.line)
        assertEquals(listOf("a"), g.rows.map { it.key })
    }

    @Test fun nextTaskIsTodaysFirstWithItsFirstOpenStep() {
        val tasks = listOf(task("a", "Call the bank. Ask for the card"), task("b", "Pay rent"))
        val steps = listOf(
            StepEntity("a", 0, "Find the number", null, done = true),
            StepEntity("a", 2, "Ask for a new card", null, done = false),
            StepEntity("a", 1, "Call before 17:00", null, done = false),
        )
        val g = nextTaskGlance(tasks, steps, projects)!!
        assertEquals("a", g.id)
        assertEquals("Call the bank", g.title)
        assertEquals("Call before 17:00", g.step)
        assertEquals("PAGEL", g.project)
        assertNull(nextTaskGlance(emptyList(), emptyList(), projects))
        assertNull(nextTaskGlance(listOf(task("x", "Done one", status = "done")), emptyList(), projects)?.step)
    }

    @Test fun habitsAreRingsWithoutThePrayersAndCountDoneOfToday() {
        val today = "2026-11-01"
        val habits = listOf(
            task("g", "Gym", due = "$today 18:00", every = "day"),
            task("w", "Drink water", due = today, every = "day", times = 3),
            task("r", "Read 10 pages", due = "$today 21:30", every = "day"),
            task("p", "Peshin", due = "$today 12:11", every = "day"),
        )
        val ticks = listOf(HabitTickEntity(today, "g", 1), HabitTickEntity(today, "w", 2), HabitTickEntity(today, "p", 1))
        val g = habitsGlance(habits, ticks, today)
        assertEquals(listOf("g", "r", "w"), g.rings.map { it.id })
        assertEquals("1/3", g.count)
        val water = g.rings.single { it.id == "w" }
        assertEquals(8, water.twelfths)
        assertFalse(water.done)
        assertEquals("gym", g.rings.single { it.id == "g" }.emoji)
        assertEquals("water", water.emoji)
    }

    @Test fun ringsLandOnExactTwelfths() {
        assertEquals(0, twelfthsOf(0, 3))
        assertEquals(4, twelfthsOf(1, 3))
        assertEquals(6, twelfthsOf(1, 2))
        assertEquals(12, twelfthsOf(5, 5))
        assertEquals(12, twelfthsOf(9, 5))
        assertEquals(0, twelfthsOf(1, 0))
    }

    private fun prayers(day: String) = Namoz.Prayer.entries.map { task("n_${it.name}", it.uz, due = "$day 05:00", every = "day") }

    @Test fun namozWaitsForTheFirstOfNovember() {
        val day = LocalDate.parse("2026-10-31")
        val g = namozGlance(prayers("2026-10-31"), listOf(HabitTickEntity("2026-10-31", "n_BOMDOD", 1)), day, LocalTime.of(13, 0))
        assertFalse(g.counting)
        assertEquals(0, g.done)
        assertEquals(listOf("Bomdod", "Peshin", "Asr", "Shom", "Xufton"), g.rows.map { it.name })
    }

    @Test fun namozMarksTheNextPrayerAndCountsFromNovember() {
        val day = LocalDate.parse("2026-11-01")
        val ticks = listOf(HabitTickEntity("2026-11-01", "n_BOMDOD", 1))
        // 13:00: Bomdod prayed, Peshin's window (12:11–15:37) is open: Peshin is the marked row.
        val noon = namozGlance(prayers("2026-11-01"), ticks, day, LocalTime.of(13, 0))
        assertTrue(noon.counting)
        assertEquals("1/5", noon.count)
        assertEquals(1, noon.next)
        assertEquals("n_PESHIN", noon.rows[1].habitId)
        assertTrue(noon.rows[0].done)
        // 23:00: Xufton's window runs past midnight, so it is still the next one tonight.
        assertEquals(4, namozGlance(prayers("2026-11-01"), ticks, day, LocalTime.of(23, 0)).next)
        // Times come from the phone's own table (NamozTest checks it against namozvaqti.uz).
        assertTrue(noon.rows[1].time.matches(Regex("""\d\d:\d\d–\d\d:\d\d""")))
    }

    @Test fun namozWithoutTrackedPrayersOffersNoTick() {
        val g = namozGlance(emptyList(), emptyList(), LocalDate.parse("2026-11-02"), LocalTime.of(9, 0))
        assertFalse(g.tracked)
        assertTrue(g.rows.all { it.habitId == null })
    }

    @Test fun caloriesReadAsBossWritesThem() {
        val g = CaloriesGlance("k", eaten = 1420, goal = 2000)
        assertEquals("1,420 / 2,000 kcal", g.text)
        assertEquals("580 left", g.left)
        assertEquals(9, g.twelfths)
        assertFalse(g.over)
        val over = CaloriesGlance("k", eaten = 2120, goal = 2000)
        assertEquals("120 over", over.left)
        assertTrue(over.over)
        assertEquals(12, over.twelfths)
        assertEquals("650 kcal", CaloriesGlance("k", eaten = 650, goal = null).text)
    }

    @Test fun caloriesComeFromTheKcalHabitAndANewDayStartsAtZero() {
        val r = HabitMeasuresResponse(
            day = "2026-11-01",
            habits = listOf(HabitMeasureDto("p", "Protein", unit = "g", goal = 150.0, goalKind = "at_least"), HabitMeasureDto("k", "Calories", unit = "kcal", goal = 2000.0, goalKind = "at_most")),
            totals = mapOf("k" to 1420.0, "p" to 90.0),
        )
        val s = CaloriesSnapshot.of(r, "2026-11-01")
        assertEquals(CaloriesSnapshot("2026-11-01", "k", 1420, 2000, atMost = true), s)
        assertEquals("1,420 / 2,000 kcal", s.glanceOn("2026-11-01")!!.text)
        assertEquals("0 / 2,000 kcal", s.glanceOn("2026-11-02")!!.text)
        // An older pagel.uz (no unit, no totals): no kcal habit, so the widget invites one instead of inventing a number.
        assertNull(CaloriesSnapshot.of(HabitMeasuresResponse(day = "2026-11-01"), "2026-11-01").glanceOn("2026-11-01"))
    }

    @Test fun sizesPickTheLayout() {
        assertEquals(Fit.Tiny, fitOf(DpSize(70.dp, 70.dp)))
        assertEquals(Fit.Strip, fitOf(DpSize(250.dp, 70.dp)))
        assertEquals(Fit.Full, fitOf(DpSize(250.dp, 180.dp)))
    }

    @Test fun aRevisionPushesOnlyWhatChanged() {
        val r = WidgetRevisions()
        assertTrue(r.changed("habits", "1/3"))
        assertFalse(r.changed("habits", "1/3"))
        assertTrue(r.changed("habits", "2/3"))
        assertTrue(r.changed("next", null))
        assertFalse(r.changed("next", null))
    }

    @Test fun onlyKnownOpenRequestsAreOpened() {
        assertEquals("t_abc", OpenRequests.taskOf("task:t_abc"))
        assertEquals("local_1a2b", OpenRequests.taskOf("task:local_1a2b"))
        assertNull(OpenRequests.taskOf("task:../../x"))
        assertNull(OpenRequests.taskOf("task:"))
        assertNull(OpenRequests.taskOf("compass"))
        OpenRequests.pending.value = null
        OpenRequests.open("wipe-everything")
        assertNull(OpenRequests.pending.value)
        OpenRequests.open(OpenRequests.VOICE)
        assertEquals("voice", OpenRequests.pending.value)
        OpenRequests.pending.value = null
    }
}
