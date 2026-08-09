@file:OptIn(ExperimentalCoroutinesApi::class)

package org.pagel.ai.net

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.net.identity.DeviceIdentity
import org.pagel.ai.net.identity.DeviceIdentityStore
import org.pagel.ai.net.identity.Ed25519
import org.pagel.ai.net.identity.buildDeviceAuthPayloadV3
import org.pagel.ai.net.ws.ConnState
import org.pagel.ai.net.ws.ConnectParams
import org.pagel.ai.net.ws.GatewayClientInfo
import org.pagel.ai.net.ws.GatewayDeviceTokenStore
import org.pagel.ai.net.ws.GatewayError
import org.pagel.ai.net.ws.GatewayFrame
import org.pagel.ai.net.ws.GatewayJson
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * [GatewayManager] owns two [org.pagel.ai.net.ws.GatewayConnection]s (operator + node) sharing one
 * device identity. These tests never assume which of the manager's two connections opens its
 * socket first — every scenario identifies "the operator one" / "the node one" from the `role`
 * field of the `connect` frame each socket actually sent, per the manager's own doc.
 */
class GatewayManagerTest {

    private class FakeDeviceIdentityStore(private val identity: DeviceIdentity) : DeviceIdentityStore {
        override suspend fun loadOrCreate(): DeviceIdentity = identity
    }

    /**
     * Proves `loadOrCreate()` is invoked concurrently by both connections rather than serialized:
     * suspends until a *second*, overlapping call arrives before either returns. A manager that
     * (wrongly) waited for one connection's handshake to make progress before starting the other's
     * would leave this permanently unresolved for both callers — the "both connect frames were
     * sent" assertion then fails outright rather than the test hanging, because the stuck
     * coroutines live in [kotlinx.coroutines.test.TestScope.backgroundScope] and are cancelled at
     * test end instead of blocking `runTest`.
     */
    private class RendezvousDeviceIdentityStore(private val identity: DeviceIdentity) : DeviceIdentityStore {
        private val entrants = AtomicInteger(0)
        private val bothEntered = CompletableDeferred<Unit>()

        override suspend fun loadOrCreate(): DeviceIdentity {
            if (entrants.incrementAndGet() == 2) bothEntered.complete(Unit)
            bothEntered.await()
            return identity
        }
    }

    private class FakeTokenStore : GatewayDeviceTokenStore {
        private val tokens = mutableMapOf<Pair<String, String>, String>()
        override suspend fun get(deviceId: String, role: String): String? = tokens[deviceId to role]
        override suspend fun put(deviceId: String, role: String, token: String) {
            tokens[deviceId to role] = token
        }
        fun snapshot(): Map<Pair<String, String>, String> = tokens.toMap()
    }

    private data class Harness(
        val manager: GatewayManager,
        val sockets: MutableList<WebSocket>,
        val listeners: MutableList<WebSocketListener>,
        val sentPerSocket: MutableList<MutableList<String>>,
    )

    private fun newHarness(
        scope: CoroutineScope,
        identity: DeviceIdentity = Ed25519.generate(),
        deviceIdentityStore: DeviceIdentityStore = FakeDeviceIdentityStore(identity),
        tokenStore: FakeTokenStore = FakeTokenStore(),
    ): Harness {
        val sockets = mutableListOf<WebSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val sentPerSocket = mutableListOf<MutableList<String>>()
        val factory = mockk<WebSocket.Factory>()
        every { factory.newWebSocket(any(), any()) } answers {
            listeners.add(secondArg())
            val sent = mutableListOf<String>()
            sentPerSocket.add(sent)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(capture(sent)) } returns true
            sockets.add(socket)
            socket
        }

