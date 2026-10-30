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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.db.SyncStatus
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.OutboxSender
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.data.upcoming.UpcomingRepository
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 4's data layer: a drop on another day, "Move all to today" and Completed's untick each answer in Room at once
 * and wait in the outbox as PATCHes sent in the order made (offline too); Undo takes a waiting one back; the older
 * finished tasks come from GET /api/v1/completed without touching a task with a change waiting.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UpcomingRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: PagelDatabase
    private val patches = CopyOnWriteArrayList<String>()
    @Volatile private var online = true
    private var kicks = 0
    /** 2026-10-28 13:00 in Tashkent (a Wednesday). */
    private val now = Instant.parse("2026-10-28T08:00:00Z").toEpochMilli()

    @Before fun setUp() {
        db = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (!online) return MockResponse().setResponseCode(503)
                return when {
                    request.method == "PATCH" && path.startsWith("/api/v1/tasks/") -> {
                        val body = request.body.readUtf8()
                        patches += path.substringAfterLast('/') + " " + body
                        MockResponse().setBody("""{"id":"x","status":"open","applied":["due","done"]}""")
                    }
                    request.method == "GET" && path.startsWith("/api/v1/completed") -> MockResponse().setBody(
                        """{"tasks":[{"id":"t_old","project":"me","text":"Renew the domain","status":"done","done_at":"2026-10-02T04:00:00.000Z","day":"2026-10-02","steps":[]},""" +
                            """{"id":"t_mine","project":"me","text":"Server copy","status":"done","done_at":"2026-10-20T04:00:00.000Z","day":"2026-10-20","steps":[]}],"before":null,"total":9}""",
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
    private val tasks by lazy { TaskRepository(db.dao(), { kicks++ }, clock = { now }) }
    private fun repo() = UpcomingRepository(db.dao(), api, tasks, clock = { now })
    private fun sender() = OutboxSender(api, db.dao(), clock = { now + 60_000 })

    private fun task(id: String, due: String?, status: String = "open", doneAt: String? = null, every: String? = null) = TaskEntity(
        id = id, project = "me", phase = "Now", sort = 1.0, text = id, title = null, status = status, priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = doneAt, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
    )

    @Test fun aDropOnAnotherDayMovesItInRoomAtOnceAndSendsTheDueKeepingTheTime() = runBlocking {
        db.dao().upsertTasks(listOf(task("t_a", "2026-10-29 10:00"), task("t_b", "2026-10-29")))
        val r = repo()
        r.reschedule("t_a", LocalDate.parse("2026-10-30"))
        r.reschedule("t_b", LocalDate.parse("2026-10-30"))
        assertEquals("2026-10-30 10:00", db.dao().taskOnce("t_a")!!.due)
        assertEquals(SyncStatus.PENDING, db.dao().taskOnce("t_a")!!.syncStatus)
        // The same day again: nothing to send.
        assertNull(r.reschedule("t_b", LocalDate.parse("2026-10-30")))
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(listOf("""t_a {"due":"2026-10-30 10:00"}""", """t_b {"due":"2026-10-30"}"""), patches)
    }

    /**
     * Checker P4 #1: a habit's due is its series. A drop of "Gym" (tue,thu,sun) on a Monday would put it on Monday and
     * take Thursday away; a late habit sent to today would undo today's tick on pagel.uz. Neither moves, nothing is sent.
     */
    @Test fun aHabitIsNeverRescheduledNeitherByADropNorByMoveAll() = runBlocking {
        db.dao().upsertTasks(listOf(task("t_gym", "2026-10-29 18:00", every = "tue,thu,sun"), task("t_late", "2026-10-26 07:30", every = "day"), task("t_1", "2026-10-25")))
        val r = repo()
        assertNull(r.reschedule("t_gym", LocalDate.parse("2026-11-02")))
        assertEquals("2026-10-29 18:00", db.dao().taskOnce("t_gym")!!.due)
        assertEquals(SyncStatus.SYNCED, db.dao().taskOnce("t_gym")!!.syncStatus)
        val moved = r.moveAllToToday(listOf("t_late", "t_1"))
        assertEquals(listOf("t_1"), moved.map { it.id })
        assertEquals("2026-10-26 07:30", db.dao().taskOnce("t_late")!!.due)
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(listOf("""t_1 {"due":"2026-10-28"}"""), patches)
    }

    @Test fun moveAllToTodayMovesEachOverdueAndUndoWhileWaitingPutsThemBack() = runBlocking {
        db.dao().upsertTasks(listOf(task("t_1", "2026-10-25"), task("t_2", "2026-10-27 09:00")))
        val r = repo()
        val moved = r.moveAllToToday(listOf("t_1", "t_2"))
        assertEquals(listOf("2026-10-28", "2026-10-28 09:00"), listOf(db.dao().taskOnce("t_1")!!.due, db.dao().taskOnce("t_2")!!.due))
        // Dated today now: both are on Today, as the web deals them.
        assertEquals(setOf("t_1", "t_2"), db.dao().todayTasks().first().map { it.id }.toSet())
        r.undoMoves(moved)
        assertEquals(listOf("2026-10-25", "2026-10-27 09:00"), listOf(db.dao().taskOnce("t_1")!!.due, db.dao().taskOnce("t_2")!!.due))
        assertTrue(db.dao().waitingOutbox().isEmpty())
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertTrue(patches.isEmpty())
    }

    @Test fun movesMadeOfflineReachPagelUzInTheOrderMade() = runBlocking {
        db.dao().upsertTasks(listOf(task("t_1", "2026-10-25"), task("t_a", "2026-10-29")))
        val r = repo()
        online = false
        r.reschedule("t_a", LocalDate.parse("2026-10-31"))
        r.moveAllToToday(listOf("t_1"))
        r.reschedule("t_a", LocalDate.parse("2026-11-01"))
        assertEquals(SendResult.TRY_LATER, sender().sendAll(account))
        assertTrue(patches.isEmpty())
        online = true
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(listOf("""t_a {"due":"2026-10-31"}""", """t_1 {"due":"2026-10-28"}""", """t_a {"due":"2026-11-01"}"""), patches)
    }

    @Test fun completedUntickReopensAtOnceAndSendsDoneFalse() = runBlocking {
        db.dao().upsertTasks(listOf(task("t_c", null, status = "done", doneAt = "2026-10-28T04:12:00.000Z")))
        val r = repo()
        val op = r.reopen("t_c")!!
        assertEquals("open", db.dao().taskOnce("t_c")!!.status)
        assertNull(db.dao().taskOnce("t_c")!!.doneAt)
        assertTrue(db.dao().completed().first().isEmpty())
        // Undo while it waits: done again with its time, and pagel.uz never hears of it.
        r.undoReopen("t_c", op)
        assertEquals("2026-10-28T04:12:00.000Z", db.dao().taskOnce("t_c")!!.doneAt)
        r.reopen("t_c")
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(listOf("""t_c {"done":false}"""), patches)
    }

    /**
     * Black-hole brief: "Clear done" empties Completed in Room at once; the removes reach pagel.uz after their hold, and
     * an Undo after that sends restore + done (pagel.uz's restore alone opens a task), so each comes back done with its
     * time. An Undo inside the hold takes them back and pagel.uz never hears of it.
     */
    @Test fun clearDoneEmptiesCompletedAtOnceAndUndoBringsEachBackDone() = runBlocking {
        db.dao().upsertTasks(listOf(task("t_1", null, status = "done", doneAt = "2026-10-28T04:12:00.000Z"), task("t_2", null, status = "done", doneAt = "2026-10-27T09:00:00.000Z")))
        val r = repo()
        val cleared = r.clearDone(listOf("t_1", "t_2"))
        assertEquals(listOf("t_1", "t_2"), cleared.map { it.id })
        assertTrue(db.dao().completed().first().isEmpty())
        assertEquals("dropped", db.dao().taskOnce("t_1")!!.status)
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(listOf("""t_1 {"remove":true}""", """t_2 {"remove":true}"""), patches)
        r.undoClear(cleared)
        assertEquals(setOf("t_1", "t_2"), db.dao().completed().first().map { it.id }.toSet())
        assertEquals("2026-10-28T04:12:00.000Z", db.dao().taskOnce("t_1")!!.doneAt)
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(listOf("""t_2 {"restore":true,"done":true}""", """t_1 {"restore":true,"done":true}"""), patches.drop(2))
        // Inside the hold: taken back on the phone, nothing more is sent.
        val again = r.clearDone(listOf("t_1"))
        r.undoClear(again)
        assertEquals("done", db.dao().taskOnce("t_1")!!.status)
        assertTrue(db.dao().waitingOutbox().isEmpty())
        assertEquals(4, patches.size)
    }

    @Test fun olderFinishedTasksComeFromCompletedButNeverOverAWaitingChange() = runBlocking {
        db.dao().upsertTasks(listOf(task("t_mine", null, status = "done", doneAt = "2026-10-20T04:00:00.000Z")))
        val r = repo()
        r.reopen("t_mine")
        // pagel.uz's count of all finished tasks comes with the page (Completed's header shows it, checker P4 #9).
        assertEquals(Outcome.Ok(9), r.refreshCompleted(account))
        assertEquals("Renew the domain", db.dao().taskOnce("t_old")!!.text)
        assertEquals("done", db.dao().taskOnce("t_old")!!.status)
        // Reopened here and still waiting: the phone's copy stays.
        assertEquals("open", db.dao().taskOnce("t_mine")!!.status)
        assertEquals(listOf("t_old"), db.dao().completed().first().map { it.id })
    }
}
