package org.pagel.ai.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.compass.CompassApi
import org.pagel.ai.data.compass.CompassDatabase
import org.pagel.ai.data.compass.CompassKey
import org.pagel.ai.data.compass.CompassRepository
import org.pagel.ai.data.compass.Goal
import org.pagel.ai.data.compass.GoalBody
import org.pagel.ai.data.compass.Level
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.data.today.TodayRepository
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compass re-check N1/N2: pagel.uz's copy must never freeze. A write whose reply is lost is sent again with the old
 * base; pagel.uz answers 409 with the version it holds; the phone takes it and sends its copy over it. Nothing is
 * dropped, and a new seal never writes back an old serverVersion.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CompassSyncTest {
    private val server = MockWebServer()
    /** The fake pagel.uz's versions and the last blob per item, like compass-api.ts. */
    private val versions = ConcurrentHashMap<String, Int>()
    private val blobs = ConcurrentHashMap<String, String>()
    private val conflicts = AtomicInteger()
    @Volatile private var loseNextReply = false
    private lateinit var main: PagelDatabase
    private lateinit var db: CompassDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() {
        main = inMemoryDb()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CompassDatabase::class.java).allowMainThreadQueries().build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (request.method != "PUT" || !path.startsWith("/api/v1/compass/items/")) return MockResponse().setResponseCode(404)
                val id = path.substringAfterLast('/')
                val b = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val held = versions[id] ?: 0
                if (versions.containsKey(id) && b["base"]!!.jsonPrimitive.int != held) {
                    conflicts.incrementAndGet()
                    return MockResponse().setResponseCode(409).setBody("""{"error":"A newer copy is on pagel.uz.","version":$held}""")
                }
                versions[id] = held + 1
                blobs[id] = b["blob"]!!.jsonPrimitive.content
                // Saved on pagel.uz, but the reply never reaches the phone (a dropped connection).
                if (loseNextReply) { loseNextReply = false; return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                return MockResponse().setBody("""{"id":"$id","version":${held + 1}}""")
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
        db.close()
        main.close()
        scope.cancel()
    }

    private val account by lazy { Account(server.url("/").toString().trimEnd('/'), "d_1", "Phone", "tok") }

    private fun repo() = CompassRepository(
        db.dao(), CompassApi(OkHttpClient(), Dispatchers.IO), CompassTestKeys(), CompassKey(scope), main.dao(),
        TodayRepository(main.dao(), { }), { }, Dispatchers.IO,
    )

    @Test fun aLostReplyThenA409IsResentAndNeverDropped() = runBlocking {
        val repo = repo()
        repo.create(repo.cipherForUnlock())
        val id = repo.saveGoal(Goal("", Level.PURPOSE, null, null, null, GoalBody(title = "first words")))
        loseNextReply = true
        // Pass 1: pagel.uz saves version 1, the reply is lost (OkHttp may retry it on its own and meet the 409 then).
        val first = repo.sendAll(account)
        // An edit meanwhile, then the next pass: base 0 against version 1 → 409 → take 1 → resend → version 2.
        repo.saveGoal(Goal(id, Level.PURPOSE, null, null, null, GoalBody(title = "second words", createdAt = 1)))
        var result = repo.sendAll(account)
        if (first != SendResult.ALL_SENT && result != SendResult.ALL_SENT) result = repo.sendAll(account)
        assertEquals(SendResult.ALL_SENT, result)
        assertTrue("pagel.uz answered at least one 409", conflicts.get() >= 1)
        assertTrue("nothing left waiting: the change was never dropped, it was sent", db.dao().outbox().isEmpty())
        val row = db.dao().item(id)!!
        assertEquals(versions[id], row.serverVersion)
        // pagel.uz holds the phone's latest seal, not the first one.
        assertEquals(row.blob, blobs[id])
    }

    @Test fun aNewSealKeepsTheVersionTheSenderStored() = runBlocking {
        val repo = repo()
        repo.create(repo.cipherForUnlock())
        val id = repo.saveGoal(Goal("", Level.PURPOSE, null, null, null, GoalBody(title = "first words")))
        db.dao().setServerVersion(id, 5)
        repo.saveGoal(Goal(id, Level.PURPOSE, null, null, null, GoalBody(title = "second words", createdAt = 1)))
        assertEquals(5, db.dao().item(id)!!.serverVersion)
        repo.deleteGoal(id)
        assertEquals(5, db.dao().item(id)!!.serverVersion)
        assertTrue(db.dao().item(id)!!.deleted)
    }
}
