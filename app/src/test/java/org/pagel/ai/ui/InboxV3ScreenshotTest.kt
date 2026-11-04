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
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import org.pagel.ai.data.inbox.PileCounts
import org.pagel.ai.ui.inbox.ArchiveView
import org.pagel.ai.ui.inbox.InboxActions
import org.pagel.ai.ui.inbox.InboxOrder
import org.pagel.ai.ui.inbox.InboxScreen
import org.pagel.ai.ui.inbox.InboxUi
import org.pagel.ai.ui.inbox.InboxViewModel
import org.pagel.ai.ui.inbox.SourceCount
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Inbox v3 at Boss's sizes (360 dp, 1x and 1.5x font, and the 320 dp screen): the capture sheet's field with the keyboard
 * up, the Inbox as a list with the Archive at its foot, the deck behind Clear, the Archive with its own count and the
 * same dock, the calm empty Inbox with its one action, and "Archive done". Every shot is checked for cut words and for red.
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

    private fun ui(vararg rows: InboxItemEntity, old: Int? = 912, oldMode: Boolean = false, oldDone: Boolean = false, sorted: Int = 3) = InboxUi(
        loading = false, cards = rows.map { InboxViewModel.card(it, false, projects.firstOrNull { p -> p.name == it.project }) },
        items = if (oldMode) emptyList() else rows.map { InboxViewModel.card(it, false, projects.firstOrNull { p -> p.name == it.project }) },
        sorted = sorted, goal = 5, kept = 2, cap = 5, minWords = 6, left = rows.size, streak = 3, projects = projects, old = old, oldMode = oldMode, oldDone = oldDone,
    )

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    @Composable
    private fun screen(u: InboxUi, onCapture: () -> Unit = {}, onOld: () -> Unit = {}, clearing: Boolean = true) =
        InboxScreen(u, leaving = null, pictures = emptyMap(), contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp), actions = InboxActions(capture = onCapture, openOld = onOld), clearing = clearing)

    /** The Inbox's own face (Boss 2026-11-03): the list, not the deck. */
    @Composable
    private fun list(u: InboxUi, onCapture: () -> Unit = {}, onOld: () -> Unit = {}) = screen(u, onCapture, onOld, clearing = false)

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

    private val listWords = listOf("Inbox", "Archive · 912", "Clear · 1")

    /** The list: + and ? in the header, the save as a row, the Archive at the foot, one Clear button; no ring, no grid. */
    @Test fun theInboxIsAListWithTheArchiveAtItsFoot() {
        show { list(ui(grant)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-list.png")
        compose.onNodeWithText("The IT Park grant round closes on 15 November", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Archive · 912", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Add to Inbox").assertIsDisplayed()
        compose.onNodeWithContentDescription("How sorting works").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear the Inbox").assertIsDisplayed()
        assertTrue("no ring on the list", compose.onAllNodesWithContentDescription("3 of 5 sorted today").fetchSemanticsNodes().isEmpty())
        assertTrue("no dock on the list", compose.onAllNodesWithText("Someday", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Sorted today (", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals("red pixels", 0, redPixels())
    }

    @Test fun tappingARowOpensTheDeckAtThatSave() {
        var opened: String? = "none"
        show { InboxScreen(ui(grant), leaving = null, pictures = emptyMap(), contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp), actions = InboxActions(clear = { opened = it }), clearing = false) }
        compose.onNodeWithText("The IT Park grant round closes on 15 November", useUnmergedTree = true).performClick()
        assertEquals("inbox/grant.md", opened)
        compose.onNodeWithContentDescription("Clear the Inbox").performClick()
        assertEquals(null, opened)
    }

    @Test fun theListAt15xFontCutsNothing() {
        show(1.5f) { list(ui(grant)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-list-font-1.5.png")
        assertNoCutText("v3 list 1.5x", listWords)
        assertEquals("red pixels", 0, redPixels())
    }

    @Test fun theListAt320dpAnd15xFontCutsNothing() {
        show(1.5f) { Box(Modifier.width(320.dp).height(640.dp)) { list(ui(grant)) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-list-320-font-1.5.png")
        assertNoCutText("v3 list 320 dp 1.5x", listWords)
    }

    /** The deck's header is 0.3.41's: back, "Clear", how many are left, the ring. */
    @Test fun theDeckHeaderIsBackClearAndTheRing() {
        show { screen(ui(grant)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-clear-deck.png")
        compose.onNodeWithContentDescription("Back to the list").assertIsDisplayed()
        compose.onNodeWithText("Clear", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("3 of 5 sorted today").assertIsDisplayed()
        assertTrue("no + on the deck", compose.onAllNodesWithContentDescription("Add to Inbox").fetchSemanticsNodes().isEmpty())
        assertEquals("red pixels", 0, redPixels())
    }

    /**
     * The checker's leftover (claim cut at 320 dp / 1.5x): the claim is on the card with the date, project and reason gone
     * ahead of it. The picture is for the eye (inbox-v3-claim-320-font-1.5.png); the full line count depends on the room.
     */
    @Test fun theClaimIsOnTheCardAt320dpAnd15xFont() {
        show(1.5f) { Box(Modifier.width(320.dp).height(640.dp)) { screen(ui(grant)) } }
        compose.onNodeWithText("The IT Park grant round closes on 15 November", useUnmergedTree = true).assertIsDisplayed()
        val claim = layoutOf(compose.onNodeWithText("The IT Park grant round closes on 15 November", useUnmergedTree = true).fetchSemanticsNode())!!
        assertTrue("the claim is whole, with no clipped second line (${claim.lineCount} lines)", !claim.hasVisualOverflow)
        assertTrue("the reason is not on a crowded card", compose.onAllNodesWithText("Humopedia can apply", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-claim-320-font-1.5.png")
    }

    /** Under 360 dp the big button shows its short hint, whole on one line (checker 5.5/10: the long example was cut). */
    @Test fun theBigButtonHintIsWholeAt320dp() {
        show(1f) { Box(Modifier.width(320.dp).height(640.dp)) { screen(ui(grant)) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-hint-320.png")
        assertNoCutText("hint at 320 dp", listOf("a next step in a plan"))
    }

    /** Two source chips are in sight at 320 dp with no sideways scroll: both end inside the screen. */
    @Test fun twoSourceChipsShowAt320dpWithoutAScroll() {
        val chips = listOf(SourceCount("instagram", "Instagram", 4), SourceCount("x", "X", 3))
        show(1f) { Box(Modifier.width(320.dp).height(640.dp)) { list(ui(grant).copy(sources = chips)) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-chips-320.png")
        for (d in listOf("All \u00B7 7", "Instagram \u00B7 4")) {
            val b = compose.onNodeWithContentDescription(d).fetchSemanticsNode().boundsInRoot
            assertTrue("$d ends inside 320 dp (right edge ${b.right})", b.right <= compose.onRoot().fetchSemanticsNode().boundsInRoot.right)
        }
    }

    @Test fun tappingTheArchiveRowOpensIt() {
        var opened = 0
        show { list(ui(grant), onOld = { opened++ }) }
        compose.onNodeWithContentDescription("Archive").performClick()
        assertEquals(1, opened)
    }

    @Test fun theOldPileHasItsOwnCountAndTheSameDock() {
        show { screen(ui(grant, old = 910, oldMode = true)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-old-pile.png")
        compose.onNodeWithText("Archive", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("saved before 1", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Focus").assertIsDisplayed()
        // Boss 2026-11-04: "drop group nope": one icon in the header, no group rows under it.
        assertTrue("no Drop group on the Archive", compose.onAllNodesWithText("Drop group", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        for (w in listOf("Drop", "Someday", "Note", "Do now", "Action", "More")) compose.onAllNodesWithText(w, useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue("$w in the old pile's dock", it.isNotEmpty()) }
        assertEquals("red pixels", 0, redPixels())
    }

    private val pile = PileCounts(mapOf("instagram" to 600, "x" to 95, "linkedin" to 50, "telegram" to 12), mapOf("ababil" to 300, "humopedia" to 200, "all" to 100), 941)

    /** Focus (Boss 2026-11-04): From, About and Order with real counts, Show N, the tidy-up one level down; a tap sets it. */
    @Test fun focusFiltersTheArchiveBySourceTopicAndOrder() {
        var set: ArchiveView? = null
        var tidied = 0
        show { InboxScreen(ui(grant, old = 941, oldMode = true).copy(pile = pile), leaving = null, pictures = emptyMap(), contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp), actions = InboxActions(setArchive = { set = it }, tidy = { tidied++ })) }
        compose.onNodeWithContentDescription("Focus").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-archive-focus.png")
        for (d in listOf("From: Instagram · 600", "From: Telegram · 12", "About: Humopedia · 200", "About: Not read yet · 100", "Order: Shuffle", "Show 941 saves", "Tidy up all old saves"))
            compose.onNodeWithContentDescription(d).assertIsDisplayed()
        compose.onNodeWithContentDescription("From: Instagram · 600").performClick()
        assertEquals(ArchiveView(source = "instagram"), set)
        compose.onNodeWithContentDescription("Order: Shuffle").performClick()
        assertEquals(ArchiveView(order = InboxOrder.SHUFFLE), set)
        compose.onNodeWithContentDescription("Tidy up all old saves").performClick()
        assertEquals(1, tidied)
    }

    /** A set focus is never hidden: the line says it in words and its count. */
    @Test fun aFocusedArchiveSaysItsFocus() {
        val u = ui(grant, old = 941, oldMode = true).copy(pile = pile.copy(left = 40), archive = ArchiveView("instagram", "humopedia", InboxOrder.SHUFFLE))
        show { screen(u) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-archive-focused.png")
        compose.onNodeWithText("Instagram · Humopedia · Shuffle · 40 left", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun theFocusSheetAt320dpAnd15xFontCutsNothing() {
        show(1.5f) { Box(Modifier.width(320.dp).height(640.dp)) { screen(ui(grant, old = 941, oldMode = true).copy(pile = pile)) } }
        compose.onNodeWithContentDescription("Focus").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-archive-focus-320-font-1.5.png")
        assertNoCutText("focus 320 dp 1.5x", listOf("Instagram · 600", "Humopedia · 200", "Shuffle", "Show 941 saves"))
    }

    /** The phone deals the same focus as pagel.uz: one source, one project ("all" = not read), oldest or newest first. */
    @Test fun theArchiveDealsItsFocusInOrder() {
        fun save(file: String, source: String, project: String, day: String) = row(file, file).copy(source = source, project = project, saved = day, posted = day)
        val items = listOf(
            save("a", "https://www.instagram.com/p/a/", "ababil", "2025-03-01"), save("b", "https://x.com/u/status/1", "ababil", "2025-01-01"),
            save("c", "https://instagram.com/p/c/", "ababil", "2025-01-15"), save("d", "https://t.me/chan/4", "humopedia", "2025-02-01"),
            save("e", "Boss said (phone capture)", "all", "2025-02-10"),
        )
        assertEquals(listOf("c", "a"), ArchiveView("instagram", "ababil").deal(items, 0).map { it.file })
        assertEquals(listOf("a", "c", "b"), ArchiveView(project = "ababil", order = InboxOrder.NEWEST).deal(items, 0).map { it.file })
        assertEquals(listOf("d"), ArchiveView("telegram").deal(items, 0).map { it.file })
        assertEquals(listOf("e"), ArchiveView(project = "all").deal(items, 0).map { it.file })
        assertEquals(ArchiveView().deal(items, 7).map { it.file }.toSet(), ArchiveView(order = InboxOrder.SHUFFLE).deal(items, 7).map { it.file }.toSet())
        assertEquals(mapOf("instagram" to 2, "x" to 1), ArchiveView(project = "ababil").counts(items, 3).bySource)
    }

    @Test fun theOldPileAt15xFontCutsNothing() {
        show(1.5f) { screen(ui(grant, old = 910, oldMode = true)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-old-pile-font-1.5.png")
        assertNoCutText("v3 old pile 1.5x", listOf("Archive", "910 left · saved before 1 November"))
    }

    @Test fun theEmptyInboxIsOneSentenceAndOneAction() {
        var captured = 0
        show { list(ui(old = 912, sorted = 4), onCapture = { captured++ }) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-empty.png")
        compose.onNodeWithText("Nothing waiting").assertIsDisplayed()
        compose.onNodeWithText("Capture something").assertIsDisplayed().performClick()
        assertEquals(1, captured)
        compose.onNodeWithText("Archive · 912", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("red pixels", 0, redPixels())
    }

    @Test fun oldPileDoneIsSaidOnce() {
        show { list(ui(old = 0, oldDone = true, sorted = 4)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-old-done.png")
        compose.onNodeWithText("Archive done", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun anEmptyOldPileOffersTheWayBack() {
        show { screen(ui(old = 0, oldMode = true)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/inbox-v3-old-pile-empty.png")
        compose.onNodeWithText("Archive is clear").assertIsDisplayed()
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
