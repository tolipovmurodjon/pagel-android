package org.pagel.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.upcoming.completedGroups
import org.pagel.ai.data.upcoming.upcomingView
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.habits.ReminderContent
import org.pagel.ai.ui.task.PanelActions
import org.pagel.ai.ui.task.PanelStep
import org.pagel.ai.ui.task.PanelUi
import org.pagel.ai.ui.task.TaskPanelContent
import org.pagel.ai.ui.upcoming.CompletedScreen
import org.pagel.ai.ui.upcoming.CompletedUi
import org.pagel.ai.ui.upcoming.UpcomingScreen
import org.pagel.ai.ui.upcoming.UpcomingUi
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Phase 4 at Boss's sizes: Upcoming with its week strip and Overdue, the same at 1.5× font, Completed by day (360 dp
 * and 1.5×), a done task's read-only panel, and the reminder sheet's two missing-grant rows (Phase 3 checker #6).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class UpcomingScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.parse("2026-10-28")
    private val projects = listOf(
        ProjectEntity("humopedia", "Humopedia", null, 0, 41), ProjectEntity("me", "Me", null, 1, 12), ProjectEntity("ababil", "ABABIL", null, 2, 27),
    ).associateBy { it.name }

    private fun task(id: String, text: String, due: String?, project: String = "me", priority: Int = 2, every: String? = null, status: String = "open", doneAt: String? = null) = TaskEntity(
        id = id, project = project, phase = "Now", sort = 1.0, text = text, title = null, status = status, priority = priority, star = false,
        due = due, every = every, doneWhen = null, doneAt = doneAt, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
    )

    private val open = listOf(
        task("u1", "Send the grant form", "2026-10-27", "humopedia", priority = 1),
        task("u2", "Pay the internet bill", "2026-10-25 18:00", priority = 0),
        task("u3", "Record the fractions lesson", "2026-10-28 15:00", "humopedia"),
        task("u4", "Review the lesson draft", "2026-10-29", "humopedia"),
        task("u5", "Measure the drone's hover time", "2026-10-31 10:00", "ababil", priority = 1),
        task("u6", "Gym", "2026-10-29 18:00", every = "tue,thu,sun"),
    )

    private fun upcoming(tasks: List<TaskEntity> = open) = UpcomingUi(loading = false, today = today, view = upcomingView(tasks, today), projects = projects, tasks = tasks)

    private val utc = { s: String -> OffsetDateTime.parse(s).toInstant().toString() }
    private val done = listOf(
        task("c1", "Renew the domain", null, status = "done", doneAt = utc("2026-10-28T09:12:00+05:00")),
        task("c2", "Ship the native app", null, "humopedia", status = "done", doneAt = utc("2026-10-28T00:04:00+05:00")),
        task("c3", "Call the bank about the report", null, status = "done", doneAt = utc("2026-10-27T23:58:00+05:00")),
        task("c4", "Export the hover log", null, "ababil", status = "done", doneAt = utc("2026-10-26T16:40:00+05:00")),
        task("c5", "Write the IT Park report", null, "humopedia", status = "done", doneAt = utc("2026-10-12T11:00:00+05:00")),
    )
    private fun completed() = CompletedUi(loading = false, today = today, groups = completedGroups(done, today), count = done.size, projects = projects)

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    private val pad = PaddingValues(top = 16.dp, bottom = 96.dp)

    @Test fun upcomingWithTheWeekStripAndOverdueAt360() {
        show { UpcomingScreen(upcoming(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/upcoming-360.png")
        compose.onNodeWithText("Overdue").assertIsDisplayed()
        compose.onNodeWithContentDescription("Move all to today").assertIsDisplayed()
        // Each task once, habits not counted ("21 planned" for 4 tasks, checker P4 #4).
        compose.onNodeWithText("5 tasks · 2 late").assertIsDisplayed()
        // The strip: today and the days with tasks say so.
        compose.onNodeWithContentDescription("Thursday 29 October, 2 tasks").assertIsDisplayed()
        compose.onNodeWithContentDescription("Saturday 31 October, 1 task").assertIsDisplayed()
    }

    @Test fun upcomingAtLargeFont() {
        show(1.5f) { UpcomingScreen(upcoming(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/upcoming-360-font-1.5.png")
        compose.onNodeWithText("Upcoming").assertIsDisplayed()
        compose.onNodeWithContentDescription("Wednesday 28 October, 1 task").assertIsDisplayed()
    }

    /** Boss 2026-10-28: the same daily habits on every day were a wall. Later days fold them into one line; tap opens. */
    @Test fun dailyHabitsFoldOnLaterDays() {
        val habits = listOf(
            task("h1", "Go through five saves in your Inbox", "2026-10-28", every = "day"),
            task("h2", "Log today's food and sugar, then shut down", "2026-10-28 20:45", every = "day"),
            task("h3", "Start winding down, in bed by 22:00", "2026-10-28 21:30", every = "day"),
            task("h4", "Do the five-minute floor routine", "2026-10-28 05:30", every = "day"),
            task("h5", "Weigh yourself and measure your waist", "2026-11-02 06:00", every = "mon"),
        )
        show { UpcomingScreen(upcoming(open.drop(2) + habits), pad) }
        // Today and Tomorrow show them in full; from Friday they fold into one line (Monday keeps its weigh-in row).
        // The list, not the week pager (round C: the strip scrolls sideways too).
        val list = compose.onNode(hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher("scrolls vertically") { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) })
        list.performScrollToNode(hasContentDescription("4 daily habits, folded"))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/upcoming-habits-folded.png")
        // The scroll brings the line to the list's top edge, under the glass top (round C): tap it by its action.
        compose.onAllNodesWithContentDescription("4 daily habits, folded")[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("4 daily habits, shown").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/upcoming-habits-open.png")
    }

    @Test fun upcomingWithNothingOverdue() {
        show { UpcomingScreen(upcoming(open.drop(2)), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/upcoming-no-overdue.png")
        compose.onNodeWithText("Overdue").assertDoesNotExist()
        compose.onNodeWithText("Today · Wed 28 Oct").assertIsDisplayed()
        compose.onNodeWithText("Friday 30 Oct").assertIsDisplayed()
    }

    @Test fun moveAllToTodayCallsBackWithTheOverdueOldestFirst() {
        var moved = emptyList<String>()
        show { UpcomingScreen(upcoming(), pad, onMoveAll = { moved = it }) }
        compose.onNodeWithContentDescription("Move all to today").performClick()
        assertEquals(listOf("u2", "u1"), moved)
    }

    /** Long-press a row, then carry it in steps onto a day of the strip and let go there (as the walk's finger does). */
    private fun SemanticsNodeInteraction.dragOnto(day: String) {
        val to = compose.onNodeWithContentDescription(day).fetchSemanticsNode().boundsInRoot.center
        val at = fetchSemanticsNode().boundsInRoot.topLeft
        performTouchInput {
            val from = center
            val by = to - (at + from)
            down(from)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            for (k in 1..8) { moveTo(from + by * (k / 8f)); advanceEventTime(16) }
            up()
        }
        compose.waitForIdle()
    }

    @Test fun aTaskDraggedOntoADayOfTheStripMovesThere() {
        var moved: Pair<String, LocalDate>? = null
        show { UpcomingScreen(upcoming(), pad, onMove = { id, day -> moved = id to day }) }
        compose.onNodeWithText("Review the lesson draft").dragOnto("Saturday 31 October, 1 task")
        assertEquals("u4" to LocalDate.parse("2026-10-31"), moved)
    }

    /** Checker P4 #1: a habit row has no drag (its due is the whole series; Habits owns it). */
    @Test fun aHabitRowCannotBeDragged() {
        var moved: Pair<String, LocalDate>? = null
        show { UpcomingScreen(upcoming(), pad, onMove = { id, day -> moved = id to day }) }
        compose.onAllNodesWithText("Gym")[0].dragOnto("Saturday 31 October, 1 task")
        assertNull(moved)
    }

    @Test fun completedByDayAt360() {
        show { CompletedScreen(completed(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/completed-360.png")
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithText("09:12").assertIsDisplayed()
        // 00:04 Tashkent is still the 27th in UTC: it is Today's.
        compose.onNodeWithText("00:04").assertIsDisplayed()
    }

    @Test fun completedAtLargeFont() {
        show(1.5f) { CompletedScreen(completed(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/completed-360-font-1.5.png")
        compose.onNodeWithText("Completed").assertIsDisplayed()
    }

    @Test fun untickingCallsBackWithItsId() {
        var reopened = ""
        show { CompletedScreen(completed(), pad, onReopen = { reopened = it }) }
        compose.onNodeWithContentDescription("Undo: Renew the domain").performClick()
        assertEquals("c1", reopened)
    }

    @Test fun aDoneTasksPanelIsReadOnlyWithReopen() {
        val ui = PanelUi(
            loading = false, id = "c1", project = "me", projectLabel = "Me", title = "Renew the domain", text = "Renew pagel.uz for two years on the registrar",
            open = false, doneAt = utc("2026-10-28T09:12:00+05:00"), steps = listOf(PanelStep("Find the login", true), PanelStep("Pay", false)),
        )
        show { Column(Modifier.padding(top = 16.dp)) { TaskPanelContent(ui, today, PanelActions()) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel-done-read-only.png")
        compose.onNodeWithText("Today 09:12").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reopen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Won't do, with the reason").assertDoesNotExist()
    }

    private val habit = task("h1", "Read 10 pages of the book", "2026-10-28 21:30", every = "day")

    @Test fun reminderSheetWhenNotificationsAreOff() {
        show(1.5f) { Column(Modifier.padding(top = 16.dp)) { ReminderContent(habit, on = true, exact = false, onToggle = {}, onTime = {}, notify = false) } }
        val file = "build/outputs/roborazzi/habits-reminder-notifications-off.png"
        compose.onRoot().captureRoboImage(file)
        compose.onNodeWithContentDescription("Notifications off · Turn on").assertIsDisplayed()
        // Checker 3 #1: ink words and an amber bell, no red pixel (a saturated hue within 15 degrees of red). Only when
        // the run records PNGs (CI's -Proborazzi.test.record=true).
        val bmp = android.graphics.BitmapFactory.decodeFile(file) ?: return
        val hsv = FloatArray(3)
        var red = 0
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            android.graphics.Color.colorToHSV(bmp.getPixel(x, y), hsv)
            if ((hsv[0] < 15f || hsv[0] > 345f) && hsv[1] > 0.5f && hsv[2] > 0.3f) red++
        }
        org.junit.Assert.assertEquals("red pixels on the notifications-off sheet", 0, red)
    }

    // ── Round C: the calendar's views (Boss: "week calendar view like Google's, many view formats, colouring") ──

    private fun calendar(view: org.pagel.ai.data.upcoming.CalView, scale: Float, name: String) {
        show(scale) { UpcomingScreen(upcoming(), pad, initialView = view) }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/calendar-$name.png")
        // One header row: the view is a small button that says it (core round 4), not a five-way switch.
        compose.onNodeWithContentDescription("Change view").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun calendarList360() = calendar(org.pagel.ai.data.upcoming.CalView.SCHEDULE, 1f, "list-360x800")
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun calendarDay360() = calendar(org.pagel.ai.data.upcoming.CalView.DAY, 1f, "day-360x800")
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun calendarThree360() = calendar(org.pagel.ai.data.upcoming.CalView.THREE, 1f, "3days-360x800")
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun calendarWeek360() = calendar(org.pagel.ai.data.upcoming.CalView.WEEK, 1f, "week-360x800")
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun calendarMonth360() = calendar(org.pagel.ai.data.upcoming.CalView.MONTH, 1f, "month-360x800")
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun calendarList360Font15() = calendar(org.pagel.ai.data.upcoming.CalView.SCHEDULE, 1.5f, "list-360x800-font-1.5")
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun calendarDay360Font15() = calendar(org.pagel.ai.data.upcoming.CalView.DAY, 1.5f, "day-360x800-font-1.5")
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun calendarThree360Font15() = calendar(org.pagel.ai.data.upcoming.CalView.THREE, 1.5f, "3days-360x800-font-1.5")
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun calendarWeek360Font15() = calendar(org.pagel.ai.data.upcoming.CalView.WEEK, 1.5f, "week-360x800-font-1.5")
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun calendarMonth360Font15() = calendar(org.pagel.ai.data.upcoming.CalView.MONTH, 1.5f, "month-360x800-font-1.5")

    /** The week strip pages by a swipe: the next week's days are on it, and "Back to today" brings this one back. */
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun theStripPagesByWeekAndTodayComesBack() {
        show { UpcomingScreen(upcoming(), pad) }
        compose.onNodeWithContentDescription("Thursday 29 October, 2 tasks").performTouchInput { swipeLeft(startX = right - 4f, endX = left - 600f) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithContentDescription("Wednesday 4 November", substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/calendar-next-week-360x800.png")
        compose.onNodeWithContentDescription("Back to today").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithContentDescription("Thursday 29 October, 2 tasks").assertIsDisplayed()
    }

    /** A block long-pressed and dragged an hour down moves its time an hour later (Room first; the VM sends it). */
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun aBlockDraggedAnHourDownMovesItsTime() {
        var moved: Pair<String, String>? = null
        show { UpcomingScreen(upcoming(), pad, initialView = org.pagel.ai.data.upcoming.CalView.DAY, onMoveTo = { id, due -> moved = id to due }) }
        compose.mainClock.advanceTimeBy(500)
        val block = compose.onNodeWithContentDescription("Record the fractions lesson, 15:00")
        block.performScrollTo()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/calendar-day-before-drag.png")
        val hour = 60f * compose.onRoot().fetchSemanticsNode().boundsInRoot.width / 360f
        block.performTouchInput {
            val from = center
            down(from)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            for (k in 1..10) { moveTo(from + androidx.compose.ui.geometry.Offset(0f, hour * k / 10f)); advanceEventTime(16) }
            up()
        }
        compose.waitForIdle()
        assertEquals("u3" to "2026-10-28 16:00", moved)
    }
}
