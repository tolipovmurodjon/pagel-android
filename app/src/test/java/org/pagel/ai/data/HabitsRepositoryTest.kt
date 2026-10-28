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
    /** Runs on the server's thread while it answers GET /habits (a tick made while the GET is on its way). */
    @Volatile private var onHabitsGet: (() -> Unit)? = null
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
                        // The reply is read before the hook's tick lands: what a slow GET sees (checker #8).
                        MockResponse().also { onHabitsGet?.invoke(); onHabitsGet = null }.setBody("""{"day":"2026-10-28","done_today":[],"ticks":[{"day":"2026-10-27","id":"t_h"},{"day":"2026-10-26","id":"t_h"},{"day":"2026-10-27","id":"t_w"}]}""")
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
        assertEquals("""{"set":{"done":true,"day":"2026-10-28","if_due":"2026-10-28 07:30"},"was":{"due":"2026-10-28 07:30","untick":"2026-10-28"}}""", op.body)
        assertEquals(1, kicks)
        assertEquals(1, plans)
        // Ticked already today (the notification's Done after the app's tick): nothing more happens.
        assertNull(repo().tick("t_h"))
        assertEquals(1, db.dao().waitingOutbox().size)
    }

    /** Checker 2026-10-28 #1: missed Tuesday, ticked Wednesday → due Thursday (it was Wednesday, late forever after). */
    @Test fun aLateHabitTickedMovesPastToday() = runBlocking {
        db.dao().upsertTasks(listOf(habit("t_h", "Drink water", "2026-10-27 08:00")))
        repo().tick("t_h")
        assertEquals("2026-10-29 08:00", db.dao().taskOnce("t_h")!!.due)
        // Still on Wednesday: a second tick (the notification's Done) is refused, nothing more is queued (#3).
        assertNull(repo().tick("t_h"))
        assertEquals(1, db.dao().waitingOutbox().size)
    }

    /** Checker #3: a habit already ticked today (the web's tick came with /habits) is not ticked again. */
    @Test fun aHabitTickedTodayElsewhereIsNotTickedAgain() = runBlocking {
        seed()
        db.dao().addHabitTick(HabitTickEntity("2026-10-28", "t_h"))
        assertNull(repo().tick("t_h"))
        assertTrue(db.dao().waitingOutbox().isEmpty())
    }

    /**
     * Checker #2: ticked at 23:50 on Tuesday on the subway, sent at 00:10 on Wednesday. The PATCH says the day it was
     * made and the date it moved from, so pagel.uz counts Tuesday and moves the habit once.
     */
    @Test fun anOfflineTickSentAfterMidnightCountsForItsOwnDay() = runBlocking {
        now = Instant.parse("2026-10-27T18:50:00Z").toEpochMilli() // Tue 23:50 Tashkent
        db.dao().upsertTasks(listOf(habit("t_h", "Stretch for ten minutes", "2026-10-27 07:30")))
        repo().tick("t_h")
        assertEquals("2026-10-28 07:30", db.dao().taskOnce("t_h")!!.due)
        now = Instant.parse("2026-10-27T19:10:00Z").toEpochMilli() // Wed 00:10 Tashkent
        assertEquals(SendResult.ALL_SENT, sender().sendAll(account))
        assertEquals(listOf("""t_h {"done":true,"day":"2026-10-27","if_due":"2026-10-27 07:30"}"""), patches)
    }

    /**
     * Checker #5 (walk 35): while the ring fills, the habit is already "done" in the header and is not "Coming up",
     * even in the frame where Room's habits moved and its ticks have not been read yet.
     */
    @Test fun aHabitBeingTickedCountsAsDoneAndIsNotComingUp() = runBlocking {
        seed()
        repo().tick("t_h")
        val half = habitsDay(db.dao().habitsOnce(), emptyList(), "2026-10-28", finishing = setOf("t_h"))
        assertEquals(1, half.doneCount)
        assertEquals(1, half.left)
        assertTrue(half.coming.isEmpty())
        assertEquals(listOf("Morning", "Evening"), half.parts.map { it.first.label })
    }

    /** Checker #8: a GET that left before a tick landed must not erase that tick when it answers. */
    @Test fun aRefreshThatLeftBeforeATickKeepsTheTick() = runBlocking {
        seed()
        val r = repo()
        onHabitsGet = { now += 1_000; runBlocking { r.tick("t_h"); sender().sendAll(account) } }
        r.refresh(account)
        assertEquals(listOf("2026-10-28"), db.dao().habitTicksOf("t_h").map { it.day })
        assertTrue(db.dao().waitingOutbox().isEmpty())
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
        assertEquals(listOf("""t_h {"done":true,"day":"2026-10-28","if_due":"2026-10-28 07:30"}"""), patches)
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
        assertEquals(listOf("""t_h {"done":true,"day":"2026-10-28","if_due":"2026-10-28 07:30"}""", """t_h {"due":"2026-10-28 07:30"}"""), patches)
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
