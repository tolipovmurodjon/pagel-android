package org.pagel.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * The Phase 1 done-when on the JVM: a fake pagel.uz (MockWebServer) serves /api/v1/sync and the live stream; a tick
 * "on the web" is a new event on the stream; the phone's Room row must change within 3 s.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SyncEngineTest {
    private val server = MockWebServer()
    private lateinit var db: PagelDatabase
    private val seen = CopyOnWriteArrayList<String>()
    @Volatile private var ticked = false
    @Volatile private var authorized = true

    private fun task(status: String, version: Int) =
        """{"id":"t_abc","project":"pagel","phase":"Now","sort":1,"text":"Ship the native app","title":null,"status":"$status","priority":1,"star":true,"due":null,"every":null,"done_when":null,"done_at":null,"updated_at":"2026-10-27T10:00:00Z","version":$version,"steps":[{"text":"Pair","title":null,"done":true}]}"""

    private val full get() = """{"cursor":10,"full":true,"projects":[{"name":"pagel","title":"PAGEL","color":null,"sort":0,"open":1}],"tasks":[${task("open", 1)}],"removed":[]}"""
    private val delta get() = """{"cursor":11,"full":false,"projects":[{"name":"pagel","title":"PAGEL","color":null,"sort":0,"open":0}],"tasks":[${task("done", 2)}],"removed":[]}"""

    @Before fun setUp() {
        db = inMemoryDb()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request.path.orEmpty()
                if (!authorized) return MockResponse().setResponseCode(401)
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/v1/sync?since=0") -> MockResponse().setBody(full)
                    path.startsWith("/api/v1/sync?since=10") -> MockResponse().setBody(if (ticked) delta else full.replace("\"full\":true", "\"full\":false"))
                    path.startsWith("/api/v1/stream") -> {
                        // The web ticks the task 0.5 s after the phone starts listening.
                        Thread.sleep(500)
                        ticked = true
                        MockResponse().setHeader("content-type", "text/event-stream")
                            .setBody("event: hello\ndata: 10\n\nevent: change\nid: 11\ndata: {\"id\":11,\"actors\":[\"dashboard\"]}\n\n")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun account() = Account(server.url("/").toString().trimEnd('/'), "d_1", "Test phone", "dev-token")
    private fun engine() = SyncEngine(PagelApi(OkHttpClient(), Dispatchers.IO), db.dao(), random = Random(1))

    @Test fun `a cold start copies projects, tasks and steps into Room`() = runBlocking {
        val out = engine().syncOnce(account())
        assertTrue(out is Outcome.Ok)
        assertEquals(listOf("PAGEL"), db.dao().projects().first().map { it.title })
        val t = db.dao().task("t_abc").first()!!
        assertEquals("open", t.status)
        assertEquals(listOf("Pair"), db.dao().steps("t_abc").first().map { it.text })
        assertEquals(10L, db.dao().syncState()!!.cursor)
    }

    @Test fun `a tick on the web reaches the phone's Room row within 3 s`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = System.currentTimeMillis()
        engine().start(scope, account()) {}
        try {
            withTimeout(3_000) {
                db.dao().task("t_abc").first { it?.status == "done" }
            }
        } finally {
            scope.cancel()
        }
        val took = System.currentTimeMillis() - started
        println("web tick → phone Room row: $took ms")
        assertTrue("took $took ms", took < 3_000)
        assertEquals(2, db.dao().task("t_abc").first()!!.serverVersion)
        assertEquals(0, db.dao().projects().first().single().openCount)
        assertTrue("the delta asked from the cursor", seen.any { it.startsWith("/api/v1/sync?since=10") })
        assertTrue("every request carried the device token", server.requestCount > 0)
    }

    @Test fun `a revoked token stops the loop and signs the phone out`() = runBlocking {
        authorized = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var signedOut = false
        engine().start(scope, account()) { signedOut = true }
        withTimeout(3_000) { while (!signedOut) kotlinx.coroutines.delay(20) }
        scope.cancel()
        assertTrue(signedOut)
    }

    @Test fun `backoff grows, caps at a minute and is jittered`() {
        val e = engine()
        val waits = (0..9).map { e.backoffMs(it) }
        assertTrue(waits.first() in 800..1_200)
        assertTrue(waits.last() in 48_000..72_000)
        assertTrue(waits.zipWithNext().take(5).all { (a, b) -> b > a })
    }
}