        val manager = GatewayManager(
            webSocketFactory = factory,
            deviceIdentityStore = deviceIdentityStore,
            tokenStore = tokenStore,
            config = GatewayConnectionConfig(
                serverUrl = "wss://gateway.test/ws",
                clientVersion = "0.1-A1",
                platform = "Android",
                deviceFamily = "SM-A057F",
            ),
            scope = scope,
        )
        return Harness(manager, sockets, listeners, sentPerSocket)
    }

    private fun challengeJson(nonce: String, ts: Long) = GatewayJson.encodeToString(
        GatewayFrame.Event.serializer(),
        GatewayFrame.Event(event = "connect.challenge", payload = buildJsonObject { put("nonce", nonce); put("ts", ts) }),
    )

    private fun helloOkResJson(id: String, role: String, deviceToken: String?) = GatewayJson.encodeToString(
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
                        putJsonArray("scopes") {}
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

    /** Delivers a `connect.challenge` to every socket the harness has opened so far, regardless of
     *  which connection (operator or node) each one belongs to. */
    private fun Harness.deliverChallengeToAll() {
        sockets.indices.forEach { i -> listeners[i].onMessage(sockets[i], challengeJson("nonce-$i", i.toLong())) }
    }

    private fun Harness.sentConnectLine(index: Int): String =
        sentPerSocket[index].single { it.contains("\"method\":\"connect\"") }

    private fun Harness.connectParams(index: Int): ConnectParams {
        val req = GatewayJson.decodeFromString(GatewayFrame.Req.serializer(), sentConnectLine(index))
        return GatewayJson.decodeFromJsonElement(ConnectParams.serializer(), req.params!!)
    }

    private fun Harness.connectId(index: Int): String =
        GatewayJson.decodeFromString(GatewayFrame.Req.serializer(), sentConnectLine(index)).id

    /** Index whose sent `connect` frame carries [role] — order-independent, since [GatewayManager]
     *  gives no guarantee which of its two connections opens its socket first. */
    private fun Harness.indexForRole(role: String): Int =
        sockets.indices.firstOrNull { i -> sentPerSocket[i].any { it.contains("\"method\":\"connect\"") } && connectParams(i).role == role }
            ?: -1

    @Test
    fun `operator reaching Connected does not imply the node connection has`() = runTest {
        val h = newHarness(scope = backgroundScope)
        h.manager.start()
        runCurrent()
        assertEquals(2, h.sockets.size)
        assertEquals(ConnState.Connecting, h.manager.operatorState.value)
        assertEquals(ConnState.Connecting, h.manager.nodeState.value)

        h.deliverChallengeToAll()
        runCurrent()

        val opIdx = h.indexForRole(GatewayClientInfo.ROLE_OPERATOR)
        val nodeIdx = h.indexForRole(GatewayClientInfo.ROLE_NODE)
        assertTrue("both roles must be present among the two sockets", opIdx >= 0 && nodeIdx >= 0 && opIdx != nodeIdx)

        // Operator completes its handshake; node instead hits NOT_PAIRED — a completely normal,
        // independent outcome per role, not a state the manager should collapse together.
        h.listeners[opIdx].onMessage(h.sockets[opIdx], helloOkResJson(h.connectId(opIdx), "operator", "tok-op"))
        h.listeners[nodeIdx].onMessage(h.sockets[nodeIdx], errorResJson(h.connectId(nodeIdx), "NOT_PAIRED", "device pending approval"))
        runCurrent()

        assertEquals(ConnState.Connected, h.manager.operatorState.value)
        assertEquals(ConnState.AwaitingApproval, h.manager.nodeState.value)
        assertEquals(GatewayStatus(ConnState.Connected, ConnState.AwaitingApproval), h.manager.status.value)
        // Task 8's debug screen reads these directly — prove the manager exposes the *right*
        // connection's error under each name, not a collapsed/shared one.
        assertEquals(null, h.manager.operatorLastError.value)
        assertEquals("NOT_PAIRED", h.manager.nodeLastError.value?.code)
    }

    @Test
    fun `operator and node connections carry the documented role, mode, and scopes`() = runTest {
        val h = newHarness(scope = backgroundScope)
        h.manager.start()
        runCurrent()
        h.deliverChallengeToAll()
        runCurrent()

        val opIdx = h.indexForRole(GatewayClientInfo.ROLE_OPERATOR)
        val nodeIdx = h.indexForRole(GatewayClientInfo.ROLE_NODE)
        val opParams = h.connectParams(opIdx)
        val nodeParams = h.connectParams(nodeIdx)

        assertEquals("operator", opParams.role)
        // Literal, not GatewayClientInfo.MODE_OPERATOR: the constant and this assertion must not
        // both silently drift to the stale protocol.md:61 value ("operator") together — pin the
        // actual wire value the server needs.
        assertEquals("ui", opParams.client.mode)
        assertEquals(listOf("operator.read", "operator.write"), opParams.scopes)

        assertEquals("node", nodeParams.role)
        assertEquals("node", nodeParams.client.mode)
        assertTrue("node pairing eligibility requires an empty scope list", nodeParams.scopes.isEmpty())
    }

    @Test
    fun `both connections sign with the same deviceId - one identity, not two`() = runTest {
        val identity = Ed25519.generate()
        val h = newHarness(scope = backgroundScope, identity = identity)
        h.manager.start()
        runCurrent()
        h.deliverChallengeToAll()
        runCurrent()

        val params0 = h.connectParams(0)
        val params1 = h.connectParams(1)
        assertEquals(identity.deviceId, params0.device.id)
        assertEquals(identity.deviceId, params1.device.id)
        assertEquals(params0.device.publicKey, params1.device.publicKey)
    }

    @Test
    fun `each connection signs its own payload - a signature is never reusable across roles`() = runTest {
        val identity = Ed25519.generate()
        val h = newHarness(scope = backgroundScope, identity = identity)
        h.manager.start()
        runCurrent()
        h.deliverChallengeToAll()
        runCurrent()

        val opParams = h.connectParams(h.indexForRole(GatewayClientInfo.ROLE_OPERATOR))
        val nodeParams = h.connectParams(h.indexForRole(GatewayClientInfo.ROLE_NODE))

        assertNotEquals(opParams.role, nodeParams.role)
        assertNotEquals(opParams.device.signature, nodeParams.device.signature)

        fun rebuild(p: ConnectParams) = buildDeviceAuthPayloadV3(
            deviceId = p.device.id,
            clientId = p.client.id,
            clientMode = p.client.mode,
            role = p.role,
            scopes = p.scopes,
            signedAtMs = p.device.signedAt,
            token = p.auth?.deviceToken,
            nonce = p.device.nonce,
            platform = p.client.platform,
            deviceFamily = p.client.deviceFamily,
        )

        val opPayload = rebuild(opParams)
        val nodePayload = rebuild(nodeParams)
        assertNotEquals("the signed role differs, so the signed payload string must too", opPayload, nodePayload)

        val pubKey = Base64.getUrlDecoder().decode(opParams.device.publicKey)
        val opSig = Base64.getUrlDecoder().decode(opParams.device.signature)
        val nodeSig = Base64.getUrlDecoder().decode(nodeParams.device.signature)

        assertTrue(Ed25519.verify(pubKey, opPayload.toByteArray(Charsets.UTF_8), opSig))
        assertTrue(Ed25519.verify(pubKey, nodePayload.toByteArray(Charsets.UTF_8), nodeSig))

        // The sharp edge this whole test exists for: the operator's signature must NOT verify
        // against the node's payload (or vice versa) — this is the property that breaks if someone
        // "optimises" by signing once and reusing the signature for both connections.
        assertTrue(!Ed25519.verify(pubKey, nodePayload.toByteArray(Charsets.UTF_8), opSig))
        assertTrue(!Ed25519.verify(pubKey, opPayload.toByteArray(Charsets.UTF_8), nodeSig))
    }

    @Test
    fun `device tokens from both hello-oks land under different role keys without overwriting`() = runTest {
        val identity = Ed25519.generate()
        val tokenStore = FakeTokenStore()
        val h = newHarness(scope = backgroundScope, identity = identity, tokenStore = tokenStore)
        h.manager.start()
        runCurrent()
        h.deliverChallengeToAll()
        runCurrent()

        val opIdx = h.indexForRole(GatewayClientInfo.ROLE_OPERATOR)
        val nodeIdx = h.indexForRole(GatewayClientInfo.ROLE_NODE)

        h.listeners[opIdx].onMessage(h.sockets[opIdx], helloOkResJson(h.connectId(opIdx), "operator", "tok-operator"))
        h.listeners[nodeIdx].onMessage(h.sockets[nodeIdx], helloOkResJson(h.connectId(nodeIdx), "node", "tok-node"))
        runCurrent()

        val snapshot = tokenStore.snapshot()
        assertEquals("tok-operator", snapshot[identity.deviceId to GatewayClientInfo.ROLE_OPERATOR])
        assertEquals("tok-node", snapshot[identity.deviceId to GatewayClientInfo.ROLE_NODE])
        assertEquals("no key collision — both tokens must be present", 2, snapshot.size)
    }

    @Test
    fun `start does not serialize the two connections' loadOrCreate calls`() = runTest {
        val identity = Ed25519.generate()
        val h = newHarness(
            scope = backgroundScope,
            identity = identity,
            deviceIdentityStore = RendezvousDeviceIdentityStore(identity),
        )
        h.manager.start()
        runCurrent()
        assertEquals(2, h.sockets.size)

        h.deliverChallengeToAll()
        runCurrent()

        // If start() had serialized the two handshakes (e.g. awaited one connection's hello-ok
        // before starting the other's connect()), RendezvousDeviceIdentityStore's second
        // loadOrCreate() call would never arrive, both attempts would stay stuck inside
        // loadOrCreate() forever, and neither connect frame would ever be sent.
        assertTrue(sentPerSocketHasConnect(h, 0))
        assertTrue(sentPerSocketHasConnect(h, 1))
    }

    private fun sentPerSocketHasConnect(h: Harness, index: Int): Boolean =
        h.sentPerSocket[index].any { it.contains("\"method\":\"connect\"") }

    @Test
    fun `stop disconnects both connections`() = runTest {
        val h = newHarness(scope = backgroundScope)
        h.manager.start()
        runCurrent()
        assertEquals(ConnState.Connecting, h.manager.operatorState.value)
        assertEquals(ConnState.Connecting, h.manager.nodeState.value)

        h.manager.stop()

        assertEquals(ConnState.Disconnected, h.manager.operatorState.value)
        assertEquals(ConnState.Disconnected, h.manager.nodeState.value)
    }
}
