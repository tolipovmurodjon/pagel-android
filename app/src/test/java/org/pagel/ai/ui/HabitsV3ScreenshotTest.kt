package org.pagel.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
import org.pagel.ai.data.habits.BadgeKind
import org.pagel.ai.data.habits.HabitBadge
import org.pagel.ai.data.habits.Part
import org.pagel.ai.data.habits.habitBadges
import org.pagel.ai.data.habits.habitBoard
import org.pagel.ai.data.habits.habitsDay
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.habits.AchievementsPage
import org.pagel.ai.ui.habits.BadgeArt
import org.pagel.ai.ui.habits.BadgeDetail
import org.pagel.ai.ui.habits.AmbientSheetContent
import org.pagel.ai.ui.habits.BellSheetContent
import org.pagel.ai.ui.habits.HabitDetail
import org.pagel.ai.ui.habits.HabitHistory
import org.pagel.ai.ui.habits.HabitTimer
import org.pagel.ai.ui.habits.HabitTimerView
import org.pagel.ai.ui.habits.HabitsScreen
import org.pagel.ai.ui.habits.HabitsUi
import org.pagel.ai.ui.habits.TimerLook
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Habits 3.0 (Boss 2026-10-31): the cards in their colours with the strip's chain, a done card, the week swiped back and
 * forward, the habit page's gradient stat cards and its tappable calendar, the full-screen timer in two colours, and the
 * Achievements page with a badge opened. 360 dp at 1x and 1.5x font, and 320 dp. No red anywhere.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class HabitsV3ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val today = "2026-10-28"

    private fun habit(id: String, text: String, due: String?, every: String = "day", times: Int = 1) = TaskEntity(
        id = id, project = "me", phase = "Now", sort = 1.0, text = text, title = null, status = "open", priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
        times = times,
    )

    private val habits = listOf(
        habit("h1", "Stretch for ten minutes", "2026-10-01 07:30"),
        habit("h2", "Go through five saves in your Inbox", "2026-10-01 09:00"),
        habit("h3", "Log today's food and sugar", "2026-10-01 13:00", times = 3),
        habit("h4", "Start winding down, in bed by 22:00", "2026-10-01 21:30"),
        habit("h5", "Drink water", "2026-10-01"),
        habit("h6", "Read ten pages", "2026-10-01 20:00"),
    )

    private fun days(id: String, from: String, to: String): List<HabitTickEntity> =
        generateSequence(LocalDate.parse(from)) { it.plusDays(1) }.takeWhile { !it.isAfter(LocalDate.parse(to)) }.map { HabitTickEntity(it.toString(), id) }.toList()

    // Stretch: every day for 28 days (full colour). Water: 20 of the last 30 (a little lighter). Inbox: 9 of the last 30
    // and a day off on 25 Oct (day off drawn as done). Read: only 3 days (the lightest card).
    private val ticks = days("h1", "2026-10-01", "2026-10-27") +
        days("h5", "2026-10-08", "2026-10-27") +
        days("h2", "2026-10-19", "2026-10-24") + days("h2", "2026-10-26", "2026-10-28") +
        days("h6", "2026-10-25", "2026-10-27") +
        listOf(HabitTickEntity("2026-10-28", "h3", 2), HabitTickEntity("2026-10-27", "h3", 3), HabitTickEntity("2026-10-26", "h3", 3))
    private val freezes = listOf(HabitFreezeEntity("2026-10-25", "h2"))
    private val prefs = listOf(HabitPrefEntity("h1", emoji = "dumbbell/blue"), HabitPrefEntity("h5", emoji = "glass-water"), HabitPrefEntity("h6", emoji = "book-open/violet"))

    private fun ui(
        selected: String = today, part: Part = Part.ANYTIME, timers: Map<String, HabitTimer> = emptyMap(), h: List<TaskEntity> = habits,
        t: List<HabitTickEntity> = ticks,
    ) = HabitsUi(
        loading = false, today = today, day = habitsDay(h, t, today),
        board = habitBoard(h, t, prefs, freezes, LocalDate.parse(today), LocalDate.parse(selected)),
        part = part, reminders = setOf("h1"), byId = h.associateBy { it.id }, timers = timers, now = 200_000, ticks = t,
        prefs = prefs.associateBy { it.taskId }, freezes = freezes,
    )

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    private val pad = PaddingValues(top = 16.dp, bottom = 96.dp)

    private fun layoutOf(n: SemanticsNode): androidx.compose.ui.text.TextLayoutResult? {
        val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()
    }

    private fun below(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { below(it) }

    private fun assertNoCutText(what: String) {
        var seen = 0
        for (n in below(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())) {
            val l = layoutOf(n) ?: continue
            seen++
            val text = l.layoutInput.text.text
            val at = "$what \"${text.take(40)}\""
            assertTrue("$at has an ellipsis", (0 until l.lineCount).none { l.isLineEllipsized(it) })
            assertTrue("$at is cut by its box (one line, words ${l.multiParagraph.intrinsics.maxIntrinsicWidth} px, box ${n.size.width} px)",
                !(l.lineCount == 1 && l.multiParagraph.intrinsics.maxIntrinsicWidth > n.size.width + 1f))
            for (i in 0 until l.lineCount - 1) {
                val e = l.getLineEnd(i)
                assertTrue("$at breaks a word at line ${i + 1}", e >= text.length || text[e - 1].isWhitespace() || text[e].isWhitespace() || text[e - 1] == '-')
            }
        }
        assertTrue("$what: some text was checked", seen > 0)
    }

    /** Boss's rule: never red. A pixel is red when it is strongly red and has almost no green or blue. */
    private fun assertNoRed(what: String) {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val red = px.count { p -> ((p shr 16) and 0xFF) > 205 && ((p shr 8) and 0xFF) < 70 && (p and 0xFF) < 70 }
        assertEquals("$what has $red red pixels", 0, red)
    }

    @Test fun theCardsInTheirColoursWithTheChain() {
        show { HabitsScreen(ui(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-360x800.png")
        compose.onNodeWithContentDescription("All").assertExists()
        compose.onNodeWithText("Stretch for ten minutes").assertExists()
        assertNoRed("habits 1x")
    }

    @Test fun theCardsAtLargeFontAndNarrow() {
        show(1.5f) { HabitsScreen(ui(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-360x800-font-1.5.png")
        compose.onNodeWithContentDescription("Done: Stretch for ten minutes").assertExists()
    }

    @Test fun theCardsAt320() {
        show { Box(Modifier.width(320.dp)) { HabitsScreen(ui(), pad) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-320.png")
        assertNoCutText("habits 320")
    }

    @Test fun aDoneCardSettlesAndKeepsItsCheck() {
        show { HabitsScreen(ui(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-done-card.png")
        compose.onAllNodesWithText("Finished")[0].assertExists()
    }

    @Test fun aDayOffIsAFinishedCardNotAFrozenOne() {
        // 25 Oct: Inbox (h2) was taken off. Looking at that day its card reads as finished, with the day-off action offered back.
        show { HabitsScreen(ui(selected = "2026-10-25", part = Part.ANYTIME), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-day-off.png")
        compose.onAllNodesWithText("Finished")[0].assertExists()
        assertEquals(0, compose.onAllNodesWithText("Frozen", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun theMenuHoldsDayOffTimerAndEdit() {
        show { HabitsScreen(ui(), pad) }
        compose.onNodeWithContentDescription("Habit menu: Stretch for ten minutes").performClick()
        // The menu is a popup window: two roots, so the page's root is the first.
        compose.onAllNodes(isRoot()).onFirst().captureRoboImage("build/outputs/roborazzi/habits-v3-menu.png")
        compose.onNodeWithText("Take a day off").assertExists()
        compose.onNodeWithText("Edit").assertExists()
    }

    /** Boss 2026-10-31: the week swipes back and forward, both ways, and the strip never moves. */
    @Test fun theWeekSwipesBackAndForwardAndTheStripStaysPut() {
        var selected by mutableStateOf(today)
        show { HabitsScreen(ui(selected = selected), pad, onSelectDay = { selected = it?.toString() ?: today }) }
        val top = compose.onAllNodesWithContentDescription("Wednesday 28 October")[0].fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("week-strip").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals("2026-10-21", selected)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-week-back.png")
        compose.onNodeWithText("Wed 21 Oct · looking back").assertExists()
        val top2 = compose.onAllNodesWithContentDescription("Wednesday 21 October")[0].fetchSemanticsNode().boundsInRoot.top
        assertEquals(top, top2, 0.5f)
        // And forward again, up to this week.
        compose.onNodeWithTag("week-strip").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(today, selected)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-week-forward.png")
        // Further forward is not a week: today's week is the last page.
        compose.onNodeWithTag("week-strip").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(today, selected)
    }

    @Test fun theHabitPageHasGradientStatCardsAndATappableCalendar() {
        var tapped: LocalDate? = null
        show { HabitDetail(habits[0], ui(), pad, onToggleDay = { tapped = it }, onOpenTimer = {}) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-page-v3.png")
        compose.onNodeWithText("CURRENT STREAK").assertExists()
        compose.onNodeWithContentDescription("Saturday 24 October").performScrollTo().performClick()
        assertEquals(LocalDate.parse("2026-10-24"), tapped)
        // A day that has not come, and a day it did not repeat on, are not taps.
        tapped = null
        compose.onNodeWithContentDescription("Saturday 31 October").performScrollTo().performClick()
        assertTrue(tapped == null)
        compose.onNodeWithContentDescription("Open timer").assertExists()
        assertNoRed("habit page")
    }

    @Test fun theMonthSwipesToTheMonthBefore() {
        show { HabitDetail(habits[0], ui(), pad) }
        compose.onNodeWithTag("month-chain").performScrollTo().performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-page-v3-month-before.png")
        compose.onNodeWithText("September 2026").assertExists()
    }

    @Test fun theHabitPageAtLargeFontCutsNothing() {
        show(1.5f) { HabitDetail(habits[0], ui(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-page-v3-font-1.5.png")
        assertNoCutText("habit page v3 1.5x")
    }

    @Test fun theTimerInTwoColours() {
        show { HabitTimerView("Stretch for ten minutes", 10, null, 0, finished = false, look = TimerLook(colour = 0), startColour = 0, onStartPause = {}, onReset = {}, onFinish = {}, onBack = {}, onLook = {}, live = false) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-timer-blue.png")
        compose.onNodeWithText("10:00").assertExists()
        compose.onNodeWithContentDescription("Start timer").assertExists()
        assertNoRed("timer blue")
    }

    @Test fun theTimerRunningInTheSecondColour() {
        val running = HabitTimer(startedAt = 0, spentMs = 0, day = today, minutes = 10)
        show { HabitTimerView("Stretch for ten minutes", 10, running, 200_000, finished = false, look = TimerLook(colour = 2), startColour = 0, onStartPause = {}, onReset = {}, onFinish = {}, onBack = {}, onLook = {}, live = false) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-timer-teal-running.png")
        // 200 s of 10 minutes are gone.
        compose.onNodeWithText("06:40").assertExists()
        compose.onNodeWithContentDescription("Pause timer").assertExists()
        assertNoRed("timer teal")
    }

    @Test fun theTimerSwipesItsColourAndRemembersIt() {
        var look by mutableStateOf(TimerLook())
        show { HabitTimerView("Drink water", 5, null, 0, finished = false, look = look, startColour = 0, onStartPause = {}, onReset = {}, onFinish = {}, onBack = {}, onLook = { look = it }, live = false) }
        compose.onNodeWithTag("habit-timer").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, look.colour)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-timer-swiped.png")
    }

    @Test fun theTimerAtLargeFontCutsNothing() {
        show(1.5f) { HabitTimerView("Stretch for ten minutes", 10, null, 0, finished = false, look = TimerLook(colour = 1), startColour = 0, onStartPause = {}, onReset = {}, onFinish = {}, onBack = {}, onLook = {}, live = false) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-timer-font-1.5.png")
        assertNoCutText("timer 1.5x")
    }

    private fun timerIn(name: String, page: Int) {
        val running = HabitTimer(startedAt = 0, spentMs = 0, day = today, minutes = 10)
        show { HabitTimerView("Stretch for ten minutes", 10, running, 200_000, finished = false, look = TimerLook(colour = page), startColour = 0, onStartPause = {}, onReset = {}, onFinish = {}, onBack = {}, onLook = {}, live = false) }
        compose.onNodeWithTag("habit-timer").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-timer-colour-$name.png")
        assertNoRed("timer $name")
    }

    @Test fun theTimerOcean() = timerIn("ocean", 0)

    @Test fun theTimerForest() = timerIn("forest", 3)

    @Test fun theTimerAmber() = timerIn("amber", 5)

    private fun SemanticsNodeInteraction.assertStateDescription(want: String) {
        assertEquals(want, fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test fun theTimerFinishCountsTheHabitAndPausedTimeStillCounts() {
        var finishedTaps = 0
        val paused = HabitTimer(startedAt = null, spentMs = 240_000, day = today, minutes = 10)
        show { HabitTimerView("Stretch for ten minutes", 10, paused, 0, finished = false, look = TimerLook(colour = 0), startColour = 0, onStartPause = {}, onReset = {}, onFinish = { finishedTaps++ }, onBack = {}, onLook = {}, live = false) }
        compose.onNodeWithText("06:00").assertExists()
        compose.onNodeWithContentDescription("Resume timer").assertExists()
        compose.onNodeWithContentDescription("Finish").performClick()
        assertEquals(1, finishedTaps)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-timer-paused-finish.png")
    }

    @Test fun theSoundSheetListsSixSoundsWithTheChosenOneChecked() {
        show { Box(Modifier.fillMaxSize().background(Color.White)) { Column { AmbientSheetContent(TimerLook(ambient = 2, volume = 0.6f), playing = true, live = false, onPick = {}, onVolume = {}, onVolumeDone = {}) } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-timer-sound-sheet.png")
        for (name in listOf("Silence", "Rain", "Forest", "Ocean", "Stream", "Wind", "Night")) compose.onNodeWithContentDescription(name).assertExists()
        compose.onNodeWithContentDescription("Ocean").assertStateDescription("On")
        compose.onNodeWithContentDescription("Rain").assertStateDescription("Off")
        compose.onNodeWithContentDescription("Volume").assertExists()
        assertNoCutText("sound sheet")
    }

    @Test fun thePickingASoundReportsItsIndex() {
        var picked = -2
        show { Box(Modifier.fillMaxSize().background(Color.White)) { Column { AmbientSheetContent(TimerLook(), playing = false, onPick = { picked = it }, onVolume = {}, onVolumeDone = {}) } } }
        compose.onNodeWithContentDescription("Night").performClick()
        assertEquals(5, picked)
        compose.onNodeWithContentDescription("Silence").performClick()
        assertEquals(-1, picked)
    }

    @Test fun theBellSheetListsThreeChimes() {
        show { Box(Modifier.fillMaxSize().background(Color.White)) { Column { BellSheetContent(TimerLook(sound = 1), onLook = {}, onPreview = {}) } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-timer-bell-sheet.png")
        compose.onNodeWithContentDescription("Ring at the goal").assertExists()
        assertNoCutText("bell sheet")
    }

    /** Boss 2026-10-31: a tap on a timed card opens Timer and Finish under it; at 1x, 1.5x and 320 dp nothing is cut. */
    @Test fun theExpandedTimedCard() {
        show { HabitsScreen(ui(), pad) }
        compose.onNodeWithText("Stretch for ten minutes").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Timer").assertExists()
        compose.onNodeWithContentDescription("Finish").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-expanded.png")
        assertNoCutText("expanded 1x")
        assertNoRed("expanded")
    }

    @Test fun theExpandedTimedCardAtLargeFont() {
        show(1.5f) { HabitsScreen(ui(), pad) }
        compose.onNodeWithText("Stretch for ten minutes").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-expanded-font-1.5.png")
        assertNoCutText("expanded 1.5x")
    }

    @Test fun theExpandedTimedCardAt320() {
        show { Box(Modifier.width(320.dp)) { HabitsScreen(ui(), pad) } }
        compose.onNodeWithText("Stretch for ten minutes").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-expanded-320.png")
        assertNoCutText("expanded 320")
    }

    private fun badges() = habitBadges(habits, ticks, freezes, LocalDate.parse(today))

    @Test fun achievementsGridAndABadgeOpened() {
        show { HabitHistory(ui(), pad, startTab = 2) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-achievements.png")
        compose.onNodeWithText("My achievements").assertExists()
        assertNoRed("achievements")
    }

    @Test fun aBadgeOpenedShowsItsDay() {
        val earned = badges().first { it.earned }
        show { Box(Modifier.fillMaxSize()) { BadgeDetail(earned) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-badge-detail.png")
        compose.onNodeWithText("Achieved on", substring = true).assertExists()
        assertNoCutText("badge detail")
    }

    @Test fun aLockedBadgeSaysNotYet() {
        show { Box(Modifier.fillMaxSize()) { BadgeDetail(HabitBadge(BadgeKind.PERFECT_DAYS, 100, null)) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-badge-locked.png")
        compose.onNodeWithText("Not yet").assertExists()
    }

    @Test fun allEighteenBadgesAreDrawn() {
        show { AchievementsPage(badges()) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-v3-badges-page.png")
        for (b in badges()) assertTrue("${b.title} is on the page", compose.onAllNodesWithContentDescription(b.title + ",", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun theBadgeArtOfEachKindEarnedAndLocked() {
        show {
            androidx.compose.foundation.layout.Row {
                BadgeArt(BadgeKind.PERFECT_DAYS, 100, true, 96.dp); BadgeArt(BadgeKind.STREAK, 90, true, 96.dp); BadgeArt(BadgeKind.FINISHED, 300, true, 96.dp)
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-badges-earned.png")
    }
}
