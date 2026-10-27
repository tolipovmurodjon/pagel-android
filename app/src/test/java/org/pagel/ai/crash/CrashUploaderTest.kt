package org.pagel.ai.crash

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CrashUploaderTest {
    private val server = MockWebServer()
    private lateinit var dir: File
    private val uploader = CrashUploader(OkHttpClient())

    @Before fun setUp() {
        server.start()
        dir = File.createTempFile("pagel-up", "").let { it.delete(); it.mkdirs(); it }
        repeat(3) { CrashLog.write(dir, CrashLog.report(Thread.currentThread(), RuntimeException("c$it"), "0.3.1", 31, "d", 34, 10L + it)) }
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun base() = server.url("/").toString()

    @Test fun `every crash is posted with the device token and deleted once the server has it`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(201)) }
        val out = uploader.upload(dir, base(), "dev-token")
        assertEquals(CrashUploader.Outcome(3, 0), out)
        assertTrue(CrashLog.pending(dir).isEmpty())
        val first = server.takeRequest()
        assertEquals("/api/v1/app/crash", first.path)
        assertEquals("Bearer dev-token", first.getHeader("Authorization"))
        assertTrue("newest first", first.body.readUtf8().contains("\"message\":\"c2\""))
    }

    @Test fun `a server error keeps the rest for the next launch`() {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(503))
        val out = uploader.upload(dir, base(), "t")
        assertEquals(CrashUploader.Outcome(1, 2), out)
        assertEquals(2, CrashLog.pending(dir).size)
    }

    @Test fun `a revoked token keeps every file`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(CrashUploader.Outcome(0, 3), uploader.upload(dir, base(), "old"))
        assertEquals(3, CrashLog.pending(dir).size)
    }

    @Test fun `a payload the server can never take is dropped, not retried forever`() {
        server.enqueue(MockResponse().setResponseCode(413))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        assertEquals(CrashUploader.Outcome(2, 0), uploader.upload(dir, base(), "t"))
        assertTrue(CrashLog.pending(dir).isEmpty())
    }

    @Test fun `no network keeps every file`() {
        val out = uploader.upload(dir, "http://127.0.0.1:1/", "t")
        assertEquals(CrashUploader.Outcome(0, 3), out)
    }
}
