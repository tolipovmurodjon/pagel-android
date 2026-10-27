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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.sync.SyncEngine
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.OutboxSender
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.data.today.TodayRepository
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The task panel's data layer (Phase 1): every edit answers in Room at once and waits in the outbox as one PATCH;
 * an older server (no `applied`) keeps new fields waiting, never rolled back; a refusal rolls back exactly what changed;
 * a delete waits 5 s for Undo; a sync never overwrites a waiting edit.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TaskRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: PagelDatabase
    private val patches = CopyOnWriteArrayList<String>()
    @Volatile private var patchCode = 200
    @Volatile private var newServer = true
    private var kicks = 0
    /** 2026-10-27 11:00 in Tashkent. */
    private val t0 = Instant.parse("2026-10-27T06:00:00Z").toEpochMilli()

    private val sync = """{"cursor":5,"full":true,"projects":[{"name":"ababil","title":"ABABIL","color":null,"sort":0,"open":2}],"tasks":[
        {"id":"t_a","project":"ababil","phase":"Phase 1","phase_sort":0,"phase_now":true,"sort":1,"text":"Measure TAU","title":null,"status":"open","priority":1,"star":true,"due":null,"every":null,"done_when":null,"done_at":null,"updated_at":"2026-10-27T05:00:00Z","version":3,
         "steps":[{"text":"Open the log","title":null,"done":false},{"text":"Export the rate","title":null,"done":true}]},
        {"id":"t_b","project":"ababil","phase":"Phase 1","phase_sort":0,"phase_now":true,"sort":2,"text":"Order props","title":null,"status":"open","priority":2,"star":false,"due":"2026-10-27","every":null,"done_when":null,"done_at":null,"updated_at":"2026-10-27T05:00:00Z","version":1,"steps":[]}
        ],"removed":[]}"""

    @Before fun setUp() {
        db = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/v1/sync") -> MockResponse().setBody(sync)
                    path == "/api/v1/today" -> MockResponse().setBody("""{"day":"2026-10-27","shown_at_once":3,"tasks":[{"id":"t_a"},{"id":"t_b"}]}""")
                    request.method == "PATCH" -> {
                        val body = request.body.readUtf8()
                        patches += "${path.removePrefix("/api/v1/tasks/")} $body"
                        val keys = Regex("\"(\\w+)\":").findAll(body).map { it.groupValues[1] }.filter { it != "kind" && it != "reason" }.toList()
                        when {
                            patchCode != 200 -> MockResponse().setResponseCode(patchCode).setBody("""{"error":"That task is gone. Refresh and try again."}""")
                            newServer -> MockResponse().setBody("""{"id":"x","status":"open","applied":[${keys.joinToString(",") { "\"$it\"" }}]}""")
                            else -> MockResponse().setBody("""{"id":"x","status":"open"}""")
                        }
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
    private fun tasks() = TaskRepository(db.dao(), { kicks++ }, clock = { t0 })
    private fun sender(at: Long = t0 + 10_000) = OutboxSender(api, db.dao(), clock = { at })
    private suspend fun task(id: String) = db.dao().taskOnce(id)!!
    private suspend fun todayIds() = db.dao().todayTasks().first().map { it.id }
    private suspend fun synced() { SyncEngine(api, db.dao()).syncOnce(account) }

    @Test fun aTitleOnAnOlderServerWaitsAndIsNeverRolledBack() = runBlocking {
        synced()
        newServer = false
        tasks().title("t_a", "Find TAU")
        assertEquals("Find TAU", task("t_a").title)
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(listOf("""t_a {"title":"Find TAU"}"""), patches.toList())
        val op = db.dao().waitingOutbox().single()
        assertTrue("kept, marked as waiting for the server", op.waitsForServer)
        assertEquals(1, db.dao().heldCount("t_a").first())
        assertEquals("Find TAU", task("t_a").title)
        assertEquals("pending", task("t_a").syncStatus)
        // pagel.uz is updated: the next pass names the field and the op leaves.
        newServer = true
        sender().sendAll(account)
        assertTrue(db.dao().waitingOutbox().isEmpty())
        assertEquals("synced", task("t_a").syncStatus)
    }

    @Test fun aTitleOnTheNewServerIsSentAndDone() = runBlocking {
        synced()
        tasks().title("t_a", "  Find   TAU  ")
        sender().sendAll(account)
        assertEquals(listOf("""t_a {"title":"Find TAU"}"""), patches.toList())
        assertTrue(db.dao().waitingOutbox().isEmpty())
        assertEquals("synced", task("t_a").syncStatus)
    }

    @Test fun stepsGoWithTheirTicksAsOneList() = runBlocking {
        synced()
        tasks().steps("t_a", listOf("Open the log", "Export the rate", "Write it down"), listOf(0, 1))
        val local = db.dao().stepsOnce("t_a")
        assertEquals(listOf("Open the log" to true, "Export the rate" to true, "Write it down" to false), local.map { it.text to it.done })
        sender().sendAll(account)
        assertEquals(listOf("""t_a {"steps":["Open the log","Export the rate","Write it down"],"ticked":[0,1]}"""), patches.toList())
    }

    @Test fun aLaterDateTakesTheTaskOffTodayAndARefusalPutsItBack() = runBlocking {
        synced()
        assertEquals(listOf("t_a", "t_b"), todayIds())
        tasks().due("t_b", "2026-11-05")
        assertEquals(listOf("t_a"), todayIds())
        assertEquals("2026-11-05", task("t_b").due)
        patchCode = 404
        sender().sendAll(account)
        assertEquals("2026-10-27", task("t_b").due)
        assertTrue("back on Today", "t_b" in todayIds())
        assertEquals("That task is gone. Refresh and try again.", db.dao().refusedOutbox().first().single().refused)
    }

    @Test fun aRefusedPriorityRollsBackOnlyThatField() = runBlocking {
        synced()
        val r = tasks()
        r.priority("t_a", 0)
        r.title("t_a", "Find TAU")
        patchCode = 400
        sender().sendAll(account)
        assertEquals(1, task("t_a").priority)
        assertNull(task("t_a").title)
        assertEquals(2, db.dao().refusedOutbox().first().size)
    }

    @Test fun wontDoRefusesAnExcuseOnThePhoneAndSendsTwoOps() = runBlocking {
        synced()
        val r = tasks()
        assertNotNull(r.notDoing("t_a", "waiting", "too busy today"))
        assertTrue("nothing saved for an excuse", db.dao().waitingOutbox().isEmpty())
        assertNull(r.notDoing("t_a", "someday", "The airframe comes first this month"))
        assertEquals(3, task("t_a").priority)
        assertFalse(task("t_a").star)
        assertFalse("t_a" in todayIds())
        sender().sendAll(account)
        assertEquals(
            listOf("""t_a {"star":false,"priority":3}""", """t_a {"not_doing":{"kind":"someday","reason":"The airframe comes first this month"}}"""),
            patches.toList(),
        )
    }

    @Test fun deleteThenUndoInsideFiveSecondsNeverReachesTheServer() = runBlocking {
        synced()
        val r = tasks()
        val op = r.delete("t_a")!!
        assertEquals("dropped", task("t_a").status)
        assertFalse("t_a" in todayIds())
        assertEquals(SendResult.TRY_LATER, sender(at = t0 + 1_000).sendAll(account))
        assertTrue(patches.isEmpty())
        r.undoDelete("t_a", op)
        assertEquals("open", task("t_a").status)
        assertTrue("t_a" in todayIds())
        assertEquals("synced", task("t_a").syncStatus)
        assertEquals(SendResult.ALL_SENT, sender(at = t0 + 10_000).sendAll(account))
        assertTrue("pagel.uz never saw it", patches.isEmpty())
    }

    @Test fun deleteSentThenUndoSendsARestore() = runBlocking {
        synced()
        val r = tasks()
        val op = r.delete("t_a")!!
        assertEquals(SendResult.ALL_SENT, sender(at = t0 + 6_000).sendAll(account))
        r.undoDelete("t_a", op)
        assertEquals("open", task("t_a").status)
        sender(at = t0 + 20_000).sendAll(account)
        assertEquals(listOf("""t_a {"remove":true}""", """t_a {"restore":true}"""), patches.toList())
    }

    @Test fun offlineEditsWaitThenGoInOrder() = runBlocking {
        synced()
        val r = tasks()
        r.priority("t_b", 0)
        r.doneWhen("t_b", "Done when: props are in the drawer")
        patchCode = 503
        assertEquals(SendResult.TRY_LATER, sender().sendAll(account))
        assertEquals(2, db.dao().waitingOutbox().size)
        patchCode = 200
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(
            listOf("""t_b {"priority":0}""", """t_b {"priority":0}""", """t_b {"done_when":"props are in the drawer"}"""),
            patches.toList(),
        )
        assertTrue(db.dao().waitingOutbox().isEmpty())
    }

    @Test fun aSyncNeverOverwritesAWaitingEditOrItsSteps() = runBlocking {
        synced()
        val r = tasks()
        r.priority("t_a", 0)
        r.steps("t_a", listOf("Open the log"), listOf(0))
        synced()
        assertEquals(0, task("t_a").priority)
        assertEquals(listOf("Open the log" to true), db.dao().stepsOnce("t_a").map { it.text to it.done })
    }

    @Test fun undoOfATickKeepsAPanelEditWaiting() = runBlocking {
        synced()
        tasks().title("t_a", "Find TAU")
        val today = TodayRepository(db.dao(), { kicks++ }, clock = { t0 })
        today.tick("t_a", done = true)
        today.tick("t_a", done = false)
        val ops = db.dao().waitingOutbox()
        assertEquals(listOf("patch"), ops.map { it.op })
    }

    @Test fun aRepeatGivesADateAndMovesTheTaskToHabits() = runBlocking {
        synced()
        tasks().every("t_a", "week")
        assertEquals("2026-10-27", task("t_a").due)
        assertFalse("t_a" in todayIds())
        sender().sendAll(account)
        assertEquals(listOf("""t_a {"every":"week"}"""), patches.toList())
    }

    @Test fun theProjectListPutsNowFirst() = runBlocking {
        synced()
        val open = db.dao().openTasks("ababil").first()
        assertEquals(listOf("t_a", "t_b"), open.map { it.id })
        assertTrue(open.all { it.phaseNow })
    }
}
