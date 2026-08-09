@file:OptIn(ExperimentalCoroutinesApi::class)

package org.pagel.ai.net.ws

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.net.identity.DeviceIdentity
import org.pagel.ai.net.identity.DeviceIdentityStore
import org.pagel.ai.net.identity.Ed25519
import org.pagel.ai.net.identity.buildDeviceAuthPayloadV3
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class GatewayConnectionTest {

    private class FakeDeviceIdentityStore(private val identity: DeviceIdentity) : DeviceIdentityStore {
        override suspend fun loadOrCreate(): DeviceIdentity = identity
    }

    private class FakeTokenStore(seed: Map<Pair<String, String>, String> = emptyMap()) : GatewayDeviceTokenStore {
        private val tokens = seed.toMutableMap()
        override suspend fun get(deviceId: String, role: String): String? = tokens[deviceId to role]
        override suspend fun put(deviceId: String, role: String, token: String) {
            tokens[deviceId to role] = token
        }
        fun snapshot(): Map<Pair<String, String>, String> = tokens.toMap()
    }

    private data class Harness(
        val connection: GatewayConnection,
        val socket: WebSocket,
        val sent: List<String>,
        val listener: () -> WebSocketListener,
    )

    private fun newHarness(
        scope: CoroutineScope,
        identity: DeviceIdentity = Ed25519.generate(),
        tokenStore: FakeTokenStore = FakeTokenStore(),
        role: String = GatewayClientInfo.ROLE_OPERATOR,
        mode: String = GatewayClientInfo.MODE_OPERATOR,
        scopes: List<String> = listOf("operator.read", "operator.write"),
    ): Harness {
        val fakeSocket = mockk<WebSocket>(relaxed = true)
        val sent = mutableListOf<String>()
        every { fakeSocket.send(capture(sent)) } returns true
        val listenerSlot = slot<WebSocketListener>()
        val factory = mockk<WebSocket.Factory>()
        every { factory.newWebSocket(any(), capture(listenerSlot)) } returns fakeSocket

        val connection = GatewayConnection(
            role = role,
            mode = mode,
            scopes = scopes,
            serverUrl = "wss://gateway.test/ws",
            clientVersion = "0.1-A1",
            platform = "Android",
            deviceFamily = "SM-A057F",
            webSocketFactory = factory,
            deviceIdentityStore = FakeDeviceIdentityStore(identity),
            tokenStore = tokenStore,
            scope = scope,
            idGenerator = { "connect-1" },
        )
        return Harness(connection, fakeSocket, sent) { listenerSlot.captured }
    }

    private fun challengeJson(nonce: String, ts: Long) = GatewayJson.encodeToString(
        GatewayFrame.Event.serializer(),
        GatewayFrame.Event(event = "connect.challenge", payload = buildJsonObject { put("nonce", nonce); put("ts", ts) }),
    )

    private fun helloOkResJson(
        id: String,
        deviceToken: String? = null,
        role: String = "operator",
        tickIntervalMs: Long = 15_000,
    ) = GatewayJson.encodeToString(
        GatewayFrame.Res.serializer(),
        GatewayFrame.Res(
            id = id,
            ok = true,
            payload = buildJsonObject {
                put("type", "hello-ok")
                put("protocol", 4)
                put(
                    "auth",
                    buildJsonObject {
                        put("role", role)
                        putJsonArray("scopes") { add("operator.read"); add("operator.write") }
                        deviceToken?.let { put("deviceToken", it) }
                    },
                )
                put(
                    "policy",
                    buildJsonObject {
                        put("maxPayload", 26_214_400)
                        put("maxBufferedBytes", 10_485_760)
                        put("tickIntervalMs", tickIntervalMs)
                    },
                )
            },
        ),
    )

    private fun errorResJson(id: String, code: String, message: String) = GatewayJson.encodeToString(
        GatewayFrame.Res.serializer(),
        GatewayFrame.Res(id = id, ok = false, error = GatewayError(code = code, message = message)),
    )

    private fun Harness.sentConnectFrame(): GatewayFrame.Req =
        GatewayJson.decodeFromString(GatewayFrame.Req.serializer(), sent.single { it.contains("\"method\":\"connect\"") })

    /** Like [Harness], but the mock factory hands out a *fresh* socket + listener on every
     *  [connect]/reconnect, so a test can drive two attempts independently — needed to prove a
     *  stale attempt's socket can't touch a newer attempt's state (see the generation-guard test). */
    private data class MultiHarness(
        val connection: GatewayConnection,
        val sockets: List<WebSocket>,
        val listeners: List<WebSocketListener>,
        val sentPerAttempt: List<List<String>>,
    )

    private fun newMultiHarness(
        scope: CoroutineScope,
        identity: DeviceIdentity = Ed25519.generate(),
        tokenStore: FakeTokenStore = FakeTokenStore(),
        deviceIdentityStore: DeviceIdentityStore = FakeDeviceIdentityStore(identity),
    ): MultiHarness {
        val sockets = mutableListOf<WebSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val sentPerAttempt = mutableListOf<MutableList<String>>()
        val factory = mockk<WebSocket.Factory>()
        every { factory.newWebSocket(any(), any()) } answers {
            listeners.add(secondArg())
            val sentForThisAttempt = mutableListOf<String>()
            sentPerAttempt.add(sentForThisAttempt)
            val fakeSocket = mockk<WebSocket>(relaxed = true)
            every { fakeSocket.send(capture(sentForThisAttempt)) } returns true
            sockets.add(fakeSocket)
            fakeSocket
        }

        val idCounter = AtomicInteger(0)
        val connection = GatewayConnection(
            role = GatewayClientInfo.ROLE_OPERATOR,
            mode = GatewayClientInfo.MODE_OPERATOR,
            scopes = listOf("operator.read", "operator.write"),
            serverUrl = "wss://gateway.test/ws",
            clientVersion = "0.1-A1",
            platform = "Android",
            deviceFamily = "SM-A057F",
            webSocketFactory = factory,
            deviceIdentityStore = deviceIdentityStore,
            tokenStore = tokenStore,
            scope = scope,
            idGenerator = { "connect-${idCounter.incrementAndGet()}" },
        )
        return MultiHarness(connection, sockets, listeners, sentPerAttempt)
    }

    /**
     * Teaches a mock socket the one piece of `RealWebSocket` semantics that matters here: once the
     * client flushes its own close frame (having already read the peer's), OkHttp delivers
     * `onClosed`. A plain relaxed mock swallows `close()` silently, and that gap is precisely what
     * hid the missing `onClosing` override — a fake socket "noticed" a close the real stack never
     * reports. Tests that assert on a server-initiated close must opt in to this.
     */
    private fun deliverOnClosedWhenClientCloses(socket: WebSocket, listener: WebSocketListener) {
        every { socket.close(any(), any()) } answers {
            listener.onClosed(socket, firstArg(), secondArg<String?>() ?: "")
            true
        }
    }

    /** Like [Harness.sentConnectFrame], for one attempt of a [MultiHarness]. */
    private fun MultiHarness.connectId(attempt: Int): String = GatewayJson.decodeFromString(
        GatewayFrame.Req.serializer(),
        sentPerAttempt[attempt].single { it.contains("\"method\":\"connect\"") },
    ).id

    @Test
    fun `completes the handshake and reaches Connected on hello-ok`() = runTest {
        val identity = Ed25519.generate()
        val tokenStore = FakeTokenStore()
        val h = newHarness(scope = backgroundScope, identity = identity, tokenStore = tokenStore)

        h.connection.state.test {
            assertEquals(ConnState.Disconnected, awaitItem())
            h.connection.connect()
            assertEquals(ConnState.Connecting, awaitItem())

            h.listener().onMessage(h.socket, challengeJson(nonce = "nonce-1", ts = 1_700_000_000_000L))
            runCurrent()
            assertEquals("connect-1", h.sentConnectFrame().id)

            h.listener().onMessage(h.socket, helloOkResJson(id = "connect-1", deviceToken = "tok-abc"))
            runCurrent()

            assertEquals(ConnState.Connected, awaitItem())
        }

        assertEquals("tok-abc", tokenStore.snapshot()[identity.deviceId to GatewayClientInfo.ROLE_OPERATOR])
    }

    @Test
    fun `the connect frame is byte-identical to what was signed`() = runTest {
        val identity = Ed25519.generate()
        val tokenStore = FakeTokenStore(
            seed = mapOf((identity.deviceId to GatewayClientInfo.ROLE_OPERATOR) to "prior-token"),
        )
        val h = newHarness(scope = backgroundScope, identity = identity, tokenStore = tokenStore)

        h.connection.connect()
        h.listener().onMessage(h.socket, challengeJson(nonce = "nonce-xyz", ts = 42L))
        runCurrent()

        val params = GatewayJson.decodeFromJsonElement(ConnectParams.serializer(), h.sentConnectFrame().params!!)
        assertEquals("prior-token", params.auth?.deviceToken)

        val rebuiltPayload = buildDeviceAuthPayloadV3(
            deviceId = params.device.id,
            clientId = params.client.id,
            clientMode = params.client.mode,
            role = params.role,
            scopes = params.scopes,
            signedAtMs = params.device.signedAt,
            token = params.auth?.deviceToken,
            nonce = params.device.nonce,
            platform = params.client.platform,
            deviceFamily = params.client.deviceFamily,
        )
        val signatureBytes = Base64.getUrlDecoder().decode(params.device.signature)
        val publicKeyBytes = Base64.getUrlDecoder().decode(params.device.publicKey)
        assertTrue(Ed25519.verify(publicKeyBytes, rebuiltPayload.toByteArray(Charsets.UTF_8), signatureBytes))

        // Prove the check isn't vacuous: a payload rebuilt from a tampered field must NOT verify.
        val tampered = rebuiltPayload.replace("nonce-xyz", "nonce-tampered")
        assertTrue(!Ed25519.verify(publicKeyBytes, tampered.toByteArray(Charsets.UTF_8), signatureBytes))
    }

    @Test
    fun `an error response fails the connection with the gateway's error code`() = runTest {
        val h = newHarness(scope = backgroundScope)

        h.connection.connect()
        h.listener().onMessage(h.socket, challengeJson("n1", 1L))
        runCurrent()

        // INVALID_REQUEST, not NOT_PAIRED: NOT_PAIRED now has its own AwaitingApproval behaviour,
        // covered separately. This test is about the generic "some other error" -> Failed path.
        // Message from the doc's own example, docs/plugins/admin-http-rpc.md:150-157.
        h.listener().onMessage(h.socket, errorResJson(h.sentConnectFrame().id, "INVALID_REQUEST", "bad params"))
        runCurrent()

        assertEquals(ConnState.Failed, h.connection.state.value)
        assertEquals("INVALID_REQUEST", h.connection.lastError.value?.code)
    }

    @Test
    fun `no connect_challenge within the timeout fails the connection`() = runTest {
        val h = newHarness(scope = backgroundScope)

        h.connection.connect()
        // Nothing ever arrives — the 15s challenge budget must elapse and fail. Advance exactly to
        // that boundary (not advanceUntilIdle): a failure now schedules a reconnect retry, so
        // draining the scheduler fully would run forever in virtual time.
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(ConnState.Failed, h.connection.state.value)
        assertEquals("CHALLENGE_TIMEOUT", h.connection.lastError.value?.code)
    }

    @Test
    fun `a socket close mid-handshake fails immediately, not after the RPC's own 30s timeout`() = runTest {
        val h = newHarness(scope = backgroundScope)

        h.connection.connect()
        h.listener().onMessage(h.socket, challengeJson("n1", 1L))
        runCurrent() // connect req is now in flight, awaiting a res that will never come

        // onClosing, not onClosed: the *server* closed first, and OkHttp 4.12 only ever delivers
        // onClosed once the client has enqueued its own close frame. This test used to call
        // onClosed directly — a callback the real stack would never have delivered here.
        deliverOnClosedWhenClientCloses(h.socket, h.listener())
        h.listener().onClosing(h.socket, 1008, "policy violation")
        runCurrent() // no time advance — must resolve now, not by exhausting the 30s RPC timeout

        assertEquals(ConnState.Failed, h.connection.state.value)
        assertEquals("SOCKET_CLOSED", h.connection.lastError.value?.code)
    }

    @Test
    fun `a server-initiated close while Connected is noticed at once, not 2x tickIntervalMs later`() = runTest {
        val h = newMultiHarness(scope = backgroundScope)
        h.connection.connect()
        h.listeners[0].onMessage(h.sockets[0], challengeJson("n1", 1L))
        runCurrent()
        h.listeners[0].onMessage(h.sockets[0], helloOkResJson(h.connectId(0), tickIntervalMs = 7_000))
        runCurrent()
        assertEquals(ConnState.Connected, h.connection.state.value)

        // `systemctl restart openclaw-gateway`: the gateway closes first, so OkHttp calls onClosing
        // and stops. Before the onClosing override this class learned nothing here and kept
        // reporting Connected until the watchdog fired at 2 * 7_000ms.
        deliverOnClosedWhenClientCloses(h.sockets[0], h.listeners[0])
        h.listeners[0].onClosing(h.sockets[0], 1008, "policy violation")
        runCurrent()

        // Verified with exact arguments, not just a state flip: the fix is "flush the client's own
        // close frame", and a state change alone would not have completed the close handshake.
        verify { h.sockets[0].close(1000, null) }
        assertEquals(ConnState.Failed, h.connection.state.value)
        assertEquals("SOCKET_CLOSED", h.connection.lastError.value?.code)

        // ...and the retry lands on the normal 1s backoff, nowhere near the 14_000ms watchdog.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, h.sockets.size)
    }

    @Test
    fun `two failures within one attempt consume only one step of the backoff curve`() = runTest {
        val h = newMultiHarness(scope = backgroundScope)
        h.connection.connect()
        runCurrent()

        // The shape this guards: a `res` error (or the watchdog's own close(4000)) followed by the
        // socket actually dying. Both reach fail() for the same generation; without the
        // already-settled early-out the curve becomes 1s, 4s, 16s instead of 1s, 2s, 4s.
        h.listeners[0].onFailure(h.sockets[0], IOException("boom"), null)
        runCurrent()
        h.listeners[0].onFailure(h.sockets[0], IOException("and the socket dies too"), null)
        runCurrent()
        assertEquals(ConnState.Failed, h.connection.state.value)
        assertEquals("the first failure is the one reported", "boom", h.connection.lastError.value?.message)

        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, h.sockets.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("the second failure must not have consumed another backoff step", 2, h.sockets.size)
    }

    @Test
    fun `a device identity store that throws fails the connection instead of killing the process`() = runTest {
        // The production trigger: a truncated or undecryptable identity file. Before the handshake
        // was wrapped, this threw out of a root coroutine on an app-lifetime scope — process death,
        // repeated on every reconnect. Here it surfaces as an ordinary, retryable failure.
        val throwingStore = object : DeviceIdentityStore {
            override suspend fun loadOrCreate(): DeviceIdentity =
                throw IllegalArgumentException("Illegal base64 character 21")
        }
        val h = newMultiHarness(scope = backgroundScope, deviceIdentityStore = throwingStore)

        h.connection.connect()
        h.listeners[0].onMessage(h.sockets[0], challengeJson("n1", 1L))
        runCurrent()

        assertEquals(ConnState.Failed, h.connection.state.value)
        assertEquals("HANDSHAKE_ERROR", h.connection.lastError.value?.code)
        assertTrue(
            "the cause must be legible in the surfaced error, not swallowed",
            h.connection.lastError.value?.message?.contains("Illegal base64 character") == true,
        )

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("a handshake exception must still schedule a retry", 2, h.sockets.size)
    }

    @Test
    fun `a server URL OkHttp cannot parse fails the connection instead of throwing at the caller`() = runTest {
        // Boss types `100.64.0.1:18789` into the debug screen with no scheme. connect() runs on the
        // main thread from the CONNECT button, so Request.Builder().url() throwing was a crash.
        val factory = mockk<WebSocket.Factory>()
        val connection = GatewayConnection(
            role = GatewayClientInfo.ROLE_OPERATOR,
            mode = GatewayClientInfo.MODE_OPERATOR,
            scopes = listOf("operator.read"),
            serverUrl = "100.64.0.1:18789",
            clientVersion = "0.1-A1",
            platform = "Android",
            deviceFamily = "SM-A057F",
            webSocketFactory = factory,
            deviceIdentityStore = FakeDeviceIdentityStore(Ed25519.generate()),
            tokenStore = FakeTokenStore(),
            scope = backgroundScope,
        )

        connection.connect() // must not throw

        assertEquals(ConnState.Failed, connection.state.value)
        assertEquals("INVALID_URL", connection.lastError.value?.code)
        // No socket was ever requested — a strict mock with no stubbing would have thrown if it had.
        verify(exactly = 0) { factory.newWebSocket(any(), any()) }

        // And no pointless retry loop: serverUrl is fixed for this instance, so a timer can never
        // heal it. The CONNECT button is the retry.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(ConnState.Failed, connection.state.value)
    }

    @Test
    fun `events other than connect_challenge are forwarded on the public events flow`() = runTest {
        val h = newHarness(scope = backgroundScope)

        h.connection.events.test {
            h.connection.connect()
            h.listener().onMessage(h.socket, challengeJson("n1", 1L))
            runCurrent()
            h.listener().onMessage(h.socket, helloOkResJson(h.sentConnectFrame().id))
            runCurrent()

            val tickJson = GatewayJson.encodeToString(
                GatewayFrame.Event.serializer(),
                GatewayFrame.Event(event = "tick", payload = buildJsonObject { put("ts", 1L) }),
            )
            h.listener().onMessage(h.socket, tickJson)

            assertEquals("tick", awaitItem().event)
        }
    }

    @Test
    fun `no frames are sent before the challenge is delivered`() = runTest {
        val h = newHarness(scope = backgroundScope)

        h.connection.connect()
        runCurrent()

        assertTrue("connect() must not send anything before connect.challenge arrives", h.sent.isEmpty())
    }

    @Test
    fun `a stale attempt cannot hijack a newer one`() = runTest {
        val identity = Ed25519.generate()
        val tokenStore = FakeTokenStore()
        val h = newMultiHarness(scope = backgroundScope, identity = identity, tokenStore = tokenStore)

        // Attempt #1: opens a socket, never receives a challenge — stalls indefinitely.
        h.connection.connect()
        runCurrent()
        assertEquals(1, h.sockets.size)

        // Torn down before it ever completes its handshake.
        h.connection.disconnect()

        // Attempt #2: a fresh socket, full successful handshake.
        h.connection.connect()
        runCurrent()
        assertEquals(2, h.sockets.size)

        h.listeners[1].onMessage(h.sockets[1], challengeJson("nonce-2", 2L))
        runCurrent()
        val attempt2Id = GatewayJson.decodeFromString(
            GatewayFrame.Req.serializer(),
            h.sentPerAttempt[1].single { it.contains("\"method\":\"connect\"") },
        ).id
        h.listeners[1].onMessage(h.sockets[1], helloOkResJson(attempt2Id, deviceToken = "tok-attempt-2"))
        runCurrent()

        assertEquals(ConnState.Connected, h.connection.state.value)

        // A late failure from the dead attempt #1 socket must not be able to touch attempt #2's state.
        h.listeners[0].onFailure(h.sockets[0], IOException("stale socket died"), null)
        runCurrent()
        assertEquals(ConnState.Connected, h.connection.state.value)

        // Advancing time past attempt #1's 15s challenge budget must also leave attempt #2 untouched
        // (belt-and-suspenders: disconnect() already cancels attempt #1's coroutine outright, so by
        // this point there may be nothing left to time out at all — the assertion holds either way).
        // advanceTimeBy + runCurrent, not advanceUntilIdle: attempt #2 is Connected with a watchdog
        // armed 30s out (2x the 15s default tickIntervalMs in helloOkResJson), and draining the
        // scheduler fully would run past that and fail the connection this test is proving survived.
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(ConnState.Connected, h.connection.state.value)

        // And attempt #2's token — not some artifact of attempt #1 — is what's persisted.
        assertEquals("tok-attempt-2", tokenStore.snapshot()[identity.deviceId to GatewayClientInfo.ROLE_OPERATOR])
    }

    // ---- Task 5: reconnect backoff, AwaitingApproval, watchdog, seq gaps ----

    @Test
    fun `reconnect backoff increases exponentially across consecutive failures, capped at 30s`() = runTest {
        val h = newMultiHarness(scope = backgroundScope)
        h.connection.connect()
        runCurrent()
        assertEquals(1, h.sockets.size)

        val expectedDelays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        for (delayMs in expectedDelays) {
            val beforeCount = h.sockets.size
            h.listeners[beforeCount - 1].onFailure(h.sockets[beforeCount - 1], IOException("boom"), null)
            runCurrent()
            assertEquals(ConnState.Failed, h.connection.state.value)
            assertEquals("no retry scheduled synchronously with the failure", beforeCount, h.sockets.size)

            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertEquals("must not retry before ${delayMs}ms", beforeCount, h.sockets.size)

            advanceTimeBy(1)
            runCurrent()
            assertEquals("must retry at exactly ${delayMs}ms", beforeCount + 1, h.sockets.size)
        }
    }

    @Test
    fun `a successful hello-ok resets the backoff to 1s for the next failure`() = runTest {
        val h = newMultiHarness(scope = backgroundScope)
        h.connection.connect()
        runCurrent()

        // First failure retries after 1s (attempt 0 of the exponential curve).
        h.listeners[0].onFailure(h.sockets[0], IOException("boom"), null)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, h.sockets.size)

        // Complete attempt #2 successfully.
        h.listeners[1].onMessage(h.sockets[1], challengeJson("nonce-b", 1L))
        runCurrent()
        h.listeners[1].onMessage(h.sockets[1], helloOkResJson(h.connectId(1)))
        runCurrent()
        assertEquals(ConnState.Connected, h.connection.state.value)

        // A fresh failure now must retry after 1s again, NOT 4s (the next un-reset value in the
        // exponential sequence) — proving the successful hello-ok reset the attempt counter.
        h.listeners[1].onFailure(h.sockets[1], IOException("dropped"), null)
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(2, h.sockets.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, h.sockets.size)
    }

    @Test
    fun `NOT_PAIRED enters AwaitingApproval and keeps retrying rather than terminating`() = runTest {
        // NOT_PAIRED — the closed six-value ErrorCodes enum, schema-BuOFpc7K.js:2646-2659:
        // "Device exists but still needs an explicit pairing approval." Documented retryable/poll,
        // protocol-reference-handshake.md §1.6.
        val h = newMultiHarness(scope = backgroundScope)
        h.connection.connect()
        h.listeners[0].onMessage(h.sockets[0], challengeJson("n1", 1L))
        runCurrent()
        h.listeners[0].onMessage(h.sockets[0], errorResJson(h.connectId(0), "NOT_PAIRED", "device pending approval"))
        runCurrent()

        assertEquals(ConnState.AwaitingApproval, h.connection.state.value)
        assertEquals("NOT_PAIRED", h.connection.lastError.value?.code)

        // Not terminal: the normal backoff still schedules a retry.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, h.sockets.size)
    }

    @Test
    fun `PAIRING_REQUIRED in error details also enters AwaitingApproval`() = runTest {
        // PAIRING_REQUIRED — the connect-failure error.details.code namespace (a separate
        // namespace from the top-level ErrorCodes enum), docs/gateway/troubleshooting.md:347-356:
        // "needs approval... show pending state, poll." Also docs/gateway/protocol.md:802-806:
        // "Keep reconnecting with the same bootstrap token until the request is approved."
        val h = newHarness(scope = backgroundScope)
        h.connection.connect()
        h.listener().onMessage(h.socket, challengeJson("n1", 1L))
        runCurrent()

        val detailsError = GatewayJson.encodeToString(
            GatewayFrame.Res.serializer(),
            GatewayFrame.Res(
                id = h.sentConnectFrame().id,
                ok = false,
                error = GatewayError(
                    code = "UNAUTHORIZED", // top-level code is generic; the pairing signal is in details.code
                    message = "pairing required",
                    details = buildJsonObject { put("code", "PAIRING_REQUIRED"); put("reason", "not-paired") },
                ),
            ),
        )
        h.listener().onMessage(h.socket, detailsError)
        runCurrent()

        assertEquals(ConnState.AwaitingApproval, h.connection.state.value)
    }

    @Test
    fun `UNAVAILABLE with startup-sidecars reason honours retryAfterMs instead of policy backoff`() = runTest {
        val h = newMultiHarness(scope = backgroundScope)
        h.connection.connect()
        h.listeners[0].onMessage(h.sockets[0], challengeJson("n1", 1L))
        runCurrent()

        val err = GatewayJson.encodeToString(
            GatewayFrame.Res.serializer(),
            GatewayFrame.Res(
                id = h.connectId(0),
                ok = false,
                error = GatewayError(
                    code = "UNAVAILABLE",
                    message = "starting up",
                    details = buildJsonObject { put("reason", "startup-sidecars") },
                    retryable = true,
                    retryAfterMs = 5_000,
                ),
            ),
        )
        h.listeners[0].onMessage(h.sockets[0], err)
        runCurrent()
        assertEquals(ConnState.Failed, h.connection.state.value)

        // Must NOT use the default policy delay (1000ms would be attempt 0's normal backoff).
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("policy backoff must be overridden by retryAfterMs", 1, h.sockets.size)

        advanceTimeBy(3_999) // total elapsed since the error: 4_999ms
        runCurrent()
        assertEquals(1, h.sockets.size)

        advanceTimeBy(1) // total elapsed: 5_000ms == retryAfterMs
        runCurrent()
        assertEquals(2, h.sockets.size)
    }

    @Test
    fun `watchdog fires at exactly 2x the server's tickIntervalMs and reconnects`() = runTest {
        // 7_000ms deliberately: neither doc example value (15_000 / 30_000) for tickIntervalMs, so
        // a hardcoded watchdog interval would fail this test.
        val h = newMultiHarness(scope = backgroundScope)
        h.connection.connect()
        h.listeners[0].onMessage(h.sockets[0], challengeJson("n1", 1L))
        runCurrent()
        h.listeners[0].onMessage(h.sockets[0], helloOkResJson(h.connectId(0), tickIntervalMs = 7_000))
        runCurrent()
        assertEquals(ConnState.Connected, h.connection.state.value)

        advanceTimeBy(13_999) // 2 * 7_000 - 1
        runCurrent()
        assertEquals("watchdog must not fire before 2x tickIntervalMs", ConnState.Connected, h.connection.state.value)
        assertEquals(1, h.sockets.size)

        advanceTimeBy(1) // exactly 14_000ms of silence
        runCurrent()
        verify { h.sockets[0].close(4000, any()) }
        assertEquals(ConnState.Failed, h.connection.state.value)
    }

    @Test
    fun `an inbound frame resets the watchdog so it does not fire on the original schedule`() = runTest {
        val h = newMultiHarness(scope = backgroundScope)
        h.connection.connect()
        h.listeners[0].onMessage(h.sockets[0], challengeJson("n1", 1L))
        runCurrent()
        h.listeners[0].onMessage(h.sockets[0], helloOkResJson(h.connectId(0), tickIntervalMs = 7_000))
        runCurrent()

        advanceTimeBy(13_000) // close to the original 14_000ms deadline, but not there yet
        runCurrent()
        val tickJson = GatewayJson.encodeToString(
            GatewayFrame.Event.serializer(),
            GatewayFrame.Event(event = "tick", payload = buildJsonObject { put("ts", 1L) }),
        )
        h.listeners[0].onMessage(h.sockets[0], tickJson) // any inbound frame resets the watchdog clock
        runCurrent()

        advanceTimeBy(13_999) // just under 14_000ms since the reset
        runCurrent()
        assertEquals("the reset must hold — this would be 26_999ms since hello-ok on the original clock", ConnState.Connected, h.connection.state.value)

        advanceTimeBy(1) // exactly 14_000ms since the reset
        runCurrent()
        assertEquals(ConnState.Failed, h.connection.state.value)
    }

    @Test
    fun `a seq gap is exposed for the caller to refetch, without requesting replay`() = runTest {
        val h = newHarness(scope = backgroundScope)
        h.connection.seqGaps.test {
            h.connection.connect()
            h.listener().onMessage(h.socket, challengeJson("n1", 1L))
            runCurrent()
            h.listener().onMessage(h.socket, helloOkResJson(h.sentConnectFrame().id))
            runCurrent()

            fun eventWithSeq(seq: Long) = GatewayJson.encodeToString(
                GatewayFrame.Event.serializer(),
                GatewayFrame.Event(event = "presence", payload = buildJsonObject {}, seq = seq),
            )
            h.listener().onMessage(h.socket, eventWithSeq(1))
            h.listener().onMessage(h.socket, eventWithSeq(2))
            h.listener().onMessage(h.socket, eventWithSeq(5)) // gap: expected 3, got 5
            runCurrent()

            val gap = awaitItem()
            assertEquals(3L, gap.expected)
            assertEquals(5L, gap.actual)
        }
    }

    @Test
    fun `a manual disconnect during the backoff window cancels the scheduled retry`() = runTest {
        // Regression test for the generation guard interacting with the retry loop: the retry loop
        // schedules connect()/disconnect() on a timer, so a failure followed by an explicit
        // disconnect() before that timer fires must NOT resurrect the dead attempt when the timer
        // eventually runs. This is exactly what [GatewayConnection]'s generation counter exists to
        // prevent. Without the `gen != generation.get()` check in the reconnect continuation, this
        // test fails: the stale retry fires anyway, opening a second socket and moving the
        // connection back to Connecting after the caller explicitly asked it to stop.
        val h = newMultiHarness(scope = backgroundScope)
        h.connection.connect()
        runCurrent()
        assertEquals(1, h.sockets.size)

        // Challenge never arrives — the 15s budget elapses, Failed, and a retry is scheduled 1s out.
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(ConnState.Failed, h.connection.state.value)
        assertEquals(1, h.sockets.size)

        // The user (or a lifecycle callback) explicitly disconnects before the scheduled retry fires.
        h.connection.disconnect()
        assertEquals(ConnState.Disconnected, h.connection.state.value)

        // Advance well past the 1s the retry was scheduled for.
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals("a stale scheduled retry must not resurrect after an explicit disconnect", 1, h.sockets.size)
        assertEquals(ConnState.Disconnected, h.connection.state.value)
    }

    // ---- Fix round 1: AwaitingApproval must survive the pairing-required close ----

    @Test
    fun `a 1008 close following NOT_PAIRED does not clobber AwaitingApproval`() = runTest {
        val h = newHarness(scope = backgroundScope)
        h.connection.connect()
        h.listener().onMessage(h.socket, challengeJson("n1", 1L))
        runCurrent()
        h.listener().onMessage(h.socket, errorResJson(h.sentConnectFrame().id, "NOT_PAIRED", "device pending approval"))
        runCurrent()
        assertEquals(ConnState.AwaitingApproval, h.connection.state.value)

        // The gateway's own unpaired-connect sequence (message-handler-CzwI6JjW.js) sends this
        // NOT_PAIRED res, then closes 1008 — the close must not downgrade the state just set.
        h.listener().onClosed(h.socket, 1008, "pairing required")
        runCurrent()

        assertEquals(ConnState.AwaitingApproval, h.connection.state.value)
    }

    @Test
    fun `the retry loop stays in AwaitingApproval across several NOT_PAIRED cycles, without ever failing`() = runTest {
        val h = newMultiHarness(scope = backgroundScope)
        val observedStates = mutableListOf<ConnState>()
        val collectJob = backgroundScope.launch { h.connection.state.collect { observedStates.add(it) } }

        h.connection.connect()
        runCurrent()

        // reconnectAttempt only resets on a successful hello-ok, which never happens in this test —
        // so, unlike the single-cycle test above, the backoff keeps growing cycle over cycle.
        val delays = listOf(1_000L, 2_000L, 4_000L)
        for ((attempt, delayMs) in delays.withIndex()) {
            h.listeners[attempt].onMessage(h.sockets[attempt], challengeJson("n$attempt", attempt.toLong()))
            runCurrent()
            h.listeners[attempt].onMessage(h.sockets[attempt], errorResJson(h.connectId(attempt), "NOT_PAIRED", "device pending approval"))
            runCurrent()
            h.listeners[attempt].onClosed(h.sockets[attempt], 1008, "pairing required")
            runCurrent()
            assertEquals(ConnState.AwaitingApproval, h.connection.state.value)

            // The settled state through the *entire* backoff wait must read AwaitingApproval — this
            // is what "Boss sees a stable waiting-for-approval" means: sampled at any point during
            // the (up to 30s) wait, not just in the instant right after the error arrives.
            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertEquals(
                "state must still read AwaitingApproval mid-wait (cycle $attempt), not stuck at Disconnected",
                ConnState.AwaitingApproval,
                h.connection.state.value,
            )

            advanceTimeBy(1) // the next attempt starts
            runCurrent()
        }

        collectJob.cancel()
        // A brief Disconnected->Connecting blip at the instant each new attempt starts is expected
        // (connect()/disconnect() are unconditional — see scheduleReconnect's doc) and not asserted
        // against here. Failed is different: nothing in a pure NOT_PAIRED loop should ever produce
        // it, so its total absence across the whole run is a meaningful, achievable assertion.
        assertTrue(
            "Failed must never appear while the server only ever says NOT_PAIRED",
            observedStates.none { it == ConnState.Failed },
        )
    }
}
