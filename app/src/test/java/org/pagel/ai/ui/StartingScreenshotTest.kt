package org.pagel.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.task.FocusContent
import org.pagel.ai.ui.task.RemindChoices
import org.pagel.ai.ui.task.StartUi
import org.pagel.ai.ui.task.StuckContent
import org.pagel.ai.ui.task.StuckReason
import org.pagel.ai.ui.task.Tiny
import org.pagel.ai.ui.task.TinyContent
import org.pagel.ai.ui.today.FIRST_FALLBACK
import org.pagel.ai.ui.today.NowPick
import org.pagel.ai.ui.today.TodayRow
import org.pagel.ai.ui.today.TodayScreen
import org.pagel.ai.ui.today.TodayUiState
import org.pagel.ai.ui.today.WhatNowContent
import org.pagel.ai.ui.today.WhatNowUi
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Core round 4, Today's starting machine: Today folded / open / mid-drag, What now (energy, the pick, the running start),
 * I'm stuck, Make it tiny, Focus "2 of 5" and Remind me, each at 360 dp and at 1.5x font, with no text cut.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class StartingScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 10, 30)
    private val pad = PaddingValues(top = 16.dp, bottom = 96.dp)

    /** The font scale the content is drawn at: one setContent per test, so both sizes recompose the same content. */
    private var scale by mutableStateOf(1f)

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) {
        scale = fontScale
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = scale)) {
                GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
            }
        }
    }

    /** Draws [body] once, then checks it at 1x and at 1.5x font. */
    private fun both(body: @Composable () -> Unit, check: (Float) -> Unit) {
        show(1f, body)
        for (s in listOf(1f, 1.5f)) { scale = s; compose.waitForIdle(); check(s) }
    }

    private fun shot(name: String, scale: Float) =
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/start-$name-360x800" + (if (scale > 1f) "-font-1.5" else "") + ".png")

    private val all = listOf(
        TodayRow("t2", "Call the bank about the report", 1, null, null, "me", "Me", null, false),
        TodayRow("t3", "Record the fractions lesson", 2, null, null, "humopedia", "Humopedia", null, false),
        TodayRow("t4", "Measure the drone's hover time", 1, "2026-10-30 17:00", null, "ababil", "ABABIL", null, false),
        TodayRow("t5", "Weigh in", 3, null, null, "me", "Me", null, false),
        TodayRow("n1", "Clean the desk", 2, null, null, "uzcosmos", "UzCosmos", null, false),
        TodayRow("n2", "Write the release note", 2, null, null, "uzcosmos", "UzCosmos", null, false),
    )
    private val folded = TodayUiState(loading = false, rows = all.take(3), open = 6, waiting = 3, doneToday = 1, status = "Live", all = all,
        allKeys = all.indices.map { it.toDouble() })

    @Composable
    private fun Today(ui: TodayUiState, drag: Pair<Int, Int>? = null, onExpand: (Boolean) -> Unit = {}) =
        TodayScreen(ui, pad, today = day, onWhatNow = {}, onExpand = onExpand, dragPreview = drag)

    // ── Today ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun todayFoldedShowsThreeAndAllToday() {
        var asked: Boolean? = null
        show { Today(folded, onExpand = { asked = it }) }
        compose.onNodeWithText("All today · 6").assertIsDisplayed()
        compose.onNodeWithContentDescription("What now?").assertIsDisplayed()
        assertTrue("the 4th waits", compose.onRoot().fetchSemanticsNode().let { texts(it) }.none { it == "Weigh in" })
        assertNoCutText(1f)
        shot("today-folded", 1f)
        compose.onNodeWithContentDescription("All today · 6").performClick()
        assertEquals("opens in place", true, asked)
    }

    @Test fun todayFoldedLargeFont() {
        show(1.5f) { Today(folded) }
        assertNoCutText(1.5f)
        shot("today-folded", 1.5f)
    }

    @Test fun todayOpenListsEveryTaskInOrder() = both({ Today(folded.copy(expanded = true)) }) { s ->
        compose.onNodeWithText("Write the release note").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show 3").assertIsDisplayed()
        assertNoCutText(s)
        shot("today-open", s)
    }

    @Test fun todayMidDragLiftsTheFifthRowOverTheFirst() = both({ Today(folded.copy(expanded = true), drag = 4 to 0) }) { s ->
        val lifted = nodes().filter { it.config.getOrNull(SemanticsProperties.StateDescription) == "Lifted" }
        assertEquals("one row is lifted", 1, lifted.size)
        // The lifted card sits over slot 0 and row 0 has made room below it: its title starts under the card, not under it.
        val card = lifted.single().boundsInRoot
        val first = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Call the bank about the report" } }.boundsInRoot
        assertTrue("${s}x row 0 moved below the lifted card (title top ${first.top}, card bottom ${card.bottom})", first.top >= card.bottom - 2f)
        assertTrue("${s}x the card covers slot 0 (card top ${card.top}, title top ${first.top})", card.top < first.top)
        assertNoCutText(s)
        shot("today-mid-drag", s)
    }

    // ── What now ───────────────────────────────────────────────────────────────────────────────────────────────────

    private val pick = NowPick("n1", "Clean the desk", "UzCosmos", "Clear one corner of it, just one 🧽", "uzcosmos", null, 2)

    @Test fun whatNowAsksTheEnergyOnce() = both({ Box(Modifier.padding(top = 24.dp)) { WhatNowContent(WhatNowUi(WhatNowUi.Stage.ENERGY)) } }) { s ->
        compose.onNodeWithContentDescription("Energy: Low").assertIsDisplayed()
        assertNoCutText(s)
        shot("what-now-energy", s)
    }

    @Test fun whatNowShowsOnePickWithWhyAndTheFirstStep() = both({ Box(Modifier.padding(top = 24.dp)) { WhatNowContent(WhatNowUi(WhatNowUi.Stage.PICK, pick, shown = 1)) } }) { s ->
        compose.onNodeWithContentDescription("Start 10 minutes: Clean the desk").assertIsDisplayed()
        compose.onNodeWithText("For UzCosmos").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pick 1 of 3").assertIsDisplayed()
        assertNoCutText(s)
        shot("what-now-card", s)
    }

    @Test fun whatNowWithNoStepsOffersMakeItTiny() = both({
        Box(Modifier.padding(top = 24.dp)) { WhatNowContent(WhatNowUi(WhatNowUi.Stage.PICK, pick.copy(first = FIRST_FALLBACK, canTiny = true), shown = 1)) }
    }) { s ->
        compose.onNodeWithText("First: Put it in front of you · 30 s").assertIsDisplayed()
        compose.onNodeWithContentDescription("Make it tiny").assertIsDisplayed()
        assertNoCutText(s)
        shot("what-now-no-steps", s)
    }

    @Test fun whatNowWithMicroStepsHasNoTinyLink() {
        show { Box(Modifier.padding(top = 24.dp)) { WhatNowContent(WhatNowUi(WhatNowUi.Stage.PICK, pick, shown = 1)) } }
        compose.onNodeWithContentDescription("Make it tiny").assertDoesNotExist()
    }

    private val t0 = 1_000_000L

    @Test fun whatNowRunsItsTenMinutes() = both({ Box(Modifier.padding(top = 24.dp)) { WhatNowContent(WhatNowUi(WhatNowUi.Stage.TIMER, pick, shown = 1, startedAt = t0), clock = { t0 + 21_000 }) } }) { s ->
        compose.onNodeWithContentDescription("9:39 left").assertIsDisplayed()
        compose.onNodeWithContentDescription("Done: Clean the desk").assertIsDisplayed()
        shot("what-now-running", s)
    }

    // ── I'm stuck · Make it tiny · Focus · Remind me ──────────────────────────────────────────────────────────────

    private val task = StartUi(loading = false, id = "n2", title = "Write the release note", project = "uzcosmos", projectLabel = "UzCosmos",
        micro = listOf("Stand up. Yes, right now 🧍", "Open the release notes file 📄", "Write one ugly first line ✍️", "Read it out loud like a news anchor 🎙️", "Tick it. You did it 🎉"))

    @Test fun stuckOffersFourPlainReasons() = both({ Box(Modifier.padding(top = 24.dp)) { StuckContent(task, null, today = day) } }) { s ->
        for (r in StuckReason.entries) compose.onNodeWithContentDescription("Stuck: ${r.label}").assertIsDisplayed()
        assertNoCutText(s)
        shot("stuck", s)
    }

    @Test fun stuckWaitingAsksWhoAndWhen() = both({ Box(Modifier.padding(top = 24.dp)) { StuckContent(task, StuckReason.WAITING, today = day) } }) { s ->
        compose.onNodeWithContentDescription("Waiting for whom").assertIsDisplayed()
        compose.onNodeWithText("Mon 2 Nov").assertIsDisplayed()
        shot("stuck-waiting", s)
    }

    @Test fun makeItTinyShowsTheSpiceAndTheSteps() = both({ Box(Modifier.padding(top = 24.dp)) { TinyContent(task.copy(micro = emptyList(), tiny = Tiny.Ready(task.micro.take(4))), spice = 2) } }) { s ->
        compose.onNodeWithContentDescription("Spice 2: Small steps").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start focus").assertIsDisplayed()
        shot("tiny-steps", s)
    }

    @Test fun makeItTinyOfflineSaysSoInOneQuietLine() {
        show { Box(Modifier.padding(top = 24.dp)) { TinyContent(task.copy(micro = emptyList(), tiny = Tiny.Offline), spice = 1) } }
        compose.onNodeWithText("Needs internet").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        assertTrue("\"try again\" is said once", texts(compose.onRoot().fetchSemanticsNode()).count { it.contains("try again", ignoreCase = true) } == 1)
        shot("tiny-offline", 1f)
    }

    private var index by mutableIntStateOf(0)

    @Test fun focusShowsTwoOfFiveAndItsCardNeverMoves() = both({ FocusContent(task, index) }) { s ->
        index = 0
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Step 1 of 5").assertIsDisplayed()
        val top1 = compose.onNodeWithTag("focus-card").getBoundsInRoot().top
        index = 1
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Step 2 of 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("I'm stuck").assertIsDisplayed()
        compose.onNodeWithContentDescription("Skip").assertIsDisplayed()
        assertEquals("the card's top is the same on step 1 and step 2", top1, compose.onNodeWithTag("focus-card").getBoundsInRoot().top)
        assertNoCutText(s)
        shot("focus-2-of-5", s)
    }

    @Test fun skipOnTheLastFocusStepAsksDone() = both({ FocusContent(task, 4, asking = true) }) { s ->
        compose.onNodeWithContentDescription("Step 5 of 5").assertIsDisplayed()
        compose.onNodeWithText("Done?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Done: Write the release note").assertIsDisplayed()
        compose.onNodeWithContentDescription("Not yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Skip").assertDoesNotExist()
        assertNoCutText(s)
        shot("focus-last-asks-done", s)
    }

    private var picked by mutableIntStateOf(10)

    @Test fun remindMeSaysWhenInPlainWords() = both({ Box(Modifier.padding(top = 24.dp)) { RemindChoices(picked, "2026-10-30 18:00") { picked = it } } }) { s ->
        compose.onNodeWithText("10 min early, 17:50").assertIsDisplayed()
        compose.onNodeWithText("Off").assertIsDisplayed()
        shot("remind-me", s)
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun below(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { below(it) }
    private fun nodes() = below(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
    private fun texts(n: SemanticsNode) = below(n).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }

    private fun layoutOf(n: SemanticsNode): androidx.compose.ui.text.TextLayoutResult? {
        val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()
    }

    /** No "…", no one-line text wider than its box, no word broken across lines (Boss: 1.5x and 360 dp cut nothing). */
    private fun assertNoCutText(scale: Float) {
        var seen = 0
        for (n in nodes()) {
            val l = layoutOf(n) ?: continue
            seen++
            val text = l.layoutInput.text.text
            val at = "${scale}x \"${text.take(40)}\""
            assertTrue("$at has an ellipsis", (0 until l.lineCount).none { l.isLineEllipsized(it) })
            val needs = l.multiParagraph.intrinsics.maxIntrinsicWidth
            assertTrue("$at is cut by its box (words $needs px, box ${n.size.width} px)", !(l.lineCount == 1 && needs > n.size.width + 1f))
            for (i in 0 until l.lineCount - 1) {
                val e = l.getLineEnd(i)
                assertTrue("$at breaks a word at line ${i + 1}", e >= text.length || text[e - 1].isWhitespace() || text[e].isWhitespace() || text[e - 1] == '-')
            }
        }
        assertTrue("some text was checked", seen > 0)
    }
}
