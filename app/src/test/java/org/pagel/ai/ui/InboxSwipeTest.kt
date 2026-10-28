package org.pagel.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pagel.ai.data.inbox.Choice
import org.pagel.ai.ui.inbox.Swipe
import org.pagel.ai.ui.inbox.captionWithoutClaim
import org.pagel.ai.ui.inbox.visibleDeck
import org.pagel.ai.data.db.InboxItemEntity

/** Boss's swipe map (2026-10-28): up shuffles, down drops, left learns, right makes an Action; and the caption rule. */
class InboxSwipeTest {
    private val width = 1000f
    private val line = 330f

    @Test fun eachDirectionMakesItsChoice() {
        assertEquals(Swipe.SHUFFLE, Swipe.at(20f, -400f, width, line))
        assertEquals(Swipe.DROP, Swipe.at(-20f, 400f, width, line))
        assertEquals(Swipe.LEARN, Swipe.at(-400f, 30f, width, line))
        assertEquals(Swipe.ACTION, Swipe.at(400f, -30f, width, line))
        // Short of the line: nothing, the card springs back.
        assertNull(Swipe.at(200f, 200f, width, line))
        // A diagonal goes by the larger move: mostly down is a Drop, mostly right an Action.
        assertEquals(Swipe.DROP, Swipe.at(320f, 420f, width, line))
        assertEquals(Swipe.ACTION, Swipe.at(420f, 320f, width, line))

        assertNull(Swipe.SHUFFLE.choice(full = false))
        assertEquals(Choice.DROP, Swipe.DROP.choice(false))
        assertEquals(Choice.KEEP, Swipe.LEARN.choice(false))
        assertEquals(Choice.ACT, Swipe.ACTION.choice(false))
        // Today's keeps full: left is a Note, as the web turns a Learn into a Note.
        assertEquals(Choice.NOTE, Swipe.LEARN.choice(full = true))
    }

    @Test fun aFlickDecidesBeforeTheLine() {
        assertEquals(Swipe.SHUFFLE, Swipe.flung(100f, -2400f))
        assertEquals(Swipe.DROP, Swipe.flung(-100f, 2400f))
        assertEquals(Swipe.LEARN, Swipe.flung(-2400f, 300f))
        assertEquals(Swipe.ACTION, Swipe.flung(2400f, -300f))
        assertNull(Swipe.flung(900f, 900f))
    }

    @Test fun theCaptionLeavesOutTheClaimItStartsWith() {
        val claim = "Most founders are using 5% of Claude's potential. The other 95% will separate the winners from everyone else 🚀"
        // LinkedIn: the caption is the claim again (cut at import): nothing left to show.
        assertEquals("", captionWithoutClaim(claim, "Most founders are using 5% of Claude's potential. The other 95% will separate the winners from everyone else 🚀..."))
        // The claim, then more: only the more.
        assertEquals("Long caption: why it works.", captionWithoutClaim("Quizzes beat rereading: recall builds memory", "Quizzes beat rereading: recall builds memory. Long caption: why it works."))
        // A caption of its own stays whole.
        val own = "{ONALAR DIQQATIGA} Agar farzandingiz matematikadan ortda qolayotgan bo'lsa"
        assertEquals(own, captionWithoutClaim("Ziyoly sells Uzbek math help for kids", own))
    }

    /** Boss 2026-10-28 ("slow to show the next one"): the swiped card leaves the top on the same frame, before Room. */
    @Test fun aSwipedCardLeavesTheTopBeforeRoomCatchesUp() {
        fun row(f: String) = InboxItemEntity(file = f, position = 0, saved = "2026-10-27", source = "", text = "", claim = f, why = "", project = "me",
            kind = "drop", next = "", note = "", guessed = true, author = "", posted = "", later = null, hasPicture = false)
        val deck = listOf(row("a"), row("b"), row("c"), row("d"))
        assertEquals(listOf("b", "c", "d"), visibleDeck(deck, setOf("a")).map { it.file })
        // Two quick swipes before Room wrote either.
        assertEquals(listOf("c", "d"), visibleDeck(deck, setOf("a", "b")).map { it.file })
        // Shuffled: Room already moved it down the deck, where it stays.
        assertEquals(listOf("b", "c", "a", "d"), visibleDeck(listOf(row("b"), row("c"), row("a"), row("d")), setOf("a")).map { it.file })
        assertEquals(deck, visibleDeck(deck, emptySet()))
    }
}
