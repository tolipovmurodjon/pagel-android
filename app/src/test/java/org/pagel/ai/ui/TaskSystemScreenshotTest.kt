package org.pagel.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.ui.add.AddTaskContent
import org.pagel.ai.ui.add.NotSaved
import org.pagel.ai.ui.add.Picks
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.DateContent
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.glass.MenuContent
import org.pagel.ai.ui.glass.PriorityContent
import org.pagel.ai.ui.glass.ProjectContent
import org.pagel.ai.ui.glass.RepeatContent
import org.pagel.ai.ui.glass.ToastCenter
import org.pagel.ai.ui.glass.ToastHost
import org.pagel.ai.ui.glass.priorityItems
import org.pagel.ai.ui.projects.PhaseGroup
import org.pagel.ai.ui.projects.ProjectRow
import org.pagel.ai.ui.projects.ProjectScreen
import org.pagel.ai.ui.projects.ProjectUi
import org.pagel.ai.ui.task.PanelActions
import org.pagel.ai.ui.task.PanelStep
import org.pagel.ai.ui.task.PanelUi
import org.pagel.ai.ui.task.TaskPanelContent
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Phase 1 (the task system) at the A05s's size: the task panel, each picker, the Add sheet and a project page, plus
 * 1.5× font for the panel and the Add sheet. Each sheet's body is drawn on the sheet's own aurora, as in the app.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class TaskSystemScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val day = LocalDate.of(2026, 10, 27)
    private val projects = listOf(
        ProjectEntity("humopedia", "Humopedia", null, 0, 41), ProjectEntity("ababil", "ABABIL", null, 1, 27),
        ProjectEntity("pagel", "PAGEL", null, 2, 58), ProjectEntity("me", "Me", null, 3, 12),
    )
    private val panel = PanelUi(
        loading = false, id = "t_1", project = "pagel", projectLabel = "PAGEL", title = "Ship the native task panel",
        text = "Ship the native task panel: title, date, priority, steps, won't do, delete with Undo", doneWhen = "Boss edits a task on his phone",
        due = "2026-10-29 15:00", every = null, priority = 0, star = true,
        steps = listOf(PanelStep("Server PATCH fields", true), PanelStep("Room v3 + outbox", true), PanelStep("Glass pickers", false)),
    )

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { Column(Modifier.padding(top = 16.dp)) { body() } } }
        }
    }

    /** A label that must stay on one line and whole (checker 2026-10-27: "Prior/ity", "PA…"). */
    private fun oneWholeLine(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        val l = layout.single()
        assertEquals("\"$text\" wraps", 1, l.lineCount)
        // The drawn line's own width against its box (a no-wrap paragraph is laid out as wide as it is allowed, so
        // its width says nothing; hasVisualOverflow also counts a pixel of height).
        val needs = l.getLineRight(0) - l.getLineLeft(0)
        assertFalse("\"$text\" is cut: needs $needs px, has ${l.size.width} px", l.isLineEllipsized(0) || needs > l.size.width + 1f)
    }

    @Test @Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi") fun taskPanelOnANarrowPhone() {
        show { TaskPanelContent(panel, day, PanelActions()) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel-360.png")
        oneWholeLine("Priority")
        oneWholeLine("PAGEL")
        compose.onNodeWithContentDescription("Make these steps tasks in PAGEL").assertIsDisplayed()
    }

    @Test @Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi") fun taskPanelOnANarrowPhoneLargeFont() {
        show(1.5f) { TaskPanelContent(panel, day, PanelActions()) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel-360-font-1.5.png")
        oneWholeLine("Priority")
        oneWholeLine("PAGEL")
    }

    @Test fun taskPanel() {
        show { TaskPanelContent(panel, day, PanelActions()) }
        compose.onNodeWithText("2/3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Undo: Server PATCH fields").assertIsDisplayed()
        compose.onNodeWithContentDescription("Date: 29 Oct 15:00").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel.png")
    }

    @Test fun taskPanelLargeFont() {
        show(1.5f) { TaskPanelContent(panel, day, PanelActions()) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel-font-1.5.png")
        oneWholeLine("PAGEL")
    }

    @Test fun taskPanelWontDoRefusesAnExcuse() {
        var saved = false
        show { TaskPanelContent(panel, day, PanelActions(onNotDoing = { _, r -> org.pagel.ai.data.tasks.NotDoing.check(r).also { if (it == null) saved = true } }), stopFirst = true) }
        compose.onNodeWithContentDescription("The real reason").performTextInput("too tired")
        compose.onNodeWithText("Save reason").performClick()
        compose.onNodeWithText("Name what blocks it, in a few words.").assertIsDisplayed()
        assertEquals(false, saved)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel-wont-do.png")
    }

    @Test fun taskPanelSendsTheWholeStepListWithTicks() {
        var sent: Pair<List<String>, List<Int>>? = null
        show { TaskPanelContent(panel, day, PanelActions(onSteps = { s, t -> sent = s to t })) }
        compose.onNodeWithContentDescription("Done: Glass pickers").performClick()
        assertEquals(listOf("Server PATCH fields", "Room v3 + outbox", "Glass pickers") to listOf(0, 1, 2), sent)
    }

    @Test fun datePicker() {
        show { DateContent("2026-10-29 15:00", day) { _, _ -> } }
        compose.onNodeWithText("October 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("26 October 2026").assertIsNotEnabled()
        compose.onNodeWithContentDescription("30 October 2026").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-date.png")
    }

    @Test fun datePickerPicksADay() {
        var picked: String? = null
        show { DateContent("2026-10-29 15:00", day) { d, _ -> picked = d } }
        compose.onNodeWithContentDescription("31 October 2026").performClick()
        assertEquals("2026-10-31 15:00", picked)
        compose.onNodeWithContentDescription("Time 15:00").performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-date-time.png")
    }

    /** Boss 2026-10-28: the time is a wheel, and the date sheet fits a 360×800 phone. */
    @Test @Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi") fun timeWheelOnANarrowPhone() {
        var picked: String? = null
        show { DateContent("2026-10-29 15:30", day) { d, _ -> picked = d } }
        compose.onNodeWithContentDescription("Time 15:30").performClick()
        compose.onNodeWithContentDescription("Time wheel 15:30").assertIsDisplayed()
        compose.onNodeWithContentDescription("No time").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-time-wheel-360.png")
        compose.onNodeWithContentDescription("No time").performClick()
        assertEquals("2026-10-29", picked)
    }

    @Test @Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi") fun datePickerFitsANarrowPhone() {
        show { DateContent("2026-10-29 15:30", day) { _, _ -> } }
        compose.onNodeWithContentDescription("Type a time").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-date-360.png")
    }

    @Test @Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi") fun timeWheelLargeFont() {
        show(1.5f) { DateContent("2026-10-29 15:30", day) { _, _ -> } }
        compose.onNodeWithContentDescription("Time 15:30").performClick()
        compose.onNodeWithContentDescription("Time wheel 15:30").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-time-wheel-360-font-1.5.png")
    }

    /** Round B (D + E): "10:00-12:00, 24 hour" from the wheel, and a Done in the header. */
    @Test @Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi") fun aTimeRangeFromTheWheelAndDone() {
        val v = androidx.compose.runtime.mutableStateOf<String?>("2026-10-29 10:00")
        var done = false
        show { DateContent(v.value, day, onDone = { done = true }) { d, _ -> v.value = d } }
        compose.onNodeWithContentDescription("Time 10:00").performClick()
        compose.onNodeWithContentDescription("Add an end time").performClick()
        assertEquals("2026-10-29 10:00-11:00", v.value)
        compose.onNodeWithContentDescription("End 11:00").assertIsDisplayed()
        compose.onNodeWithText("29 Oct 10:00–11:00").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-time-range-360x800.png")
        compose.onNodeWithContentDescription("Done").performClick()
        org.junit.Assert.assertTrue(done)
    }

    @Test @Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi") fun aTimeRangeAtLargeFont() {
        show(1.5f) { DateContent("2026-10-29 10:00-12:00", day, onDone = {}) { _, _ -> } }
        compose.onNodeWithContentDescription("End 12:00").performClick()
        compose.onNodeWithContentDescription("Time wheel 12:00").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-time-range-360x800-font-1.5.png")
    }

    /** Checker 2026-10-28 #5: opening the wheel writes nothing; the day chip goes back; a tap on the shown time saves it. */
    @Test @Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi") fun timeWheelSavesNothingUntilAsked() {
        val picks = mutableListOf<String?>()
        show { DateContent("2026-10-29", day) { d, _ -> picks += d } }
        // Always six weeks (spacing audit #6): October 2026 shows 2-8 November muted in its sixth row.
        compose.onAllNodesWithContentDescription("2 November 2026").assertCountEquals(1)
        compose.onNodeWithContentDescription("1 November 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add time").performClick()
        compose.onNodeWithContentDescription("Time wheel 09:00").assertIsDisplayed()
        assertEquals(emptyList<String?>(), picks)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-wheel-unsaved-360.png")
        compose.onNodeWithContentDescription("Back to the calendar").performClick()
        compose.onNodeWithText("October 2026").assertIsDisplayed()
        assertEquals(emptyList<String?>(), picks)
        compose.onNodeWithContentDescription("Add time").performClick()
        compose.onNodeWithContentDescription("Set 09:00").performClick()
        assertEquals(listOf<String?>("2026-10-29 09:00"), picks)
    }

    @Test fun prioritySheet() {
        var got = -1
        show { PriorityContent(1) { got = it } }
        compose.onNodeWithText("this week").assertIsDisplayed()
        compose.onNodeWithContentDescription("P1").performClick()
        assertEquals(0, got)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-priority-sheet.png")
    }

    @Test fun repeatMonthlyOnChosenDays() {
        var got: String? = "x"
        show { RepeatContent("month 1,15") { v, _ -> got = v } }
        compose.onNodeWithText("Monthly · 1, 15").assertIsDisplayed()
        compose.onNodeWithContentDescription("Last day of the month").performClick()
        assertEquals("month 1,15,last", got)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-repeat-monthly.png")
    }

    @Test fun priorityMenu() {
        show { Box(Modifier.padding(24.dp)) { MenuContent(priorityItems(1)) {} } }
        compose.onNodeWithText("this week").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-priority.png")
    }

    @Test fun repeatPicker() {
        var got: String? = "x"
        show { RepeatContent("tue,thu") { v, _ -> got = v } }
        compose.onNodeWithText("Every Tue, Thu").assertIsDisplayed()
        compose.onNodeWithContentDescription("Saturday").performClick()
        assertEquals("tue,thu,sat", got)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-repeat.png")
    }

    @Test fun projectPicker() {
        show { ProjectContent(projects, "pagel") {} }
        compose.onNodeWithContentDescription("Find a project").performTextInput("zz")
        compose.onNodeWithText("No project called “zz”").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-project-empty.png")
    }

    @Test fun projectPickerList() {
        show { ProjectContent(projects, "pagel") {} }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-project.png")
    }

    private val now = LocalDateTime.of(2026, 10, 27, 10, 0)

    @Test fun addSheetEmpty() {
        show { AddTaskContent(projects, now = { now }, autoFocus = false) { _, _, _, _ -> true } }
        compose.onNodeWithContentDescription("Add task").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/add-empty.png")
    }

    @Test fun addSheetReady() {
        show { AddTaskContent(projects, now = { now }, initial = "Call Aziz tomorrow 3pm #me p1", autoFocus = false) { _, _, _, _ -> true } }
        compose.onNodeWithContentDescription("Add task").assertIsEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/add-ready.png")
    }

    /** As the app lays it out: the toast sits above the sheet's top edge, never over the chips. */
    @Test fun addSheetAfterAddShowsTheToastAboveTheSheet() {
        compose.mainClock.autoAdvance = false
        val toasts = ToastCenter().apply { show("Added · Today, in line", millis = 600_000) }
        show {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                ToastHost(toasts, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp))
                AddTaskContent(projects, now = { now }, autoFocus = false) { _, _, _, _ -> true }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Added · Today, in line").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add task").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/add-after-add.png")
    }

    @Test fun addSheetNotSavedPutsTheWordsBack() {
        show { AddTaskContent(projects, now = { now }, autoFocus = false, notSaved = listOf(NotSaved(7, "Call Aziz about the visa"))) { _, _, _, _ -> true } }
        compose.onNodeWithText("Not saved").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add task").assertIsEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/add-not-saved.png")
    }

    @Test fun addSheetPicksBeatTheTypedLine() {
        var saved: Picks? = null
        show(1.5f) {
            AddTaskContent(projects, now = { now }, initial = "Call the bank tomorrow 3pm", initialPicks = Picks(dueSet = true, due = "2026-10-30", priority = 0), autoFocus = false) { _, _, p, _ -> saved = p; true }
        }
        compose.onNodeWithText("30 Oct").assertIsDisplayed()
        compose.onNodeWithText("P1").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/add-typed-font-1.5.png")
        compose.onNodeWithContentDescription("Add task").performClick()
        assertEquals(Picks(dueSet = true, due = "2026-10-30", priority = 0), saved)
    }

    @Test fun addSheetTooLong() {
        show { AddTaskContent(projects, now = { now }, initial = (1..22).joinToString(" ") { "word$it" }, autoFocus = false) { _, _, _, _ -> false } }
        compose.onNodeWithContentDescription("22 words, 20 at most").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/add-too-long.png")
    }

    private val projectUi = ProjectUi(
        loading = false, name = "pagel", title = "PAGEL", open = 5, now = 3,
        groups = listOf(
            PhaseGroup(
                "Phase 3 · Native app", true,
                listOf(ProjectRow("a", "Ship the task panel", 0, "2026-10-27", null), ProjectRow("b", "Glass pickers", 1, "2026-10-30 15:00", null), ProjectRow("c", "Weekly review", 2, "2026-10-26", "week")),
                done = listOf(ProjectRow("x", "Room v3 + outbox", 1, null, null), ProjectRow("y", "Server PATCH fields", 0, null, null)),
            ),
            PhaseGroup("Phase 4 · Habits", false, listOf(ProjectRow("d", "Reminders with a Done action", 2, null, null), ProjectRow("e", "Streaks", 3, null, null))),
            PhaseGroup("Phase 2 · Today", false, emptyList(), done = listOf(ProjectRow("z", "Today screen native", 1, null, null))),
        ),
    )

    @Test fun projectScreen() {
        var adding = false
        show { Box(Modifier.fillMaxWidth()) { ProjectScreen(projectUi, PaddingValues(bottom = 24.dp), today = day, onAdd = { adding = true }) } }
        compose.onNodeWithText("NOW").assertIsDisplayed()
        compose.onNodeWithText("3 now · 5 open in all").assertIsDisplayed()
        compose.onNodeWithText("2 done").assertIsDisplayed()
        compose.onNodeWithText("Later · 1 phase").assertIsDisplayed()
        compose.onNodeWithText("Done · 1 phase").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project.png")
        compose.onNodeWithContentDescription("Add a task to PAGEL").performClick()
        assertEquals(true, adding)
    }

    @Test fun projectScreenFoldsOpen() {
        show { Box(Modifier.fillMaxWidth()) { ProjectScreen(projectUi, PaddingValues(bottom = 24.dp), today = day) } }
        compose.onNodeWithText("2 done").performClick()
        compose.onNodeWithText("Phase 4 · Habits").performClick()
        compose.onNodeWithText("Room v3 + outbox").assertIsDisplayed()
        compose.onNodeWithText("Streaks").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-folds-open.png")
    }

    @Test fun projectScreenWithNoPlan() {
        show { Box(Modifier.fillMaxWidth()) { ProjectScreen(ProjectUi(loading = false, name = "me", title = "Me"), PaddingValues(bottom = 24.dp), today = day) } }
        compose.onNodeWithText("No plan yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add a task to Me").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-empty.png")
    }

    /** Layout rule 3 (spacing audit #7): calendar → wheel keeps the sheet's title and the time chip in place. */
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun dateSheetKeepsItsPlacesFromCalendarToWheel() {
        show {
            var v by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>("2026-10-29") }
            Box(Modifier.testTag("sheet")) { DateContent(v, day) { nv, _ -> v = nv } }
        }
        fun at(desc: String) = compose.onNodeWithContentDescription(desc).fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Date").fetchSemanticsNode().boundsInRoot.top
        val chip = at("Add time").let { it.left to it.top }
        val height = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.height
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-date-state-a.png")
        compose.onNodeWithContentDescription("Add time").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/picker-date-state-b.png")
        assertEquals(title, compose.onNodeWithText("Date").fetchSemanticsNode().boundsInRoot.top, 0.5f)
        assertEquals("the time chip moved", chip, at("Set 09:00").let { it.left to it.top })
        assertEquals(height, compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.height, 0.5f)
    }

    /** Layout rule 3 (spacing audit #9): "To tasks" becomes "Made", and the Props pane under it stays put. */
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun panelPropsStayWhenStepsBecomeTasks() {
        show { TaskPanelContent(panel, day, PanelActions()) }
        fun dateTop() = compose.onNodeWithContentDescription("Date: 29 Oct 15:00").fetchSemanticsNode().boundsInRoot.top
        val before = dateTop()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel-made-state-a.png")
        compose.onNodeWithContentDescription("Make these steps tasks in PAGEL").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Made into tasks").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel-made-state-b.png")
        assertEquals(before, dateTop(), 0.5f)
    }

    /** Layout rule 3 (spacing audit #11): the Add sheet keeps its height while the line grows too long. */
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun addSheetKeepsItsHeightWhenTheLineIsTooLong() {
        show { Box(Modifier.testTag("sheet")) { AddTaskContent(projects, now = { now }, autoFocus = false) { _, _, _, _ -> true } } }
        val h = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.height
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/add-state-a.png")
        compose.onNodeWithContentDescription("New task").performTextInput((1..22).joinToString(" ") { "word$it" })
        compose.waitForIdle()
        compose.onNodeWithContentDescription("22 words, 20 at most").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/add-state-b.png")
        assertEquals(h, compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.height, 0.5f)
    }
}
