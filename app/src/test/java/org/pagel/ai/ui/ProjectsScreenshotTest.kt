package org.pagel.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
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
import org.pagel.ai.data.projects.Milestone
import org.pagel.ai.data.projects.ProjectGoal
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.projects.PhaseGroup
import org.pagel.ai.ui.projects.ProjectActions
import org.pagel.ai.ui.projects.ProjectRow
import org.pagel.ai.ui.projects.ProjectScreen
import org.pagel.ai.ui.projects.ProjectSettingsContent
import org.pagel.ai.ui.projects.ProjectUi
import org.pagel.ai.ui.projects.ProjectsScreen
import org.pagel.ai.ui.projects.ProjectsUiState
import org.pagel.ai.ui.projects.SettingsActions
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The core round's projects (2026-10-29) at the A05s's 360×800 and at 1.5× font: the project page in each state
 * (next step, no next step, all done, finished), the settings sheet's colour picker and merge, the projects list
 * grouped by area with a new project in 2 taps.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class ProjectsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val day = LocalDate.of(2026, 10, 29)

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { Column(Modifier.padding(top = 16.dp)) { body() } } }
        }
    }

    private val others = listOf(ProjectEntity("humopedia", "Humopedia", null, 0, 41), ProjectEntity("ababil", "ABABIL", null, 1, 27))
    private val goals = listOf(ProjectGoal("g1", "Ship QUASAR to the Play Store", listOf(Milestone("m1", "First playable build", "2026-12-01"), Milestone("m2", "Closed test", null))))
    private val running = ProjectUi(
        loading = false, name = "games", title = "QUASAR", color = "h212", icon = "emoji:🚀", open = 4, now = 3, done = 3,
        step = "Open Unity and make an empty scene",
        groups = listOf(
            PhaseGroup("Now · First playable", true, listOf(
                ProjectRow("a", "Learn the Unity input system", 1, "2026-10-29", null, 1.0, "m1"),
                ProjectRow("b", "Rocket controller with thrust and tilt", 2, null, null, 2.0, "m1"),
                ProjectRow("c", "Mars terrain from the low-poly kit", 2, "2026-11-02", null, 3.0),
            ), done = listOf(ProjectRow("x", "Pick the engine", 2, null, null, 0.0, "m1"))),
            PhaseGroup("Later · Store", false, listOf(ProjectRow("d", "Store listing screenshots", 3, null, null, 4.0))),
        ),
        goals = goals, others = others,
    )

    @Test fun projectPageOpensOnTheNextStep() {
        var started = false
        show { Box(Modifier.fillMaxWidth()) { ProjectScreen(running, PaddingValues(bottom = 24.dp), today = day, actions = ProjectActions(onTick = { started = true }, onAddText = {})) } }
        compose.onNodeWithText("Next step").assertIsDisplayed()
        compose.onNodeWithText("Start: Open Unity and make an empty scene").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start, 10 minutes").assertIsDisplayed()
        compose.onNodeWithContentDescription("3 of 7 done").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-next.png")
        compose.onNodeWithContentDescription("Done: Learn the Unity input system").performClick()
        assertEquals(true, started)
        compose.onNodeWithContentDescription("Start, 10 minutes").performClick()
        compose.onNodeWithContentDescription("Stop the timer").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-next-timer.png")
    }

    @Test fun projectPageGoalsAndAddInline() {
        var added = ""
        show { Box(Modifier.fillMaxWidth()) { ProjectScreen(running, PaddingValues(bottom = 24.dp), today = day, actions = ProjectActions(onAddText = { added = it })) } }
        compose.onNodeWithText("1-year goals").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ship QUASAR to the Play Store").assertIsDisplayed()
        compose.onNodeWithText("1/3").assertIsDisplayed() // m1: x done, a and b open
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-goals.png")
        compose.onNodeWithContentDescription("Add a task to QUASAR").performScrollTo().performTextInput("Export the first APK")
        compose.onNodeWithContentDescription("Add a task to QUASAR").performImeAction()
        assertEquals("Export the first APK", added)
    }

    @Test fun projectPageWithNoNextActionAsksForOne() {
        show { Box(Modifier.fillMaxWidth()) { ProjectScreen(ProjectUi(loading = false, name = "me", title = "Me", others = others), PaddingValues(bottom = 24.dp), today = day, actions = ProjectActions(onAddText = {})) } }
        compose.onNodeWithText("Add the next step").assertIsDisplayed()
        compose.onNodeWithText("No plan yet").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-empty.png")
    }

    @Test fun allDoneShowsFinishProject() {
        var finishing = false
        val all = running.copy(open = 0, now = 0, done = 5, groups = listOf(PhaseGroup("Now", true, emptyList(), done = listOf(ProjectRow("x", "Pick the engine", 2, null, null)))))
        show { Box(Modifier.fillMaxWidth()) { ProjectScreen(all, PaddingValues(bottom = 24.dp), today = day, actions = ProjectActions(onFinish = { finishing = true })) } }
        compose.onNodeWithText("Everything is done 🎉").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-finish.png")
        compose.onNodeWithText("Finish project").performClick()
        assertEquals(true, finishing)
    }

    @Test fun aFinishedProjectShowsWhatShipped() {
        val done = running.copy(open = 0, done = 5, finishedAt = "2026-10-29T12:00:00Z", shipped = "QUASAR 0.1 on the phone")
        show { Box(Modifier.fillMaxWidth()) { ProjectScreen(done, PaddingValues(bottom = 24.dp), today = day) } }
        compose.onNodeWithText("Finished · 2026-10-29").assertIsDisplayed()
        compose.onNodeWithText("QUASAR 0.1 on the phone").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-finished.png")
    }

    @Test fun projectPageAtBigFont() {
        show(fontScale = 1.5f) { Box(Modifier.fillMaxWidth()) { ProjectScreen(running, PaddingValues(bottom = 24.dp), today = day, actions = ProjectActions(onAddText = {})) } }
        compose.onNodeWithText("Next step").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-next-font-1.5.png")
    }

    private val games = ProjectEntity("games", "QUASAR", "h212", 2, 4, icon = "emoji:🚀", area = "Games")
    private val all = others + games

    @Test fun settingsColourPickerPreviewsAndSaves() {
        val picked = mutableListOf<String>()
        show { ProjectSettingsContent(games, all, onDone = {}, actions = SettingsActions(onColor = { picked += it })) }
        compose.onNodeWithText("Project settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-settings-colour.png")
        compose.onNodeWithContentDescription("Colour violet").performClick()
        assertEquals(listOf("violet"), picked)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-settings-violet.png")
    }

    @Test fun settingsIconSearch() {
        var icon: String? = "none"
        show { ProjectSettingsContent(games, all, onDone = {}, actions = SettingsActions(onIcon = { icon = it })) }
        compose.onNodeWithContentDescription("Search icons").performScrollTo().performTextInput("game")
        compose.onNodeWithContentDescription("Emoji game").performClick()
        assertEquals("emoji:🎮", icon)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-settings-icon.png")
    }

    @Test fun settingsMergeAsksWhereThenMerges() {
        var into = ""
        show { ProjectSettingsContent(games, all, onDone = {}, actions = SettingsActions(onMerge = { into = it })) }
        compose.onNodeWithContentDescription("Merge into another project").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Into Humopedia").performScrollTo().performClick()
        compose.onNodeWithText("Merge into Humopedia").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-settings-merge.png")
        compose.onNodeWithText("Merge into Humopedia").performClick()
        assertEquals("humopedia", into)
    }

    @Test fun settingsDeleteOffersInboxOrWithTasks() {
        var drop: Boolean? = null
        show { ProjectSettingsContent(games, all, onDone = {}, actions = SettingsActions(onDelete = { drop = it })) }
        compose.onNodeWithContentDescription("Delete the project").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Delete, its tasks go to Inbox").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/project-settings-delete.png")
        compose.onNodeWithContentDescription("Delete, its tasks go to Inbox").performClick()
        assertEquals(false, drop)
    }

    @Test fun projectsListByAreaAndANewProjectInTwoTaps() {
        var made = ""
        val list = listOf(
            ProjectEntity("me", "Me", "coral", 0, 12), ProjectEntity("games", "QUASAR", "h212", 1, 4, icon = "emoji:🚀", area = "Games"),
            ProjectEntity("unity", "Learn Unity", "violet", 2, 3, area = "Games", parent = "games"),
            ProjectEntity("old", "Old site", null, 3, 0, finishedAt = "2026-10-20T00:00:00Z", shipped = "Moved to pagel.uz"),
        )
        show { ProjectsScreen(ProjectsUiState(false, list), PaddingValues(top = 8.dp), onCreate = { made = it }) }
        compose.onNodeWithText("Games").assertIsDisplayed()
        compose.onNodeWithText("Learn Unity").assertIsDisplayed()
        compose.onNodeWithText("Done projects").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/projects-by-area.png")
        compose.onNodeWithContentDescription("New project").performClick()
        compose.onNodeWithContentDescription("New project name").performTextInput("Learn Blender")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/projects-new.png")
        compose.onNodeWithContentDescription("New project name").performImeAction()
        assertEquals("Learn Blender", made)
    }
}
