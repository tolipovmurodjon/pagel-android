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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.db.SyncStatus
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.data.habits.habitsDay
import org.pagel.ai.data.today.OutboxSender
import org.pagel.ai.data.today.SendResult
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 3 data layer: a habit tick moves its date at once by the web's repeat rule and counts today, waits in the
 * outbox as PATCH {done: true}; Undo takes it back while it waits, or sends the old date after; a refusal rolls back
 * both the date and the day; pagel.uz's ticks never overwrite a habit with a change still waiting.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HabitsRepositoryTest {
    private val server = MockWebServer()
    private lateinit var db: PagelDatabase
    private val patches = CopyOnWriteArrayList<String>()
    @Volatile private var patchCode = 200
    private var kicks = 0
    private var plans = 0
    /** 2026-10-28 13:00 in Tashkent. */
    private var now = Instant.parse("2026-10-28T08:00:00Z").toEpochMilli()

    @Before fun setUp() {
        db = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "PATCH" && path.startsWith("/api/v1/tasks/") -> {
                        patches += path.substringAfterLast('/') + " " + request.body.readUtf8()
                        if (patchCode == 200) MockResponse().setBody("""{"id":"t_h","status":"open","applied":["done"]}""")
                        else MockResponse().setResponseCode(patchCode).setBody("""{"error":"No such task."}""")
                    }
                    request.method == "GET" && path.startsWith("/api/v1/habits") ->
                        MockResponse().setBody("""{"day":"2026-10-28","done_today":[],"ticks":[{"day":"2026-10-27","id":"t_h"},{"day":"2026-10-26","id":"t_h"},{"day":"2026-10-27","id":"t_w"}]}""")
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
    private fun repo() = HabitsRepository(db.dao(), api, { kicks++ }, { plans++ }, clock = { now })
    private fun sender() = OutboxSender(api, db.dao(), clock = { now })

    private fun habit(id: String, text: String, due: String?, every: String = "day") = TaskEntity(
        id = id, project = "me", phase = "Now", sort = 1.0, text = text, title = null, status = "open", priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
    )

    private suspend fun seed() = db.dao().upsertTasks(listOf(habit("t_h", "Stretch for ten minutes", "2026-10-28 07:30"), habit("t_w", "Wind down", "2026-10-28 21:30", "tue,thu,sun")))

    @Test fun aTickMovesTheDateAtOnceCountsTodayAndWaitsAsDoneTrue() = runBlocking {
        seed()
        val t = repo().tick("t_h")!!
        val row = db.dao().taskOnce("t_h")!!
        assertEquals("2026-10-29 07:30", row.due)
        assertEquals(SyncStatus.PENDING, row.syncStatus)
        assertEquals(listOf(HabitTickEntity("2026-10-28", "t_h")), db.dao().habitTicksOf("t_h"))
        assertEquals("2026-10-28 07:30", t.was)
        val op = db.dao().waitingOutbox().single()
        assertEquals("""{"set":{"done":true},"was":{"due":"2026-10-28 07:30","untick":"2026-10-28"}}""", op.body)
        assertEquals(1, kicks)
        assertEquals(1, plans)
        // Ticked already today (the notification's Done after the app's tick): nothing more happens.
        assertNull(repo().tick("t_h"))
        assertEquals(1, db.dao().waitingOutbox().size)
    }

    @Test fun aSetOfDaysMovesToTheNextDayInTheSet() = runBlocking {
        seed()
        // Wednesday 28 → "tue,thu,sun" → Thursday 29.
        repo().tick("t_w")
        assertEquals("2026-10-29 21:30", db.dao().taskOnce("t_w")!!.due)
    }

    @Test fun sentAsDoneTrueThenTheServerCopyWinsAgain() = runBlocking {
        seed()
        repo().tick("t_h")
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(listOf("""t_h {"done":true}"""), patches)
        assertTrue(db.dao().waitingOutbox().isEmpty())
        assertEquals(SyncStatus.SYNCED, db.dao().taskOnce("t_h")!!.syncStatus)
    }

    @Test fun undoWhileWaitingTakesTheTickBackAndPagelUzNeverHearsOfIt() = runBlocking {
        seed()
        val r = repo()
        val t = r.tick("t_h")!!
        r.undo("t_h", t)
        assertEquals("2026-10-28 07:30", db.dao().taskOnce("t_h")!!.due)
        assertTrue(db.dao().habitTicksOf("t_h").isEmpty())
        assertTrue(db.dao().waitingOutbox().isEmpty())
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertTrue(patches.isEmpty())
    }

    @Test fun undoAfterItWasSentSendsTheOldDateBackAsTheWebDoes() = runBlocking {
        seed()
        val r = repo()
        val t = r.tick("t_h")!!
        sender().sendAll(account)
        r.undo("t_h", t)
        assertEquals("2026-10-28 07:30", db.dao().taskOnce("t_h")!!.due)
        assertTrue(db.dao().habitTicksOf("t_h").isEmpty())
        sender().sendAll(account)
        assertEquals(listOf("""t_h {"done":true}""", """t_h {"due":"2026-10-28 07:30"}"""), patches)
    }

    @Test fun aRefusedTickRollsBackTheDateAndTheDay() = runBlocking {
        seed()
        patchCode = 404
        repo().tick("t_h")
        sender().sendAll(account)
        assertEquals("2026-10-28 07:30", db.dao().taskOnce("t_h")!!.due)
        assertTrue(db.dao().habitTicksOf("t_h").isEmpty())
        assertNotNull(db.dao().refusedOutbox().first().singleOrNull())
    }

    @Test fun refreshBringsTheServersTicksButKeepsAWaitingOne() = runBlocking {
        seed()
        val r = repo()
        r.tick("t_h")
        r.refresh(account)
        // t_h has a tick waiting: the phone's day stays, the server's older ones for it wait for the next refresh.
        assertEquals(listOf("2026-10-28"), db.dao().habitTicksOf("t_h").map { it.day })
        assertEquals(listOf("2026-10-27"), db.dao().habitTicksOf("t_w").map { it.day })
        sender().sendAll(account)
        r.refresh(account)
        assertEquals(setOf("2026-10-27", "2026-10-26"), db.dao().habitTicksOf("t_h").map { it.day }.toSet())
    }

    @Test fun theDayViewGroupsByPartAndCountsStreaks() = runBlocking {
        seed()
        db.dao().addHabitTicks(listOf(HabitTickEntity("2026-10-27", "t_h"), HabitTickEntity("2026-10-26", "t_h")))
        val habits = db.dao().habitsOnce()
        val before = habitsDay(habits, db.dao().habitTicksOf("t_h"), "2026-10-28")
        assertEquals(listOf("Morning", "Evening"), before.parts.map { it.first.label })
        assertEquals(2, before.parts[0].second.single().streak)
        assertEquals(0, before.doneCount)
        assertEquals(2, before.left)
        repo().tick("t_h")
        val after = habitsDay(db.dao().habitsOnce(), db.dao().habitTicksOf("t_h"), "2026-10-28")
        assertEquals(listOf("Evening"), after.parts.map { it.first.label })
        assertEquals(3, after.done.single().streak)
        assertEquals(1, after.doneCount)
        assertEquals(1, after.left)
        // While its ring fills it keeps its row, then moves to Done today.
        val folding = habitsDay(db.dao().habitsOnce(), db.dao().habitTicksOf("t_h"), "2026-10-28", finishing = setOf("t_h"))
        assertEquals(listOf("Morning", "Evening"), folding.parts.map { it.first.label })
        assertTrue(folding.done.isEmpty())
    }
}
