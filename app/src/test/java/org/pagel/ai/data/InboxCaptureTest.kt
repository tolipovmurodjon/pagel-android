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
import org.pagel.ai.data.db.InboxItemEntity
import org.pagel.ai.data.db.OLD_POSITION
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.inbox.CapturedFiles
import org.pagel.ai.data.inbox.Choice
import org.pagel.ai.data.inbox.FRESH_START
import org.pagel.ai.data.inbox.InboxRepository
import org.pagel.ai.data.inbox.InboxSender
import org.pagel.ai.data.inbox.isCurrent
import org.pagel.ai.data.inbox.oldPileDays
import org.pagel.ai.data.inbox.splitCapture
import org.pagel.ai.data.inbox.tashkentDay
import org.pagel.ai.data.tasks.UNDO_WINDOW_MS
import org.pagel.ai.data.today.OutboxSender
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.ui.capture.parseShare
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Inbox v3 (Boss 2026-10-31): the Old pile against the current Inbox at the fresh-start line (midnight in Tashkent),
 * a capture that is in the deck at once and waits in the outbox in the order typed (offline too), a pasted list with one
 * Undo, a share intent read into one save, and the Undo of an act that takes the op back before it is sent.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InboxCaptureTest {
    private val server = MockWebServer()
    private lateinit var db: PagelDatabase
    private val adds = CopyOnWriteArrayList<String>()
    private val others = CopyOnWriteArrayList<String>()
    private val gets = CopyOnWriteArrayList<String>()
    @Volatile private var addCode = 201
    private var kicks = 0
    private val captured = CapturedFiles()
    /** 15:00 in Tashkent on 31 October: the day before the fresh start. */
    private var now = Instant.parse("2026-10-31T10:00:00Z").toEpochMilli()

    private fun item(f: String, saved: String) =
        """{"file":"inbox/$f.md","saved":"$saved","source":"https://x.com/a/status/1","text":"post $f","claim":"Claim $f","why":"","project":"me","kind":"drop","next":"","note":"","guessed":true,"author":"a","posted":"","later":null,"has_picture":false}"""

    @Before fun setUp() {
        db = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.startsWith("/api/v1/inbox") -> {
                        gets += path
                        val old = path.contains("older_than=")
                        val items = if (old) listOf("o1", "o2", "o3").joinToString(",") { item(it, "2026-01-05") } else listOf(item("old", "2026-01-05"), item("new", "2026-11-01")).joinToString(",")
                        MockResponse().setBody("""{"day":"2026-11-05","goal":20,"sorted":0,"kept_today":0,"cap":5,"min_words":6,"total":912,"items":[$items]}""")
                    }
                    request.method == "POST" && path == "/api/v1/inbox" -> {
                        val body = request.body.readUtf8()
                        if (body.contains("\"action\":\"add\"")) {
                            if (addCode == 201) { adds += body; MockResponse().setResponseCode(201).setBody("""{"file":"inbox/cap-${adds.size}.md"}""") }
                            else MockResponse().setResponseCode(addCode).setBody("""{"error":"later"}""")
                        } else { others += body; MockResponse().setBody("""{"ok":true,"sorted":1,"kept_today":0}""") }
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
    private fun repo() = InboxRepository(api, db.inbox(), { kicks++ }, clock = { now }, captured = captured)
    private fun sender() = OutboxSender(api, db.dao(), clock = { now }, inbox = InboxSender(api, db.inbox(), clock = { now }, captured = captured))
    private fun textOf(body: String) = Regex(""""text":"([^"]*)"""").find(body)!!.groupValues[1]

    private fun row(file: String, saved: String) = InboxItemEntity(file = file, position = 1, saved = saved, source = "", text = "t", claim = "c", why = "", project = "me",
        kind = "drop", next = "", note = "", guessed = true, author = "", posted = "", later = null, hasPicture = false)

    @Test fun theLineIsMidnightInTashkent() {
        assertEquals("2026-10-31", tashkentDay(Instant.parse("2026-10-31T18:59:59Z").toEpochMilli()))
        assertEquals("2026-11-01", tashkentDay(Instant.parse("2026-10-31T19:00:00Z").toEpochMilli()))
        assertFalse("the last day before the line is old", isCurrent(row("inbox/a.md", "2026-10-31"), FRESH_START, emptySet()))
        assertTrue("the first day after it is current", isCurrent(row("inbox/b.md", "2026-11-01"), FRESH_START, emptySet()))
        assertTrue("a save with no date is never called old", isCurrent(row("inbox/c.md", ""), FRESH_START, emptySet()))
    }

    @Test fun aCaptureMadeTodayStaysInTheInboxEvenBeforeTheLine() {
        assertTrue(isCurrent(row("capture/c1", "2026-10-31"), FRESH_START, emptySet()))
        assertTrue(isCurrent(row("inbox/named-by-pagel.md", "2026-10-31"), FRESH_START, setOf("inbox/named-by-pagel.md")))
        assertFalse(isCurrent(row("inbox/named-by-pagel.md", "2026-10-31"), FRESH_START, emptySet()))
    }

    @Test fun theOldPileAsksForExactlyTheSavesBeforeTheLine() {
        assertEquals(1, oldPileDays(FRESH_START, "2026-11-01"))
        assertEquals(5, oldPileDays(FRESH_START, "2026-11-05"))
        assertEquals("before the line everything is old", 0, oldPileDays(FRESH_START, "2026-10-31"))
        assertEquals("pagel.uz can move the line", 3, oldPileDays("2026-11-10", "2026-11-12"))
    }

    @Test fun pastedLinesBecomeSeparateSaves() {
        assertEquals(listOf("one thing"), splitCapture("  one thing \n"))
        assertEquals(listOf("milk", "call Aziz", "https://a.example/x"), splitCapture("- milk\r\n2. call Aziz\n\n* https://a.example/x\n"))
        assertEquals(emptyList<String>(), splitCapture(" \n \n"))
    }

    @Test fun aSharedLinkStaysTheLinkAndSharedWordsAreBossesOwn() {
        assertEquals("https://example.com/p", parseShare("android.intent.action.SEND", " https://example.com/p ", "A page")?.text)
        assertNull("a link alone has no source: pagel.uz reads it", parseShare("android.intent.action.SEND", "https://example.com/p", null)?.source)
        val words = parseShare("android.intent.action.SEND", "Read this: https://example.com/p", "A page")
        assertEquals("A page\nRead this: https://example.com/p", words?.text)
        assertEquals("Boss said (shared)", words?.source)
        assertNull("a picture alone has no words", parseShare("android.intent.action.SEND", null, null))
        assertNull("only SEND is read", parseShare("android.intent.action.VIEW", "hello", null))
    }

    @Test fun rapidCaptureQueuesInOrderAndWaitsOfflineUntilThePhoneIsBackOnline() = runBlocking {
        repo().capture(listOf("Buy milk"))
        repo().capture(listOf("Call Aziz"))
        repo().capture(listOf("Read the grant form"))
        assertEquals("newest on top, at once", listOf("Read the grant form", "Call Aziz", "Buy milk"), db.inbox().deck().first().map { it.text })
        assertTrue(kicks >= 3)
        addCode = 503
        assertEquals(SendResult.TRY_LATER, sender().sendAll(account))
        assertTrue("offline: nothing reached pagel.uz", adds.isEmpty())
        assertEquals("all three still wait, in order", 3, db.dao().waitingOutbox().size)
        addCode = 201
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals("in the order typed", listOf("Buy milk", "Call Aziz", "Read the grant form"), adds.map(::textOf))
        assertEquals("three different ids, so a retry never saves twice", 3, adds.map { Regex(""""id":"([^"]+)"""").find(it)!!.groupValues[1] }.toSet().size)
        assertEquals(0, db.dao().waitingOutbox().size)
        val deck = db.inbox().deck().first()
        assertEquals("they keep their place under pagel.uz's own names", listOf("inbox/cap-3.md", "inbox/cap-2.md", "inbox/cap-1.md"), deck.map { it.file })
        assertTrue("and stay in the Inbox though their day is before the line", deck.all { isCurrent(it, FRESH_START, captured.files.value) })
    }

    @Test fun aRefreshNeverDropsACaptureThatHasNotLeftYet() = runBlocking {
        repo().capture(listOf("Pending thought"))
        assertTrue(repo().refresh(account) is Outcome.Ok)
        val files = db.inbox().deck().first().map { it.file }
        assertTrue("the capture is still in the deck", files.any { it.startsWith("capture/") })
        assertEquals("and still on top", "Pending thought", db.inbox().deck().first().first().text)
    }

    @Test fun refreshCountsTheOldPileFromTheNewestFirstPage() = runBlocking {
        now = Instant.parse("2026-11-05T10:00:00Z").toEpochMilli()
        assertTrue(repo().refresh(account) is Outcome.Ok)
        assertEquals("912 saves, 1 of them current", 911, captured.oldLeft.value)
    }

    @Test fun theOldPileComesInPagesBehindTheCurrentDeckAndSurvivesARefresh() = runBlocking {
        now = Instant.parse("2026-11-05T10:00:00Z").toEpochMilli()
        val r = repo()
        r.refresh(account)
        assertEquals(3, (r.loadOld(account) as Outcome.Ok).value)
        assertTrue("asked for exactly the saves before the line", gets.any { it.contains("older_than=5") })
        assertEquals("nothing new the second time", 0, (r.loadOld(account) as Outcome.Ok).value)
        val old = db.inbox().deck().first().filter { it.position >= OLD_POSITION }
        assertEquals(listOf("inbox/o1.md", "inbox/o2.md", "inbox/o3.md"), old.map { it.file })
        r.refresh(account)
        assertEquals("a refresh of the current deck leaves the old pile alone", 3, db.inbox().deck().first().count { it.position >= OLD_POSITION })
    }

    @Test fun aPastedListWaitsForOneUndoThatStopsAllOfIt() = runBlocking {
        val rows = repo().capture(listOf("one", "two", "three"), hold = true)
        assertEquals(3, db.inbox().deck().first().size)
        assertEquals("held: pagel.uz hears nothing yet", SendResult.TRY_LATER, sender().sendAll(account))
        assertTrue(adds.isEmpty())
        assertEquals(3, repo().undoCapture(rows))
        assertTrue(db.inbox().deck().first().isEmpty())
        now += UNDO_WINDOW_MS + 1
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertTrue("an Undone list never reaches pagel.uz", adds.isEmpty())
    }

    @Test fun aPastedListGoesOutOnceTheUndoWindowEnds() = runBlocking {
        repo().capture(listOf("one", "two"), hold = true)
        now += UNDO_WINDOW_MS + 1
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(listOf("one", "two"), adds.map(::textOf))
    }

    @Test fun undoOfWaitingAndCalendarTakesTheOpBackBeforeItIsSent() = runBlocking {
        val waiting = repo().choose("inbox/w.md", Choice.WAITING, "Waiting for Aziz: the form", "me", false, "2026-11-03")
        val calendar = repo().choose("inbox/c.md", Choice.CALENDAR, "Fill in the form", "me", false, "2026-11-06 09:00")
        assertTrue(repo().undo(waiting, "inbox/w.md", Choice.WAITING))
        assertTrue(repo().undo(calendar, "inbox/c.md", Choice.CALENDAR))
        assertEquals(0, db.dao().waitingOutbox().size)
        now += UNDO_WINDOW_MS + 1
        sender().sendAll(account)
        assertTrue("pagel.uz never heard of either", others.isEmpty())
    }
}
