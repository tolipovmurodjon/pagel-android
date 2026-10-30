package org.pagel.ai.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
import org.pagel.ai.ui.guide.GuideCards
import org.pagel.ai.ui.guide.GuideDestination
import org.pagel.ai.ui.guide.GuideScreen
import org.pagel.ai.ui.shell.ProjectListRoute
import org.pagel.ai.ui.shell.ReviewRoute
import org.pagel.ai.ui.shell.ShutdownRoute
import org.pagel.ai.ui.shell.Tab
import org.pagel.ai.ui.shell.UpcomingListRoute
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "How it works" (Boss 2026-10-30): ten tiny GTD cards, swiped like pages. Read at Boss's sizes (360 dp, 1.5x font),
 * every card is on screen with its Try it, and each Try it names the screen the brief says it opens.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class GuideScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val pad = PaddingValues(top = 16.dp, bottom = 96.dp)
    private var tried: GuideDestination? = null

    private fun show(card: Int = 0, fontScale: Float = 1f) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { GuideScreen(card, pad) { tried = it } } }
        }
    }

    @Test fun tenCardsEachWithOneIdea() {
        assertEquals(10, GuideCards.all.size)
        assertEquals(10, GuideCards.all.map { it.id }.toSet().size)
        // Tiny on purpose: Boss hates long AI text. One line, one example, one don't, each short.
        for (c in GuideCards.all) {
            assertTrue("${c.id} line too long", c.line.length <= 120)
            assertTrue("${c.id} example too long", c.example.length <= 60)
            assertTrue("${c.id} dont too long", c.dont.length <= 40)
        }
    }

    @Test fun everyTryItOpensTheScreenTheBriefNames() {
        val want = mapOf(
            "capture" to GuideDestination.AddSheet,
            "clarify" to GuideDestination.OnTab(Tab.Inbox),
            "next-step" to GuideDestination.OnTab(Tab.Today),
            "projects" to GuideDestination.Push(ProjectListRoute),
            "areas" to GuideDestination.Push(ProjectListRoute),
            "calendar" to GuideDestination.Push(UpcomingListRoute),
            "waiting" to GuideDestination.Push(ReviewRoute),
            "todays-3" to GuideDestination.OnTab(Tab.Today),
            "close-the-day" to GuideDestination.Push(ShutdownRoute),
            "stuck" to GuideDestination.OnTab(Tab.Today),
        )
        assertEquals(want.keys, GuideCards.all.map { it.id }.toSet())
        for (c in GuideCards.all) assertEquals(c.id, want[c.id], c.goes)
    }

    @Test fun theHelpCardsOfTheScreensExist() {
        assertEquals("clarify", GuideCards.all[GuideCards.CLARIFY].id)
        assertEquals("projects", GuideCards.all[GuideCards.PROJECTS].id)
        assertEquals("close-the-day", GuideCards.all[GuideCards.CLOSE_THE_DAY].id)
    }

    @Test fun swipingReadsAllTenCardsAndTryItSendsTheCardsDestination() {
        show()
        for ((i, c) in GuideCards.all.withIndex()) {
            compose.onNodeWithText(c.title).assertIsDisplayed()
            compose.onNodeWithContentDescription("Try it").assertIsDisplayed()
            compose.onNodeWithContentDescription("Try it").performClick()
            assertEquals(c.id, c.goes, tried)
            tried = null
            if (i < GuideCards.all.lastIndex) {
                compose.onRoot().performTouchInput { swipeLeft() }
                compose.waitForIdle()
            }
        }
    }

    // One composition per card: a swipe in Robolectric leaves the next page undrawn in the capture (the device draws it).
    private fun shoot(i: Int, scale: Float) {
        show(card = i, fontScale = scale)
        compose.onNodeWithText(GuideCards.all[i].title).assertIsDisplayed()
        compose.onNodeWithContentDescription("Try it").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/guide-card-${i + 1}-360" + (if (scale > 1f) "-1.5x" else "") + ".png")
    }
    @Test fun card1At1x() = shoot(0, 1f)
    @Test fun card1At15x() = shoot(0, 1.5f)
    @Test fun card2At1x() = shoot(1, 1f)
    @Test fun card2At15x() = shoot(1, 1.5f)
    @Test fun card3At1x() = shoot(2, 1f)
    @Test fun card3At15x() = shoot(2, 1.5f)
    @Test fun card4At1x() = shoot(3, 1f)
    @Test fun card4At15x() = shoot(3, 1.5f)
    @Test fun card5At1x() = shoot(4, 1f)
    @Test fun card5At15x() = shoot(4, 1.5f)
    @Test fun card6At1x() = shoot(5, 1f)
    @Test fun card6At15x() = shoot(5, 1.5f)
    @Test fun card7At1x() = shoot(6, 1f)
    @Test fun card7At15x() = shoot(6, 1.5f)
    @Test fun card8At1x() = shoot(7, 1f)
    @Test fun card8At15x() = shoot(7, 1.5f)
    @Test fun card9At1x() = shoot(8, 1f)
    @Test fun card9At15x() = shoot(8, 1.5f)
    @Test fun card10At1x() = shoot(9, 1f)
    @Test fun card10At15x() = shoot(9, 1.5f)

    @Test fun theCardOpensOnTheCardAScreenAsksFor() {
        show(card = GuideCards.CLOSE_THE_DAY)
        compose.onNodeWithText("Close the day and the week").assertIsDisplayed()
        compose.onNodeWithContentDescription("Card 9 of 10").assertIsDisplayed()
    }
}
