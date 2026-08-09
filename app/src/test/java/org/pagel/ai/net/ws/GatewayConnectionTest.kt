@file:OptIn(ExperimentalCoroutinesApi::class)

package org.pagel.ai.net.ws

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.util.Base64

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

    private fun helloOkResJson(id: String, deviceToken: String? = null, role: String = "operator") = GatewayJson.encodeToString(
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
                        put("tickIntervalMs", 15_000)
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

    @Test
    fun `completes the handshake and reaches Connected on hello-ok`() = runTest {
        val identity = Ed25519.generate()
        val tokenStore = FakeTokenStore()
        val h = newHarness(scope = this, identity = identity, tokenStore = tokenStore)

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
        val h = newHarness(scope = this, identity = identity, tokenStore = tokenStore)

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
        val h = newHarness(scope = this)

        h.connection.connect()
        h.listener().onMessage(h.socket, challengeJson("n1", 1L))
        runCurrent()

        h.listener().onMessage(h.socket, errorResJson(h.sentConnectFrame().id, "NOT_PAIRED", "awaiting approval"))
        runCurrent()

        assertEquals(ConnState.Failed, h.connection.state.value)
        assertEquals("NOT_PAIRED", h.connection.lastError.value?.code)
    }

    @Test
    fun `no connect_challenge within the timeout fails the connection`() = runTest {
        val h = newHarness(scope = this)

        h.connection.connect()
        advanceUntilIdle() // nothing ever arrives — the 15s challenge budget must elapse and fail

        assertEquals(ConnState.Failed, h.connection.state.value)
        assertEquals("CHALLENGE_TIMEOUT", h.connection.lastError.value?.code)
    }

    @Test
    fun `a socket close mid-handshake fails immediately, not after the RPC's own 30s timeout`() = runTest {
        val h = newHarness(scope = this)

        h.connection.connect()
        h.listener().onMessage(h.socket, challengeJson("n1", 1L))
        runCurrent() // connect req is now in flight, awaiting a res that will never come

        h.listener().onClosed(h.socket, 1008, "policy violation")
        runCurrent() // no time advance — must resolve now, not by exhausting the 30s RPC timeout

        assertEquals(ConnState.Failed, h.connection.state.value)
        assertEquals("SOCKET_CLOSED", h.connection.lastError.value?.code)
    }

    @Test
    fun `events other than connect_challenge are forwarded on the public events flow`() = runTest {
        val h = newHarness(scope = this)

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
}
