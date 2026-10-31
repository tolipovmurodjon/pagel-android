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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
        for (w in listOf("Drop", "Someday", "Note", "Learn", "Later", "Do now", "Action", "Project", "Waiting", "Calendar")) compose.onAllNodesWithText(w, useUnmergedTree = true).onFirst().assertIsDisplayed()
        // Today's 20, not 905.
        compose.onNodeWithText("Today's 20").assertIsDisplayed()
        // All ten sit above the 96 dp the tab bar takes.
        val last = compose.onNodeWithText("Calendar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the choices end at ${last.bottom} px of ${root.bottom}", last.bottom <= root.bottom - 96 * 3f)
    }

    /** Checker 2026-10-30 #3: "Yes" was cut at 1.5x and Someday/Calendar shrank alone. Every word whole, one size. */
    @Test fun theTwoGroupsUnderTheCardAt15xFont() {
        show(1.5f) { screen(ui(grant)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-groups-360-font-1.5.png")
        // (Action is also the AI pick's own word above, so it is read as the grid's last node below.)
        for (w in listOf("No", "Yes", "Drop", "Someday", "Note", "Learn", "Do now", "Project", "Waiting", "Calendar", "Later")) oneWholeLine(w)
        val sizes = listOf("Drop", "Someday", "Note", "Learn", "Do now", "Action", "Project", "Waiting", "Calendar").map { fontSize(it) }.toSet()
        assertEquals("one word size in the grid: $sizes", 1, sizes.size)
    }

    @Test fun oneWordSizeInTheGridAt1x() {
        show { screen(ui(grant)) }
        val sizes = listOf("Drop", "Someday", "Note", "Learn", "Do now", "Action", "Project", "Waiting", "Calendar").map { fontSize(it) }.toSet()
        assertEquals("one word size in the grid: $sizes", 1, sizes.size)
        oneWholeLine("Calendar")
    }

    private val dockWords = listOf("Drop", "Someday", "Note", "Learn", "Do now", "Action", "Project", "Waiting", "Calendar", "Later", "Yes", "No",
        "Hold = repeat", "Can I do something about it?", "Inbox", "Today's 20", "Done")

    private fun layoutOf(n: SemanticsNode): TextLayoutResult? {
        val out = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()
    }

    /** The dock's own words: none ends in "…", none is wider than its box, none breaks inside a word (Boss: nothing cut mid-word). */
    private fun assertNoCutText(what: String) {
        var seen = 0
        for (w in dockWords) for (n in compose.onAllNodesWithText(w, useUnmergedTree = true).fetchSemanticsNodes()) {
            val l = layoutOf(n) ?: continue
            seen++
            val text = l.layoutInput.text.text
            val at = "$what \"${text.take(40)}\""
            assertTrue("$at has an ellipsis", (0 until l.lineCount).none { l.isLineEllipsized(it) })
            assertTrue("$at is cut by its box (words ${l.multiParagraph.intrinsics.maxIntrinsicWidth} px, box ${n.size.width} px)", !(l.lineCount == 1 && l.multiParagraph.intrinsics.maxIntrinsicWidth > n.size.width + 1f))
            for (i in 0 until l.lineCount - 1) {
                val e = l.getLineEnd(i)
                assertTrue("$at breaks a word at line ${i + 1}", e >= text.length || text[e - 1].isWhitespace() || text[e].isWhitespace() || text[e - 1] == '-')
            }
        }
        assertTrue("$what: some dock text was checked", seen >= 8)
    }

    @Test fun theDockCutsNothingAt15xFont() {
        show(1.5f) { screen(ui(grant)) }
        assertNoCutText("dock 1.5x")
    }

    @Test fun theDockCutsNothingAt320dpAnd15xFont() {
        show(1.5f) { Box(Modifier.width(320.dp).height(640.dp)) { screen(ui(grant)) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-dock-320-font-1.5.png")
        assertNoCutText("dock 320 dp 1.5x")
    }

    @Test fun theDockCutsNothingAt1x() {
        show { screen(ui(grant)) }
        assertNoCutText("dock 1x")
    }

    /**
     * Boss: never red. A pixel is red when its hue is within 15 degrees of 0 (345-360 or 0-15), saturation is at least 0.5
     * and value at least 0.35; amber (hue about 28-35) and ink are not. Round 2 had a red trash (0xD1453B) and a red badge.
     */
    private fun redPixels(): Int {
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val hsv = FloatArray(3)
        var red = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            android.graphics.Color.colorToHSV(bmp.getPixel(x, y), hsv)
            if ((hsv[0] >= 345f || hsv[0] <= 15f) && hsv[1] >= 0.5f && hsv[2] >= 0.35f) red++
        }
        return red
    }

    @Test fun inboxIsNeverRedInTheDock() {
        show { screen(ui(grant)) }
        assertEquals("red pixels in the dock", 0, redPixels())
    }

    @Test fun inboxIsNeverRedInTheGroupsAtLargeFont() {
        show(1.5f) { screen(ui(grant)) }
        assertEquals("red pixels in the dock at 1.5x", 0, redPixels())
    }

    @Test fun inboxIsNeverRedInTheTidySheet() {
        val t = Tidy(loading = false, items = old(23, "www.instagram"))
        show { Column(Modifier.padding(top = 16.dp).testTag("tidy-sheet")) { TidyContent(t, {}, {}) } }
        assertEquals("red pixels in the tidy sheet", 0, redPixels())
    }

    private fun fontSize(text: String): Float {
        // The last node with the word: the grid's (the AI pick's own word comes first in the tree).
        val node = compose.onAllNodesWithText(text, useUnmergedTree = true).onLast().fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        return layout.single().layoutInput.style.fontSize.value
    }

    /** Later is a small text link under the two rows (checker 2026-10-30 #9), not a sixth disc in the No row. */
    @Test fun laterIsALinkUnderTheRows() {
        var chosen: Choice? = null
        show { screen(ui(grant), onChoose = { chosen = it }) }
        val later = compose.onNodeWithText("Later", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val calendar = compose.onNodeWithText("Calendar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Later (top ${later.top}) sits under the Yes row (bottom ${calendar.bottom})", later.top >= calendar.bottom - 1f)
        compose.onNodeWithText("Later", useUnmergedTree = true).performClick()
        assertEquals(Choice.LATER, chosen)
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

    /** Checker 2026-10-30 #7 and #9: Project asks for an Area, and each field keeps its name when filled. */
    @Test fun projectForm() {
        val u = ui(grant)
        var edited: Form? = null
        show { Column(Modifier.padding(top = 16.dp)) { ChoiceFormContent(Form(u.cards[0], Choice.PROJECT, "Open the grant form", "humopedia", false, name = "IT Park grant", area = "Work"), u, { edited = it }, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-form-project.png")
        compose.onNodeWithContentDescription("Start project").assertIsDisplayed()
        compose.onNodeWithContentDescription("Project name").assertIsDisplayed()
        compose.onNodeWithText("Project name").assertIsDisplayed()
        compose.onNodeWithText("First step").assertIsDisplayed()
        compose.onNodeWithText("Area").assertIsDisplayed()
        compose.onNodeWithContentDescription("Area: None").performClick()
        assertTrue("picking an area edits the form", edited != null)
        assertEquals(null, edited!!.area)
    }

    @Test fun projectFormAt15xFont() {
        val u = ui(grant)
        show(1.5f) { Column(Modifier.padding(top = 16.dp)) { ChoiceFormContent(Form(u.cards[0], Choice.PROJECT, "Open the grant form", "humopedia", false, name = "IT Park grant", area = "Work"), u, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-form-project-font-1.5.png")
        compose.onNodeWithContentDescription("Start project").assertIsDisplayed()
    }

    /** Checker 2026-10-30 #6: Calendar asks for the time; the sheet opens on the wheel at 09:00 on the save's day. */
    @Test fun calendarOpensOnTheWheel() {
        val today = java.time.LocalDate.parse("2026-10-30")
        show { Column(Modifier.padding(top = 16.dp)) { org.pagel.ai.ui.glass.DateContent("2026-10-30 09:00", today, onDone = {}, startMode = "wheel") { _, _ -> } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-calendar-wheel.png")
        compose.onNodeWithContentDescription("Time 09:00").assertIsDisplayed()
        compose.onNodeWithContentDescription("Done").assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Bring back").assertIsDisplayed()
        // Checker 2026-10-30 #4: "Learn to weld" broke one word a line beside a chip that took 40 % of the row.
        oneWholeLine("Learn to weld")
    }

    /** Titles wrap: two lines at 1x, all of it at 1.5x; Bring back offers the Inbox or an Action. */
    @Test fun somedayLongTitlesWrapAndBringBackOffersTwoWays() {
        val long = "Try a cooperative maths game for grade 6 with the whole class and write down what the children say"
        val u = SomedayUi(someday = listOf(task("t2", "humopedia", long)), projects = projects.associateBy { it.name })
        show(1.5f) { Someday(u, PaddingValues(top = 16.dp, bottom = 96.dp)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/someday-long-font-1.5.png")
        val node = compose.onNodeWithText(long, useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        assertFalse("the title is cut at 1.5x", layout.single().hasVisualOverflow)
    }

    @Test fun bringBackSheet() {
        var picked: Boolean? = null
        show { Column(Modifier.padding(top = 16.dp)) { org.pagel.ai.ui.inbox.BringBackContent(task("t1", "me", "Learn to weld")) { picked = it } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/someday-bring-back.png")
        compose.onNodeWithContentDescription("To the Inbox").performClick()
        assertEquals(true, picked)
    }

    /** Checker 2026-10-30 #8: Waiting for in the weekly review, with data and each check day. */
    @Test fun waitingInTheWeeklyReview() {
        val waiting = listOf(
            org.pagel.ai.ui.rituals.RitualTask("w1", "Waiting for Aziz: the signed grant form", "humopedia", "Humopedia", null, 2, due = "2026-10-28"),
            org.pagel.ai.ui.rituals.RitualTask("w2", "Waiting for: the drone parts from Uzum", "me", "Me", null, 2, due = "2026-11-02"),
        )
        val review = org.pagel.ai.ui.rituals.ReviewUi(loading = false, step = 3, inbox = 14, someday = listOf(org.pagel.ai.ui.rituals.RitualTask("p1", "Learn to weld", "me", "Me", null, 3)), waiting = waiting)
        show { org.pagel.ai.ui.rituals.ReviewScreen(review, PaddingValues(top = 16.dp, bottom = 96.dp)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/review-waiting.png")
        compose.onNodeWithText("Waiting for").assertExists()
        compose.onNodeWithText("Aziz · the signed grant form").assertExists()
        compose.onNodeWithText("the drone parts from Uzum").assertExists()
        compose.onNodeWithText("check was Wed 28 Oct").assertExists()
    }

    @Test fun somedayEmpty() {
        show { Someday(SomedayUi(), PaddingValues(top = 16.dp, bottom = 96.dp)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/someday-empty.png")
        compose.onNodeWithText("Nothing here. The maybes you file from the Inbox wait in this list.").assertIsDisplayed()
    }

    private fun old(n: Int, source: String) = (1..n).map { row("inbox/$source$it.md", "Old save $it").copy(source = "https://$source.com/p/$it") }

    @Test fun tidySheetShowsTheCountBeforeAnythingMoves() {
        val t = Tidy(loading = false, items = old(23, "www.instagram") + old(9, "x") + old(5, "www.linkedin"))
        show { Column(Modifier.padding(top = 16.dp).testTag("tidy-sheet")) { TidyContent(t, {}, {}) } }
        compose.onNodeWithTag("tidy-sheet").captureRoboImage("build/outputs/roborazzi/inbox-tidy.png")
        compose.onNodeWithText("37 saves older than 60 days").assertIsDisplayed()
        compose.onNodeWithContentDescription("Move all 37 to Someday").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drop all 37").assertIsDisplayed()
    }

    /** Checker 2026-10-30 #2: pagel.uz's whole count (412), not the first page of 60. */
    @Test fun tidySheetShowsTheWholeCount() {
        val t = Tidy(loading = false, items = old(60, "www.instagram"), total = 412, bySource = mapOf("instagram" to 700, "x" to 150, "note" to 55, "telegram" to 12))
        show { Column(Modifier.padding(top = 16.dp).testTag("tidy-sheet")) { TidyContent(t, {}, {}) } }
        compose.onNodeWithTag("tidy-sheet").captureRoboImage("build/outputs/roborazzi/inbox-tidy-412.png")
        compose.onNodeWithText("412 saves older than 60 days").assertIsDisplayed()
        compose.onNodeWithContentDescription("Move all 412 to Someday").assertIsDisplayed()
        compose.onNodeWithContentDescription("Drop all 412").assertIsDisplayed()
        compose.onNodeWithText("Telegram", substring = true).assertIsDisplayed()
    }

    /** The quiet progress line while a bulk pages through: "120 of 412", in the counts' place, no spinner. */
    @Test fun bulkProgressLine() {
        show { InboxScreen(ui(grant), leaving = null, pictures = emptyMap(), contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp), bulk = org.pagel.ai.ui.inbox.Bulk(Choice.SOMEDAY, 120, 412)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-bulk-progress.png")
        compose.onNodeWithText("Old saves to Someday · 120\u00A0of\u00A0412").assertIsDisplayed()
    }

    /** After the day's goal: done for today, never the pile ("900 more"). */
    @Test fun afterTheGoalItSaysDoneForToday() {
        show { screen(ui(grant, sorted = 20)) }
        compose.onNodeWithText("Done today").assertIsDisplayed()
    }

    @Test fun tidySheetAt15xFont() {
        val t = Tidy(loading = false, items = old(12, "www.instagram"))
        show(1.5f) { Column(Modifier.padding(top = 16.dp).testTag("tidy-sheet")) { TidyContent(t, {}, {}) } }
        compose.onNodeWithTag("tidy-sheet").captureRoboImage("build/outputs/roborazzi/inbox-tidy-font-1.5.png")
        compose.onNodeWithContentDescription("Move all 12 to Someday").assertIsDisplayed()
    }

    @Test fun tidySheetOtherStates() {
        show { Column(Modifier.padding(top = 16.dp).testTag("tidy-sheet")) { TidyContent(Tidy(loading = false, failed = true), {}, {}) } }
        compose.onNodeWithText("Could not reach pagel.uz. Nothing moved.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry").assertIsDisplayed()
        compose.onNodeWithTag("tidy-sheet").captureRoboImage("build/outputs/roborazzi/inbox-tidy-offline.png")
    }
}
