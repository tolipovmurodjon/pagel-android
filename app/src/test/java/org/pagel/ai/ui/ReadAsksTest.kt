package org.pagel.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pagel.ai.ui.inbox.ReadAsks

/** Phase 3 checker #4: the AI is asked to read the same unread cards at most 3 times, then never again by a timer. */
class ReadAsksTest {
    @Test fun threeAsksThenItStops() {
        val asks = ReadAsks()
        var sent = 0
        // A day of a save the AI cannot read, checked every 15 s: it used to be one ask a minute, 1,440 a day.
        repeat(5_760) { if (asks.more()) { sent++; asks.count() } }
        assertEquals(ReadAsks.MAX, sent)
        assertEquals(3, ReadAsks.MAX)
    }
}
