package org.pagel.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
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
import org.pagel.ai.data.db.InboxItemEntity
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.inbox.Choice
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.inbox.ChoiceFormContent
import org.pagel.ai.ui.inbox.Form
import org.pagel.ai.ui.inbox.InboxActions
import org.pagel.ai.ui.inbox.InboxScreen
import org.pagel.ai.ui.inbox.InboxUi
import org.pagel.ai.ui.inbox.InboxViewModel
import org.pagel.ai.ui.inbox.Someday
import org.pagel.ai.ui.inbox.SomedayUi
import org.pagel.ai.ui.inbox.Throw
import org.pagel.ai.ui.inbox.Tidy
import org.pagel.ai.ui.inbox.TidyContent
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Inbox clarify at Boss's sizes (360 dp, 1x and 1.5x font): the two GTD groups under the card, the Waiting, Project and
 * Calendar forms, the Someday list with Waiting for, and the tidy-up sheet in each of its states.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class InboxClarifyScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val projects = listOf(ProjectEntity("humopedia", "Humopedia", null, 0, 41), ProjectEntity("me", "Me", null, 1, 12))

    private fun row(file: String, claim: String, next: String = "", guessed: Boolean = true, kind: String = "action") = InboxItemEntity(
        file = file, position = 0, saved = "2026-10-27", source = "https://www.linkedin.com/posts/grant",
        text = "LinkedIn post by IT Park posted 2026-10-27\n\n$claim. Long caption with the details.", claim = claim, why = "Humopedia can apply", project = "humopedia", kind = kind,
        next = next, note = "", guessed = guessed, author = "IT Park", posted = "2026-10-27", later = null, hasPicture = false,
    )

    private val grant = row("inbox/grant.md", "The IT Park grant round closes on 15 November", "Open the grant form and fill page one")
    private val unread = row("inbox/unread.md", "A thread about welding", guessed = false, kind = "learn")

    private fun ui(vararg rows: InboxItemEntity, sorted: Int = 7) = InboxUi(
        loading = false, cards = rows.map { InboxViewModel.card(it, false, projects.firstOrNull { p -> p.name == it.project }) },
        sorted = sorted, goal = 20, kept = 2, cap = 5, minWords = 6, left = 905, streak = 3, projects = projects,
    )

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    @Composable
    private fun screen(u: InboxUi, onChoose: (Choice) -> Unit = {}) =
        InboxScreen(u, leaving = null, pictures = emptyMap(), contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp), actions = InboxActions(choose = { _, c, _: Throw -> onChoose(c) }))

    private fun oneWholeLine(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        val l = layout.single()
        assertEquals("\"$text\" wraps", 1, l.lineCount)
        assertFalse("\"$text\" is cut", l.isLineEllipsized(0) || l.getLineRight(0) - l.getLineLeft(0) > l.size.width + 1f)
    }

    @Test fun theTwoGroupsUnderTheCardAt360() {
        show { screen(ui(grant)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-groups-360.png")
        compose.onNodeWithText("Can I do something about it?").assertIsDisplayed()
        for (w in listOf("Drop", "Someday", "Note", "Learn", "Later", "Do now", "Action", "Project", "Waiting", "Calendar")) compose.onNodeWithText(w, useUnmergedTree = true).assertIsDisplayed()
        // Today's 20, not 905.
        compose.onNodeWithText("Today's 20").assertIsDisplayed()
        // All ten sit above the 96 dp the tab bar takes.
        val last = compose.onNodeWithText("Calendar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the choices end at ${last.bottom} px of ${root.bottom}", last.bottom <= root.bottom - 96 * 3f)
    }

    @Test fun theTwoGroupsUnderTheCardAt15xFont() {
        show(1.5f) { screen(ui(grant)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-groups-360-font-1.5.png")
        for (w in listOf("Someday", "Project", "Waiting", "Calendar", "Later")) oneWholeLine(w)
    }

    /** A card with an AI pick and one with none: the question line and the groups stay at the same y (no layout jump). */
    @Test fun theDockDoesNotMoveBetweenAPickedAndAnUnreadCard() {
        var u by androidx.compose.runtime.mutableStateOf(ui(grant))
        show { screen(u) }
        fun tops() = listOf("Can I do something about it?", "Someday", "Calendar").map { compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
        val picked = tops()
        u = ui(unread)
        compose.waitForIdle()
        assertEquals(picked, tops())
    }

    @Test fun tappingASomedayChipChoosesSomeday() {
        var chosen: Choice? = null
        show { screen(ui(grant), onChoose = { chosen = it }) }
        compose.onNodeWithText("Someday", useUnmergedTree = true).performClick()
        assertEquals(Choice.SOMEDAY, chosen)
    }

    @Test fun anUnreadCardShowsTheSameGroups() {
        show { screen(ui(unread)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-groups-unread.png")
        compose.onNodeWithText("Waiting", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun waitingForm() {
        val u = ui(grant)
        show { Column(Modifier.padding(top = 16.dp)) { ChoiceFormContent(Form(u.cards[0], Choice.WAITING, "the signed grant form", "humopedia", false, due = "2026-11-02", who = "Aziz"), u, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-form-waiting.png")
        compose.onNodeWithContentDescription("Add to waiting").assertIsDisplayed()
        compose.onNodeWithText("3 days").assertIsDisplayed()
    }

    @Test fun waitingFormAt15xFont() {
        val u = ui(grant)
        show(1.5f) { Column(Modifier.padding(top = 16.dp)) { ChoiceFormContent(Form(u.cards[0], Choice.WAITING, "the signed grant form", "humopedia", false, due = "2026-11-02"), u, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-form-waiting-font-1.5.png")
        compose.onNodeWithContentDescription("Add to waiting").assertIsDisplayed()
    }

    @Test fun projectForm() {
        val u = ui(grant)
        show { Column(Modifier.padding(top = 16.dp)) { ChoiceFormContent(Form(u.cards[0], Choice.PROJECT, "Open the grant form", "humopedia", false, name = "IT Park grant"), u, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-form-project.png")
        compose.onNodeWithContentDescription("Start project").assertIsDisplayed()
        compose.onNodeWithContentDescription("Project name").assertIsDisplayed()
    }

    @Test fun calendarFormAt15xFont() {
        val u = ui(grant)
        show(1.5f) { Column(Modifier.padding(top = 16.dp)) { ChoiceFormContent(Form(u.cards[0], Choice.CALENDAR, "Grant round closes", "humopedia", false, due = "2026-11-15 09:00"), u, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-form-calendar-font-1.5.png")
        compose.onNodeWithContentDescription("Put on calendar").assertIsDisplayed()
    }

    private fun task(id: String, project: String, text: String, due: String? = null) = TaskEntity(
        id = id, project = project, phase = "", sort = 1.0, text = text, title = null, status = "open", priority = if (due == null) 3 else 1, star = false, due = due, every = null,
        doneWhen = null, doneAt = null, updatedAt = "2026-10-30T08:00:00Z", serverVersion = 1, lastModified = 0,
    )

    @Test fun somedayListWithWaiting() {
        val u = SomedayUi(
            someday = listOf(task("t1", "me", "Learn to weld"), task("t2", "humopedia", "Try a cooperative maths game for grade 6 with the class")),
            waiting = listOf(task("t3", "humopedia", "Waiting for Aziz: the signed grant form", "2026-11-02")),
            projects = projects.associateBy { it.name },
        )
        show { Someday(u, PaddingValues(top = 16.dp, bottom = 96.dp)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/someday-list.png")
        compose.onNodeWithText("Learn to weld").assertIsDisplayed()
        compose.onNodeWithText("Waiting for").assertIsDisplayed()
    }

    @Test fun somedayListAt15xFont() {
        val u = SomedayUi(someday = listOf(task("t1", "me", "Learn to weld")), projects = projects.associateBy { it.name })
        show(1.5f) { Someday(u, PaddingValues(top = 16.dp, bottom = 96.dp)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/someday-list-font-1.5.png")
        compose.onNodeWithContentDescription("Bring back: Learn to weld").assertIsDisplayed()
    }

    @Test fun somedayEmpty() {
        show { Someday(SomedayUi(), PaddingValues(top = 16.dp, bottom = 96.dp)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/someday-empty.png")
        compose.onNodeWithText("Nothing here. The maybes you file from the Inbox wait in this list.").assertIsDisplayed()
    }

    private fun old(n: Int, source: String) = (1..n).map { row("inbox/$source$it.md", "Old save $it").copy(source = "https://$source.com/p/$it") }

    @Test fun tidySheetShowsTheCountBeforeAnythingMoves() {
        val t = Tidy(loading = false, items = old(23, "www.instagram") + old(9, "x") + old(5, "www.linkedin"))
        show { Column(Modifier.padding(top = 16.dp)) { TidyContent(t, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-tidy.png")
        compose.onNodeWithText("37 saves are older than 60 days").assertIsDisplayed()
        compose.onNodeWithContentDescription("Move all 37 to Someday").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drop all 37").assertIsDisplayed()
    }

    @Test fun tidySheetAt15xFont() {
        val t = Tidy(loading = false, items = old(12, "www.instagram"))
        show(1.5f) { Column(Modifier.padding(top = 16.dp)) { TidyContent(t, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-tidy-font-1.5.png")
        compose.onNodeWithContentDescription("Move all 12 to Someday").assertIsDisplayed()
    }

    @Test fun tidySheetOtherStates() {
        show { Column(Modifier.padding(top = 16.dp)) { TidyContent(Tidy(loading = false, failed = true), {}, {}) } }
        compose.onNodeWithText("Could not reach pagel.uz. Try again when you are online.").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-tidy-offline.png")
    }
}
