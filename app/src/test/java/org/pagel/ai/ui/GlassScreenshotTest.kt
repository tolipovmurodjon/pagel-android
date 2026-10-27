package org.pagel.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.glass.LiquidBottomTab
import org.pagel.ai.ui.glass.LiquidBottomTabs
import org.pagel.ai.ui.pair.PairScreen
import org.pagel.ai.ui.pair.PairUiState
import org.pagel.ai.ui.projects.ProjectsScreen
import org.pagel.ai.ui.projects.ProjectsUiState
import org.pagel.ai.ui.shell.Tab
import org.pagel.ai.ui.add.AddTaskContent
import org.pagel.ai.ui.add.Saved
import org.pagel.ai.ui.today.TodayRow
import java.time.LocalDateTime
import androidx.compose.ui.test.performTextInput
import org.pagel.ai.ui.today.TodayScreen
import org.pagel.ai.ui.today.TodayUiState
import java.time.LocalDate
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of each glass piece at the A05s's size (390×844 dp class) and with Boss's font made 1.5× larger.
 * Saved by Roborazzi under build/outputs/roborazzi and kept as a CI artifact; a human (and the next session) looks.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class GlassScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val projects = listOf(
        ProjectEntity("humopedia", "Humopedia", null, 0, 41), ProjectEntity("ababil", "ABABIL", null, 1, 27),
        ProjectEntity("pagel", "PAGEL", null, 2, 58), ProjectEntity("uzcosmos", "UzCosmos", null, 3, 4),
        ProjectEntity("me", "Me", null, 4, 12), ProjectEntity("labs", "Labs", null, 5, 6),
    )

    @Test fun glassBar() {
        compose.setContent {
            GlassTheme {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(390.dp, 220.dp)) {
                    AuroraBackground(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        Text("Content scrolls under the glass", Modifier.align(Alignment.Center), fontSize = 22.sp)
                    }
                    LiquidBottomTabs({ 0 }, {}, backdrop, Tab.entries.size, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                        Tab.entries.forEach { t ->
                            LiquidBottomTab({}) { Icon(t.icon, null, Modifier.size(22.dp)); Text(t.label, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Notebook").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/glass-bar.png")
    }

    @Test fun pairing() {
        compose.setContent { GlassTheme { PairScreen(PairUiState(code = "48"), {}, {}, {}) } }
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/pair-empty.png")
    }

    @Test fun pairingReadyAndWrongCode() {
        compose.setContent { GlassTheme { PairScreen(PairUiState(code = "482913", error = "Wrong code. 4 tries left."), {}, {}, {}) } }
        compose.onNodeWithText("Connect").assertIsEnabled()
        compose.onNodeWithText("Wrong code. 4 tries left.", substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/pair-error.png")
    }

    @Test fun projects() {
        compose.setContent { GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { ProjectsScreen(ProjectsUiState(false, projects, "Live"), PaddingValues(top = 24.dp)) } } }
        compose.onNodeWithText("Humopedia").assertIsDisplayed()
        compose.onNodeWithText("148 open tasks · Live").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/projects.png")
    }

    @Test fun projectsLargeFont() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.5f)) {
                GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { ProjectsScreen(ProjectsUiState(false, projects, "Live"), PaddingValues(top = 24.dp)) } }
            }
        }
        compose.onNodeWithText("Humopedia").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/projects-font-1.5.png")
    }

    private val day = LocalDate.of(2026, 10, 27)
    private fun row(id: String, title: String, p: Int, due: String?, project: String, done: Boolean = false, every: String? = null) =
        TodayRow(id, title, p, due, every, project, projects.first { it.name == project }.title, null, done)
    private val rows = listOf(
        row("t1", "Ship the native Today screen", 0, "2026-10-27 15:00", "pagel"),
        row("t2", "Call the bank about the IT-Park report", 1, "2026-10-25", "me"),
        row("t3", "Weigh in", 3, "2026-10-27", "me", every = "week"),
    )
    private val today = TodayUiState(loading = false, rows = rows, open = 5, waiting = 2, status = "Live")

    private fun showToday(ui: TodayUiState, fontScale: Float = 1f) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { TodayScreen(ui, PaddingValues(top = 24.dp, bottom = 96.dp), today = day) } }
        }
    }

    @Test fun todayThreeAtATime() {
        showToday(today)
        compose.onNodeWithText("Ship the native Today screen").assertIsDisplayed()
        compose.onNodeWithText("5 tasks today, 3 at a time · Live").assertIsDisplayed()
        compose.onNodeWithText("2 more after these", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Done: Weigh in").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/today.png")
    }

    @Test fun todayTickedRowFills() {
        showToday(today.copy(rows = listOf(rows[0].copy(done = true)) + rows.drop(1)))
        compose.onNodeWithContentDescription("Done: Ship the native Today screen").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/today-ticked.png")
    }

    @Test fun todayAllDone() {
        showToday(TodayUiState(loading = false, allDone = true, status = "Live"))
        compose.onNodeWithText("All done for today ✓").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/today-all-done.png")
    }

    @Test fun todayLargeFont() {
        showToday(today, fontScale = 1.5f)
        compose.onNodeWithText("Ship the native Today screen").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/today-font-1.5.png")
    }

    @Test fun addTaskReadsTheLine() {
        val now = LocalDateTime.of(2026, 10, 26, 10, 0)
        compose.setContent {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) {
                AddTaskContent(projects, listOf(Saved(0, "Weigh in", "Me · Today, in line")), now = { now }, onSave = { _, _ -> true })
            } }
        }
        compose.onNodeWithContentDescription("New task").performTextInput("Call the bank tomorrow 3pm #humopedia p1")
        compose.onNodeWithText("Tomorrow 15:00").assertIsDisplayed()
        compose.onNodeWithText("⚑ P1").assertIsDisplayed()
        compose.onNodeWithText("Add task").assertIsEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/add-task.png")
    }
}
