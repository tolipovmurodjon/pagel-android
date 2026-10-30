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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasTestTag
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
    /** Note 4: Drink water stacked after Stretch sits right under it, one step in, before the Inbox habit. */
    @Test fun aStackedHabitSitsUnderItsAnchor() {
        show { HabitsScreen(ui().copy(prefs = mapOf("h5" to HabitPrefEntity("h5", after = "h1"))), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-stacked.png")
        fun box(t: String) = compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot
        val (a, b, c) = listOf(box("Stretch for ten minutes"), box("Drink water"), box("Go through five saves in your Inbox"))
        assertTrue("stacked row under its anchor", a.top < b.top && b.top < c.top)
        assertTrue("stacked row one step in", b.left > a.left + 4f)
        // PNG finding 3: the step in lives inside the card; every check stays on one x position.
        val checks = compose.onAllNodesWithTag("row-tick", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.left }
        assertTrue("every check on one x: $checks", checks.size >= 3 && checks.all { kotlin.math.abs(it - checks[0]) < 0.6f })
    }

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
        assertOneRhythm(min = 2)
    }

    private fun assertOneRhythm(min: Int = 3) {
        val rows = compose.onAllNodesWithTag("habit-row", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }
            // A row cut by the screen's bottom edge reports clipped bounds: judge only whole rows.
            .filter { it.bottom < compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom - 0.5f }
            // A row on a pager page off screen is clipped to nothing (CI 40998124339: heights [0.0, 336.0, 336.0]).
            .filter { it.height > 0.5f }
        assertTrue("at least $min rows on screen, got ${rows.size}", rows.size >= min)
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
     * The top bar is the ALL-HABITS chain (Boss 2026-10-29), each habit counted from its own start: on Monday 26 the
     * four habits that had started were all done (full); Tuesday 27 held 4 of 5 (a thin ring); today 1 of 5.
     */
    @Test fun theTopBarIsTheAllHabitsChain() {
        show { HabitsScreen(ui(), pad) }
        compose.onAllNodesWithContentDescription("Monday 26 October")[0].assertStateDescription("Done")
        compose.onAllNodesWithContentDescription("Tuesday 27 October")[0].assertStateDescription("4 of 5 done")
        compose.onAllNodesWithContentDescription("Wednesday 28 October")[0].assertStateDescription("1 of 5 done")
    }

    /**
     * Checker 2026-10-29 #2, from real ticks and a freeze through habitBoard (not hand-made marks): Mon-Wed full and
     * joined, Thursday half done with a freeze on the done habit = a ring that never links, today soft.
     */
    @Test fun theChainFromRealTicksAndAFreeze() {
        val h = listOf(habit("a", "Stretch", "2026-10-19 07:30"), habit("b", "Drink water", "2026-10-19"))
        val sunday = "2026-11-01"
        val t = listOf("2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28").flatMap { d -> listOf(HabitTickEntity(d, "a"), HabitTickEntity(d, "b")) } +
            HabitTickEntity("2026-10-29", "a")
        val f = listOf(HabitFreezeEntity("2026-10-29", "a"))
        val u = HabitsUi(
            loading = false, today = "2026-10-31", day = habitsDay(h, t, "2026-10-31"),
            board = habitBoard(h, t, emptyList(), f, LocalDate.parse("2026-10-31")), part = Part.MORNING, byId = h.associateBy { it.id }, ticks = t, freezes = f,
        )
        show { HabitsScreen(u, pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-chain-from-ticks.png")
        compose.onAllNodesWithContentDescription("Wednesday 28 October")[0].assertStateDescription("Done")
        compose.onAllNodesWithContentDescription("Thursday 29 October")[0].assertStateDescription("1 of 2 done")
        // Sunday 25 was full too: the bar runs in from last week.
        assertTrue(u.board!!.chainIn)
        assertEquals(sunday, u.board!!.week.last().date.toString())
    }

    /** Checker #4: a title gets two lines, never cut, at 1.5x font, and every row keeps one height. */
    @Test fun titlesWrapToTwoLinesAtLargeFont() {
        show(1.5f) { HabitsScreen(ui(), pad) }
        val node = compose.onNodeWithText("Stretch for ten minutes", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val l = layouts.first()
        assertTrue("two lines, got ${l.lineCount}", l.lineCount == 2)
        assertTrue("not cut", (0 until l.lineCount).none { l.isLineEllipsized(it) })
        assertOneRhythm(min = 2)
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
        compose.onNodeWithContentDescription("Sunday 25 October").assertStateDescription("Missed")
    }

    @Test fun lookingBackAtAPastDayShowsTheTodayPill() {
        show { HabitsScreen(ui(selected = "2026-10-26"), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-past-day.png")
        compose.onNodeWithContentDescription("Back to today").assertIsDisplayed()
        compose.onNodeWithText("Mon 26 Oct · looking back").assertIsDisplayed()
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
        // The Namoz group is the Namoz team's (0.3.34): its rows show under the head.
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
    }

    @Test fun namozNotStartedYetOffersOneButton() {
        var started = false
        show { HabitsScreen(ui(), pad, onStartNamoz = { started = true }) }
        compose.onNodeWithText("Start tracking").performClick()
        assertTrue(started)
    }

    /** Boss 2026-10-29: the page = header → stats → the month; every action sits behind ⋮ (and the bell chip). */
    @Test fun theHabitsPageIsMainlyStats() {
        var at: org.pagel.ai.ui.habits.SheetAt? = null
        show { HabitDetail(habits[0], ui(), pad, onSettings = { at = it }) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-page-360x800.png")
        compose.onNode(hasTestTag("habit-stats")).assertIsDisplayed()
        compose.onNodeWithText("October 2026").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Reminder · 07:30").assertCountEquals(0)
        compose.onAllNodesWithText("Freeze today · 1 left this month").assertCountEquals(0)
        compose.onAllNodesWithText("Emoji").assertCountEquals(0)
        // The bell chip is the quick glance (h1's reminder is on) and opens the sheet at Reminder.
        compose.onNodeWithText("🔔 07:30").performClick()
        assertEquals(org.pagel.ai.ui.habits.SheetAt.REMINDER, at)
        compose.onNodeWithContentDescription("Habit settings").performClick()
        assertEquals(org.pagel.ai.ui.habits.SheetAt.TOP, at)
    }

    @Test fun theHabitsPageShowsItsRecipe() {
        show { HabitDetail(habits[1], ui(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-detail.png")
        compose.onNodeWithText("Go through five saves in your Inbox").assertIsDisplayed()
        // h2 comes after h1: "After 🧘 Stretch for ten minutes → 📥 Go through five saves in your Inbox".
        compose.onNodeWithText("Stretch for ten minutes →", substring = true).assertIsDisplayed()
    }

    @Test fun theHabitsPageAtLargeFont() {
        show(1.5f) { HabitDetail(habits[0], ui(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-page-font-1.5.png")
        compose.onNode(hasTestTag("habit-stats")).assertIsDisplayed()
    }

    /** Stats, state 1: a habit 3 days old shows when the chart comes, one bar, no "0 / 0" rows. */
    @Test fun statsForANewHabit() {
        val fresh = habit("n1", "Read 10 pages", "2026-10-26 21:00")
        show { org.pagel.ai.ui.habits.StatsCard(org.pagel.ai.data.habits.habitWeeks(fresh, listOf(HabitTickEntity("2026-10-27", "n1")), emptyList(), LocalDate.parse(today))) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-stats-new.png")
        compose.onNodeWithText("Stats show up after a week · 4 days to go").assertIsDisplayed()
        compose.onAllNodesWithText("0 / 0").assertCountEquals(0)
    }

    private val twelveWeeks: Pair<TaskEntity, List<HabitTickEntity>> = run {
        val h = habit("t12", "Stretch", "2026-08-01 07:30")
        val start = LocalDate.parse("2026-08-03")
        // Most days done, a weaker stretch in September, a full last week.
        val t = (0L..86L).map { start.plusDays(it) }.filter { d -> !(d.monthValue == 9 && d.dayOfMonth % 3 == 0) && d.dayOfMonth % 7 != 0 }
            .map { HabitTickEntity(it.toString(), "t12") }
        h to t
    }

    /** Stats, state 2: 12 weeks of data, the strip over the bars. */
    @Test fun statsForTwelveWeeks() {
        val (h, t) = twelveWeeks
        val w = org.pagel.ai.data.habits.habitWeeks(h, t, emptyList(), LocalDate.parse(today))
        assertEquals(12, w.bars.size)
        show { org.pagel.ai.ui.habits.StatsCard(w) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-stats-12-weeks.png")
        compose.onNodeWithText("Average ${w.average} % · Best week ${w.bestWeek} %").assertIsDisplayed()
    }

    /** Stats, state 3: the same at 1.5x font, no sideways scroll, the three numbers fit 360 dp. */
    @Test fun statsForTwelveWeeksAtLargeFont() {
        val (h, t) = twelveWeeks
        show(1.5f) { org.pagel.ai.ui.habits.StatsCard(org.pagel.ai.data.habits.habitWeeks(h, t, emptyList(), LocalDate.parse(today))) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-stats-12-weeks-font-1.5.png")
        compose.onNode(hasTestTag("habit-weeks")).assertIsDisplayed()
    }

    /** The ⋮ sheet: Look · Schedule · Routine · Actions; "After" lists only real habits, never itself or a loop. */
    @Test fun theSettingsSheetAndTheStackPicker() {
        val weekly = habit("w1", "Run the weekly review", "2026-11-01", every = "week")
        val u = ui(h = habits + weekly)
        show { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { org.pagel.ai.ui.habits.HabitSettings(habits[0], u, org.pagel.ai.ui.habits.SheetAt.TOP, reminder = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-settings-sheet.png")
        compose.onNodeWithText("LOOK").assertIsDisplayed()
        compose.onNodeWithText("ROUTINE").performScrollTo()
        compose.onNodeWithText("After · None").performScrollTo().performClick()
        compose.onNodeWithContentDescription("After: ☕ Coffee").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("After: Drink water").performScrollTo().assertExists()
        // A weekly task is not a habit; h2 already comes after h1, so h1 after h2 would be a loop; never itself.
        compose.onAllNodesWithContentDescription("After: Run the weekly review").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("After: Go through five saves in your Inbox").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("After: Stretch for ten minutes").assertCountEquals(0)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-settings-after.png")
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
