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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.task.PanelActions
import org.pagel.ai.ui.task.PanelStep
import org.pagel.ai.ui.task.PanelUi
import org.pagel.ai.ui.task.TaskPanelContent
import org.pagel.ai.ui.today.DayClearedCard
import org.pagel.ai.ui.today.DayProgress
import org.pagel.ai.ui.today.DayProgressBar
import org.pagel.ai.ui.today.TodayRow
import org.pagel.ai.ui.today.TodayScreen
import org.pagel.ai.ui.today.TodayUiState
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Round B (H + G): the day's progress bar, the day-cleared card, the task panel after its ring (done, struck through,
 * Reopen) and the "Pin to Today" row, at 360x800 and at 1.5x font.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class FinishScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 10, 28)
    private val pad = PaddingValues(top = 16.dp, bottom = 96.dp)

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    private val rows = listOf(
        TodayRow("t1", "Ship the native app", 0, "2026-10-28 15:00", null, "pagel", "PAGEL", null, false),
        TodayRow("t2", "Call the bank about the report", 1, null, null, "me", "Me", null, false),
        TodayRow("t3", "Record the fractions lesson", 2, null, null, "humopedia", "Humopedia", null, false),
    )

    @Test fun todayWithItsProgressBar() {
        show { TodayScreen(TodayUiState(loading = false, rows = rows, open = 3, doneToday = 2, status = "Live"), pad, today = day) }
        compose.onNodeWithContentDescription("2 of 5 done today").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/today-progress-360x800.png")
    }

    @Test fun todayWithItsProgressBarLargeFont() {
        show(1.5f) { TodayScreen(TodayUiState(loading = false, rows = rows, open = 3, doneToday = 2, status = "Live"), pad, today = day) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/today-progress-360x800-font-1.5.png")
    }

    @Test fun theProgressBarWithTheNextTasksNudge() {
        show { Column(Modifier.padding(top = 24.dp)) { DayProgressBar(DayProgress(3, 2), "Next: Call the bank about the report · 10 min?", Modifier.padding(horizontal = 20.dp)) } }
        compose.onNodeWithText("3 of 5 ✓").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/today-progress-nudge-360x800.png")
    }

    @Test fun theDayClearedCard() {
        show { TodayScreen(TodayUiState(loading = false, rows = emptyList(), open = 0, allDone = true, doneToday = 5, clearedStreak = 3, status = "Live"), pad, today = day) }
        compose.onNodeWithText("Day cleared").assertIsDisplayed()
        compose.onNodeWithText("🔥 3 days cleared in a row").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/today-day-cleared-360x800.png")
    }

    @Test fun theDayClearedCardLargeFont() {
        show(1.5f) { Column(Modifier.padding(top = 24.dp)) { DayClearedCard(done = 1, streak = 1) } }
        compose.onNodeWithText("🔥 First cleared day").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/today-day-cleared-360x800-font-1.5.png")
    }

    private val panel = PanelUi(
        loading = false, id = "t_1", project = "me", projectLabel = "Me", title = "Ovqatni rasmga olib kaloriyasini o'lchash, botga yuborish",
        text = "Ovqatni rasmga olib kaloriyasini o'lchash, botga yuborish", due = "2026-10-28", priority = 3,
        steps = listOf(PanelStep("Open the bot", true), PanelStep("Send the photo", false)), pinned = 1,
    )

    @Test fun thePanelAfterItsRingIsDoneStruckAndOffersReopen() {
        show { Column(Modifier.padding(top = 16.dp)) { TaskPanelContent(panel.copy(open = false, doneAt = "2026-10-28T07:55:00Z"), day, PanelActions()) } }
        compose.onNodeWithContentDescription("Reopen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Done: ${panel.title}").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel-done-360x800.png")
    }

    @Test fun thePanelHidesANoteThatRepeatsTheTitleAndSaysPinToToday() {
        show { Column(Modifier.padding(top = 16.dp)) { TaskPanelContent(panel.copy(due = null), day, PanelActions()) } }
        compose.onNodeWithText("Pin to Today").assertExists()
        compose.onNodeWithText("Shows on Today even without a date · max 3").assertExists()
        assertTrue(compose.onAllNodesWithTextCount("Note") == 0)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel-pin-360x800.png")
    }

    @Test fun withThreePinnedTheSwitchIsOffAndSaysWhy() {
        show(1.5f) { Column(Modifier.padding(top = 16.dp)) { TaskPanelContent(panel.copy(due = null, star = false, pinned = 3), day, PanelActions()) } }
        compose.onNodeWithText("3 already pinned · unpin one first").assertExists()
        compose.onNodeWithContentDescription("Pin to Today").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/panel-pin-full-360x800-font-1.5.png")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
