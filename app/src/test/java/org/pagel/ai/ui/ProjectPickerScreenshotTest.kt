package org.pagel.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.glass.PickerRow
import org.pagel.ai.ui.glass.ProjectContent
import org.pagel.ai.ui.glass.ProjectPickerActions
import org.pagel.ai.ui.glass.pickerRows
import org.pagel.ai.ui.projects.PasteListContent
import org.pagel.ai.ui.projects.pastedLines
import org.pagel.ai.ui.task.LESSON_STEPS
import org.pagel.ai.ui.task.PanelActions
import org.pagel.ai.ui.task.PanelStep
import org.pagel.ai.ui.task.PanelUi
import org.pagel.ai.ui.task.TaskPanelContent
import org.pagel.ai.ui.task.looksLikeLessons
import java.time.LocalDate
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The project picker's pure rules and its look: normal, typing a new name, 1.5x font, 360 dp, the create field. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ProjectPickerScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val projects = listOf(
        ProjectEntity("humopedia", "Humopedia", null, 0, 41), ProjectEntity("ababil", "ABABIL", null, 1, 27),
        ProjectEntity("pagel", "PAGEL", null, 2, 58), ProjectEntity("me", "Me", null, 3, 12), ProjectEntity("gym", "Gym time", "coral", 4, 3),
    )
    private val many = projects + (1..6).map { ProjectEntity("p$it", "Project $it", null, 10 + it, 1) }
    private var made: String? = null
    private var moved: Triple<String, String?, String?>? = null
    private var restored: String? = null
    private val picker = ProjectPickerActions(
        recent = listOf("pagel", "me"),
        create = { title, _ -> made = title; "ci-picker" },
        move = { n, a, b -> moved = Triple(n, a, b) },
        restore = { n, _ -> restored = n },
    )

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { Column(Modifier.padding(top = 16.dp)) { body() } } }
        }
    }

    @Test fun rowsListMatchesThenCreateThenNoProject() {
        assertEquals(listOf("humopedia", "ababil", "pagel", "me", "gym"), pickerRows(projects, "", emptyList(), true).filterIsInstance<PickerRow.Item>().map { it.p.name })
        assertEquals(PickerRow.NoProject, pickerRows(projects, "", emptyList(), true).last())
        assertEquals(listOf(PickerRow.Item(projects[4]), PickerRow.Create("Gy")), pickerRows(projects, "Gy", emptyList(), true))
    }

    @Test fun anExactNameOffersNoCreateAndNothingMatchedOffersOnlyCreate() {
        assertEquals(listOf(PickerRow.Item(projects[4])), pickerRows(projects, "gym TIME", emptyList(), true))
        assertEquals(listOf(PickerRow.Create("Zzz")), pickerRows(projects, "Zzz", emptyList(), true))
        assertEquals(emptyList<PickerRow>(), pickerRows(projects, "Zzz", emptyList(), false))
    }

    @Test fun recentShowsOnlyBeyondEightProjectsAndNothingTyped() {
        assertEquals(0, pickerRows(projects, "", listOf("pagel"), true).count { it is PickerRow.Recent })
        val r = pickerRows(many, "", listOf("pagel", "me", "ghost", "p1", "p2"), true)
        assertEquals(listOf("pagel", "me", "p1"), r.filterIsInstance<PickerRow.Recent>().map { it.p.name })
        assertEquals(0, pickerRows(many, "pa", listOf("pagel"), true).count { it is PickerRow.Recent })
    }

    @Test fun inboxAndArchivedAreNotListed() {
        val r = pickerRows(projects + ProjectEntity("inbox", "Inbox", null, 9, 0) + ProjectEntity("old", "Old", null, 8, 0, archived = true), "", emptyList(), true)
        assertEquals(5, r.count { it is PickerRow.Item })
    }

    @Test fun pickerNormal() {
        show { ProjectContent(projects, "pagel", picker, onEdit = {}) {} }
        compose.onNodeWithText("New project").assertIsDisplayed()
        compose.onNodeWithText("No project").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit PAGEL").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-picker.png")
    }

    @Test fun typingANewNameOffersCreateAndTheRowMakesItAndPicksIt() {
        var picked: String? = null
        show { ProjectContent(projects, "pagel", picker, onEdit = {}) { picked = it } }
        compose.onNodeWithContentDescription("Find a project").performTextInput("Gymnastics")
        compose.onNodeWithText("Create “Gymnastics”").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-picker-create-row.png")
        compose.onNodeWithText("Create “Gymnastics”").performClick()
        compose.waitForIdle()
        assertEquals("Gymnastics", made)
        assertEquals("ci-picker", picked)
    }

    @Test fun theNewProjectRowOpensAFieldInPlace() {
        show { ProjectContent(projects, "pagel", picker, onEdit = {}) {} }
        compose.onNodeWithText("New project").performClick()
        compose.onNodeWithContentDescription("New project name").assertIsDisplayed()
        compose.onNodeWithContentDescription("New project name").performTextInput("CI Picker")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-picker-field.png")
    }

    @Test fun recentAndAllWhenThereAreMany() {
        show { ProjectContent(many, "pagel", picker, onEdit = {}) {} }
        compose.onNodeWithText("Recent").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-picker-recent.png")
    }

    @Test fun pickerAtOnePointFiveFont() {
        show(fontScale = 1.5f) { ProjectContent(projects, "gym", picker, onEdit = {}) {} }
        compose.onNodeWithText("New project").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-picker-font-1.5.png")
    }

    @Test @Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi") fun pickerOnANarrowPhoneAtOnePointFiveFont() {
        show(fontScale = 1.5f) { ProjectContent(projects, "gym", picker, onEdit = {}) {} }
        compose.onNodeWithText("New project").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-picker-360-font-1.5.png")
    }

    /** Checker #6: an archived project's name offers "Restore", never a silent pick of a hidden project or a twin. */
    @Test fun anArchivedNameOffersRestoreNotCreate() {
        val all = projects + ProjectEntity("unity", "Unity course", "violet", 9, 0, archived = true)
        val r = pickerRows(all, "unity COURSE", emptyList(), true)
        assertEquals(listOf(PickerRow.Restore(all.last())), r)
        assertEquals(PickerRow.Create("Unity"), pickerRows(all, "Unity", emptyList(), true).last())
    }

    @Test fun theRestoreRowBringsItBackAndPicksIt() {
        var picked: String? = null
        val all = projects + ProjectEntity("unity", "Unity course", "violet", 9, 0, archived = true)
        show { ProjectContent(all, "pagel", picker, onEdit = {}) { picked = it } }
        compose.onNodeWithContentDescription("Find a project").performTextInput("Unity course")
        compose.onNodeWithText("Restore “Unity course”").performClick()
        compose.waitForIdle()
        assertEquals("unity", restored)
        assertEquals("unity", picked)
    }

    /** Checker #8: with a name typed, only "Create “X”" makes it; "+ New project" steps away. */
    @Test fun typingHidesNewProjectAndLeavesOnlyTheCreateRow() {
        show { ProjectContent(projects, "pagel", picker, onEdit = {}) {} }
        compose.onNodeWithText("New project").assertIsDisplayed()
        compose.onNodeWithContentDescription("Find a project").performTextInput("Gymnastics")
        compose.onNodeWithText("New project").assertDoesNotExist()
        compose.onNodeWithText("Create “Gymnastics”").assertIsDisplayed()
    }

    /** A row says its colour, so a recolour from the ⋯ is heard (and checked by the walkthrough). */
    @Test fun aRowSaysItsNameAndColour() {
        show { ProjectContent(projects, "pagel", picker, onEdit = {}) {} }
        compose.onNodeWithContentDescription("Gym time, coral").assertIsDisplayed()
        compose.onNodeWithContentDescription("Humopedia, mint").assertIsDisplayed()
    }

    /** Checker #12: the drag, caught mid-air (lifted row, glass shadow, the rows below making room), then dropped. */
    @Test fun draggingARowLiftsItAndTheDropIsOneMove() {
        show { ProjectContent(projects, "pagel", picker, onEdit = {}) {} }
        val to = compose.onNodeWithText("ABABIL").fetchSemanticsNode().boundsInRoot.center
        val at = compose.onNodeWithText("Me").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithText("Me").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            for (k in 1..8) { moveTo(center + Offset(0f, (to.y - at.y) * k / 8f)); advanceEventTime(16) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-picker-dragging.png")
        compose.onNodeWithText("Me").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("me", moved?.first)
    }

    @Test fun lessonStepsShowOnlyWhereTasksAreLessons() {
        assertTrue(looksLikeLessons("Finish the Unity course", emptyList()))
        assertTrue(looksLikeLessons("Unity", listOf("Lesson 1 Setup", "Lesson 2 Scenes", "Buy a mouse")))
        assertTrue(looksLikeLessons("Blender", listOf("Lecture 4 UVs", "Module 2 · Basics")))
        assertFalse(looksLikeLessons("Me", listOf("Call the bank", "Weigh in", "Pay the internet bill")))
        assertFalse(looksLikeLessons("Me", listOf("Lesson plan for Aziz")))
        assertFalse(looksLikeLessons("Humopedia", listOf("Record the fractions lesson", "Review the lesson draft", "Lesson plan for grade 5")))
    }

    private val lessonTask = PanelUi(
        loading = false, id = "t_l1", project = "unity", projectLabel = "Unity course", title = "Lesson 3 · Prefabs",
        text = "Lesson 3 · Prefabs", priority = 2, steps = listOf(PanelStep("Open lesson", true)),
    )

    @Test fun aLessonTaskHasTheLessonStepsChip() {
        var steps: List<String>? = null
        show { TaskPanelContent(lessonTask.copy(lessons = true), LocalDate.of(2026, 10, 30), PanelActions(onSteps = { s, _ -> steps = s })) }
        compose.onNodeWithText("Lesson steps").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel-lesson-steps.png")
        compose.onNodeWithText("Lesson steps").performClick()
        assertEquals(LESSON_STEPS, steps)
    }

    @Test fun anOrdinaryTaskKeepsLessonStepsInTheDots() {
        var steps: List<String>? = null
        show { TaskPanelContent(lessonTask.copy(project = "me", projectLabel = "Me", title = "Call the bank", text = "Call the bank"), LocalDate.of(2026, 10, 30), PanelActions(onSteps = { s, _ -> steps = s })) }
        compose.onNodeWithText("Lesson steps").assertDoesNotExist()
        compose.onNodeWithContentDescription("Step templates").performClick()
        compose.onNodeWithText("Lesson steps").performClick()
        assertEquals(LESSON_STEPS, steps)
    }

    @Test fun pastedLinesKeepOrderSkipBlanksAndKeepLongLinesWhole() {
        val long = (1..26).joinToString(" ") { "w$it" }
        val lines = pastedLines("1. Lesson 1 Setup\n\n  - Lesson 2 Scenes \n• Lesson 3 Prefabs\n\n$long\n")
        assertEquals(listOf("Lesson 1 Setup", "Lesson 2 Scenes", "Lesson 3 Prefabs"), lines.take(3).map { it.text })
        assertEquals(listOf(null, null, null), lines.take(3).map { it.note })
        assertEquals(20, lines[3].text.split(' ').size)
        assertEquals(long, lines[3].note)
    }

    @Test fun pasteAListLooksCalm() {
        val raw = "Lesson 1 · Install Unity\nLesson 2 · The editor\nLesson 3 · Prefabs\nLesson 4 · Physics\nLesson 5 · " +
            (1..22).joinToString(" ") { "word" }
        show { PasteListContent("Unity course", onAdd = {}, autoFocus = false, initial = raw) }
        compose.onNodeWithText("Add 5 tasks").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/paste-a-list.png")
    }

    @Test fun theOldReadOnlyPickerStillSaysNothingMatched() {
        show { ProjectContent(projects, "pagel") {} }
        compose.onNodeWithContentDescription("Find a project").performTextInput("zz")
        compose.onNodeWithText("No project called “zz”").assertIsDisplayed()
    }
}
