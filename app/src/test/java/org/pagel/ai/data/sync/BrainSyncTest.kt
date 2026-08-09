package org.pagel.ai.data.sync

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.pagel.ai.data.spend.SpendStore
import org.pagel.ai.domain.SpendEvent
import org.pagel.ai.domain.UsageSnapshot

/**
 * BrainSync.kt documents "no raw events, no notification text" and SettingsScreen tells Boss
 * "only the amount syncs" — but `buildJson` used to also send `SpendEvent.raw`, the literal first
 * 140 characters of the bank notification. `buildJson` is private, so this drives the real public
 * seam (`push()`) against a local loopback MockWebServer and inspects the exact bytes written to
 * the wire — the strongest seam short of making a privacy-sensitive method public just to test it.
 */
class BrainSyncTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `synced payload carries the spend amount but never the raw bank notification text`() = runTest {
        val bankText = "Uzum Bank: -45,000 so'm to card *1234. Balance: 320,000 so'm"
        val spendEvent = SpendEvent(ts = 1_700_000_000_000L, amount = 45_000L, raw = bankText.take(140))

        val settings = mockk<SyncSettings>()
        coEvery { settings.current() } returns (server.url("/").toString() to "test-bearer")

        val spendStore = mockk<SpendStore>()
        coEvery { spendStore.today() } returns listOf(spendEvent)

        val brainSync = BrainSync(ctx = mockk<Context>(relaxed = true), settings = settings, spendStore = spendStore)

        server.enqueue(MockResponse().setResponseCode(200))

        val snapshot = UsageSnapshot(apps = emptyList(), activeMs = 0, totalOpens = 0, distinctApps = 0)
        val result = brainSync.push(snapshot, "2026-08-09")
        assertTrue("push should succeed against the local server: ${result.exceptionOrNull()}", result.isSuccess)

        val body = server.takeRequest().body.readUtf8()

        // The literal notification text must never leave the device, in any form.
        assertFalse("payload leaked the raw bank notification text", body.contains("Uzum"))
        assertFalse("payload leaked the raw bank notification text", body.contains(bankText))

        // The actual privacy contract: no "raw" key anywhere in the spend array.
        val spendArr = Json.parseToJsonElement(body).jsonObject["spend"]!!.jsonArray
        assertTrue("expected the spend event to be present", spendArr.isNotEmpty())
        spendArr.forEach { entry ->
            assertFalse("spend entry should not carry a raw key: $entry", entry.jsonObject.containsKey("raw"))
        }
    }
}
