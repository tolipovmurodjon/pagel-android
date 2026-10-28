package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.data.notebook.Block
import org.pagel.ai.data.notebook.Grade
import org.pagel.ai.data.notebook.Hit
import org.pagel.ai.data.notebook.HitKind
import org.pagel.ai.data.notebook.Inline
import org.pagel.ai.data.notebook.LocalNote
import org.pagel.ai.data.notebook.LocalQuickNote
import org.pagel.ai.data.notebook.LocalStep
import org.pagel.ai.data.notebook.LocalTask
import org.pagel.ai.data.notebook.Markdown
import org.pagel.ai.data.notebook.NoteTemplates
import org.pagel.ai.data.notebook.RecallCard
import org.pagel.ai.data.notebook.RecallSession
import org.pagel.ai.data.notebook.SearchLogic
import java.time.LocalDate

/**
 * The second brain's pure rules (Phase 6b): the markdown reader's blocks and wikilinks (pagel.uz vault-store.ts's
 * grammar), the search box's words, snippets and groups, the Review 5 session, the new-note paths and the Kundalik wall.
 * Every note here is sample text made in this test.
 */
class NotebookLogicTest {
    private val note = """
        ---
        title: Sample book
        tags: [books]
        ---
        # Sample Habits

        Some **bold**, *soft* and `code` with [[Folder/Note#Part|shown]], [[Other]] and [web](https://example.com).
        a second line

        - one
          - two [[Deep]]
        - [ ] to do
        - [x] done
        1. first

        > [!quote] Big idea
        > a quoted line

        ```kotlin
        val x = 1
        ```

        ![[cover.png]]
        ![alt](https://example.com/b.jpg)
        ---
        end snake_case_word
    """.trimIndent()

    @Test fun theReaderSeesEveryBlockObsidianWrites() {
        val b = Markdown.parse(note)
        assertEquals(Block.Heading(1, listOf(Inline.Text("Sample Habits"))), b[0])
        val p = (b[1] as Block.Paragraph).inlines
        assertTrue(Inline.Bold("bold") in p)
        assertTrue(Inline.Italic("soft") in p)
        assertTrue(Inline.Code("code") in p)
        assertTrue(Inline.WikiLink("Folder/Note", "shown", "Part") in p)
        assertTrue(Inline.WikiLink("Other", "Other", null) in p)
        assertTrue(Inline.Link("web", "https://example.com") in p)
        assertEquals(Block.Item(0, "•", null, listOf(Inline.Text("one"))), b[2])
        assertEquals(1, (b[3] as Block.Item).depth)
        assertEquals(false, (b[4] as Block.Item).checked)
        assertEquals(true, (b[5] as Block.Item).checked)
        assertEquals("1.", (b[6] as Block.Item).marker)
        assertEquals(Block.Quote(listOf(Inline.Text("Big idea\na quoted line"))), b[7])
        assertEquals(Block.Code("val x = 1", "kotlin"), b[8])
        assertEquals(Block.Image("cover.png", "cover.png", wiki = true), b[9])
        assertEquals(Block.Image("https://example.com/b.jpg", "alt", wiki = false), b[10])
        assertEquals(Block.Rule, b[11])
        // snake_case stays one word, not italics.
        assertEquals(Block.Paragraph(listOf(Inline.Text("end snake_case_word"))), b[12])
    }

    @Test fun wikilinksAreKeyedAndResolvedAsPagelUzDoes() {
        assertEquals(listOf("folder/note", "other", "deep"), Markdown.wikilinks(note))
        val paths = listOf("A/Note.md", "Folder/Note.md", "Note x.md")
        assertEquals("Folder/Note.md", Markdown.resolve("Folder/Note", paths))
        // The short link: the note's own name, the shortest path wins.
        assertEquals("A/Note.md", Markdown.resolve("note", paths))
        assertNull(Markdown.resolve("Missing", paths))
        assertEquals("Attachments/cover.png", Markdown.resolveFile("cover.png", listOf("x/y/cover.png", "Attachments/cover.png")))
        // A link with a heading or an alias keeps its target.
        assertEquals(Inline.WikiLink("Deep", "Deep › Why", "Why"), Markdown.inlines("[[Deep#Why]]").single())
    }

    @Test fun theTitleComesFromFrontMatterThenTheHeadingThenTheName() {
        assertEquals("Sample book", Markdown.titleOf("Sources/Books/x.md", note))
        assertEquals("Heading", Markdown.titleOf("a/b.md", "# Heading\ntext"))
        assertEquals("b", Markdown.titleOf("a/b.md", "no heading"))
    }

