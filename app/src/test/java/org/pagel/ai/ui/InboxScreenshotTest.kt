package org.pagel.ai.ui

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.InboxItemEntity
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.inbox.Choice
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.inbox.ChoiceFormContent
import org.pagel.ai.ui.inbox.Form
import org.pagel.ai.ui.inbox.GuideContent
import org.pagel.ai.ui.inbox.InboxScreen
import org.pagel.ai.ui.inbox.InboxUi
import org.pagel.ai.ui.inbox.InboxViewModel
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2 (the Inbox) at Boss's sizes: a card with its picture at 360 dp and at 1.5× font, each swipe's stamp, the dock
 * above the bar on Boss's A05s (360x800, 1x and 1.5x), Inbox zero, the day's 20/20 ring, the forms and the guide.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class InboxScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val projects = listOf(
        ProjectEntity("humopedia", "Humopedia", null, 0, 41), ProjectEntity("ababil", "ABABIL", null, 1, 27),
        ProjectEntity("pagel", "PAGEL", null, 2, 58), ProjectEntity("me", "Me", null, 3, 12),
    )

    private fun row(file: String, source: String, claim: String, why: String, project: String, kind: String, author: String, pic: Boolean, next: String = "", guessed: Boolean = true) =
        InboxItemEntity(file = file, position = 0, saved = "2026-10-27", source = source,
            text = "Instagram post by @$author posted 2026-10-27\n\n$claim. Long caption: why it works, three examples from real classes, and a link to the full study in the comments.",
            claim = claim, why = why, project = project, kind = kind, next = next, note = "", guessed = guessed, author = author, posted = "2026-10-27", later = null, hasPicture = pic)

    private val ig = row("inbox/ig.md", "https://www.instagram.com/p/x1/", "Quizzes beat rereading: recall builds memory", "Every Humopedia lesson can end with 3 questions", "humopedia", "learn", "learning.lab", pic = true)
    private val meme = row("inbox/meme.md", "https://x.com/a/status/1", "A meme about Mondays", "not tied to your work", "all", "drop", "memes", pic = false)
    private val grant = row("inbox/grant.md", "https://www.linkedin.com/posts/grant", "The IT Park grant round closes on 15 November", "Humopedia can apply for the startup grant", "humopedia", "action", "IT Park", pic = false, next = "Open the grant form and fill page one")

    private fun ui(rows: List<InboxItemEntity>, sorted: Int = 7, kept: Int = 2, left: Int = 214) = InboxUi(
        loading = false,
        cards = rows.map { InboxViewModel.card(it, kept >= 5, projects.firstOrNull { p -> p.name == it.project }) },
        sorted = sorted, goal = 20, kept = kept, cap = 5, minWords = 6, left = left, streak = 3, projects = projects,
    )

    /** A post picture: the mint-to-violet gradient the fake pagel.uz serves in CI. */
    private val picture: Bitmap = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888).also { b ->
        android.graphics.Canvas(b).drawRect(0f, 0f, 720f, 480f, Paint().apply { shader = LinearGradient(0f, 0f, 720f, 480f, 0xFF2BB5A8.toInt(), 0xFF7C4DFF.toInt(), Shader.TileMode.CLAMP) })
    }

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    @Composable
    private fun screen(u: InboxUi, peek: Offset = Offset.Zero) =
        InboxScreen(u, leaving = null, pictures = mapOf(ig.file to picture), contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp), peek = peek)

    private fun oneWholeLine(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        val l = layout.single()
        assertEquals("\"$text\" wraps", 1, l.lineCount)
        val needs = l.getLineRight(0) - l.getLineLeft(0)
        assertFalse("\"$text\" is cut: needs $needs px, has ${l.size.width} px", l.isLineEllipsized(0) || needs > l.size.width + 1f)
    }

    @Test fun cardWithPictureAt360() {
        show { screen(ui(listOf(ig, meme))) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-card-360.png")
        compose.onNodeWithContentDescription("AI pick: Learn").assertIsDisplayed()
        compose.onNodeWithContentDescription("The post's picture").assertIsDisplayed()
        compose.onNodeWithContentDescription("7 of 20 sorted today").assertIsDisplayed()
        oneWholeLine("Inbox")
        // The row of choices ends above the 96 dp the tab bar takes (checker 2026-10-28 #5).
        val drop = compose.onNodeWithText("Drop", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("the choices end at ${drop.bottom} px of ${root.bottom}: under the tab bar", drop.bottom <= root.bottom - 96 * 3f)
    }

    @Test fun cardWithPictureAt360LargeFont() {
        show(1.5f) { screen(ui(listOf(ig, meme))) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-card-360-font-1.5.png")
        oneWholeLine("Inbox")
        oneWholeLine("Drop")
        oneWholeLine("Later")
        // The AI's pick stays on the 780 dp screen at 1.5x (checker 2026-10-28 #1).
        compose.onNodeWithContentDescription("AI pick: Learn").assertIsDisplayed()
        val pick = compose.onNodeWithContentDescription("AI pick: Learn").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("the AI's pick ends at ${pick.bottom} px of ${root.bottom}: under the 96 dp the tab bar takes", pick.bottom <= root.bottom - 96 * 3f)
    }

    // Boss's map (2026-10-28): right = Action, left = Learn, down = Drop, up = Shuffle; each stamps its icon, word, colour.
    @Test fun cardMidSwipeRightIsAnAction() {
        show { screen(ui(listOf(ig, meme)), peek = Offset(420f, 40f)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-mid-swipe.png")
        compose.onNodeWithText("ACTION").assertIsDisplayed()
    }

    @Test fun cardMidSwipeLeftIsLearn() {
        show { screen(ui(listOf(grant, meme)), peek = Offset(-420f, 10f)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-mid-swipe-learn.png")
        compose.onNodeWithText("LEARN").assertIsDisplayed()
    }

    @Test fun cardMidSwipeDownIsADrop() {
        show { screen(ui(listOf(grant, meme)), peek = Offset(10f, 420f)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-mid-swipe-drop.png")
        compose.onNodeWithText("DROP").assertIsDisplayed()
    }

    @Test fun cardMidSwipeUpShuffles() {
        show { screen(ui(listOf(grant, meme)), peek = Offset(-10f, -420f)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-mid-swipe-shuffle.png")
        compose.onNodeWithText("SHUFFLE").assertIsDisplayed()
    }

    /** Learn with today's keeps full is a Note: the stamp says so. */
    @Test fun cardMidSwipeLeftWithKeepsFullIsANote() {
        show { screen(ui(listOf(grant, meme), kept = 5), peek = Offset(-420f, 10f)) }
        compose.onNodeWithText("NOTE").assertIsDisplayed()
    }

    /**
     * Boss's A05s (360x800 dp, three-button navigation: 48 dp + the bar's 80 dp = 128 dp of shell padding). The dock sits
     * fully above the glass bar, 12 dp clear of its top (0.8.1 drew the round choices behind it), the card centred above.
     */
    private val a05s = PaddingValues(top = 24.dp, bottom = 128.dp)

    private fun dockClearsTheBar(names: List<String>) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val px = root.width / 360f
        val barTop = root.bottom - (128 - 8) * px
        for (n in names) {
            val b = compose.onNodeWithText(n, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("\"$n\" ends at ${b.bottom} px, the bar starts at $barTop px", b.bottom <= barTop - 12 * px + 1f)
        }
        val pick = compose.onNodeWithContentDescription("AI pick: Learn").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("the AI's pick ends at ${pick.bottom} px, the bar starts at $barTop px", pick.bottom <= barTop - 12 * px + 1f)
    }

    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    @Test fun dockAboveTheBarOnTheA05s() {
        show { InboxScreen(ui(listOf(ig, meme)), leaving = null, pictures = mapOf(ig.file to picture), contentPadding = a05s) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-dock-360x800.png")
        dockClearsTheBar(listOf("Action", "Note", "Do now", "Later", "Drop"))
        // The pick is one row tall (64 dp), not a slab.
        val pick = compose.onNodeWithContentDescription("AI pick: Learn").fetchSemanticsNode().boundsInRoot
        val px = compose.onRoot().fetchSemanticsNode().boundsInRoot.width / 360f
        assertEquals(64f, pick.height / px, 1f)
        // The caption leaves out the claim it starts with.
        compose.onNodeWithText("Long caption: why it works, three examples from real classes, and a link to the full study in the comments.", substring = true).assertIsDisplayed()
    }

    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    @Test fun dockAboveTheBarOnTheA05sAtLargeFont() {
        show(1.5f) { InboxScreen(ui(listOf(ig, meme)), leaving = null, pictures = mapOf(ig.file to picture), contentPadding = a05s) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-dock-360x800-font-1.5.png")
        dockClearsTheBar(listOf("Action", "Note", "Do now", "Later", "Drop"))
    }

    /** LinkedIn's caption is its claim again: the card says it once (Boss 2026-10-28). */
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    @Test fun aCaptionThatRepeatsTheClaimIsLeftOut() {
        val claim = "Most founders are using 5% of Claude's potential. The other 95% will separate the winners from everyone else"
        val li = InboxItemEntity(file = "inbox/li.md", position = 0, saved = "2026-10-26", source = "https://www.linkedin.com/posts/gf", text = "$claim 🚀...",
            claim = claim, why = "", project = "all", kind = "learn", next = "", note = "", guessed = true, author = "Guillermo Flor", posted = "2026-04-29", later = null, hasPicture = false)
        show { InboxScreen(ui(listOf(li, meme)), leaving = null, pictures = emptyMap(), contentPadding = a05s) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-linkedin-no-repeat.png")
        assertEquals(1, compose.onAllNodesWithText(claim, substring = true).fetchSemanticsNodes().size)
    }

    /**
     * Boss 2026-10-28 ("when I swipe it is slow to show the next one"): a swipe that starts low on the screen, below the
     * card, moves it; the next card is the top one on the first frame after the choice, with no rise-in.
     */
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    @Test fun aLowSwipeDropsAndTheNextCardIsOnTopTheNextFrame() {
        var u by androidx.compose.runtime.mutableStateOf(ui(listOf(meme, grant, ig)))
        var chosen: Choice? = null
        show {
            InboxScreen(u, leaving = null, pictures = emptyMap(), contentPadding = a05s,
                actions = org.pagel.ai.ui.inbox.InboxActions(choose = { _, c, _ -> chosen = c; u = u.copy(cards = u.cards.drop(1)) }))
        }
        val card = compose.onNodeWithText("A meme about Mondays").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithContentDescription("AI pick: Drop").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            // In the gap between the card's text and the dock, where a thumb rests.
            val start = Offset(centerX, (card.bottom + dock.top) / 2f)
            down(start)
            repeat(10) { moveBy(Offset(0f, 60f), delayMillis = 16) }
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(Choice.DROP, chosen)
        compose.onNodeWithText("The IT Park grant round closes on 15 November").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
    }

    @Test fun inboxZero() {
        show { screen(ui(emptyList(), sorted = 14, kept = 3, left = 0)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-empty.png")
        compose.onNodeWithText("Inbox zero").assertIsDisplayed()
        compose.onNodeWithText("3 learned today.").assertIsDisplayed()
    }

    /** Offline after the saved deck ran out: no "214 left" over "Inbox zero" (checker 2026-10-28 #8). */
    @Test fun emptyHereButMoreOnPagelUz() {
        show { screen(ui(emptyList(), sorted = 14, kept = 3, left = 214)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-more-when-online.png")
        compose.onNodeWithText("214 more when online").assertIsDisplayed()
        compose.onNodeWithText("Inbox zero").assertDoesNotExist()
        compose.onNodeWithText("214 left").assertDoesNotExist()
    }

    /** An unread save while pagel.uz's AI reads it: no big button, the reading line, the six choices in one row. */
    @Test fun unreadSaveWhileTheAiReads() {
        val unread = row("inbox/new.md", "https://x.com/a/status/9", "Unread save from last night", "", "all", "learn", "", pic = false, guessed = false)
        show { screen(ui(listOf(unread, meme)).copy(reading = true)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-ai-reading.png")
        compose.onNodeWithText("AI is reading this one").assertIsDisplayed()
        oneWholeLine("Do now")
    }

    @Test fun ringAtTwentyOfTwenty() {
        show { screen(ui(listOf(grant, meme), sorted = 20, kept = 5)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-ring-20.png")
        compose.onNodeWithContentDescription("20 of 20 sorted today").captureRoboImage("build/outputs/roborazzi/inbox-ring-20-closeup.png")
        // Five kept today: the big button offers a Note in Learn's place, as the web does.
        compose.onNodeWithText("20/20 sorted today · 5 learned ✓").assertIsDisplayed()
    }

    @Test fun keepsFullTurnALearnIntoANote() {
        show { screen(ui(listOf(ig, meme), kept = 5)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-keeps-full.png")
        compose.onNodeWithContentDescription("AI pick: Note").assertIsDisplayed()
    }

    @Test fun actionForm() {
        val u = ui(listOf(grant))
        show { Column(Modifier.padding(top = 16.dp)) { ChoiceFormContent(Form(u.cards[0], Choice.ACT, grant.next, "humopedia", false), u, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-form-action.png")
        compose.onNodeWithContentDescription("Add to plan").assertIsDisplayed()
    }

    @Test fun learnFormCountsTheWords() {
        val u = ui(listOf(ig))
        show(1.5f) { Column(Modifier.padding(top = 16.dp)) { ChoiceFormContent(Form(u.cards[0], Choice.KEEP, "quizzes beat rereading", "humopedia", false), u, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-form-learn-font-1.5.png")
        compose.onNodeWithText("3 more words").assertIsDisplayed()
    }

    @Test fun guide() {
        show { Column(Modifier.padding(top = 16.dp)) { GuideContent() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-guide.png")
        // The five gestures as one picture, said in one line for TalkBack.
        compose.onNodeWithContentDescription("Swipe up to shuffle, down to drop, left to learn, right for an action; hold for Do now").assertIsDisplayed()
    }
}
