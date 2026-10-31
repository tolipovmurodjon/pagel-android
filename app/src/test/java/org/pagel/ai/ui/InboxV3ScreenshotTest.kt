package org.pagel.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.InboxItemEntity
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.inMemoryDb
import org.pagel.ai.data.inbox.InboxRepository
import org.pagel.ai.ui.capture.CaptureContent
import org.pagel.ai.ui.capture.CaptureViewModel
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.glass.ToastCenter
import org.pagel.ai.ui.inbox.InboxActions
import org.pagel.ai.ui.inbox.InboxScreen
import org.pagel.ai.ui.inbox.InboxUi
import org.pagel.ai.ui.inbox.InboxViewModel
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Inbox v3 at Boss's sizes (360 dp, 1x and 1.5x font, and the 320 dp screen): the capture sheet's field with the keyboard
 * up, the current Inbox with its one quiet Old pile row, the Old pile with its own count and the same dock, the calm empty
 * Inbox with its one action, and "Old pile done". Every shot is checked for cut words and for red.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class InboxV3ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private var db: PagelDatabase? = null

    @After fun close() { db?.close() }

    private val projects = listOf(ProjectEntity("humopedia", "Humopedia", null, 0, 41), ProjectEntity("me", "Me", null, 1, 12))

    private fun row(file: String, claim: String) = InboxItemEntity(
        file = file, position = 0, saved = "2026-11-02", source = "https://www.linkedin.com/posts/grant",
        text = "LinkedIn post by IT Park posted 2026-11-02\n\n$claim. Long caption with the details.", claim = claim, why = "Humopedia can apply", project = "humopedia", kind = "action",
        next = "Open the grant form and fill page one", note = "", guessed = true, author = "IT Park", posted = "2026-11-02", later = null, hasPicture = false,
    )

    private val grant = row("inbox/grant.md", "The IT Park grant round closes on 15 November")

    private fun ui(vararg rows: InboxItemEntity, old: Int? = 912, oldMode: Boolean = false, oldDone: Boolean = false, sorted: Int = 7) = InboxUi(
        loading = false, cards = rows.map { InboxViewModel.card(it, false, projects.firstOrNull { p -> p.name == it.project }) },
        sorted = sorted, goal = 20, kept = 2, cap = 5, minWords = 6, left = rows.size, streak = 3, projects = projects, old = old, oldMode = oldMode, oldDone = oldDone,
    )

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    @Composable
    private fun screen(u: InboxUi, onCapture: () -> Unit = {}, onOld: () -> Unit = {}) =
        InboxScreen(u, leaving = null, pictures = emptyMap(), contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp), actions = InboxActions(capture = onCapture, openOld = onOld))

    private fun layoutOf(n: SemanticsNode): TextLayoutResult? {
        val out = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()
    }

    /** None of these words ends in an ellipsis, is wider than its box, or breaks inside a word. */
    private fun assertNoCutText(what: String, words: List<String>) {
        var seen = 0
        for (w in words) for (n in compose.onAllNodesWithText(w, useUnmergedTree = true).fetchSemanticsNodes()) {
            val l = layoutOf(n) ?: continue
            seen++
            val text = l.layoutInput.text.text
            val at = "$what \"${text.take(40)}\""
            assertTrue("$at has an ellipsis", (0 until l.lineCount).none { l.isLineEllipsized(it) })
            assertTrue("$at is cut by its box", !(l.lineCount == 1 && l.multiParagraph.intrinsics.maxIntrinsicWidth > n.size.width + 1f))
            for (i in 0 until l.lineCount - 1) {
                val e = l.getLineEnd(i)
                assertTrue("$at breaks a word at line ${i + 1}", e >= text.length || text[e - 1].isWhitespace() || text[e].isWhitespace() || text[e - 1] == '-')
            }
        }
        assertTrue("$what: some text was checked ($seen)", seen >= words.size)
    }

    /** Boss: never red (hue within 15 degrees of 0, saturation at least 0.5, value at least 0.35). */
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

    private val headerWords = listOf("Inbox", "Today's 20", "Old pile · 912", "2/5 learned")

    @Test fun currentInboxWithOneQuietOldPileRow() {
        show { screen(ui(grant)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-current.png")
        compose.onNodeWithText("Old pile · 912", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Add to Inbox").assertIsDisplayed()
        // The day's 20 is said once: by the ring, not again as words ("one count only").
        compose.onNodeWithContentDescription("7 of 20 sorted today").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("sorted today", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Sorted today (", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals("red pixels", 0, redPixels())
    }

    @Test fun currentInboxAt15xFontCutsNothing() {
        show(1.5f) { screen(ui(grant)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-current-font-1.5.png")
        assertNoCutText("v3 header 1.5x", headerWords)
        assertEquals("red pixels", 0, redPixels())
    }

    @Test fun currentInboxAt320dpAnd15xFontCutsNothing() {
        show(1.5f) { Box(Modifier.width(320.dp).height(640.dp)) { screen(ui(grant)) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-current-320-font-1.5.png")
        assertNoCutText("v3 header 320 dp 1.5x", headerWords)
    }

    /** The checker's leftover: at 320 dp and 1.5x the card's claim was cut ("The IT Park grant round" and no more). It fits whole now. */
    @Test fun theClaimFitsWholeAt320dpAnd15xFont() {
        show(1.5f) { Box(Modifier.width(320.dp).height(640.dp)) { screen(ui(grant)) } }
        val claim = compose.onNodeWithText("The IT Park grant round closes on 15 November", useUnmergedTree = true).fetchSemanticsNode()
        val l = layoutOf(claim)!!
        assertTrue("the claim overflows its box (${l.lineCount} lines, ${l.size.height} px)", !l.hasVisualOverflow)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-claim-320-font-1.5.png")
    }

    @Test fun tappingTheOldPileRowOpensIt() {
        var opened = 0
        show { screen(ui(grant), onOld = { opened++ }) }
        compose.onNodeWithContentDescription("Old pile").performClick()
        assertEquals(1, opened)
    }

    @Test fun theOldPileHasItsOwnCountAndTheSameDock() {
        show { screen(ui(grant, old = 910, oldMode = true)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-old-pile.png")
        compose.onNodeWithText("Old pile", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("910 left", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Tidy old saves").assertIsDisplayed()
        for (w in listOf("Drop", "Someday", "Note", "Learn", "Do now", "Action", "Project", "Waiting", "Calendar", "Later")) compose.onAllNodesWithText(w, useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue("$w in the old pile's dock", it.isNotEmpty()) }
        assertEquals("red pixels", 0, redPixels())
    }

    @Test fun theOldPileAt15xFontCutsNothing() {
        show(1.5f) { screen(ui(grant, old = 910, oldMode = true)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-old-pile-font-1.5.png")
        assertNoCutText("v3 old pile 1.5x", listOf("Old pile", "910 left"))
    }

    @Test fun theEmptyInboxIsOneSentenceAndOneAction() {
        var captured = 0
        show { screen(ui(old = 912, sorted = 14), onCapture = { captured++ }) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-empty.png")
        compose.onNodeWithText("Nothing waiting").assertIsDisplayed()
        compose.onNodeWithText("Capture something").assertIsDisplayed().performClick()
        assertEquals(1, captured)
        compose.onNodeWithText("Old pile · 912", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("red pixels", 0, redPixels())
    }

    @Test fun oldPileDoneIsSaidOnce() {
        show { screen(ui(old = 0, oldDone = true, sorted = 14)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-old-done.png")
        compose.onNodeWithText("Old pile done", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun anEmptyOldPileOffersTheWayBack() {
        show { screen(ui(old = 0, oldMode = true)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-old-pile-empty.png")
        compose.onNodeWithText("Old pile is clear").assertIsDisplayed()
        compose.onNodeWithText("Back to Inbox").assertIsDisplayed()
    }

    private fun captureVm(): CaptureViewModel {
        val d = inMemoryDb().also { db = it }
        return CaptureViewModel(InboxRepository(PagelApi(OkHttpClient(), Dispatchers.IO), d.inbox(), { }), ToastCenter())
    }

    @Test fun theCaptureFieldSavesOnSendAndClearsForTheNextOne() {
        val vm = captureVm()
        show { CaptureContent(vm) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-capture-empty.png")
        compose.onNodeWithContentDescription("New capture").assertIsDisplayed()
        compose.onNodeWithContentDescription("Speak").assertIsDisplayed()
        compose.onNodeWithContentDescription("New capture").performTextInput("Call the bank about the report")
        compose.onNodeWithContentDescription("Add to Inbox").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-capture-typed.png")
        compose.onNodeWithContentDescription("Add to Inbox").performClick()
        compose.waitForIdle()
        // Cleared at once, and in the deck on top.
        compose.onNodeWithContentDescription("Speak").assertIsDisplayed()
        var deck = runBlocking { db!!.inbox().deck().first() }
        // Room answers on its own thread: give the main looper a moment to see the row.
        for (i in 0 until 50) { if (deck.isNotEmpty()) break; compose.waitForIdle(); Thread.sleep(100); deck = runBlocking { db!!.inbox().deck().first() } }
        assertEquals(listOf("Call the bank about the report"), deck.map { it.text })
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-capture-saved.png")
        assertEquals(1, vm.count.value)
    }

    @Test fun theCaptureFieldAt15xFontCutsNothing() {
        val vm = captureVm()
        show(1.5f) { Box(Modifier.width(320.dp)) { CaptureContent(vm) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-capture-320-font-1.5.png")
        assertNoCutText("capture 320 dp 1.5x", listOf("Add to Inbox", "A thought, a link or a list"))
        assertEquals("red pixels", 0, redPixels())
    }
}
