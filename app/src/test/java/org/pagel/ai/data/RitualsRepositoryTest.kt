package org.pagel.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.pagel.ai.data.api.SweepRowDto
import org.pagel.ai.data.db.LOCAL_ID
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.db.SweepEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.rituals.Move
import org.pagel.ai.data.rituals.RitualSender
import org.pagel.ai.data.rituals.Rituals
import org.pagel.ai.data.rituals.RitualsRepository
import org.pagel.ai.data.rituals.rowList
import org.pagel.ai.data.rituals.stuckList
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.OutboxSender
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.data.today.TodayRepository
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 5's data layer: a dump written offline is kept and split when online; "Add N" adds every kept row as a task
 * that reaches pagel.uz and its Undo takes them back; Close the day and the review change Room at once, send the task
 * changes first and the ritual's event after, and Undo puts everything back. A busy AI never holds a tick.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RitualsRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: PagelDatabase
    /** Every request pagel.uz got, as "METHOD path body", in order. */
    private val calls = CopyOnWriteArrayList<String>()
    @Volatile private var online = true
    @Volatile private var sweepReply: MockResponse = rowsReply()
    /** Like pagel.uz (ritual-api.ts): rows made from the dump's lines, kept by id, so a repeat id gets the old rows. */
    @Volatile private var echoSweeps = false
    private val sweepCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val ids = AtomicInteger(0)
    /** 2026-10-28 21:40 in Tashkent. */
    private var now = Instant.parse("2026-10-28T16:40:00Z").toEpochMilli()

    private fun rowsReply() = MockResponse().setBody(
        """{"rows":[{"kind":"action","text":"Call mom about Sunday","project":"me"},{"kind":"action","text":"Fix the login bug","project":"pagel"},{"kind":"someday","text":"Learn the guitar","project":"me"}]}""",
    )

    @Before fun setUp() {
        db = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (!online) return MockResponse().setResponseCode(503)
                val body = request.body.readUtf8()
                calls += "${request.method} $path $body"
                return when {
                    request.method == "POST" && path == "/api/v1/sweep" && echoSweeps -> {
                        val b = kotlinx.serialization.json.Json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
                        val id = b["id"].toString().trim('"')
                        val dump = (b["dump"] as kotlinx.serialization.json.JsonPrimitive).content
                        val rows = sweepCache.getOrPut(id) {
                            dump.lines().filter(String::isNotBlank).joinToString(",", "[", "]") { """{"kind":"action","text":"${it.trim()}","project":"me"}""" }
                        }
                        MockResponse().setBody("""{"rows":$rows}""")
                    }
                    request.method == "POST" && path == "/api/v1/sweep" -> sweepReply
                    request.method == "POST" && path == "/api/v1/tasks" -> MockResponse().setResponseCode(201).setBody("""{"id":"t_srv${ids.incrementAndGet()}"}""")
                    request.method == "PATCH" && path.startsWith("/api/v1/tasks/") -> MockResponse().setBody("""{"id":"x","status":"open","applied":["star","remove","restore","done","due"]}""")
                    request.method == "POST" && path == "/api/v1/rituals" -> MockResponse().setResponseCode(201).setBody("""{"ok":true}""")
                    request.method == "GET" && path == "/api/v1/rituals" -> MockResponse().setBody(
                        """{"day":"2026-10-28","shut_today":false,"last_review":"2026-10-20","review_due":true,"stuck":[{"project":"labs","title":"Labs"}]}""",
                    )
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
    private val clock = { now }
    private val today by lazy { TodayRepository(db.dao(), {}, clock = clock) }
    private val tasks by lazy { TaskRepository(db.dao(), {}, clock = clock) }
    private val repo by lazy { RitualsRepository(db.dao(), db.rituals(), api, today, tasks, {}, {}, clock = clock) }
    private val sender by lazy { OutboxSender(api, db.dao(), clock = clock, rituals = RitualSender(api, db.rituals(), clock)) }

    private fun task(id: String, project: String = "me", priority: Int = 1, star: Boolean = false, now: Boolean = true) = TaskEntity(
        id = id, project = project, phase = "Now", sort = 1.0, text = "Task $id", title = null, status = "open", priority = priority, star = star,
        due = null, every = null, doneWhen = null, doneAt = null, updatedAt = "2026-10-28T10:00:00Z", serverVersion = 1, lastModified = 0, phaseNow = now,
    )

    private suspend fun sweep(id: String) = db.rituals().sweepOnce(id)!!

    @Test fun aDumpWrittenOfflineIsKeptAndSplitWhenOnline() = runBlocking {
        var id = repo.saveDraft(null, "call mom\nfix the login\nlearn guitar")
        assertEquals(SweepEntity.DRAFT, sweep(id).state)
        online = false
        id = repo.split(id)!!
        assertEquals(SendResult.TRY_LATER, sender.sendAll(account))
        assertEquals(SweepEntity.WAITING, sweep(id).state)
        assertEquals("call mom\nfix the login\nlearn guitar", sweep(id).dump)
        online = true
        assertEquals(SendResult.ALL_SENT, sender.sendAll(account))
        assertEquals(SweepEntity.READY, sweep(id).state)
        assertEquals(listOf("Call mom about Sunday", "Fix the login bug", "Learn the guitar"), sweep(id).rowList().map { it.text })
        assertTrue(calls.single().startsWith("POST /api/v1/sweep {\"id\":\"$id\",\"dump\":\"call mom\\nfix the login\\nlearn guitar\"}"))
        assertTrue(db.dao().waitingOutbox().isEmpty())
    }

    @Test fun aBusyAiWaitsWithoutHoldingATickMadeAfterIt() = runBlocking {
        db.dao().upsertTasks(listOf(task("t_1")))
        var id = repo.saveDraft(null, "call mom")
        id = repo.split(id)!!
        today.tick("t_1", done = true)
        sweepReply = MockResponse().setResponseCode(503).setBody("""{"error":"The AI is reading another dump.","busy":true}""")
        assertEquals(SendResult.TRY_LATER, sender.sendAll(account))
        assertTrue(calls.any { it.startsWith("PATCH /api/v1/tasks/t_1 {\"done\":true") })
        assertEquals(SweepEntity.WAITING, sweep(id).state)
        assertEquals(listOf(OutboxEntity.SWEEP), db.dao().waitingOutbox().map { it.op })
    }

    @Test fun aRefusedDumpSaysWhyAndIsNotSentAgainByItself() = runBlocking {
        sweepReply = MockResponse().setResponseCode(429).setBody("""{"error":"Today's 20 AI reads are used. It reads again tomorrow."}""")
        var id = repo.saveDraft(null, "call mom")
        id = repo.split(id)!!
        assertEquals(SendResult.ALL_SENT, sender.sendAll(account))
        assertEquals(SweepEntity.FAILED, sweep(id).state)
        assertEquals("Today's 20 AI reads are used. It reads again tomorrow.", sweep(id).message)
        assertTrue(db.dao().waitingOutbox().isEmpty())
        sender.sendAll(account)
        assertEquals(1, calls.count { it.startsWith("POST /api/v1/sweep") })
    }

    private suspend fun readySweep(): String {
        var id = repo.saveDraft(null, "call mom\nfix the login\nlearn guitar")
        id = repo.split(id)!!
        sender.sendAll(account)
        calls.clear()
        return id
    }

    @Test fun addNPutsEveryKeptRowOnThePhoneAtOnceAndThreeTasksReachPagelUz() = runBlocking {
        val id = readySweep()
        // Boss fixes the 2nd row's words before adding.
        repo.setRows(id, sweep(id).rowList().mapIndexed { i, r -> if (i == 1) r.copy(text = "Fix the login bug on pagel.uz") else r })
        val added = repo.add(id)!!
        assertEquals(2, added.actions)
        assertEquals(1, added.someday)
        val local = sweep(id).addedIds.split(',')
        assertEquals(3, local.size)
        assertTrue(local.all { it.startsWith(LOCAL_ID) })
        assertEquals(listOf(1, 1, 3), local.map { db.dao().taskOnce(it)!!.priority })
        assertEquals(SweepEntity.ADDED, sweep(id).state)
        assertNull(repo.sweep.first())
        assertEquals(SendResult.ALL_SENT, sender.sendAll(account))
        assertEquals(
            listOf(
                """POST /api/v1/tasks {"project":"me","text":"Call mom about Sunday","priority":1,"client_id":"${local[0]}"}""",
                """POST /api/v1/tasks {"project":"pagel","text":"Fix the login bug on pagel.uz","priority":1,"client_id":"${local[1]}"}""",
                """POST /api/v1/tasks {"project":"me","text":"Learn the guitar","priority":3,"client_id":"${local[2]}"}""",
            ),
            calls.toList(),
        )
        // The dump follows its tasks' new ids, so Undo still finds them.
        assertEquals(listOf("t_srv1", "t_srv2", "t_srv3"), sweep(id).addedIds.split(','))
    }

    @Test fun undoOfAddBeforeSendingTakesEveryTaskBackAndPagelUzNeverHearsOfThem() = runBlocking {
        val id = readySweep()
        repo.add(id)!!
        val local = sweep(id).addedIds.split(',')
        repo.undoAdd(id)
        assertTrue(local.all { db.dao().taskOnce(it) == null })
        assertTrue(db.dao().waitingOutbox().isEmpty())
        assertEquals(SweepEntity.READY, sweep(id).state)
        sender.sendAll(account)
        assertTrue(calls.isEmpty())
    }

    @Test fun undoOfAddAfterSendingDeletesThemOnPagelUz() = runBlocking {
        val id = readySweep()
        repo.add(id)!!
        sender.sendAll(account)
        calls.clear()
        repo.undoAdd(id)
        now += 6_000
        sender.sendAll(account)
        assertEquals(listOf("t_srv1", "t_srv2", "t_srv3").map { """PATCH /api/v1/tasks/$it {"remove":true}""" }, calls.toList())
    }

    @Test fun aRowOverTwentyWordsIsNotAddedSoNothingIsLostOnTheWay() = runBlocking {
        val id = readySweep()
        repo.setRows(id, listOf(SweepRowDto("action", (1..21).joinToString(" ") { "word$it" }, "me")))
        assertNull(repo.add(id))
        assertEquals(SweepEntity.READY, sweep(id).state)
        assertEquals(0, db.dao().taskCount())
    }

    private suspend fun dayOfStars(): List<TaskEntity> {
        db.dao().upsertTasks(listOf(task("s1", star = true), task("s2", star = true, priority = 2), task("s3", star = true), task("c1", "humopedia")))
        return Rituals.stars(repo.openTasks.first())
    }

    @Test fun closeTheDayChangesRoomAtOnceThenSendsTheChangesAndTheShutdownEvent() = runBlocking {
        val stars = dayOfStars()
        repo.closeDay(stars, mapOf("s2" to Move.LATER, "s3" to Move.DROP), listOf("c1"))
        assertFalse(db.dao().taskOnce("s2")!!.star)
        assertEquals("dropped", db.dao().taskOnce("s3")!!.status)
        assertTrue(db.dao().taskOnce("c1")!!.star)
        assertTrue(db.dao().taskOnce("s1")!!.star)
        assertEquals("2026-10-28", repo.state.first().shutDay)
        now += 6_000
        assertEquals(SendResult.ALL_SENT, sender.sendAll(account))
        val ritual = calls.indexOfFirst { it.startsWith("POST /api/v1/rituals") }
        assertTrue(calls.indexOf("""PATCH /api/v1/tasks/s2 {"star":false}""") in 0 until ritual)
        assertTrue(calls.indexOf("""PATCH /api/v1/tasks/c1 {"star":true}""") in 0 until ritual)
        assertTrue("""PATCH /api/v1/tasks/s3 {"remove":true}""" in calls)
        assertTrue(calls[ritual].matches(Regex("""POST /api/v1/rituals \{"action":"shutdown","id":"sd_[0-9a-f]{16}","day":"2026-10-28","kept":1,"pushed":1,"dropped":1,"picked":1}""")))
    }

    /**
     * Boss 2026-10-28: "I closed the day but it shows tasks for today?! I should have an empty Today". Kept stars and
     * picks become tomorrow's: dated 2026-10-29, so Today hides them tonight; kept + picked are 3 at most.
     */
    @Test fun closeTheDayMakesTomorrowsStarsThreeAtMostAndTodayEmpty() = runBlocking {
        val stars = dayOfStars()
        db.dao().upsertTasks(listOf(task("c2", "me"), task("c3", "ababil"), task("c4", "labs")))
        repo.closeDay(stars, mapOf("s2" to Move.LATER, "s3" to Move.DROP), listOf("c1", "c2", "c3", "c4"))
        assertEquals(listOf(true, true, true, false, false), listOf("s1", "c1", "c2", "c3", "c4").map { db.dao().taskOnce(it)!!.star })
        assertEquals(listOf("2026-10-29", "2026-10-29", "2026-10-29"), listOf("s1", "c1", "c2").map { db.dao().taskOnce(it)!!.due })
        now += 6_000
        sender.sendAll(account)
        assertTrue("""PATCH /api/v1/tasks/s1 {"due":"2026-10-29"}""" in calls)
        assertTrue("""PATCH /api/v1/tasks/c1 {"due":"2026-10-29"}""" in calls)
        assertTrue(calls.single { it.startsWith("POST /api/v1/rituals") }.contains("\"kept\":1,\"pushed\":1,\"dropped\":1,\"picked\":2"))
    }

    /** Checker P5 #1: Edit text → a new dump → its own rows, never the old dump's (pagel.uz keeps rows by id for 24 h). */
    @Test fun anEditedDumpGetsItsOwnRowsNotTheOldOnes() = runBlocking {
        echoSweeps = true
        val first = repo.saveDraft(null, "call mom")
        val sent = repo.split(first)!!
        sender.sendAll(account)
        assertEquals(listOf("call mom"), sweep(sent).rowList().map { it.text })
        repo.backToText(sent)
        val edited = repo.saveDraft(sent, "call mom\npay rent")
        val again = repo.split(edited)!!
        assertTrue(again != sent)
        assertNull(db.rituals().sweepOnce(sent))
        sender.sendAll(account)
        assertEquals(listOf("call mom", "pay rent"), sweep(again).rowList().map { it.text })
        assertEquals(1, db.rituals().openSweep().first().let { if (it == null) 0 else 1 })
    }

    /** Checker P5 #2: two taps at once on "Add 3" make 3 tasks, not 6. */
    @Test fun addTwiceAtOnceAddsEachRowOnce() = runBlocking {
        val id = readySweep()
        val both = listOf(async(Dispatchers.Default) { repo.add(id) }, async(Dispatchers.Default) { repo.add(id) }).awaitAll()
        assertEquals(1, both.count { it != null })
        assertEquals(3, db.dao().waitingOutbox().count { it.op == OutboxEntity.ADD })
        sender.sendAll(account)
        assertEquals(3, calls.count { it.startsWith("POST /api/v1/tasks") })
        assertNull(repo.add(id))
    }

    /** Checker P5 #2: a double "Close" makes one set of changes and one shutdown event. */
    @Test fun closeTheDayTwiceAtOnceClosesOnce() = runBlocking {
        val stars = dayOfStars()
        val moves = mapOf("s2" to Move.LATER, "s3" to Move.DROP)
        val both = listOf(
            async(Dispatchers.Default) { repo.closeDay(stars, moves, listOf("c1"), "sd_token") },
            async(Dispatchers.Default) { repo.closeDay(stars, moves, listOf("c1"), "sd_token") },
        ).awaitAll()
        assertEquals(both[0], both[1])
        val ops = db.dao().waitingOutbox()
        assertEquals(1, ops.count { it.op == OutboxEntity.RITUAL })
        // later + drop + pick, and tomorrow's date for the kept star and the pick (Boss 2026-10-28: an empty Today).
        assertEquals(5, ops.count { it.op == OutboxEntity.PATCH })
        now += 6_000
        sender.sendAll(account)
        assertEquals(1, calls.count { it.startsWith("POST /api/v1/rituals") })
    }

    /** Checker P5 #2: a double "Finish review" adds each next step once. */
    @Test fun finishReviewTwiceAtOnceAddsOnce() = runBlocking {
        val both = listOf(
            async(Dispatchers.Default) { repo.finishReview(mapOf("ababil" to "Write the test plan"), emptyList(), "rv_token") },
            async(Dispatchers.Default) { repo.finishReview(mapOf("ababil" to "Write the test plan"), emptyList(), "rv_token") },
        ).awaitAll()
        assertEquals(both[0], both[1])
        sender.sendAll(account)
        assertEquals(1, calls.count { it.startsWith("POST /api/v1/tasks") })
        assertEquals(1, calls.count { it.startsWith("POST /api/v1/rituals") })
    }

    /** Checker P5 #10: a pagel.uz without the rituals (404) makes them wait, never refuses them. */
    @Test fun anOlderPagelUzWithoutTheCallsMakesThemWait() = runBlocking {
        sweepReply = MockResponse().setResponseCode(404).setBody("""{"error":"Unknown path."}""")
        val id = repo.saveDraft(null, "call mom")
        val sent = repo.split(id)!!
        assertEquals(SendResult.TRY_LATER, sender.sendAll(account))
        assertEquals(SweepEntity.WAITING, sweep(sent).state)
        assertNull(sweep(sent).message)
        assertEquals(listOf(OutboxEntity.SWEEP), db.dao().waitingOutbox().map { it.op })
    }

    @Test fun undoOfCloseTheDayBeforeSendingPutsEverythingBack() = runBlocking {
        val stars = dayOfStars()
        val closed = repo.closeDay(stars, mapOf("s2" to Move.LATER, "s3" to Move.DROP), listOf("c1"))
        repo.undo(closed)
        assertTrue(db.dao().taskOnce("s2")!!.star)
        assertEquals("open", db.dao().taskOnce("s3")!!.status)
        assertFalse(db.dao().taskOnce("c1")!!.star)
        // Tomorrow's date is taken back too: the kept star and the pick are as they were.
        assertNull(db.dao().taskOnce("c1")!!.due)
        assertNull(db.dao().taskOnce("s1")!!.due)
        assertNull(repo.state.first().shutDay)
        assertTrue(db.dao().waitingOutbox().isEmpty())
        now += 6_000
        sender.sendAll(account)
        assertTrue(calls.isEmpty())
    }

    @Test fun undoOfCloseTheDayAfterSendingSendsTheOpposite() = runBlocking {
        val stars = dayOfStars()
        val closed = repo.closeDay(stars, mapOf("s2" to Move.LATER, "s3" to Move.DROP), listOf("c1"))
        now += 6_000
        sender.sendAll(account)
        calls.clear()
        repo.undo(closed)
        sender.sendAll(account)
        assertTrue("""PATCH /api/v1/tasks/s2 {"star":true}""" in calls)
        assertTrue("""PATCH /api/v1/tasks/c1 {"star":false}""" in calls)
        assertTrue("""PATCH /api/v1/tasks/s3 {"restore":true}""" in calls)
    }

    @Test fun theReviewAddsNextStepsDropsSomedayAndLogsItself() = runBlocking {
        db.dao().upsertTasks(listOf(task("p3", priority = 3)))
        repo.finishReview(mapOf("ababil" to "  Write the   test plan ", "labs" to " "), listOf("p3"))
        assertEquals("2026-10-28", repo.state.first().lastReview)
        now += 6_000
        assertEquals(SendResult.ALL_SENT, sender.sendAll(account))
        assertTrue(calls.any { it.startsWith("""POST /api/v1/tasks {"project":"ababil","text":"Write the test plan","priority":1,"client_id":"local_""") })
        assertTrue("""PATCH /api/v1/tasks/p3 {"remove":true}""" in calls)
        assertTrue(calls.any { it.matches(Regex("""POST /api/v1/rituals \{"action":"review","id":"rv_[0-9a-f]{16}","day":"2026-10-28","dropped":1,"added":1}""")) })
        assertEquals(1, calls.count { it.startsWith("POST /api/v1/tasks") })
    }

    @Test fun undoOfTheReviewAfterSendingDeletesTheNewStep() = runBlocking {
        val closed = repo.finishReview(mapOf("ababil" to "Write the test plan"), emptyList())
        sender.sendAll(account)
        calls.clear()
        repo.undo(closed)
        now += 6_000
        sender.sendAll(account)
        assertEquals(listOf("""PATCH /api/v1/tasks/t_srv1 {"remove":true}"""), calls.toList())
        assertNull(repo.state.first().lastReview)
    }

    @Test fun aRefreshTakesPagelUzsViewButNeverStepsThePhoneBack() = runBlocking {
        db.dao().upsertTasks(listOf(task("s1", star = true)))
        repo.closeDay(Rituals.stars(repo.openTasks.first()), emptyMap(), emptyList())
        repo.refresh(account)
        val s = repo.state.first()
        // pagel.uz has not seen the phone's shutdown yet (shut_today false): the phone's own stays.
        assertEquals("2026-10-28", s.shutDay)
        assertEquals("2026-10-20", s.lastReview)
        assertEquals(listOf("labs"), s.stuckList().map { it.project })
        assertTrue(s.fetchedAt > 0)
    }

    @Test fun theEveningReminderIsOnAt2045UntilBossChangesIt() = runBlocking {
        assertTrue(repo.state.first().eveningOn)
        assertEquals("20:45", repo.state.first().eveningAt)
        repo.setEvening(true, "22:15")
        assertEquals("22:15", repo.state.first().eveningAt)
        repo.setEvening(false)
        assertFalse(repo.state.first().eveningOn)
        assertEquals("22:15", repo.state.first().eveningAt)
    }
}
