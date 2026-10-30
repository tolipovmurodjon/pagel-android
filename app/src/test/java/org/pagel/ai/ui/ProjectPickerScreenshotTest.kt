package org.pagel.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
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
    private val picker = ProjectPickerActions(
        recent = listOf("pagel", "me"),
        create = { title, _ -> made = title; "ci-picker" },
        move = { _, _, _ -> },
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

    @Test fun theOldReadOnlyPickerStillSaysNothingMatched() {
        show { ProjectContent(projects, "pagel") {} }
        compose.onNodeWithContentDescription("Find a project").performTextInput("zz")
        compose.onNodeWithText("No project called “zz”").assertIsDisplayed()
    }
}
