package org.pagel.ai.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.api.StuckDto
import org.pagel.ai.data.api.SweepRowDto
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.SweepEntity
import org.pagel.ai.data.rituals.Kind
import org.pagel.ai.data.rituals.Move
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.rituals.ReviewScreen
import org.pagel.ai.ui.rituals.ReviewUi
import org.pagel.ai.ui.rituals.RitualPills
import org.pagel.ai.ui.rituals.RitualTask
import org.pagel.ai.ui.rituals.RowActions
import org.pagel.ai.ui.rituals.ShutdownScreen
import org.pagel.ai.ui.rituals.ShutdownUi
import org.pagel.ai.ui.rituals.SweepScreen
import org.pagel.ai.ui.rituals.SweepUi
import org.pagel.ai.ui.today.TodayRow
import org.pagel.ai.ui.today.TodayScreen
import org.pagel.ai.ui.today.TodayUiState
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Phase 5 at Boss's sizes (360 dp and 1.5× font): Empty your head with the AI's rows and while it waits offline,
 * Close the day's star card and its picks, the weekly review's next-steps and someday steps, and Today's ritual pills.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class RitualsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val day = LocalDate.parse("2026-10-28")
    private val projects = listOf(ProjectEntity("me", "Me", null, 0, 12), ProjectEntity("pagel", "PAGEL", null, 1, 58), ProjectEntity("humopedia", "Humopedia", null, 2, 41))
    private val pad = PaddingValues(top = 16.dp, bottom = 96.dp)

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    private val rows = listOf(
        SweepRowDto("action", "Call mom about Sunday lunch", "me"),
        SweepRowDto("action", "Fix the login bug on the phone", "pagel"),
        SweepRowDto("someday", "Learn the guitar", "me"),
        SweepRowDto("drop", "Worried about the rain", "me"),
    )
    private fun ready() = SweepUi(loading = false, id = "sw_1", dump = "call mom\nfix login\nguitar\nrain", state = SweepEntity.READY, rows = rows, projects = projects)

    @Test fun sweepRowsAt360() {
        show { SweepScreen(ready(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sweep-rows-360.png")
        compose.onNodeWithText("Empty your head").assertIsDisplayed()
        compose.onNodeWithContentDescription("Kind of step 1: Do").assertIsDisplayed()
        // The Add button sits under the rows: scroll the list to it (a dropped row is not counted).
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Add 3"))
        compose.onNodeWithContentDescription("Add 3").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sweep-rows-360-bottom.png")
    }

    @Test fun sweepRowsAtLargeFont() {
        show(1.5f) { SweepScreen(ready(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sweep-rows-360-font-1.5.png")
        compose.onNodeWithText("Call mom about Sunday lunch").assertIsDisplayed()
    }

    @Test fun aRowsKindAndRemoveCallBack() {
        var kind: Pair<Int, Kind>? = null
        var removed = -1
        show { SweepScreen(ready(), pad, rows = RowActions(kind = { i, k -> kind = i to k }, remove = { removed = it })) }
        compose.onNodeWithContentDescription("Kind of step 1: Someday").performClick()
        assertEquals(0 to Kind.SOMEDAY, kind)
        compose.onNodeWithContentDescription("Remove: Fix the login bug on the phone").performClick()
        assertEquals(1, removed)
    }

    @Test fun sweepWaitingOfflineShowsASkeletonPerLineAndOneLine() {
        show { SweepScreen(SweepUi(loading = false, id = "sw_1", dump = "call mom\nfix the login\nlearn guitar", state = SweepEntity.WAITING, online = false), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sweep-waiting-offline.png")
        compose.onNodeWithContentDescription("Saved. The AI reads it once PAGEL is online.").assertIsDisplayed()
    }

    @Test fun sweepDumpBox() {
        show { SweepScreen(SweepUi(loading = false), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sweep-dump.png")
        compose.onNodeWithContentDescription("What is on your mind").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next prompt").assertIsDisplayed()
    }

    private fun rt(id: String, title: String, project: String, priority: Int = 2) = RitualTask(id, title, project, projects.first { it.name == project }.title, null, priority)
    private val stars = listOf(rt("s1", "Ship the native app", "pagel", 0), rt("s2", "Call the bank about the report", "me", 1), rt("s3", "Record the fractions lesson", "humopedia"))
    private val candidates = listOf(rt("c1", "Send the grant form", "humopedia", 1), rt("c2", "Pay the internet bill", "me", 0), rt("c3", "Review the lesson draft", "humopedia"), rt("c4", "Measure the hover time", "pagel"))

    @Test fun closeTheDayStarCardAt360() {
        var chose: Pair<String, Move>? = null
        show { ShutdownScreen(ShutdownUi(loading = false, stars = stars, candidates = candidates), pad, onChoose = { id, m -> chose = id to m }, today = day) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shutdown-star-360.png")
        compose.onNodeWithContentDescription("Star 1 of 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Later: Ship the native app").performClick()
        assertEquals("s1" to Move.LATER, chose)
    }

    @Test fun closeTheDayStarCardAtLargeFont() {
        show(1.5f) { ShutdownScreen(ShutdownUi(loading = false, stars = stars, candidates = candidates, step = 1, moves = mapOf("s1" to Move.KEEP)), pad, today = day) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shutdown-star-font-1.5.png")
        compose.onNodeWithContentDescription("Drop: Call the bank about the report").assertIsDisplayed()
    }

    private fun picks(picked: List<String>) = ShutdownUi(
        loading = false, stars = stars, candidates = candidates, step = 3, moves = mapOf("s1" to Move.KEEP, "s2" to Move.LATER, "s3" to Move.DROP), picks = picked,
    )

    @Test fun closeTheDayPicksAt360() {
        show { ShutdownScreen(picks(listOf("c1")), pad, today = day) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shutdown-picks-360.png")
        compose.onNodeWithText("2 stars for tomorrow").assertIsDisplayed()
        compose.onNodeWithContentDescription("Finish: close the day").assertIsDisplayed()
    }

    @Test fun closeTheDayPicksAtLargeFont() {
        show(1.5f) { ShutdownScreen(picks(listOf("c1", "c2")), pad, today = day) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shutdown-picks-font-1.5.png")
        compose.onNodeWithText("Anything else for tomorrow?").assertIsDisplayed()
    }

    @Test fun aFourthPickCannotBeMade() {
        var picked: String? = null
        show { ShutdownScreen(picks(listOf("c1", "c2", "c3")), pad, onPick = { picked = it }, today = day) }
        compose.onNodeWithContentDescription("Pick: Measure the hover time").performClick()
        assertNull(picked)
        compose.onNodeWithContentDescription("Pick: Send the grant form").performClick()
        assertEquals("c1", picked)
    }

    @Test fun closedTonight() {
        show { ShutdownScreen(picks(listOf("c1")).copy(done = true, shutTonight = true), pad, today = day) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shutdown-done.png")
        compose.onNodeWithText("Day closed ✓").assertIsDisplayed()
    }

    private val someday = listOf(rt("p1", "Learn the guitar", "me", 3), rt("p2", "Write a Humopedia blog post", "humopedia", 3))
    private fun review(step: Int) = ReviewUi(
        loading = false, step = step, inbox = 14, stuck = listOf(StuckDto("pagel", "PAGEL"), StuckDto("humopedia", "Humopedia")), someday = someday,
        steps = mapOf("pagel" to "Write the Phase 6 brief"), drops = setOf("p2"), last = "2026-10-19",
    )

    @Test fun reviewNextStepsAt360() {
        show { ReviewScreen(review(2), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/review-next-steps-360.png")
        compose.onNodeWithText("3. Every project needs one next step").assertIsDisplayed()
        compose.onNodeWithContentDescription("Step 3: Next steps").assertIsDisplayed()
    }

    @Test fun reviewSomedayAt360() {
        var dropped = ""
        show { ReviewScreen(review(3), pad, onDrop = { dropped = it }) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/review-someday-360.png")
        compose.onNodeWithContentDescription("Finish review").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drop: Learn the guitar").performClick()
        assertEquals("p1", dropped)
        compose.onNodeWithContentDescription("Undo drop: Write a Humopedia blog post").assertIsDisplayed()
    }

    @Test fun reviewInboxAtLargeFont() {
        show(1.5f) { ReviewScreen(review(0), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/review-inbox-font-1.5.png")
        compose.onNodeWithText("14 saves waiting.").assertIsDisplayed()
    }

    private val todayRows = listOf(
        TodayRow("t1", "Ship the native app", 0, "2026-10-28 15:00", null, "pagel", "PAGEL", null, false),
        TodayRow("t2", "Call the bank about the report", 1, null, null, "me", "Me", null, false),
        TodayRow("t3", "Record the fractions lesson", 2, null, null, "humopedia", "Humopedia", null, false),
    )

    @Test fun todaysRitualPillsAt360() {
        show { TodayScreen(TodayUiState(loading = false, rows = todayRows, open = 3, status = "Live"), pad, today = day, pills = RitualPills(shutTonight = false, reviewDue = true)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/today-pills-360.png")
        compose.onNodeWithContentDescription("Empty your head").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close the day").assertIsDisplayed()
        compose.onNodeWithContentDescription("Weekly review is due").assertIsDisplayed()
    }

    @Test fun todaysRitualPillsAtLargeFontAfterClosing() {
        show(1.5f) { TodayScreen(TodayUiState(loading = false, rows = todayRows, open = 3, status = "Live"), pad, today = day, pills = RitualPills(shutTonight = true, reviewDue = false)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/today-pills-font-1.5.png")
        compose.onNodeWithContentDescription("Close the day: closed tonight").assertIsDisplayed()
        compose.onNodeWithContentDescription("Weekly review is due").assertDoesNotExist()
    }
}
