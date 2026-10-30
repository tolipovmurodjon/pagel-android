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
import org.pagel.ai.data.api.SortBody
import org.pagel.ai.data.db.InboxItemEntity
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.inbox.Choice
import org.pagel.ai.data.inbox.InboxRepository
import org.pagel.ai.data.inbox.InboxSender
import org.pagel.ai.data.inbox.checkDate
import org.pagel.ai.data.inbox.dayIn
import org.pagel.ai.data.inbox.daysOld
import org.pagel.ai.data.inbox.holdsForUndo
import org.pagel.ai.data.inbox.isWaiting
import org.pagel.ai.data.inbox.olderThan
import org.pagel.ai.data.inbox.plainDay
import org.pagel.ai.data.inbox.somedayLine
import org.pagel.ai.data.inbox.waitingLine
import org.pagel.ai.data.inbox.waitingRowText
import org.pagel.ai.data.today.OutboxSender
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.ui.inbox.Form
import org.pagel.ai.ui.inbox.InboxViewModel
import org.pagel.ai.ui.inbox.Tidy
import org.pagel.ai.ui.inbox.formReady
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Inbox clarify (Boss 2026-10-30): each GTD choice reaches pagel.uz as the fields it already has (Someday = a held P3
 * task, Waiting and Calendar carry a due, Project is a new project plus a first step), and the bulk tidy-up of the old
 * saves shows its count first, moves them all quietly and has one Undo that stops every one before pagel.uz hears.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InboxClarifyTest {
    private val server = MockWebServer()
    private lateinit var db: PagelDatabase
    private val sorts = CopyOnWriteArrayList<String>()
    private val asked = CopyOnWriteArrayList<String>()
    private var kicks = 0
    private var now = java.time.Instant.parse("2026-10-30T08:00:00Z").toEpochMilli()

    /** pagel.uz that does NOT know `older_than`: it deals its newest cards, fresh and old mixed, as an older server would. */
    private fun item(f: String, saved: String) = """{"file":"inbox/$f.md","saved":"$saved","source":"https://www.instagram.com/p/$f/","text":"post $f","claim":"Claim about $f","why":"","project":"me","kind":"drop","next":"","note":"","guessed":true,"author":"a","posted":"$saved","later":null,"has_picture":false}"""
    private val dealt = listOf("fresh" to "2026-10-29", "old1" to "2026-07-01", "old2" to "2026-05-15", "edge" to "2026-08-31", "undated" to "")
    /** pagel.uz's count of all its 60+ day saves (null = an older pagel.uz that sends none). */
    private var olderCount: Int? = null
    private val body get() = """{"day":"2026-10-30","goal":20,"sorted":3,"kept_today":0,"cap":5,"min_words":6,"total":905,""" +
        (olderCount?.let { """"older_than_60":$it,"by_source":{"instagram":700,"x":150,"note":55},""" } ?: "") +
        """"items":[${dealt.joinToString(",") { item(it.first, it.second) }}]}"""

    @Before fun setUp() {
        db = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path.orEmpty().startsWith("/api/v1/inbox") -> { asked += request.path.orEmpty(); MockResponse().setBody(body) }
                request.method == "POST" && request.path == "/api/v1/inbox" -> { sorts += request.body.readUtf8(); MockResponse().setBody("""{"ok":true,"sorted":4,"kept_today":0}""") }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    private val api by lazy { PagelApi(OkHttpClient(), Dispatchers.IO) }
    private val account by lazy { Account(server.url("/").toString().trimEnd('/'), "d_1", "Phone", "tok") }
    private fun repo() = InboxRepository(api, db.inbox(), { kicks++ }, clock = { now })
    private fun sender() = OutboxSender(api, db.dao(), clock = { now }, inbox = InboxSender(api, db.inbox(), clock = { now }))
    private fun entity(f: String, saved: String, next: String = "", claim: String = "Claim", text: String = "post") = InboxItemEntity(
        file = "inbox/$f.md", position = 0, saved = saved, source = "https://x.com/a", text = text, claim = claim, why = "", project = "me", kind = "learn", next = next,
        note = "", guessed = true, author = "a", posted = saved, later = null, hasPicture = false,
    )
    private fun sent() = sorts.map { kotlinx.serialization.json.Json.decodeFromString(SortBody.serializer(), it) }

    // ── The pure rules ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun anAgeIsCountedFromThePostsOwnDayAndOnlyTheOldOnesAreKept() {
        val today = "2026-10-30"
        assertEquals(1, daysOld(entity("a", "2026-10-29"), today))
        assertNull("no date is never old", daysOld(entity("b", ""), today))
        val all = listOf(entity("new", "2026-10-29"), entity("old", "2026-07-01"), entity("older", "2026-05-15"), entity("none", ""), entity("edge", "2026-08-31"))
        // 60 days before 30 Oct is 31 Aug: that day is old enough, the day after is not.
        assertEquals(listOf("older", "old", "edge"), olderThan(all, 60, today).map { it.file.removePrefix("inbox/").removeSuffix(".md") })
    }

    @Test fun theWordsEachChoiceSends() {
        assertEquals("Waiting for Aziz: the grant form", waitingLine(" Aziz ", "the grant form "))
        assertEquals("no filler word when nobody is named", "Waiting for: the grant form", waitingLine("", "the grant form"))
        assertEquals("person first, no colon", "👤 Aziz · the grant form", waitingRowText(waitingLine("Aziz", "the grant form")))
        assertEquals("nobody named", "👤 the grant form", waitingRowText(waitingLine("", "the grant form")))
        assertTrue(isWaiting(waitingLine("Aziz", "x")))
        assertTrue(isWaiting(waitingLine("", "x")))
        assertFalse(isWaiting("Email Aziz about the form"))
        assertEquals("2026-11-02", checkDate("2026-10-30"))
        assertEquals("2026-10-31", checkDate("2026-10-30", 1))
        assertEquals("Open the form and fill page one", somedayLine(entity("a", "2026-10-29", next = "Open the form and fill page one", claim = "Grant")))
        assertEquals("Claim about drones", somedayLine(entity("a", "2026-10-29", claim = "Claim about drones")))
        assertEquals("Look at: Drones", somedayLine(entity("a", "2026-10-29", claim = "Drones")))
    }

    @Test fun eachFormNeedsWhatItsChoiceNeeds() {
        val card = InboxViewModel.card(entity("a", "2026-10-29"), false, null)
        fun f(c: Choice, line: String = "", name: String = "") = Form(card, c, line, "me", false, name = name)
        assertTrue(formReady(f(Choice.WAITING, "the grant form"), 6))
        assertFalse("one word is not a step", formReady(f(Choice.WAITING, "form"), 6))
        assertTrue(formReady(f(Choice.CALENDAR, "grant round closes"), 6))
        assertFalse("a project needs its name", formReady(f(Choice.PROJECT, "pick the tool"), 6))
        assertFalse("and its first step", formReady(f(Choice.PROJECT, "", "Newsletter"), 6))
        assertTrue(formReady(f(Choice.PROJECT, "pick the tool", "Newsletter"), 6))
        assertTrue(formReady(f(Choice.NOW), 6))
        assertFalse(formReady(f(Choice.KEEP, "too short"), 6))
    }

    // ── Each choice reaches pagel.uz as the fields it already has ───────────────────────────────────────────────────

    @Test fun someday_waiting_calendar_and_project_are_acts_with_the_right_fields() = runBlocking {
        val r = repo()
        r.refresh(account)
        r.choose("inbox/fresh.md", Choice.SOMEDAY, "Learn to weld", "me", true)
        r.choose("inbox/old1.md", Choice.WAITING, waitingLine("Aziz", "the invoice"), "humopedia", false, "2026-11-02")
        r.choose("inbox/old2.md", Choice.CALENDAR, "Grant round closes", "humopedia", false, "2026-11-15 09:00")
        r.choose("inbox/edge.md", Choice.PROJECT, "Pick the newsletter tool", "newsletter", false)
        now += 6_000
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        val b = sent()
        assertEquals(listOf("act", "act", "act", "act"), b.map { it.action })
        assertEquals(SortBody("act", "inbox/fresh.md", "Learn to weld", "me", true), b[0])
        assertEquals("Waiting for Aziz: the invoice", b[1].sentence)
        assertEquals("2026-11-02", b[1].due)
        assertEquals("2026-11-15 09:00", b[2].due)
        assertEquals(SortBody("act", "inbox/edge.md", "Pick the newsletter tool", "newsletter", false), b[3])
    }

    @Test fun theDayASaveNamesIsTheCalendarsFirstGuess() {
        val fri = java.time.LocalDate.parse("2026-10-30") // a Friday
        assertEquals("closes on Friday, said on a Friday: today", fri, dayIn("Grant round closes on Friday", fri))
        assertEquals(java.time.LocalDate.parse("2026-11-03"), dayIn("Demo day is next Tuesday", fri))
        assertEquals(java.time.LocalDate.parse("2026-11-15"), dayIn("The IT Park grant round closes on 15 November", fri))
        assertEquals(java.time.LocalDate.parse("2026-11-15"), dayIn("Deadline: Nov 15th", fri))
        assertEquals("a day already gone this year is next year's", java.time.LocalDate.parse("2027-01-05"), dayIn("Sale on 5 Jan", fri))
        assertEquals(fri.plusDays(1), dayIn("Call back tomorrow", fri))
        assertNull("\"sat\" is a word, not Saturday", dayIn("It has sat here since spring", fri))
        assertNull(dayIn("10 AI tools lists", fri))
        assertEquals("Mon 2 Nov", plainDay("2026-11-02"))
        assertEquals("Fri 30 Oct 09:00", plainDay("2026-10-30 09:00"))
    }

    @Test fun everyActAndADropWaitOutTheirUndoAndANoteDoesNot() = runBlocking {
        assertTrue(SortBody("act", "f", "x", "me", true).holdsForUndo())
        assertTrue(SortBody("drop", "f").holdsForUndo())
        assertTrue("Waiting, Calendar, Project and Action have an Undo too", SortBody("act", "f", "x", "me", false, "2026-11-02").holdsForUndo())
        assertFalse(SortBody("note", "f", "x", "me").holdsForUndo())
        val r = repo()
        r.refresh(account)
        val op = r.choose("inbox/fresh.md", Choice.SOMEDAY, "Learn to weld", "me", true)
        assertEquals(SendResult.TRY_LATER, sender().sendAll(account))
        assertTrue("nothing goes inside the 5 s", sorts.isEmpty())
        assertTrue(r.undo(op, "inbox/fresh.md", Choice.SOMEDAY))
        now += 6_000
        sender().sendAll(account)
        assertTrue("an undone Someday never reaches pagel.uz", sorts.isEmpty())
    }

    // ── The bulk tidy-up ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun theTidyUpCountsOnlyTheReallyOldEvenFromAServerThatIgnoresOlderThan() = runBlocking {
        val page = (repo().older(account) as Outcome.Ok).value
        val old = page.items
        assertTrue("asked for the old ones", asked.single().contains("older_than=60"))
        assertEquals("the fresh and the undated ones are not old", listOf("old2", "old1", "edge"), old.map { it.file.removePrefix("inbox/").removeSuffix(".md") })
        assertEquals("an older pagel.uz: the page is all that is known", 3, page.total)
        val tidy = Tidy(loading = false, items = old, total = page.total)
        assertEquals(listOf("Instagram" to 3), tidy.sources)
    }

    @Test fun theTidyUpShowsPagelUzsWholeCountNotOnePage() = runBlocking {
        olderCount = 412
        val page = (repo().older(account) as Outcome.Ok).value
        assertEquals(412, page.total)
        val tidy = Tidy(loading = false, items = page.items, total = page.total, bySource = page.bySource)
        assertEquals(412, tidy.count)
        assertEquals(listOf("Instagram" to 700, "X" to 150, "Typed by you" to 55), tidy.sources)
    }

    @Test fun aBulkSomedayMovesThemAllQuietlyAndOneUndoStopsEveryOne() = runBlocking {
        val r = repo()
        r.refresh(account)
        val ring = db.inbox().stateOnce()!!.sorted
        val old = (r.older(account) as Outcome.Ok).value.items
        val rows = r.chooseMany(old, Choice.SOMEDAY, "me")
        assertEquals(3, rows.size)
        assertEquals("a bulk does not fake the day's 20", ring, db.inbox().stateOnce()!!.sorted)
        assertEquals(3, db.dao().waitingOutbox().size)
        // The one Undo, inside the window: every part is taken back and pagel.uz hears of none.
        assertEquals(3, r.undoMany(rows))
        assertTrue(db.dao().waitingOutbox().isEmpty())
        now += 6_000
        sender().sendAll(account)
        assertTrue(sorts.isEmpty())
        assertEquals("too late once sent has nothing to take back", 0, r.undoMany(rows))
    }

    @Test fun aBulkThatIsNotUndoneReachesPagelUzAfterTheWindowWithItsFields() = runBlocking {
        val r = repo()
        val old = (r.older(account) as Outcome.Ok).value.items
        r.chooseMany(old, Choice.SOMEDAY, "me")
        assertEquals(SendResult.TRY_LATER, sender().sendAll(account))
        assertTrue(sorts.isEmpty())
        now += 5_001
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        val b = sent()
        assertEquals(3, b.size)
        assertTrue(b.all { it.action == "act" && it.someday == true && it.project == "me" && !it.sentence.isNullOrBlank() && it.quiet == true })
        r.chooseMany(old, Choice.DROP, "me")
        now += 5_001
        sender().sendAll(account)
        assertEquals(listOf("drop", "drop", "drop"), sent().drop(3).map { it.action })
    }

    /** Checker 2026-10-30 #1: the ring read 27/20 after a tidy-up. Quiet parts never move it, waiting or sent. */
    @Test fun aBulkThatReachesPagelUzNeverMovesTheRing() = runBlocking {
        val r = repo()
        r.refresh(account)
        val ring = db.inbox().stateOnce()!!.sorted
        val old = (r.older(account) as Outcome.Ok).value.items
        r.chooseMany(old, Choice.SOMEDAY, "me")
        // A fresh deck while they wait: pagel.uz's count, with no waiting quiet part added.
        r.refresh(account)
        assertEquals(ring, db.inbox().stateOnce()!!.sorted)
        now += 6_000
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(3, sent().count { it.quiet == true })
        // pagel.uz answered "sorted": 4 to each: a quiet part keeps the phone's count.
        assertEquals(ring, db.inbox().stateOnce()!!.sorted)
        // A real choice still counts and follows pagel.uz.
        r.choose("inbox/fresh.md", Choice.NOTE, "@a: one line to keep", "me")
        assertEquals(ring + 1, db.inbox().stateOnce()!!.sorted)
        sender().sendAll(account)
        assertEquals(4, db.inbox().stateOnce()!!.sorted)
    }
}
