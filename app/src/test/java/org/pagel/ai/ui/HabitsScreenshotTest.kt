package org.pagel.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.HabitFreezeEntity
import org.pagel.ai.data.db.HabitPrefEntity
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.Part
import org.pagel.ai.data.habits.habitBoard
import org.pagel.ai.data.habits.habitsDay
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.habits.HabitDetail
import org.pagel.ai.ui.habits.HabitHistory
import org.pagel.ai.ui.habits.HabitTimer
import org.pagel.ai.ui.habits.HabitsScreen
import org.pagel.ai.ui.habits.HabitsUi
import org.pagel.ai.ui.habits.ReminderContent
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Habits 2.0 (round D) at Boss's sizes: the chain week strip, the part pills, the bold cards with their emoji tiles,
 * time tabs and "•••", a timed habit's clock, the Namoz group before and after 1 November, a past day, the habit's
 * page, History's three tabs, and the reminder sheet. 360 dp and 1.5× font.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class HabitsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val today = "2026-10-28"

    private fun habit(id: String, text: String, due: String?, every: String = "day", times: Int = 1, group: String? = null) = TaskEntity(
        id = id, project = "me", phase = "Now", sort = 1.0, text = text, title = null, status = "open", priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
        times = times, habitGroup = group,
    )

    private val habits = listOf(
        habit("h1", "Stretch for ten minutes", "2026-10-28 07:30"),
        habit("h2", "Go through five saves in your Inbox", "2026-10-28 09:00"),
        habit("h3", "Log today's food and sugar, then shut down", "2026-10-28 13:00", times = 3),
        habit("h4", "Start winding down, in bed by 22:00", "2026-10-28 21:30"),
        habit("h5", "Drink water", "2026-10-28"),
        habit("h6", "Do the full-body F workout at openGym", "2026-10-29 18:00", every = "tue,thu,sun"),
    )
    private fun days(id: String, vararg d: String) = d.map { HabitTickEntity(it, id) }
    private val ticks = days("h1", "2026-10-27", "2026-10-26", "2026-10-25", "2026-10-24") + days("h2", "2026-10-28", "2026-10-27", "2026-10-26") +
        listOf(HabitTickEntity(today, "h3", 2), HabitTickEntity("2026-10-27", "h3", 3), HabitTickEntity("2026-10-26", "h3", 3)) +
        days("h5", "2026-10-26", "2026-10-25") + days("h6", "2026-10-27", "2026-10-25")
    private val prefs = listOf(HabitPrefEntity("h2", after = "h1", identity = "I'm someone who reads what he saves"))
    private val freezes = listOf(HabitFreezeEntity("2026-10-24", "h5"))

    private fun ui(
        h: List<TaskEntity> = habits, t: List<HabitTickEntity> = ticks, selected: String = today, part: Part = Part.MORNING,
        timers: Map<String, HabitTimer> = emptyMap(), reminders: Set<String> = setOf("h1"),
    ) = HabitsUi(
        loading = false, today = today, day = habitsDay(h, t, today),
        board = habitBoard(h, t, prefs, freezes, LocalDate.parse(today), LocalDate.parse(selected)),
        part = part, reminders = reminders, byId = h.associateBy { it.id }, timers = timers, now = 200_000, ticks = t,
        prefs = prefs.associateBy { it.taskId }, freezes = freezes,
    )

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    private val pad = PaddingValues(top = 16.dp, bottom = 96.dp)

    @Test fun morningWithTheChainAndTheCards() {
        show { HabitsScreen(ui(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-360x800.png")
        compose.onNodeWithText("Habits").assertIsDisplayed()
        compose.onNodeWithContentDescription("Morning").assertIsDisplayed()
        compose.onNodeWithText("Stretch for ten minutes").assertIsDisplayed()
        compose.onNodeWithText("0 / 10 min").assertIsDisplayed()
        // Four days in a row, today not done yet: the flame says 4.
        compose.onNodeWithContentDescription("4 in a row").assertIsDisplayed()
        // Boss 2026-10-29: no bell, no "•••", no chips on the row; the reminder lives on the habit's page.
        compose.onAllNodesWithContentDescription("Reminder: Stretch for ten minutes").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("More: Stretch for ten minutes").assertCountEquals(0)
        compose.onAllNodesWithText("Never miss twice").assertCountEquals(0)
        compose.onNodeWithText("Finished").assertIsDisplayed()
    }

    @Test fun morningAtLargeFont() {
        show(1.5f) { HabitsScreen(ui(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-360x800-font-1.5.png")
        compose.onNodeWithContentDescription("Start: Stretch for ten minutes").assertIsDisplayed()
    }

    @Test fun afternoonWithACountHabitAndARunningTimer() {
        val timers = mapOf("h1" to HabitTimer(startedAt = 0, spentMs = 0))
        show { HabitsScreen(ui(part = Part.AFTERNOON, timers = timers), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-afternoon.png")
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        // Water has no time: it shows in every part.
        compose.onNodeWithText("Drink water").assertIsDisplayed()
    }

    @Test fun aTimedHabitShowsItsClock() {
        // 200 s of 10 min: "3:20 / 10 min".
        show { HabitsScreen(ui(timers = mapOf("h1" to HabitTimer(startedAt = null, spentMs = 200_000))), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-timer.png")
        compose.onNodeWithText("3:20 / 10 min").assertIsDisplayed()
    }

    @Test fun theCheckTicksThePlayRingRunsTheClockAndTheRowOpensThePage() {
        var ticked = ""
        var timed = ""
        var opened = ""
        show { HabitsScreen(ui(), pad, onTick = { ticked = it }, onTimer = { id, m -> timed = "$id $m" }, onOpen = { opened = it }) }
        compose.onNodeWithContentDescription("Done: Drink water").performClick()
        assertEquals("h5", ticked)
        compose.onNodeWithContentDescription("Start: Stretch for ten minutes").performClick()
        assertEquals("h1 10", timed)
        compose.onNodeWithText("Stretch for ten minutes").performClick()
        assertEquals("h1", opened)
    }

    /**
     * Boss 2026-10-29 "the spacing is different for all": one rhythm. Every habit row has the same height and every gap
     * between rows is the same, with a long title, a count, a timer and a done row, at 1x and 1.5x font.
     */
    @Test fun rowsHaveOneHeightAndOneGap() {
        val long = habit("h7", "Log today's food and sugar, then shut down the kitchen for the night", "2026-10-28 08:00", times = 3)
        show { HabitsScreen(ui(h = habits + long), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-rows.png")
        assertOneRhythm()
    }

    @Test fun rowsHaveOneHeightAndOneGapAtLargeFont() {
        val long = habit("h7", "Log today's food and sugar, then shut down the kitchen for the night", "2026-10-28 08:00", times = 3)
        show(1.5f) { HabitsScreen(ui(h = habits + long), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-rows-font-1.5.png")
        assertOneRhythm()
    }

    private fun assertOneRhythm() {
        val rows = compose.onAllNodesWithTag("habit-row", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }
        assertTrue("at least 3 rows on screen, got ${rows.size}", rows.size >= 3)
        val h = rows.map { it.height }
        assertTrue("rows have one height: $h", h.all { kotlin.math.abs(it - h[0]) < 0.6f })
        val gaps = rows.zipWithNext { a, b -> b.top - a.bottom }
        assertTrue("rows have one gap: $gaps", gaps.all { kotlin.math.abs(it - gaps[0]) < 0.6f })
    }

    /** Boss 2026-10-29: "at most 5 things per row": the check, the emoji, the title, the progress, the streak. */
    @Test fun atMostFiveThingsPerRow() {
        show { HabitsScreen(ui(), pad) }
        val parts = setOf("row-tick", "row-emoji", "row-title", "row-progress", "row-streak")
        fun count(n: androidx.compose.ui.semantics.SemanticsNode): Int =
            (if (n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) in parts) 1 else 0) + n.children.sumOf { count(it) }
        fun clickables(n: androidx.compose.ui.semantics.SemanticsNode): Int =
            (if (n.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)) 1 else 0) + n.children.sumOf { clickables(it) }
        val rows = compose.onAllNodesWithTag("habit-row", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(rows.isNotEmpty())
        for (r in rows) {
            assertTrue("a row shows ${count(r)} things", count(r) in 3..5)
            // Two targets only: the check and the row itself (no bell, no menu, no chip).
            assertTrue("a row has ${clickables(r)} tap targets", clickables(r) <= 2)
        }
    }

    /**
     * The top bar is the ALL-HABITS chain (Boss 2026-10-29): Monday 26 held 4 of the 5 habits due, so it is a thin
     * ring, not a fill; nothing is a full day this week yet.
     */
    @Test fun theTopBarIsTheAllHabitsChain() {
        show { HabitsScreen(ui(), pad) }
        compose.onAllNodesWithContentDescription("Monday 26 October")[0].assertStateDescription("4 of 5 done")
        compose.onAllNodesWithContentDescription("Wednesday 28 October")[0].assertStateDescription("1 of 5 done")
    }

    /** The chain as one connected shape: a 5-day streak joined by one bar, a gap, a thin partial ring, today a ring. */
    @Test fun aFiveDayStreakAndAGap() {
        val oct = (1..31).map { LocalDate.of(2026, 10, it) }
        val marks = oct.map { d ->
            d to when (d.dayOfMonth) {
                in 20..24 -> org.pagel.ai.data.habits.Mark.DONE
                25 -> org.pagel.ai.data.habits.Mark.MISSED
                26 -> org.pagel.ai.data.habits.Mark.PARTIAL
                27 -> org.pagel.ai.data.habits.Mark.DONE
                28 -> org.pagel.ai.data.habits.Mark.TODAY
                in 29..31 -> org.pagel.ai.data.habits.Mark.FUTURE
                else -> org.pagel.ai.data.habits.Mark.MISSED
            }
        }
        val counts = oct.map { if (it.dayOfMonth == 26) 2 to 4 else 0 to 4 }
        show { org.pagel.ai.ui.habits.MonthChain("October 2026", marks, LocalDate.parse(today), {}, null, counts) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-chain-streak-and-gap.png")
        // 20-24 join (4 bars), 25 breaks it, 26 is partial so it never joins; 27 stands alone.
        assertEquals(listOf(true, true, true, true, false, false, false, false), org.pagel.ai.data.habits.links(marks.subList(19, 27).map { it.second }))
        compose.onNodeWithContentDescription("Monday 26 October").assertStateDescription("2 of 4 done")
        compose.onNodeWithContentDescription("Saturday 25 October").assertStateDescription("Missed")
    }

    @Test fun lookingBackAtAPastDayShowsTheTodayPill() {
        show { HabitsScreen(ui(selected = "2026-10-26"), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-past-day.png")
        compose.onNodeWithContentDescription("Back to today").assertIsDisplayed()
        compose.onNodeWithText("Monday 26 October · looking back").assertIsDisplayed()
    }

    /** RULES.md Layout 3: the strip's top stays put between today and a past day (the header line keeps its slot). */
    @Test fun theStripDoesNotMoveBetweenDays() {
        var day by androidx.compose.runtime.mutableStateOf(today)
        show { HabitsScreen(ui(selected = day), pad) }
        val top1 = compose.onAllNodesWithContentDescription("Wednesday 28 October")[0].fetchSemanticsNode().boundsInRoot.top
        day = "2026-10-26"
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back to today").assertIsDisplayed()
        val top2 = compose.onAllNodesWithContentDescription("Wednesday 28 October")[0].fetchSemanticsNode().boundsInRoot.top
        assertEquals(top1, top2, 0.5f)
    }

    private val prayers = Namoz.Prayer.entries.map { habit("n_${it.name}", it.uz, "2026-11-01 05:35", group = Namoz.GROUP) }

    @Test fun namozWaitsForTheFirstOfNovember() {
        show { HabitsScreen(ui(h = habits + prayers), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-namoz-before.png")
        compose.onNodeWithText("Namoz").assertIsDisplayed()
        compose.onNodeWithText("From 1 November").assertIsDisplayed()
        compose.onNodeWithText("Bomdod").assertIsDisplayed()
    }

    @Test fun namozOnTheSecondOfNovemberWithThreeOfFive() {
        val day = "2026-11-02"
        val t = listOf("BOMDOD", "PESHIN", "ASR").map { HabitTickEntity(day, "n_$it") }
        val h = habits + prayers
        val u = HabitsUi(
            loading = false, today = day, day = habitsDay(h, t, day), board = habitBoard(h, t, emptyList(), emptyList(), LocalDate.parse(day)),
            part = Part.EVENING, byId = h.associateBy { it.id }, ticks = t,
        )
        show { HabitsScreen(u, pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-namoz-3-of-5.png")
        compose.onNodeWithContentDescription("3 of 5 habits done today").assertIsDisplayed()
        compose.onNodeWithText("Five a day").assertIsDisplayed()
    }

    @Test fun namozNotStartedYetOffersOneButton() {
        var started = false
        show { HabitsScreen(ui(), pad, onStartNamoz = { started = true }) }
        compose.onNodeWithText("Start tracking").performClick()
        assertTrue(started)
    }

    @Test fun theHabitsPageHoldsTheReminderAndTheClock() {
        var bell = false
        var timed = ""
        show { HabitDetail(habits[0], ui(), pad, onReminder = { bell = true }, onTimer = { id, m -> timed = "$id $m" }) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-detail-actions.png")
        compose.onNodeWithText("Reminder · 07:30").performClick()
        assertTrue(bell)
        compose.onNodeWithText("Start 10 min").performClick()
        assertEquals("h1 10", timed)
        compose.onNodeWithText("Freeze today · 1 left this month").assertExists()
    }

    @Test fun theHabitsPage() {
        show { HabitDetail(habits[1], ui(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-detail.png")
        compose.onNodeWithText("Go through five saves in your Inbox").assertIsDisplayed()
        compose.onNodeWithText("“I'm someone who reads what he saves”").assertIsDisplayed()
        compose.onNodeWithText("October 2026").assertIsDisplayed()
    }

    @Test fun theHabitsPageAtLargeFont() {
        show(1.5f) { HabitDetail(habits[0], ui(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-detail-font-1.5.png")
    }

    @Test fun historyCalendar() {
        show { HabitHistory(ui(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-history.png")
        compose.onNodeWithText("History").assertIsDisplayed()
    }

    @Test fun historyAllHabits() {
        show { HabitHistory(ui(), pad, startTab = 1) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-history-all.png")
    }

    @Test fun historyAchievements() {
        show { HabitHistory(ui(), pad, startTab = 2) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-achievements.png")
        compose.onNodeWithContentDescription("3 in a row, earned").assertIsDisplayed()
    }

    @Test fun reminderSheetAt360() {
        show { Column(Modifier.padding(top = 16.dp)) { ReminderContent(habits[0], on = true, exact = true, onToggle = {}, onTime = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-reminder.png")
        compose.onNodeWithText("At 07:30 on the days it repeats, with a Done button.").assertIsDisplayed()
    }

    @Test fun reminderSheetLargeFontWithoutExactAlarms() {
        show(1.5f) { Column(Modifier.padding(top = 16.dp)) { ReminderContent(habits[3], on = true, exact = false, onToggle = {}, onTime = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-reminder-font-1.5.png")
        compose.onNodeWithContentDescription("Exact time · Allow").assertIsDisplayed()
    }

    private fun SemanticsNodeInteraction.assertStateDescription(want: String) {
        val got = fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(want, got)
    }
}
