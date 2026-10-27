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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.sync.SyncEngine
import org.pagel.ai.data.today.OutboxSender
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.data.today.TodayRepository
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 2 data layer: Today in the server's order, a tick that answers at once and waits in the outbox, the conflict
 * rule (a waiting tick is not overwritten by a sync), and the three ways a send can end.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TodayRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: PagelDatabase
    private val patches = CopyOnWriteArrayList<String>()
    @Volatile private var patchCode = 200
    private val posts = CopyOnWriteArrayList<String>()
    @Volatile private var postCode = 201
    private var kicks = 0

    private fun task(id: String, status: String) =
        """{"id":"$id","project":"pagel","phase":"Now","sort":1,"text":"Task $id","title":null,"status":"$status","priority":1,"star":true,"due":null,"every":null,"done_when":null,"done_at":null,"updated_at":"2026-10-27T12:00:00Z","version":1,"steps":[]}"""

    @Volatile private var serverStatusOfA = "open"
    private val full get() = """{"cursor":5,"full":true,"projects":[{"name":"pagel","title":"PAGEL","color":null,"sort":0,"open":2}],"tasks":[${task("t_a", serverStatusOfA)},${task("t_b", "open")}],"removed":[]}"""

    @Before fun setUp() {
        db = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/v1/sync") -> MockResponse().setBody(full)
                    // pagel.uz drops a ticked task from Today (it unstars it), as task-store.ts does.
                    path == "/api/v1/today" -> MockResponse().setBody(if (serverStatusOfA == "done") """{"day":"2026-10-27","shown_at_once":3,"tasks":[{"id":"t_b"}]}"""
                        else """{"day":"2026-10-27","shown_at_once":3,"tasks":[{"id":"t_b"},{"id":"t_a"}]}""")
                    request.method == "POST" && path == "/api/v1/tasks" -> {
                        posts += request.body.readUtf8()
                        if (postCode == 201) MockResponse().setResponseCode(201).setBody("""{"id":"t_new","project":"me","text":"Call Aziz","status":"open"}""")
                        else MockResponse().setResponseCode(postCode).setBody("""{"error":"One short task line, 20 words at most."}""")
                    }
                    request.method == "PATCH" -> {
                        patches += "$path ${request.body.readUtf8()}"
                        if (patchCode == 200) MockResponse().setBody("""{"id":"t_a","status":"done"}""")
                        else MockResponse().setResponseCode(patchCode).setBody("""{"error":"That task was dropped on the web."}""")
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

    private val account get() = Account(server.url("/").toString(), "d_1", "Test phone", "tok")
    private val api get() = PagelApi(OkHttpClient(), Dispatchers.IO)
    private fun repo() = TodayRepository(db.dao(), { kicks++ }, clock = { 1_000L })
    private suspend fun statusOf(id: String) = db.dao().taskOnce(id)!!

    @Test fun todayKeepsTheServersOrder() = runBlocking {
        SyncEngine(api, db.dao()).syncOnce(account)
        assertEquals(listOf("t_b", "t_a"), repo().today.first().map { it.id })
    }

    @Test fun aTickAnswersAtOnceAndWaitsInTheOutbox() = runBlocking {
        SyncEngine(api, db.dao()).syncOnce(account)
        repo().tick("t_a", done = true)
        assertEquals("done", statusOf("t_a").status)
        assertEquals("pending", statusOf("t_a").syncStatus)
        assertEquals(1, db.dao().waitingOutbox().size)
        assertEquals(1, kicks)
        assertTrue("nothing sent yet", patches.isEmpty())
    }

    @Test fun aSyncDoesNotOverwriteAWaitingTick() = runBlocking {
        val sync = SyncEngine(api, db.dao())
        sync.syncOnce(account)
        repo().tick("t_a", done = true)
        sync.syncOnce(account) // pagel.uz still says open: the phone's waiting tick must survive
        assertEquals("done", statusOf("t_a").status)
    }

    @Test fun aSentTickLeavesTheOutboxAndTheServerWinsAgain() = runBlocking {
        val sync = SyncEngine(api, db.dao())
        sync.syncOnce(account)
        repo().tick("t_a", done = true)
        assertEquals(SendResult.ALL_SENT, OutboxSender(api, db.dao()).sendAll(account))
        assertEquals(listOf("/api/v1/tasks/t_a {\"done\":true}"), patches.toList())
        assertTrue(db.dao().waitingOutbox().isEmpty())
        assertEquals("synced", statusOf("t_a").syncStatus)
        serverStatusOfA = "done"
        sync.syncOnce(account)
        assertEquals("done", statusOf("t_a").status)
    }

    @Test fun aRefusedTickRollsBackAndIsShownNotDropped() = runBlocking {
        SyncEngine(api, db.dao()).syncOnce(account)
        val r = repo()
        r.tick("t_a", done = true)
        patchCode = 409
        assertEquals(SendResult.ALL_SENT, OutboxSender(api, db.dao(), clock = { 2_000L }).sendAll(account))
        assertEquals("open", statusOf("t_a").status)
        val refused = r.refused.first()
        assertEquals(1, refused.size)
        assertEquals("That task was dropped on the web.", refused[0].refused)
        r.acknowledge(refused[0])
        assertTrue(r.refused.first().isEmpty())
        assertEquals("synced", statusOf("t_a").syncStatus)
    }

    @Test fun aServerErrorKeepsTheTickForLater() = runBlocking {
        SyncEngine(api, db.dao()).syncOnce(account)
        repo().tick("t_a", done = true)
        patchCode = 503
        assertEquals(SendResult.TRY_LATER, OutboxSender(api, db.dao()).sendAll(account))
        assertEquals(1, db.dao().waitingOutbox().size)
        assertEquals("done", statusOf("t_a").status)
    }

    @Test fun undoBeforeSendingTakesTheTickBack() = runBlocking {
        SyncEngine(api, db.dao()).syncOnce(account)
        val r = repo()
        r.tick("t_a", done = true)
        r.tick("t_a", done = false)
        assertTrue(db.dao().waitingOutbox().isEmpty())
        assertEquals("open", statusOf("t_a").status)
        assertEquals("synced", statusOf("t_a").syncStatus)
        assertEquals(SendResult.ALL_SENT, OutboxSender(api, db.dao()).sendAll(account))
        assertTrue("pagel.uz never saw it", patches.isEmpty())
    }

    private suspend fun todayIds() = db.dao().todayTasks().first().map { it.id }

    @Test fun anAddedTaskShowsAtOnceAndGetsItsRealIdWhenSent() = runBlocking {
        SyncEngine(api, db.dao()).syncOnce(account)
        val id = repo().add("Call Aziz", "me", priority = 0, due = "2026-10-27 15:00", every = null, onToday = true)
        assertTrue(id.startsWith("local_"))
        assertEquals(listOf("t_b", "t_a", id), todayIds())
        assertEquals(1, kicks)
        assertEquals(SendResult.ALL_SENT, OutboxSender(api, db.dao()).sendAll(account))
        assertEquals(listOf("""{"project":"me","text":"Call Aziz","priority":0,"due":"2026-10-27 15:00"}"""), posts.toList())
        assertEquals(listOf("t_b", "t_a", "t_new"), todayIds())
        assertTrue(db.dao().waitingOutbox().isEmpty())
        assertEquals("synced", statusOf("t_new").syncStatus)
    }

    @Test fun aSyncBeforeSendingKeepsTheNewTaskOnToday() = runBlocking {
        val sync = SyncEngine(api, db.dao())
        sync.syncOnce(account)
        val id = repo().add("Call Aziz", "me", 2, "2026-10-27", null, onToday = true)
        sync.syncOnce(account)
        assertEquals(listOf("t_b", "t_a", id), todayIds())
    }

    @Test fun aTickOnATaskNotYetSentFollowsItsNewId() = runBlocking {
        SyncEngine(api, db.dao()).syncOnce(account)
        val r = repo()
        val id = r.add("Call Aziz", "me", 2, "2026-10-27", null, onToday = true)
        r.tick(id, done = true)
        r.tick(id, done = false) // undo takes back the tick, never the add
        r.tick(id, done = true)
        assertEquals(SendResult.ALL_SENT, OutboxSender(api, db.dao()).sendAll(account))
        assertEquals(1, posts.size)
        assertEquals(listOf("/api/v1/tasks/t_new {\"done\":true}"), patches.toList())
    }

    @Test fun aRefusedAddLeavesThePhoneAndIsShown() = runBlocking {
        SyncEngine(api, db.dao()).syncOnce(account)
        val r = repo()
        val id = r.add("Call Aziz", "me", 2, "2026-10-27", null, onToday = true)
        postCode = 400
        assertEquals(SendResult.ALL_SENT, OutboxSender(api, db.dao()).sendAll(account))
        assertEquals(listOf("t_b", "t_a"), todayIds())
        assertEquals(null, db.dao().taskOnce(id))
        assertEquals("One short task line, 20 words at most.", r.refused.first().single().refused)
    }

    @Test fun aTaskForAnotherDayIsSavedButNotOnToday() = runBlocking {
        SyncEngine(api, db.dao()).syncOnce(account)
        val id = repo().add("Plan sprint", "me", 2, "2026-11-02", null, onToday = false)
        assertEquals(listOf("t_b", "t_a"), todayIds())
        assertEquals("Plan sprint", db.dao().taskOnce(id)!!.text)
    }

    @Test fun undoAfterTheTickWasSentBringsTheTaskBackWithItsStar() = runBlocking {
        val sync = SyncEngine(api, db.dao())
        sync.syncOnce(account)
        val r = repo()
        r.tick("t_a", done = true)
        assertEquals(SendResult.ALL_SENT, OutboxSender(api, db.dao()).sendAll(account))
        serverStatusOfA = "done"
        sync.syncOnce(account) // pagel.uz now says done and no longer deals it on Today
        assertEquals(listOf("t_b"), todayIds())
        r.tick("t_a", done = false) // Boss taps Undo
        assertEquals(listOf("t_b", "t_a"), todayIds())
        assertEquals("open", statusOf("t_a").status)
        assertEquals(SendResult.ALL_SENT, OutboxSender(api, db.dao()).sendAll(account))
        assertEquals("/api/v1/tasks/t_a {\"done\":false,\"star\":true}", patches.last())
    }
}
