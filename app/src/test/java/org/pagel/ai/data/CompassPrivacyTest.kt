package org.pagel.ai.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.pagel.ai.data.compass.CompassApi
import org.pagel.ai.data.compass.CompassCrypto
import org.pagel.ai.data.compass.CompassDatabase
import org.pagel.ai.data.compass.CompassKey
import org.pagel.ai.data.compass.CompassRepository
import org.pagel.ai.data.compass.CompassRules
import org.pagel.ai.data.compass.Goal
import org.pagel.ai.data.compass.GoalBody
import org.pagel.ai.data.compass.KeyGone
import org.pagel.ai.data.compass.KeyResult
import org.pagel.ai.data.compass.Level
import org.pagel.ai.data.compass.NoteBody
import org.pagel.ai.data.compass.NoteKind
import org.pagel.ai.data.compass.VaultState
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.data.today.TodayRepository
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * THE PROOF (Boss 2026-10-28: "my goals are sacred, no AI ever will read them"). Goals written with marked words go
 * through the real repository, Room on a real file and the real outbox against a fake pagel.uz: the marked words
 * appear in no request body, no log line and no file on the phone, only inside ciphertext that opens with the key.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CompassPrivacyTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    /** pagel.uz's sealed store, as the fake keeps it (id → PUT body), and the recovery blob. */
    private val stored = ConcurrentHashMap<String, String>()
    @Volatile private var recovery: String? = null
    @Volatile private var putCode = 200
    private val logs = CopyOnWriteArrayList<String>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var main: PagelDatabase
    private val opened = mutableListOf<CompassDatabase>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Tuesday of week 1 of Season 1, Year 19 (2026-11-03 13:00 Tashkent). */
    private val now = Instant.parse("2026-11-03T08:00:00Z").toEpochMilli()
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() {
        ShadowLog.clear()
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { logs += message }
        })
        main = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                requests += "${request.method} $path $body"
                return when {
                    request.method == "PUT" && putCode != 200 -> MockResponse().setResponseCode(putCode).setBody("""{"error":"Unknown path."}""")
                    request.method == "PUT" && path.startsWith("/api/v1/compass/items/") -> {
                        stored[path.substringAfterLast('/')] = body
                        MockResponse().setBody("""{"id":"x","version":1}""")
                    }
                    request.method == "PUT" && path == "/api/v1/compass/recovery" -> {
                        recovery = json.parseToJsonElement(body).jsonObject["blob"]?.jsonPrimitive?.content
                        MockResponse().setBody("""{"recovery":true}""")
                    }
                    request.method == "PUT" && path.startsWith("/api/v1/compass/links/") -> MockResponse().setBody("{}")
                    request.method == "GET" && path == "/api/v1/compass" -> {
                        val items = stored.entries.joinToString(",") { (id, b) -> b.trimEnd('}') + ""","id":"$id","version":1}""" }
                        MockResponse().setBody("""{"items":[$items],"links":[],"recovery":${recovery?.let { "\"$it\"" } ?: "null"}}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        Timber.uprootAll()
        server.shutdown()
        opened.forEach { it.close() }
        main.close()
        scope.cancel()
    }

    private val account by lazy { Account(server.url("/").toString().trimEnd('/'), "d_1", "Phone", "tok") }

    private fun phone(keys: CompassTestKeys = CompassTestKeys(), name: String = "compass-${System.nanoTime()}.db"): Pair<CompassRepository, CompassKey> {
        val db = Room.databaseBuilder(ctx, CompassDatabase::class.java, name).allowMainThreadQueries().build().also { opened += it }
        val key = CompassKey(scope)
        val today = TodayRepository(main.dao(), { }, clock = { now })
        return CompassRepository(db.dao(), CompassApi(OkHttpClient(), Dispatchers.IO), keys, key, main.dao(), today, { }, Dispatchers.IO, clock = { now }) to key
    }

    private suspend fun writeTheCascade(repo: CompassRepository): String {
        val purpose = repo.saveGoal(Goal("", Level.PURPOSE, null, null, null, GoalBody(title = "Zebra purpose: find every child's gift", why = "zebra why line")))
        val ten = repo.saveGoal(Goal("", Level.TEN_YEAR, purpose, null, null, GoalBody(title = "Zebra ten years: a school in every region")))
        val year = repo.saveGoal(Goal("", Level.YEAR, ten, "2026-11-01", "2027-10-31", GoalBody(title = "Zebra year: ship the app")))
        val season = CompassRules.seasonOf(java.time.LocalDate.parse("2026-11-03"))
        val s = repo.saveGoal(
            Goal(
                "", Level.SEASON, year, season.starts.toString(), season.ends.toString(),
                GoalBody(
                    title = "Zebra season: 100 learners", myPart = "zebra my part: 3 lessons a week", outcome = "zebra outcome: the grant",
                    keyResults = listOf(KeyResult("zebra key result one")), leadAction = "Walk 20 minutes",
                ),
            ),
        )
        repo.saveNote(NoteKind.WEEK, "2026-11-01", NoteBody(listOf("zebra got in the way", "zebra smaller step"), s))
        repo.link("humopedia", s)
        return s
    }

    /** Every marked phrase has "zebra " with a space: base64 ciphertext can never hold a space, so a hit is real. */
    private fun hasMarker(s: String) = s.lowercase().contains("zebra ")

    @Test fun goalWordsLeaveThePhoneOnlyAsCiphertext() = runBlocking {
        val (repo, key) = phone()
        assertEquals(VaultState.NEW, repo.state.first())
        repo.create()
        assertEquals(VaultState.OPEN, repo.state.first())
        val season = writeTheCascade(repo)
        repo.setRecovery("a long recovery passphrase".toCharArray())
        assertEquals(SendResult.ALL_SENT, repo.sendAll(account))

        // 1. Every request: no marked word, only the sealed shape.
        val puts = requests.filter { it.startsWith("PUT /api/v1/compass/items/") }
        assertEquals(5, puts.size)
        for (r in requests) assertFalse("plain goal words in a request: $r", hasMarker(r))
        val k = key.key.value!!
        var openedWithKey = 0
        for (r in puts) {
            val id = r.substringAfter("/items/").substringBefore(' ')
            val body = json.parseToJsonElement(r.substringAfter(' ').substringAfter(' ')).jsonObject
            assertEquals(setOf("kind", "parent", "blob", "starts", "ends", "deleted"), body.keys)
            val plain = CompassCrypto.open(k, id, body["blob"]!!.jsonPrimitive.content)
            assertNotNull("a blob that does not open with the key", plain)
            if (hasMarker(String(plain!!))) openedWithKey++
        }
        // The words were there all along, sealed: every one of the 5 blobs opens to its marked words.
        assertEquals(5, openedWithKey)
        assertTrue(requests.any { it == """PUT /api/v1/compass/links/humopedia {"goal":"$season"}""" })
        assertTrue(requests.any { it.startsWith("PUT /api/v1/compass/recovery {\"blob\":\"") })

        // 2. The lead action became a habit through the normal path, with its own words only.
        val habits = main.dao().habitsOnce()
        assertEquals(listOf("Walk 20 minutes"), habits.map { it.text })
        assertEquals("week", habits.single().every)

        // 3. No log line (Timber or android.util.Log) carries the words.
        for (l in logs + ShadowLog.getLogs().map { it.msg.orEmpty() }) assertFalse("plain goal words in a log: $l", hasMarker(l))

        // 4. No file on the phone (databases, WAL, files, caches) holds the words.
        val root = ctx.filesDir.parentFile!!
        val files = root.walkTopDown().filter(File::isFile).toList()
        assertTrue("the Compass database file was not written", files.any { it.name.startsWith("compass-") })
        for (f in files) assertFalse("plain goal words in ${f.name}", hasMarker(String(f.readBytes(), Charsets.ISO_8859_1)))

        // 5. Unlocked, the screen has the words; locked, nothing is left in memory to show.
        assertEquals(4, repo.opened.first().goals.size)
        repo.lock()
        assertEquals(VaultState.LOCKED, repo.state.first())
        assertTrue(repo.opened.first().goals.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theKeyIsWipedSixtySecondsAfterLeaving() {
        val test = TestScope(StandardTestDispatcher())
        val key = CompassKey(test)
        val k = CompassCrypto.newDataKey()
        key.open(k)
        key.leave()
        test.advanceTimeBy(59_000); test.runCurrent()
        assertNotNull(key.key.value)
        key.stay()
        test.advanceTimeBy(120_000); test.runCurrent()
        assertNotNull("coming back in time keeps it open", key.key.value)
        key.leave()
        test.advanceTimeBy(60_001); test.runCurrent()
        assertNull(key.key.value)
        assertTrue("the key's bytes are zeroed", k.all { it == 0.toByte() })
    }

    @Test fun aNewPhoneOpensTheSealedCopyOnlyWithTheRecoveryPassphrase() = runBlocking {
        val (old, _) = phone()
        old.create()
        writeTheCascade(old)
        old.setRecovery("a long recovery passphrase".toCharArray())
        old.sendAll(account)

        val (fresh, _) = phone(CompassTestKeys())
        fresh.pullIfNew(account)
        assertEquals(VaultState.LOST, fresh.state.first())
        assertFalse(fresh.recover("not the passphrase".toCharArray()))
        assertEquals(VaultState.LOST, fresh.state.first())
        assertTrue(fresh.recover("a long recovery passphrase".toCharArray()))
        assertEquals(VaultState.OPEN, fresh.state.first())
        val goals = fresh.opened.first().goals
        assertEquals(4, goals.size)
        assertEquals("Zebra purpose: find every child's gift", goals.single { it.level == Level.PURPOSE }.body.title)
    }

    @Test fun aServerWithoutTheCompassKeepsEverythingWaiting() = runBlocking {
        val (repo, _) = phone()
        repo.create()
        repo.saveGoal(Goal("", Level.PURPOSE, null, null, null, GoalBody(title = "Zebra purpose")))
        putCode = 405
        assertEquals(SendResult.ALL_SENT, repo.sendAll(account))
        assertTrue(stored.isEmpty())
        putCode = 200
        assertEquals(SendResult.ALL_SENT, repo.sendAll(account))
        assertEquals(1, stored.size)
    }

    @Test fun aLostGuardKeySaysSoAndNoLockAsksForOne() = runBlocking {
        val keys = CompassTestKeys()
        val (repo, _) = phone(keys)
        repo.create()
        repo.saveGoal(Goal("", Level.PURPOSE, null, null, null, GoalBody(title = "Zebra purpose")))
        repo.lock()
        keys.gone = true
        val thrown = runCatching { repo.unlock() }.exceptionOrNull()
        assertTrue(thrown is KeyGone)
        assertEquals(VaultState.LOST, repo.state.first())

        val (none, _) = phone(CompassTestKeys(screenLock = false))
        assertEquals(VaultState.NO_LOCK, none.state.first())
    }

    @Test fun todaysLineIsSealedOnThePhoneAndShowsTheFirstWords() = runBlocking {
        val (repo, _) = phone()
        repo.create()
        val id = repo.saveGoal(Goal("", Level.PURPOSE, null, null, null, GoalBody(title = "Find every child's gift and grow it")))
        assertNull(repo.line.first())
        repo.setLine(true, repo.opened.first().goals.single { it.id == id })
        assertEquals("Find every child's gift and grow…", repo.line.first())
        repo.lock()
        // The line has its own key: Today keeps it while the Compass is locked.
        assertEquals("Find every child's gift and grow…", repo.line.first())
        repo.setLine(false, null)
        assertNull(repo.line.first())
    }
}
