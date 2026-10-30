package org.pagel.ai.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.upcoming.CalView
import org.pagel.ai.data.upcoming.upcomingView
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.upcoming.CAL_TITLE
import org.pagel.ai.ui.upcoming.UpcomingScreen
import org.pagel.ai.ui.upcoming.UpcomingUi
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Core round 4 · Builder B: the calendar Monday to Sunday, Google-clean (hour lines only, no day lines, soft blocks),
 * a real drag (a glass ghost on the 15-minute grid, read at the drop), a resize, Month's drop, the swipe that keeps
 * the grid's place, at 360 dp and at 1.5x font. Today is Wednesday 28 October 2026.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class CalendarScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.parse("2026-10-28")
    private val projects = listOf(
        ProjectEntity("humopedia", "Humopedia", null, 0, 41), ProjectEntity("me", "Me", null, 1, 12), ProjectEntity("ababil", "ABABIL", null, 2, 27),
    ).associateBy { it.name }

    private fun task(id: String, text: String, due: String?, project: String = "me", every: String? = null) = TaskEntity(
        id = id, project = project, phase = "Now", sort = 1.0, text = text, title = null, status = "open", priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
    )

    private val open = listOf(
        task("u3", "Record the fractions lesson", "2026-10-28 15:00", "humopedia"),
        task("u4", "Review the lesson draft", "2026-10-29", "humopedia"),
        task("u7", "Pick up the parcel", "2026-10-29"),
        task("u5", "Measure the drone's hover time", "2026-10-31 10:00", "ababil"),
        task("u8", "Write the IT Park report", "2026-10-30 09:00-11:00", "humopedia"),
        task("u9", "Plan the lesson week", "2026-11-01 10:00", "humopedia"),
        task("u6", "Gym", "2026-10-29 18:00", every = "tue,thu,sun"),
        task("h1", "Bomdod", "2026-10-28 05:30", every = "day"),
    )

    private fun ui() = UpcomingUi(loading = false, today = today, view = upcomingView(open, today), projects = projects, tasks = open)

    private var moved: Pair<String, String>? = null
    private var movedDay: Pair<String, LocalDate>? = null

    private fun show(view: CalView, fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
                GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { Calendar(view) } }
            }
        }
        compose.mainClock.advanceTimeBy(500)
    }

    @Composable private fun Calendar(view: CalView) = UpcomingScreen(
        ui(), PaddingValues(top = 16.dp, bottom = 96.dp), initialView = view,
        onMoveTo = { id, due -> moved = id to due }, onMove = { id, day -> movedDay = id to day },
    )

    /**
     * Checker round 2 #1: every calendar title on screen (blocks, the ghost, all-day chips) is whole words of its task's
     * title, every line ends between two words, and nothing spills out of its box ("Recor", "Gy", "Revie" were cut).
     */
    private fun assertNoBrokenWord() {
        val titles = open.map { it.text }
        val nodes = compose.onAllNodesWithTag(CAL_TITLE, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no calendar title on screen", nodes.isNotEmpty())
        for (n in nodes) {
            val shown = n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
            assertTrue("\"$shown\" is not whole words of a title", shown.isEmpty() || shown.startsWith("+") || titles.any { it == shown || it.startsWith("$shown ") })
            val results = mutableListOf<TextLayoutResult>()
            n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val r = results.single()
            if (shown.isEmpty()) continue
            val why = "in ${n.boundsInRoot}, box ${r.layoutInput.constraints}, text ${r.size}, ${r.lineCount} lines, " +
                "wide ${r.didOverflowWidth}, tall ${r.didOverflowHeight}, ${r.layoutInput.style.fontSize}"
            assertFalse("\"$shown\" spills out of its box ($why)", r.hasVisualOverflow)
            for (i in 0 until r.lineCount) {
                val end = r.getLineEnd(i)
                assertTrue("\"$shown\" breaks inside a word on line ${i + 1}", end >= shown.length || shown[end - 1].isWhitespace() || shown[end].isWhitespace())
            }
        }
    }

    /**
     * Checker round 2 #2 (never alarm red): no pixel is a saturated warm red. Alarm red = HSV hue at most 20° or at least
     * 340°, saturation at least 0.40, value at least 0.55 (ABABIL's bar was #FF7E76: hue 3°, saturation 0.54).
     */
    private fun assertNoAlarmRed(what: String) {
        val map = compose.onRoot().captureToImage().toPixelMap()
        var red = 0
        for (y in 0 until map.height) for (x in 0 until map.width) {
            val c = map[x, y]
            val max = maxOf(c.red, c.green, c.blue)
            val min = minOf(c.red, c.green, c.blue)
            val d = max - min
            if (max < 0.55f || d / max < 0.40f || max != c.red) continue
            var hue = 60f * ((c.green - c.blue) / d)
            if (hue < 0f) hue += 360f
            if (hue <= 20f || hue >= 340f) red++
        }
        assertEquals("alarm-red pixels in $what", 0, red)
    }

    private fun px(dp: Float) = dp * compose.onRoot().fetchSemanticsNode().boundsInRoot.width / 360f
    private fun centre(desc: String) = compose.onNodeWithContentDescription(desc).fetchSemanticsNode().boundsInRoot.center

    /** Long-press [node] at [at] (its own space, default its centre), carry it by [by] in steps; [lift] = keep it held. */
    private fun SemanticsNodeInteraction.hold(by: Offset, at: Offset? = null, lift: Boolean = true) = performTouchInput {
        val from = at ?: center
        down(from)
        advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
        for (k in 1..10) { moveTo(from + by * (k / 10f)); advanceEventTime(16) }
        if (lift) up()
    }

    @Test fun theWeekIsMondayToSundayAndClean() {
        show(CalView.WEEK)
        compose.onNodeWithContentDescription("Record the fractions lesson, 15:00").performScrollTo()
        compose.mainClock.advanceTimeBy(300)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/cal4-week-360x800.png")
        assertNoBrokenWord()
        assertNoAlarmRed("the Week")
        // The dates once, on the columns: Monday 26 October to Sunday 1 November (the next week waits off screen), no strip with dots.
        compose.onNodeWithContentDescription("Monday 26 October").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sunday 1 November").assertIsDisplayed()
        compose.onNodeWithContentDescription("Monday 2 November").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Thursday 29 October, 3 tasks").assertDoesNotExist()
        compose.onNodeWithContentDescription("Change view").assertIsDisplayed()
        // Two all-day tasks on Thursday fold to one "+2" chip; the daily habit is in no all-day line.
        compose.onNodeWithContentDescription("2 all-day tasks on Thursday 29 October, show all").assertIsDisplayed()
        compose.onAllNodesWithText("Bomdod").fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test fun theWeekAtLargeFont() {
        show(CalView.WEEK, 1.5f)
        compose.onNodeWithContentDescription("Record the fractions lesson, 15:00").performScrollTo()
        compose.mainClock.advanceTimeBy(300)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/cal4-week-360x800-font-1.5.png")
        assertNoBrokenWord()
        compose.onNodeWithContentDescription("Wednesday 28 October").assertIsDisplayed()
        compose.onNodeWithContentDescription("Change view").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun theWeekOnTheSmallScreen() {
        show(CalView.WEEK, 1.5f)
        compose.onNodeWithContentDescription("Record the fractions lesson, 15:00").performScrollTo()
        compose.mainClock.advanceTimeBy(300)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/cal4-week-320x640-font-1.5.png")
        assertNoBrokenWord()
        compose.onNodeWithContentDescription("Sunday 1 November").assertIsDisplayed()
    }

    /** Wednesday 15:00 long-pressed and carried two columns right and an hour down: Friday 16:00, read at the drop. */
    @Test fun aBlockDraggedToAnotherDayAndTimeMovesThere() {
        show(CalView.WEEK)
        val block = compose.onNodeWithContentDescription("Record the fractions lesson, 15:00")
        block.performScrollTo()
        val dx = centre("Column Friday 30 October").x - centre("Column Wednesday 28 October").x
        block.hold(Offset(dx, px(52f)), lift = false)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/cal4-week-mid-drag-ghost.png")
        compose.onNodeWithContentDescription("Moving Record the fractions lesson to 16:00").assertIsDisplayed()
        assertNoBrokenWord()
        block.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("u3" to "2026-10-30 16:00", moved)
    }

    /** The bottom edge of a range carried an hour down: the end moves, the start stays. */
    @Test fun theBottomEdgeResizesARange() {
        show(CalView.DAY)
        compose.onNodeWithContentDescription("Change view").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/cal4-day-360x800.png")
        compose.onNodeWithContentDescription("Record the fractions lesson, 15:00").performScrollTo()
        val block = compose.onNodeWithContentDescription("Record the fractions lesson, 15:00")
        val h = block.fetchSemanticsNode().boundsInRoot.height
        block.hold(Offset(0f, px(60f)), at = Offset(block.fetchSemanticsNode().size.width / 2f, h - px(4f)))
        compose.waitForIdle()
        assertEquals("u3" to "2026-10-28 15:00-16:30", moved)
    }

    /** Checker P4 #1 for the grid: Gym (a habit) and Bomdod (Namoz) never move. */
    @Test fun aHabitBlockCannotBeDragged() {
        show(CalView.DAY)
        // A daily habit is on the next day's page too (composed off screen): the first is today's.
        val bomdod = compose.onAllNodesWithContentDescription("Bomdod, 05:30")[0]
        bomdod.performScrollTo()
        bomdod.hold(Offset(0f, px(120f)))
        compose.waitForIdle()
        assertNull(moved)
    }

    /** A swipe turns the week; the dates follow the finger and the grid keeps its place (no jump, no lost scroll). */
    @Test fun aSwipeTurnsTheWeekAndKeepsTheGridsPlace() {
        show(CalView.WEEK)
        // The hour label "07" sits beside the grid (never a date): its place says where the grid is scrolled to.
        val before = compose.onAllNodesWithText("07").fetchSemanticsNodes().first().positionInRoot.y
        compose.onNodeWithContentDescription("Column Thursday 29 October").performTouchInput { swipeLeft(startX = right - 4f, endX = left - 400f) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithContentDescription("Monday 2 November").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to today").assertIsDisplayed()
        val after = compose.onAllNodesWithText("07").fetchSemanticsNodes().first().positionInRoot.y
        assertEquals(before, after, 1f)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/cal4-week-next-360x800.png")
        compose.onNodeWithContentDescription("Back to today").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithContentDescription("Monday 26 October").assertIsDisplayed()
    }

    /** Month: a task of the day's list long-pressed and dropped on Saturday 31: it moves there, its time kept. */
    @Test fun aRowDroppedOnADayOfTheMonthMovesThere() {
        show(CalView.MONTH)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/cal4-month-360x800.png")
        assertNoAlarmRed("the Month")
        val row = compose.onAllNodesWithText("Record the fractions lesson")[0]
        val from = row.fetchSemanticsNode().boundsInRoot.center
        val to = centre("Saturday 31 October, 1 task")
        row.hold(to - from)
        compose.waitForIdle()
        assertEquals("u3" to LocalDate.parse("2026-10-31"), movedDay)
    }

    @Test fun theMonthAtLargeFont() {
        show(CalView.MONTH, 1.5f)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/cal4-month-360x800-font-1.5.png")
        assertNoAlarmRed("the Month at 1.5x")
        compose.onNodeWithContentDescription("Next month").assertIsDisplayed()
    }
}
