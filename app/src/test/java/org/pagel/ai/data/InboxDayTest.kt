package org.pagel.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.InboxItemEntity
import org.pagel.ai.data.db.InboxStateEntity
import org.pagel.ai.data.db.PagelDatabase
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The Inbox's day on the phone (checker 2026-10-28 #3): a ring is never reset by a stale day field from a server that
 * spoke today, and a refresh whose GET left before a choice reached pagel.uz never steps the ring back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InboxDayTest {
    private lateinit var db: PagelDatabase
    /** 2026-10-28 03:19 in Tashkent (22:19 UTC the day before): the hour the CI run failed. */
    private val now = Instant.parse("2026-10-27T22:19:00Z").toEpochMilli()
    private val dayStart = Instant.parse("2026-10-27T19:00:00Z").toEpochMilli()

    private fun item(f: String) = InboxItemEntity(file = f, position = 0, saved = "2026-10-27", source = "", text = "t", claim = "c", why = "", project = "me",
        kind = "drop", next = "", note = "", guessed = true, author = "", posted = "", later = null, hasPicture = false)

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() = db.close()

    @Test fun aStaleDayFromAServerThatSpokeTodayNeverResetsTheRing() = runBlocking {
        val d = db.inbox()
        d.upsert(listOf(item("inbox/a.md"), item("inbox/b.md")))
        // pagel.uz (the old fake) answered at 03:18 Tashkent with the UTC day, 2026-10-27, and 12 sorted.
        d.saveState(InboxStateEntity(day = "2026-10-27", goal = 20, sorted = 12, keptToday = 2, cap = 5, minWords = 6, total = 2, fetchedAt = now - 60_000))
        d.choose("inbox/a.md", "drop", "{}", "2026-10-28", keep = false, at = now, dayStart = dayStart)
        assertEquals(13, d.stateOnce()!!.sorted)
        // Offline since yesterday: the first choice of the new day starts at 1.
        d.saveState(d.stateOnce()!!.copy(fetchedAt = dayStart - 1))
        d.choose("inbox/b.md", "drop", "{}", "2026-10-28", keep = false, at = now, dayStart = dayStart)
        assertEquals(1, d.stateOnce()!!.sorted)
        assertEquals("2026-10-28", d.stateOnce()!!.day)
    }

    @Test fun aRefreshThatLeftBeforeASentChoiceNeverStepsTheRingBack() = runBlocking {
        val d = db.inbox()
        d.upsert(listOf(item("inbox/a.md"), item("inbox/b.md")))
        d.saveState(InboxStateEntity(day = "2026-10-28", goal = 20, sorted = 12, keptToday = 2, cap = 5, minWords = 6, total = 2, fetchedAt = now))
        val op = d.choose("inbox/a.md", "drop", """{"action":"drop","file":"inbox/a.md"}""", "2026-10-28", keep = false, at = now, dayStart = dayStart)
        val getLeft = now + 1_000
        // The choice lands at +2 s: pagel.uz counts 13.
        d.sent(op, "inbox/a.md", "drop", 13, 2, at = now + 2_000)
        assertEquals(13, d.stateOnce()!!.sorted)
        // The GET that left at +1 s comes back with 12: the ring stays at 13.
        val fresh = InboxStateEntity(day = "2026-10-28", goal = 20, sorted = 12, keptToday = 2, cap = 5, minWords = 6, total = 1, fetchedAt = now + 3_000)
        d.refresh(listOf(item("inbox/b.md")), fresh, startedAt = getLeft)
        assertEquals(13, d.stateOnce()!!.sorted)
        // A GET that left after it is believed, even lower (the web undid one).
        d.refresh(listOf(item("inbox/b.md")), fresh, startedAt = now + 4_000)
        assertEquals(12, d.stateOnce()!!.sorted)
    }
}
