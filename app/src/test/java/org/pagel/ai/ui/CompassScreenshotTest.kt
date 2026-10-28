package org.pagel.ai.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.compass.CompassRules
import org.pagel.ai.data.compass.CompassVaultEntity
import org.pagel.ai.data.compass.Goal
import org.pagel.ai.data.compass.GoalBody
import org.pagel.ai.data.compass.GoalProgress
import org.pagel.ai.data.compass.KeyResult
import org.pagel.ai.data.compass.Level
import org.pagel.ai.data.compass.Opened
import org.pagel.ai.data.compass.VaultState
import org.pagel.ai.data.compass.YearNumbers
import org.pagel.ai.ui.compass.CompassActions
import org.pagel.ai.ui.compass.CompassScreen
import org.pagel.ai.ui.compass.CompassUi
import org.pagel.ai.ui.compass.Draft
import org.pagel.ai.ui.compass.GoalForm
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The Compass at Boss's sizes (360 × 800 and 1.5× font): the empty frame that invites writing, the locked screen, a
 * filled cascade, a goal being written, the season (a win and a quiet week), and the age report. Every goal here is
 * sample data made in this test, never Boss's.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class CompassScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val pad = PaddingValues(top = 16.dp, bottom = 96.dp)
    /** Tuesday of week 3 of Season 1, Year 19. */
    private val today = LocalDate.parse("2026-11-17")
    private val season = CompassRules.seasonOf(today)

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    private fun goal(id: String, level: Level, parent: String?, title: String, body: GoalBody = GoalBody()) = Goal(
        id, level, parent,
        when (level) { Level.YEAR -> "2026-11-01"; Level.SEASON -> season.starts.toString(); else -> null },
        when (level) { Level.YEAR -> "2027-10-31"; Level.SEASON -> season.ends.toString(); else -> null },
        body.copy(title = title),
    )

    private fun day(w: Int, d: Long = 1) = CompassRules.weekStart(season, w).plusDays(d).toString()

    private val goals = listOf(
        goal("g_p", Level.PURPOSE, null, "Sample purpose: help every child find what they are good at", GoalBody(why = "Sample why: one line of meaning")),
        goal("g_t1", Level.TEN_YEAR, "g_p", "Sample direction: a learning app used in every region"),
        goal("g_t2", Level.TEN_YEAR, "g_p", "Sample direction: a strong, healthy body"),
        goal("g_y1", Level.YEAR, "g_t1", "Sample year goal: ship the app to 1,000 learners"),
        goal(
            "g_s1", Level.SEASON, "g_y1", "Sample season goal: 100 weekly learners",
            GoalBody(myPart = "Sample part: 3 lessons a week", outcome = "Sample outcome: a grant", leadAction = "Write one lesson", keyResults = listOf(KeyResult("Sample result: 30 lessons", true), KeyResult("Sample result: 5 schools"))),
        ),
        goal("g_s2", Level.SEASON, "g_y1", "Sample season goal: train 3 times a week", GoalBody(myPart = "Sample part: gym Tue Thu Sun", leadAction = "Go to the gym")),
        goal("g_x", Level.SOMEDAY, null, "Sample someday goal: learn to fly a plane"),
    )
    private val progress = mapOf(
        "g_s1" to GoalProgress(listOf("humopedia"), 12, "t_h1", setOf(day(1), day(2), day(3))),
        "g_s2" to GoalProgress(emptyList(), 0, "t_h2", setOf(day(1))),
        "g_y1" to GoalProgress(listOf("humopedia"), 12),
    )
    private fun open() = CompassUi(
        state = VaultState.OPEN, today = today, opened = Opened(goals), progress = progress,
        vault = CompassVaultEntity(lineOn = true, lineGoal = "g_s1"), report = YearNumbers(312, 140, 21, 0),
    )

    @Test fun theEmptyCompassInvitesWriting() {
        show { CompassScreen(CompassUi(state = VaultState.NEW, today = today), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-empty-360x800.png")
        compose.onNodeWithText("Write why you live").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start my Compass").assertIsDisplayed()
    }

    @Test fun theEmptyPathAfterTheKeyIsMade() {
        show { CompassScreen(CompassUi(state = VaultState.OPEN, today = today), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-path-empty-360x800.png")
        compose.onNodeWithContentDescription("Write why you live").assertIsDisplayed()
    }

    @Test fun theLockedScreen() {
        var asked = 0
        show { CompassScreen(CompassUi(state = VaultState.LOCKED, today = today), pad, CompassActions(unlock = { _, _ -> asked++ })) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-locked-360x800.png")
        compose.onNodeWithText("Your Compass is locked").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unlock").performClick()
        assertEquals(1, asked)
    }

    @Test fun theLockedScreenAtLargeFont() {
        show(1.5f) { CompassScreen(CompassUi(state = VaultState.LOCKED, today = today, message = "The unlock ran out. Try again."), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-locked-360x800-font-1.5.png")
        compose.onNodeWithContentDescription("Unlock").assertIsDisplayed()
    }

    @Test fun aFilledCascade() {
        show { CompassScreen(open(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-path-360x800.png")
        compose.onNodeWithContentDescription("Why I live: Sample purpose: help every child find what they are good at").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Season 1: Sample season goal: 100 weekly learners"))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-path-season-360x800.png")
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Show on Today"))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-path-settings-360x800.png")
    }

    @Test fun aFilledCascadeAtLargeFont() {
        show(1.5f) { CompassScreen(open(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-path-360x800-font-1.5.png")
    }

    @Test fun theSeasonShowsAWinAsFullMintAndAQuietWeekWithReset() {
        var reset = 0
        show { CompassScreen(open(), pad, CompassActions(reset = { reset++ }), startTab = 1) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-season-360x800.png")
        compose.onNodeWithContentDescription("Season goal: Sample season goal: 100 weekly learners, a win").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Reset, not restart: Sample season goal: train 3 times…"))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-season-reset-360x800.png")
        compose.onNodeWithContentDescription("Reset, not restart: Sample season goal: train 3 times…").performClick()
        assertEquals(1, reset)
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("What got in the way"))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-week-360x800.png")
    }

    @Test fun theSeasonAtLargeFont() {
        show(1.5f) { CompassScreen(open(), pad, startTab = 1) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-season-360x800-font-1.5.png")
    }

    @Test fun theAgeReport() {
        show { CompassScreen(open().copy(today = LocalDate.parse("2026-10-30")), pad, startTab = 2) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-age-report-360x800.png")
        compose.onNodeWithText("Year 18 → 19").assertIsDisplayed()
        compose.onNodeWithContentDescription("312 tasks done").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Write Year 19's goals"))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-age-report-bottom-360x800.png")
    }

    @Test fun writingASeasonGoalMidSeasonAsksWhy() {
        val parents = goals.filter { it.level == Level.YEAR }
        val draft = Draft(Goal("", Level.SEASON, "g_y1", season.starts.toString(), season.ends.toString(), GoalBody()), parents)
        show { GoalForm(draft, open(), onSave = { _, _ -> }) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-goal-sheet-360x800.png")
        compose.onNodeWithContentDescription("The goal").assertIsDisplayed()
        // The parent chips scroll sideways too: scroll the field itself into view.
        compose.onNodeWithContentDescription("Why change the season now?").performScrollTo()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-goal-sheet-bottom-360x800.png")
    }

    @Test fun writingAGoalAtLargeFont() {
        val draft = Draft(goals.first { it.id == "g_s1" }, goals.filter { it.level == Level.YEAR })
        show(1.5f) { GoalForm(draft, open(), onSave = { _, _ -> }) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-goal-sheet-360x800-font-1.5.png")
    }

    /** Checker #3: the passphrase is masked, with an eye; the rule line is always laid out. */
    @Test fun theRecoveryPassphraseIsMasked() {
        show { org.pagel.ai.ui.compass.RecoveryForm(on = false, onSet = {}, onOff = {}) }
        compose.onNodeWithContentDescription("Passphrase").performClick()
        compose.onNodeWithContentDescription("Passphrase").performTextInput("tea under the old walnut")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-recovery-masked-360x800.png")
        compose.onAllNodesWithContentDescription("Show passphrase")[0].performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-recovery-shown-360x800.png")
    }

    /** RULES.md Layout 3: switching Path → Season never moves the head or the switch (equal tops). */
    @Test fun theTabsDoNotMoveBetweenPathAndSeason() {
        val tab = mutableIntStateOf(0)
        show { androidx.compose.runtime.key(tab.intValue) { CompassScreen(open(), pad, startTab = tab.intValue) } }
        val path = compose.onNodeWithContentDescription("View: Path").fetchSemanticsNode().boundsInRoot.top
        val head = compose.onNodeWithText("Compass").fetchSemanticsNode().boundsInRoot.top
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-tab-path-360x800.png")
        tab.intValue = 1
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compass-tab-season-360x800.png")
        assertEquals(path, compose.onNodeWithContentDescription("View: Season").fetchSemanticsNode().boundsInRoot.top, 0.5f)
        assertEquals(head, compose.onNodeWithText("Compass").fetchSemanticsNode().boundsInRoot.top, 0.5f)
        assertTrue(compose.onNodeWithText("Season 1").fetchSemanticsNode().boundsInRoot.top > path)
    }
}
