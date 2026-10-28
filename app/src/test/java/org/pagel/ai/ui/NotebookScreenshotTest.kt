package org.pagel.ai.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.compass.VaultState
import org.pagel.ai.data.journal.EntryBody
import org.pagel.ai.data.journal.JournalEntry
import org.pagel.ai.data.journal.OpenJournal
import org.pagel.ai.data.notebook.BacklinkDto
import org.pagel.ai.data.notebook.Grade
import org.pagel.ai.data.notebook.Hit
import org.pagel.ai.data.notebook.HitKind
import org.pagel.ai.data.notebook.RecallCard
import org.pagel.ai.data.notebook.RecallSession
import org.pagel.ai.data.notebook.SearchLogic
import org.pagel.ai.data.notebook.VaultFolderEntity
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.journal.JournalScreen
import org.pagel.ai.ui.journal.JournalUi
import org.pagel.ai.ui.notebook.FoldersScreen
import org.pagel.ai.ui.notebook.NoteReader
import org.pagel.ai.ui.notebook.NoteRow
import org.pagel.ai.ui.notebook.NoteWriter
import org.pagel.ai.ui.notebook.NotebookScreen
import org.pagel.ai.ui.notebook.NotebookUi
import org.pagel.ai.ui.notebook.RecallScreen
import org.pagel.ai.ui.notebook.sampleNote
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The second brain at Boss's sizes (360 × 800 and 1.5× font): the Notebook with Review 5, search results grouped with
 * their marked words, the folders with Kundalik locked, the reader, the writer, a recall card face down and face up
 * (the card never moves), the journal locked and open. Every note, card and entry here is sample text made in this test.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class NotebookScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val pad = PaddingValues(top = 16.dp, bottom = 96.dp)

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    private val cards = listOf(
        RecallCard("c_1", "What makes recall stick?", "Spacing: answer it again just before you would forget.", "2026-10-28T00:00:00.000Z"),
        RecallCard("c_2", "Sample question two", "Sample answer two", "2026-10-28T01:00:00.000Z"),
    )
    private val recent = listOf(
        NoteRow("Notes/Spaced recall.md", "Spaced recall", "Notes", false),
        NoteRow("Sources/Books/Sample Habits.md", "Sample Habits", "Sources/Books", true),
        NoteRow("Notes/Ideas.md", "Ideas", "Notes", false),
    )
    private val home = NotebookUi(due = cards, recent = recent, openFolders = 2)

    @Test fun theNotebookWithReviewFive() {
        show { NotebookScreen(home, pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/notebook-home-360x800.png")
        compose.onNodeWithContentDescription("Search everything").assertIsDisplayed()
        compose.onNodeWithContentDescription("Journal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start the review").assertIsDisplayed()
    }

    @Test fun theNotebookAtOneAndAHalfFont() {
        show(1.5f) { NotebookScreen(home, pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/notebook-home-360x800-font-1.5.png")
    }

    @Test fun searchResultsGroupedByKindWithTheWordsMarked() {
        val w = SearchLogic.words("sample")
        val hits = listOf(
            Hit(HitKind.TASK, "t_1", "Read the sample book", "Read the **sample** book", "me"),
            Hit(HitKind.STEP, "t_2#0", "Find the sample chapter", "Call the library", "me"),
            Hit(HitKind.NOTE, "Sources/Books/Sample Habits.md", "Sample Habits", SearchLogic.snippet("Tiny changes compound: a sample habit a day.", w), "Sources/Books"),
            Hit(HitKind.QUICK_NOTE, "humopedia#3", "A sample lesson idea", "A **sample** lesson idea", "humopedia"),
            Hit(HitKind.RESOURCE, "r_1", "A sample tool", "a **sample** tool for spaced recall"),
        )
        show { NotebookScreen(NotebookUi(query = "sample", groups = SearchLogic.group(hits)), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/notebook-search-360x800.png")
        compose.onNodeWithText("TASKS · 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Note: Sample Habits").assertIsDisplayed()
    }

    private val folders = listOf(
        VaultFolderEntity("Home", allowed = false, switched = false, locked = false, notes = 0),
        VaultFolderEntity("Kundalik", allowed = false, switched = false, locked = true, notes = 0),
        VaultFolderEntity("Notes", allowed = true, switched = true, locked = false, notes = 42),
        VaultFolderEntity("Sources", allowed = true, switched = true, locked = false, notes = 3),
        VaultFolderEntity("Sources/Books", allowed = true, switched = false, locked = false, notes = 3),
        VaultFolderEntity("Sources/Life", allowed = false, switched = true, locked = false, notes = 0),
    )

    @Test fun theFoldersWithKundalikLocked() {
        var switched: Pair<String, Boolean>? = null
        show { FoldersScreen(folders, pad, onSwitch = { p, on -> switched = p to on }) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/notebook-folders-360x800.png")
        compose.onNodeWithContentDescription("Kundalik is locked").assertIsDisplayed()
        compose.onNodeWithContentDescription("Kundalik is locked").performClick()
        assertEquals("Kundalik has no switch to flip", null, switched)
        compose.onNodeWithContentDescription("Allow Home").performClick()
        assertEquals("Home" to true, switched)
    }

    @Test fun theFoldersAtOneAndAHalfFont() {
        show(1.5f) { FoldersScreen(folders, pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/notebook-folders-360x800-font-1.5.png")
    }

    private val md = """
        # Spaced recall

        Answer a question **just before** you would forget it; see [[Ideas]] and [[Not here]].

        ## How
        - Review 5 cards a day
          - grade *honestly*
        - [x] Set up the cards
        - [ ] Write my own questions

        > [!quote] Sample quote
        > Memory is a muscle.

        ```
        again → 1 day
        ```
    """.trimIndent()

    @Test fun theReaderWithWikilinksAndBacklinks() {
        val note = sampleNote("Notes/Spaced recall.md", md, listOf(BacklinkDto("Notes/Ideas.md", "Ideas"), BacklinkDto("Sources/Books/Sample Habits.md", "Sample Habits")))
        show { NoteReader("Notes/Spaced recall.md", note, pad, known = { it == "Ideas" }) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/notebook-reader-360x800.png")
        compose.onNodeWithText("LINKED FROM · 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit note").assertIsDisplayed()
    }

    @Test fun theReaderAtOneAndAHalfFont() {
        show(1.5f) { NoteReader("Notes/Spaced recall.md", sampleNote("Notes/Spaced recall.md", md), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/notebook-reader-360x800-font-1.5.png")
    }

    @Test fun theWriterMakesABookNote() {
        var saved: Pair<String, String>? = null
        show { NoteWriter(null, listOf("Notes", "Sources/Books"), pad, startKind = 2, today = "2026-10-29", onSave = { p, b -> saved = p to b }) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/notebook-writer-book-360x800.png")
        compose.onNodeWithContentDescription("Folder Sources/Books").assertIsDisplayed()
        compose.onNodeWithContentDescription("Book title").performClick()
        compose.onNodeWithContentDescription("Book title").performTextInput("Sample Book")
        compose.onNodeWithContentDescription("Save the new note").performClick()
        val (path, body) = saved!!
        assertEquals("Sources/Books/Sample Book.md", path)
        assertTrue(body.startsWith("# Sample Book\n") && body.contains("## Highlights"))
    }

    @Test fun aRecallCardDoesNotMoveWhenItsAnswerShows() {
        var s by mutableStateOf(RecallSession.start(cards, "2026-10-29T10:00:00.000Z"))
        val graded = mutableListOf<Grade>()
        show { RecallScreen(s, pad, onReveal = { s = s.reveal() }, onGrade = { graded += it; s = s.grade(it) }) }
        val down = compose.onNodeWithTag("recall-card").fetchSemanticsNode().boundsInRoot
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/notebook-recall-down-360x800.png")
        compose.onNodeWithContentDescription("Show answer").performClick()
        compose.waitForIdle()
        val up = compose.onNodeWithTag("recall-card").fetchSemanticsNode().boundsInRoot
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/notebook-recall-up-360x800.png")
        assertEquals("the card's top never moves (Layout 3)", down.top, up.top)
        assertEquals("nor its height", down.height, up.height)
        compose.onNodeWithContentDescription("Good").performClick()
        assertEquals(listOf(Grade.GOOD), graded)
    }

    @Test fun aRecallCardAtOneAndAHalfFont() {
        show(1.5f) { RecallScreen(RecallSession.start(cards, "2026-10-29T10:00:00.000Z").reveal(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/notebook-recall-up-360x800-font-1.5.png")
    }

    @Test fun theJournalLocked() {
        var asked = 0
        show { JournalScreen(JournalUi(state = VaultState.LOCKED, today = "2026-10-29"), pad, org.pagel.ai.ui.journal.JournalActions(unlock = { asked++ })) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/journal-locked-360x800.png")
        compose.onNodeWithContentDescription("Unlock").performClick()
        assertEquals(1, asked)
    }

    @Test fun theNewJournalInvitesWriting() {
        show { JournalScreen(JournalUi(state = VaultState.NEW, today = "2026-10-29"), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/journal-new-360x800.png")
        compose.onNodeWithContentDescription("Start my journal").assertIsDisplayed()
    }

    private val entries = OpenJournal(
        listOf(
            JournalEntry("j_a", EntryBody("2026-10-29", "Sample entry: a calm morning, a long walk, then work on the app.", "🙂")),
            JournalEntry("j_b", EntryBody("2026-10-29", "Sample entry two, written in the evening.", null)),
            JournalEntry("j_c", EntryBody("2026-10-28", "Sample entry from yesterday.", "😔")),
        ),
    )

    @Test fun theJournalOpen() {
        show { JournalScreen(JournalUi(state = VaultState.OPEN, opened = entries, today = "2026-10-29"), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/journal-open-360x800.png")
        compose.onNodeWithText("TODAY").assertIsDisplayed()
        compose.onNodeWithText("YESTERDAY").assertIsDisplayed()
        compose.onNodeWithContentDescription("Write today").assertIsDisplayed()
    }

    @Test fun theJournalOpenAtOneAndAHalfFont() {
        show(1.5f) { JournalScreen(JournalUi(state = VaultState.OPEN, opened = entries, today = "2026-10-29"), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/journal-open-360x800-font-1.5.png")
    }
}

