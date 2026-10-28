package org.pagel.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.inbox.Choice
import org.pagel.ai.data.inbox.InboxRepository
import org.pagel.ai.data.inbox.InboxSender
import org.pagel.ai.data.inbox.newestFirst
import org.pagel.ai.data.db.InboxItemEntity
import org.pagel.ai.data.today.OutboxSender
import org.pagel.ai.data.today.SendResult
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 2 data layer: the Inbox deck in Room, a choice that answers at once and waits in the outbox, the order it is
 * sent in, Drop's 5 s hold and Undo, a refusal that brings the card back, and a refresh that never moves the top card.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InboxRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: PagelDatabase
    private val sorts = CopyOnWriteArrayList<String>()
    @Volatile private var sortCode = 200
    @Volatile private var deckFiles = listOf("a", "b", "c", "d", "e", "f")
    private var kicks = 0
    /** 08:00 UTC = 13:00 in Tashkent, the same day pagel.uz deals ("2026-10-28"). */
    private var now = java.time.Instant.parse("2026-10-28T08:00:00Z").toEpochMilli()

    private fun item(f: String) = """{"file":"inbox/$f.md","saved":"2026-10-27","source":"https://x.com/a/status/1","text":"post $f","claim":"Claim $f","why":"","project":"me","kind":"drop","next":"","note":"","guessed":true,"author":"a","posted":"","later":null,"has_picture":false}"""
    private val deckBody get() = """{"day":"2026-10-28","goal":20,"sorted":3,"kept_today":1,"cap":5,"min_words":6,"total":${deckFiles.size},"items":[${deckFiles.joinToString(",") { item(it) }}]}"""

    @Before fun setUp() {
        db = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.startsWith("/api/v1/inbox") -> MockResponse().setBody(deckBody)
                    request.method == "POST" && path == "/api/v1/inbox" -> {
                        sorts += request.body.readUtf8()
                        if (sortCode == 200) MockResponse().setBody("""{"ok":true,"sorted":${3 + sorts.size},"kept_today":1}""")
                        else MockResponse().setResponseCode(sortCode).setBody("""{"error":"Write it in your words: 6+ words."}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
        db.close()
    }

    private val api by lazy { PagelApi(OkHttpClient(), Dispatchers.IO) }
    private val account by lazy { Account(server.url("/").toString().trimEnd('/'), "d_1", "Phone", "tok") }
    private fun repo() = InboxRepository(api, db.inbox(), { kicks++ }, clock = { now })
    private fun sender() = OutboxSender(api, db.dao(), clock = { now }, inbox = InboxSender(api, db.inbox(), clock = { now }))
    private suspend fun deck() = db.inbox().deck().first().map { it.file.removePrefix("inbox/").removeSuffix(".md") }

    @Test fun refreshFillsTheDeckAndTheDaysCounts() = runBlocking {
        repo().refresh(account)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), deck())
        val s = db.inbox().stateOnce()!!
        assertEquals(20, s.goal)
        assertEquals(3, s.sorted)
        assertEquals(1, s.keptToday)
    }

    @Test fun fiveChoicesOfflineLeaveAtOnceAndAreSentInOrderWhenOnline() = runBlocking {
        val r = repo()
        r.refresh(account)
        r.choose("inbox/a.md", Choice.DROP)
        r.choose("inbox/b.md", Choice.LATER)
        r.choose("inbox/c.md", Choice.DROP)
        r.choose("inbox/d.md", Choice.ACT, "Open the form and fill page one", "humopedia", false)
        r.choose("inbox/e.md", Choice.NOTE, "@maker: FPV drones with ESP32", "ababil")
        // Room answered every choice at once: the deck is f, then b at the back (put off once); the ring moved by 5.
        assertEquals(listOf("f", "b"), deck())
        assertEquals(8, db.inbox().stateOnce()!!.sorted)
        assertEquals(5, kicks)

        // Offline: nothing leaves, nothing is lost.
        server.shutdown()
        assertEquals(SendResult.TRY_LATER, sender().sendAll(account))
        assertEquals(5, db.dao().waitingOutbox().size)

        // Back online, after Drop's 5 s: all five, in the order made.
        val back = MockWebServer().also { it.dispatcher = server.dispatcher; it.start() }
        val online = account.copy(serverUrl = back.url("/").toString().trimEnd('/'))
        now += 6_000
        assertEquals(SendResult.ALL_SENT, sender().sendAll(online))
        back.shutdown()
        assertEquals(listOf("drop:a", "later:b", "drop:c", "act:d", "note:e"), sorts.map { s ->
            Regex("\"action\":\"(\\w+)\",\"file\":\"inbox/(\\w)\\.md\"").find(s)!!.destructured.let { (a, f) -> "$a:$f" }
        })
        assertTrue(sorts[3].contains("\"sentence\":\"Open the form and fill page one\",\"project\":\"humopedia\",\"someday\":false"))
        assertTrue(db.dao().waitingOutbox().isEmpty())
        // Sent: the dropped and sorted cards are gone from Room; the put-off one stays at the back.
        assertEquals(listOf("f", "b"), deck())
        assertNull(db.inbox().item("inbox/a.md"))
        assertEquals("2026-10-28", db.inbox().item("inbox/b.md")!!.later)
        assertEquals(3 + 5, db.inbox().stateOnce()!!.sorted)
    }

    @Test fun aDropWaitsOutItsUndoAndHoldsTheChoicesAfterIt() = runBlocking {
        val r = repo()
        r.refresh(account)
        r.choose("inbox/a.md", Choice.DROP)
        r.choose("inbox/b.md", Choice.LATER)
        assertEquals(SendResult.TRY_LATER, sender().sendAll(account))
        assertTrue("nothing goes inside the 5 s", sorts.isEmpty())
        now += 5_001
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(2, sorts.size)
    }

    @Test fun undoBeforeSendingBringsTheCardBackOnTop() = runBlocking {
        val r = repo()
        r.refresh(account)
        val op = r.choose("inbox/a.md", Choice.DROP)
        assertEquals("b", deck().first())
        assertEquals(4, db.inbox().stateOnce()!!.sorted)
        assertTrue(r.undo(op, "inbox/a.md", Choice.DROP))
        assertEquals("a", deck().first())
        assertEquals(3, db.inbox().stateOnce()!!.sorted)
        now += 6_000
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertTrue("an undone drop never reaches pagel.uz", sorts.isEmpty())
        assertFalse("too late once sent", r.undo(op, "inbox/a.md", Choice.DROP))
    }

    @Test fun aRefusedChoiceComesBackOnTopAndSaysWhy() = runBlocking {
        val r = repo()
        r.refresh(account)
        r.choose("inbox/c.md", Choice.KEEP, "too short")
        assertEquals(2, db.inbox().stateOnce()!!.keptToday)
        sortCode = 400
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals("c", deck().first())
        assertEquals(1, db.inbox().stateOnce()!!.keptToday)
        assertEquals(3, db.inbox().stateOnce()!!.sorted)
        val refused = db.dao().refusedOutbox().first().single()
        assertEquals("Write it in your words: 6+ words.", refused.refused)
    }

    /** Swipe up (Boss 2026-10-28): the card goes back into the deck at least two cards later; nothing is sent or counted. */
    @Test fun aShuffleKeepsTheCardInTheDeckAndSendsNothing() = runBlocking {
        val r = repo()
        r.refresh(account)
        // The earliest spot it may take: two cards on.
        assertTrue(r.shuffle("inbox/a.md") { 0 })
        assertEquals(listOf("b", "c", "a", "d", "e", "f"), deck())
        // The latest: the back.
        assertTrue(r.shuffle("inbox/b.md") { it - 1 })
        assertEquals(listOf("c", "a", "d", "e", "f", "b"), deck())
        assertEquals(3, db.inbox().stateOnce()!!.sorted)
        assertEquals(0, kicks)
        assertTrue(db.dao().waitingOutbox().isEmpty())
        now += 6_000
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertTrue("a shuffle never reaches pagel.uz", sorts.isEmpty())
    }

    @Test fun theLastCardCannotBeShuffled() = runBlocking {
        deckFiles = listOf("a")
        val r = repo()
        r.refresh(account)
        assertFalse(r.shuffle("inbox/a.md"))
        assertEquals(listOf("a"), deck())
    }

    /** Newest first by the post's own date (908 saves share the import day as `saved`); put-off saves last. */
    @Test fun theDeckIsNewestFirst() {
        fun row(f: String, saved: String, posted: String, later: String? = null) = InboxItemEntity(
            file = f, position = 0, saved = saved, source = "", text = "", claim = f, why = "", project = "me", kind = "drop", next = "", note = "",
            guessed = true, author = "", posted = posted, later = later, hasPicture = false,
        )
        val order = newestFirst(listOf(
            row("april", "2026-10-26", "2026-04-29"),
            row("put-off", "2026-10-26", "2026-10-27", later = "2026-10-28"),
            row("october", "2026-10-26", "2026-10-25"),
            row("my-note", "2026-10-27", ""),
            row("tie-1", "2026-10-26", "2026-09-01"),
            row("tie-2", "2026-10-26", "2026-09-01"),
        )).map { it.file }
        assertEquals(listOf("my-note", "october", "tie-1", "tie-2", "april", "put-off"), order)
    }

    @Test fun theDeckIsAskedForWithNoSeed() = runBlocking {
        repo().refresh(account)
        assertEquals("/api/v1/inbox?limit=${InboxRepository.DECK}&seed=0", server.takeRequest().path)
    }

    @Test fun aRefreshKeepsTheTopCardAndTheChoicesOnTheirWay() = runBlocking {
        val r = repo()
        r.refresh(account)
        r.choose("inbox/a.md", Choice.DROP)
        // The web sorted b and c meanwhile; pagel.uz now deals d, e, f, g, and a (it has not heard of the drop yet).
        deckFiles = listOf("d", "e", "f", "g", "a")
        r.refresh(account)
        assertEquals(listOf("b", "d", "e", "f", "g"), deck())
        assertEquals("inbox/a.md", db.inbox().item("inbox/a.md")!!.file)
        assertEquals(3 + 1, db.inbox().stateOnce()!!.sorted)
    }
}