    @Test fun searchWordsSplitAsTheServerDoes() {
        assertEquals(listOf("o", "zbek", "tili"), SearchLogic.words("O'zbek  tili!"))
        assertEquals(listOf("o", "zbek"), SearchLogic.words("oʻzbek"))
        assertTrue(SearchLogic.matches("Spaced repetition beats rereading", SearchLogic.words("spac rer")))
        assertFalse("a word is matched from its start", SearchLogic.matches("Spaced repetition", SearchLogic.words("pace")))
    }

    @Test fun aSnippetMarksTheMatchedWords() {
        val long = "This is a long note about many things. Sample habits are tiny changes that compound over years, said a sample author in a sample book."
        val s = SearchLogic.snippet(long, SearchLogic.words("compound"))
        assertTrue(s, s.contains("**compound**"))
        assertTrue(s.startsWith("…"))
        assertEquals(listOf("a " to false, "b" to true, " c" to false), SearchLogic.parts("a **b** c"))
    }

    @Test fun resultsGroupByKindTitleMatchesFirst() {
        val hits = SearchLogic.local(
            "sample",
            listOf(LocalTask("t1", "Read the sample book", null, "me", "2026-10-01"), LocalTask("t2", "Call the bank", null, "me", "2026-10-02")),
            listOf(LocalStep("t2", 0, "Find the sample number")),
            listOf(LocalNote("Notes/x.md", "X", "about a sample idea", "2026-10-09"), LocalNote("Sources/Books/Sample.md", "Sample", "text", "2026-09-01")),
            listOf(LocalQuickNote("humopedia", 3, "a sample lesson idea", "2026-10-20")),
        )
        val groups = SearchLogic.group(hits)
        assertEquals(listOf(HitKind.TASK, HitKind.STEP, HitKind.NOTE, HitKind.QUICK_NOTE), groups.map { it.kind })
        assertEquals(listOf("Sources/Books/Sample.md", "Notes/x.md"), groups.first { it.kind == HitKind.NOTE }.hits.map { it.ref })
        assertEquals("t2#0", groups.first { it.kind == HitKind.STEP }.hits.single().ref)
        assertEquals("humopedia#3", groups.first { it.kind == HitKind.QUICK_NOTE }.hits.single().ref)
        // pagel.uz's answer first, the phone's own extras after, one row per thing.
        val merged = SearchLogic.merge(listOf(Hit(HitKind.TASK, "t1", "Read the sample book", "")), hits)
        assertEquals(1, merged.count { it.ref == "t1" })
        assertEquals(hits.size, merged.size)
        assertTrue(SearchLogic.local("   ", emptyList(), emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test fun reviewFiveTakesTheOldestDueCardsAndGradesOnlyAfterReveal() {
        val cards = listOf(
            RecallCard("c2", "q2", "a2", "2026-10-29T01:00:00.000Z"), RecallCard("c1", "q1", "a1", "2026-10-28T01:00:00.000Z"),
            RecallCard("c3", "q3", "a3", "2026-11-28T01:00:00.000Z"),
        ) + (4..9).map { RecallCard("c$it", "q", "a", "2026-10-20T0$it:00:00.000Z") }
        val s = RecallSession.start(cards, "2026-10-29T10:00:00.000Z")
        assertEquals(5, s.cards.size)
        assertEquals("c4", s.cards.first().id)
        assertTrue("the not-yet-due card stays out", s.cards.none { it.id == "c3" })
        assertEquals(s, s.grade(Grade.GOOD))
        assertEquals("1/5", s.position)
        val after = s.reveal().grade(Grade.HARD)
        assertEquals("2/5", after.position)
        assertFalse(after.revealed)
        var done = after
        repeat(4) { done = done.reveal().grade(Grade.EASY) }
        assertTrue(done.finished)
        assertEquals(Grade.HARD, done.graded.first().second)
        assertEquals(listOf("c2"), RecallSession.start(cards.take(2), "2026-10-29T10:00:00.000Z", waiting = setOf("c1")).cards.map { it.id })
    }

    @Test fun newNotesGetSafePathsAndKundalikIsAWall() {
        assertEquals("Sources/Books/Sample Habits.md", NoteTemplates.pathIn("Sources/Books", "Sample: Habits?"))
        assertEquals("Today.md", NoteTemplates.pathIn(".", "Today"))
        assertEquals("Untitled.md", NoteTemplates.pathIn(".", "  ..  "))
        assertTrue(NoteTemplates.isLocked("Kundalik/2026-10-29.md"))
        assertTrue(NoteTemplates.isLocked("Life/KUNDALIK"))
        assertFalse(NoteTemplates.isLocked("Kundaliklar/x.md"))
        val book = NoteTemplates.book("Sample Book", "A. Writer")
        assertTrue(book.startsWith("# Sample Book\n"))
        for (part in listOf("Author: A. Writer", "## Why I read it", "## Highlights", "## My words")) assertTrue(part, book.contains(part))
        assertEquals("# 2026-10-29\n\n", NoteTemplates.daily(LocalDate.parse("2026-10-29")))
    }
}
