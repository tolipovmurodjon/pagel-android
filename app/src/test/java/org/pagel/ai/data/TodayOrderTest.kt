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
import org.pagel.ai.data.api.TinyBody
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.sync.SyncEngine
import org.pagel.ai.data.sync.microOf
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.OutboxSender
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.ui.today.orderToday
import org.pagel.ai.ui.today.todayKeys
import org.pagel.ai.ui.today.todaySortAfterMove
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Core round 4's data: Today's own order is one PATCH of one row (`today_sort` between its neighbours) and its Undo is a
 * real inverse write; micro-steps are a task's own list (PATCH {micro}, back through /sync); the events are fixed words.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TodayOrderTest {
    private val server = MockWebServer()
    private lateinit var db: PagelDatabase
    private val patches = CopyOnWriteArrayList<String>()
    private val posts = CopyOnWriteArrayList<String>()
    @Volatile private var micro = "[]"
    /** 2026-10-30 11:00 in Tashkent. */
    private val t0 = Instant.parse("2026-10-30T06:00:00Z").toEpochMilli()
    private val day = "2026-10-30"

    /** What pagel.uz says of a task beyond the basics (its status, today_sort…), as JSON fields after a comma. */
    private val said = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val status = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun task(id: String, sort: Int, extra: String = "") =
        """{"id":"$id","project":"me","phase":"Now","sort":$sort,"text":"Task $id","status":"${status[id] ?: "open"}","priority":2,"star":true,"updated_at":"2026-10-30T05:00:00Z","version":1,"steps":[]$extra${said[id].orEmpty()}}"""

    @Before fun setUp() {
        db = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/v1/sync") -> MockResponse().setBody(
                        """{"cursor":5,"full":true,"projects":[{"name":"me","title":"Me","color":null,"sort":0,"open":5}],"tasks":[""" +
                            listOf(task("t_1", 1), task("t_2", 2), task("t_3", 3), task("t_4", 4), task("t_5", 5, ""","micro":$micro""")).joinToString(",") + """],"removed":[]}""")
                    path == "/api/v1/today" -> MockResponse().setBody("""{"day":"$day","shown_at_once":3,"tasks":[{"id":"t_1"},{"id":"t_2"},{"id":"t_3"},{"id":"t_4"},{"id":"t_5"}]}""")
                    path == "/api/v1/tiny" -> { posts += request.body.readUtf8(); MockResponse().setBody("""{"steps":["Pick up the book 📖","Open page 12","Read the first line"]}""") }
                    path == "/api/v1/kpi" -> { posts += request.body.readUtf8(); MockResponse().setBody("""{"ok":true}""") }
                    request.method == "PATCH" -> {
                        val body = request.body.readUtf8()
                        patches += "${path.removePrefix("/api/v1/tasks/")} $body"
                        val keys = Regex("\"(\\w+)\":").findAll(body).map { it.groupValues[1] }.toList()
                        MockResponse().setBody("""{"id":"x","status":"open","applied":[${keys.joinToString(",") { "\"$it\"" }}]}""")
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
    private fun tasks() = TaskRepository(db.dao(), {}, clock = { t0 })
    private fun today() = TodayRepository(db.dao(), {}, clock = { t0 })
    private fun sender() = OutboxSender(api, db.dao(), clock = { t0 + 10_000 })
    private suspend fun synced() { SyncEngine(api, db.dao()).syncOnce(account) }
    private suspend fun order(): List<String> {
        val list = db.dao().todayTasks().first()
        return orderToday(list, today().todaySorts(day).first()).map { it.id }
    }
    private suspend fun keys(): List<Double> {
        val list = db.dao().todayTasks().first()
        val k = todayKeys(list, today().todaySorts(day).first())
        return orderToday(list, today().todaySorts(day).first()).map { k[it.id]!! }
    }

    @Test fun dragFiveToOneIsOnePatchOfOneRowAndItBecomesOneOfTheThree() = runBlocking {
        synced()
        assertEquals(listOf("t_1", "t_2", "t_3", "t_4", "t_5"), order())
        val sort = todaySortAfterMove(keys(), from = 4, to = 0)
        assertEquals(-1.0, sort, 0.0)
        tasks().todaySort("t_5", day, sort)
        assertEquals("answers in Room at once", listOf("t_5", "t_1", "t_2", "t_3", "t_4"), order())
        sender().sendAll(account)
        assertEquals(listOf("""t_5 {"today_sort":-1.0,"today_day":"2026-10-30"}"""), patches.toList())
        // A sync (pagel.uz's own order back) never wipes the phone's: the order lives in its own table.
        synced()
        assertEquals(listOf("t_5", "t_1", "t_2", "t_3", "t_4"), order())
    }

    @Test fun aDropBetweenNeighboursTakesTheMiddle() = runBlocking {
        synced()
        val sort = todaySortAfterMove(keys(), from = 0, to = 2)
        assertEquals("between t_3 (2) and t_4 (3)", 2.5, sort, 0.0)
        tasks().todaySort("t_1", day, sort)
        assertEquals(listOf("t_2", "t_3", "t_1", "t_4", "t_5"), order())
    }

    @Test fun undoAfterTheMoveLeftSendsTheOldPlaceBack() = runBlocking {
        synced()
        val r = tasks()
        val op = r.todaySort("t_5", day, -1.0)!!
        sender().sendAll(account)
        r.undoTodaySort("t_5", op, day, old = null)
        assertEquals("back at pagel.uz's place", listOf("t_1", "t_2", "t_3", "t_4", "t_5"), order())
        sender().sendAll(account)
        assertEquals(
            listOf("""t_5 {"today_sort":-1.0,"today_day":"2026-10-30"}""", """t_5 {"today_sort":null,"today_day":"2026-10-30"}"""),
            patches.toList(),
        )
    }

    @Test fun undoBeforeTheMoveLeftTakesItBackAndPagelNeverHearsOfIt() = runBlocking {
        synced()
        val r = tasks()
        r.todaySort("t_2", day, 0.5)
        val op = r.todaySort("t_5", day, -1.0)!!
        r.undoTodaySort("t_5", op, day, old = null)
        assertEquals(listOf("t_1", "t_2", "t_3", "t_4", "t_5"), order())
        sender().sendAll(account)
        assertEquals("only the first move left the phone", listOf("""t_2 {"today_sort":0.5,"today_day":"2026-10-30"}"""), patches.toList())
    }

    @Test fun undoOfASecondMoveWritesTheFirstSortBack() = runBlocking {
        synced()
        val r = tasks()
        r.todaySort("t_5", day, -1.0)
        sender().sendAll(account)
        val op = r.todaySort("t_5", day, 2.5)!!
        sender().sendAll(account)
        r.undoTodaySort("t_5", op, day, old = -1.0)
        sender().sendAll(account)
        assertEquals("""t_5 {"today_sort":-1.0,"today_day":"2026-10-30"}""", patches.last())
        assertEquals("t_5", order().first())
    }

    @Test fun microStepsAreTheirOwnListSavedByOnePatchAndBackThroughSync() = runBlocking {
        synced()
        val reply = api.tiny(account.serverUrl, account.token, TinyBody("t_5", 3))
        val steps = (reply as Outcome.Ok).value.steps
        assertEquals(3, steps.size)
        assertEquals("""{"id":"t_5","spice":3}""", posts.single())
        tasks().micro("t_5", steps)
        assertEquals(steps, microOf(db.dao().taskOnce("t_5")!!.micro))
        assertTrue("GTD steps untouched", db.dao().stepsOnce("t_5").isEmpty())
        sender().sendAll(account)
        assertEquals("""t_5 {"micro":["Pick up the book 📖","Open page 12","Read the first line"]}""", patches.single())
        micro = """["Pick up the book 📖","Open page 12"]"""
        synced()
        assertEquals(listOf("Pick up the book 📖", "Open page 12"), microOf(db.dao().taskOnce("t_5")!!.micro))
    }

    @Test fun eventsAreFixedWordsOnTheKpiPost() = runBlocking {
        synced()
        today().event("stuck", "too_big")
        today().event("what_now_pick")
        sender().sendAll(account)
        assertEquals(listOf("""{"day":"2026-10-30","event":"stuck","reason":"too_big"}""", """{"day":"2026-10-30","event":"what_now_pick"}"""), posts.toList())
        val refused = runCatching { today().event("stuck", "I am tired of this") }
        assertTrue("free text never becomes an event", refused.isFailure)
    }

    @Test fun theOrderComesBackFromPagelOnAFreshInstall() = runBlocking {
        // Boss dragged t_5 to the top on another phone (or before a reinstall): pagel.uz has it, this phone has nothing.
        said["t_5"] = ""","today_sort":-1.0,"today_day":"$day""""
        synced()
        assertEquals(listOf("t_5", "t_1", "t_2", "t_3", "t_4"), order())
        // A second sync changes nothing.
        synced()
        assertEquals(listOf("t_5", "t_1", "t_2", "t_3", "t_4"), order())
    }

    @Test fun aDragStillOnThePhoneWinsOverPagelsOlderOrder() = runBlocking {
        said["t_5"] = ""","today_sort":-1.0,"today_day":"$day""""
        synced()
        tasks().todaySort("t_5", day, 2.5)
        synced()
        assertEquals("the phone's newer drag (not yet sent) stays", listOf("t_1", "t_2", "t_3", "t_5", "t_4"), order())
        // Sent, and pagel.uz now says 2.5 too: it stays.
        sender().sendAll(account)
        said["t_5"] = ""","today_sort":2.5,"today_day":"$day""""
        synced()
        assertEquals(listOf("t_1", "t_2", "t_3", "t_5", "t_4"), order())
    }

    @Test fun ticksAboveNeverSlideAKeptPlaceRowPastADraggedOne() = runBlocking {
        synced()
        val r = today()
        // The full list opens: every row gets its current place as its own sort (phone only, no PATCH).
        val list = db.dao().todayTasks().first()
        r.pinSorts(day, org.pagel.ai.ui.today.unpinnedKeys(list, r.todaySorts(day).first(), expanded = true))
        assertTrue("pinning sends nothing", db.dao().waitingOutbox().none { it.taskId != "kpi" })
        tasks().todaySort("t_5", day, todaySortAfterMove(keys(), from = 4, to = 3))
        assertEquals(listOf("t_1", "t_2", "t_3", "t_5", "t_4"), order())
        r.tick("t_1", done = true)
        r.tick("t_2", done = true)
        assertEquals("t_5 stays above t_4 after two ticks above it", listOf("t_3", "t_5", "t_4"), order().filter { it !in setOf("t_1", "t_2") })
        assertEquals("nothing left to pin", emptyMap<String, Double>(), org.pagel.ai.ui.today.unpinnedKeys(db.dao().todayTasks().first(), r.todaySorts(day).first(), expanded = true))
    }

    @Test fun theDoneCountNeverGoesDownWhenPagelSendsADoneTaskWithoutItsTime() = runBlocking {
        synced()
        val r = today()
        r.tick("t_1", done = true)
        assertEquals(1, r.doneToday().first())
        sender().sendAll(account)
        status["t_1"] = "done"
        synced()
        assertEquals("the phone keeps its tick time", 1, r.doneToday().first())
        assertTrue(db.dao().taskOnce("t_1")!!.doneAt != null)
    }

    @Test fun theFullListAndEnergyAreRememberedForTodayOnly() = runBlocking {
        val r = today()
        assertEquals(false, r.state(day).first().expanded)
        r.setExpanded(true)
        r.setEnergy("low")
        assertEquals(true, r.state(day).first().expanded)
        assertEquals("low", r.state(day).first().energy)
        val tomorrow = r.state("2026-10-31").first()
        assertEquals("a new day starts folded", false, tomorrow.expanded)
        assertNull("and asks the energy again", tomorrow.energy)
    }
}
