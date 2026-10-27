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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
 * Phase 2 (the Inbox) at Boss's sizes: a card with its picture at 360 dp and at 1.5× font, a card mid-swipe with its
 * stamp, Inbox zero, the day's 20/20 ring, and the Action and Learn forms.
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

    private fun ui(rows: List<InboxItemEntity>, sorted: Int = 7, kept: Int = 2) = InboxUi(
        loading = false,
        cards = rows.map { InboxViewModel.card(it, kept >= 5, projects.firstOrNull { p -> p.name == it.project }) },
        sorted = sorted, goal = 20, kept = kept, cap = 5, minWords = 6, left = 214, streak = 3, projects = projects,
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
    }

    @Test fun cardWithPictureAt360LargeFont() {
        show(1.5f) { screen(ui(listOf(ig, meme))) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-card-360-font-1.5.png")
        oneWholeLine("Inbox")
        oneWholeLine("Drop")
        oneWholeLine("Later")
    }

    @Test fun cardMidSwipeShowsItsStamp() {
        // Dragged right past the line: the AI's pick (Learn) is stamped and the card behind grows into place.
        show { screen(ui(listOf(ig, meme)), peek = Offset(420f, 40f)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-mid-swipe.png")
        compose.onNodeWithText("LEARN").assertIsDisplayed()
    }

    @Test fun cardMidSwipeLeftIsADrop() {
        show { screen(ui(listOf(grant, meme)), peek = Offset(-420f, 10f)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-mid-swipe-drop.png")
        compose.onNodeWithText("DROP").assertIsDisplayed()
    }

    @Test fun inboxZero() {
        show { screen(ui(emptyList(), sorted = 14, kept = 3)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-empty.png")
        compose.onNodeWithText("Inbox zero").assertIsDisplayed()
        compose.onNodeWithText("3 learned today.").assertIsDisplayed()
    }

    @Test fun ringAtTwentyOfTwenty() {
        show { screen(ui(listOf(grant, meme), sorted = 20, kept = 5)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-ring-20.png")
        compose.onNodeWithContentDescription("20 of 20 sorted today").captureRoboImage("build/outputs/roborazzi/inbox-ring-20-closeup.png")
        // Five kept today: the big button offers a Note in Learn's place, as the web does.
        compose.onNodeWithText("20/20 sorted today · 5 learned ✓").assertIsDisplayed()
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
    }
}
