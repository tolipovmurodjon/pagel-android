package org.pagel.ai.net.ws

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.pagel.ai.net.identity.DeviceIdentity
import org.pagel.ai.net.identity.DeviceIdentityStore
import org.pagel.ai.net.identity.Ed25519
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The one test in this package that runs a [GatewayConnection] over a **real OkHttp WebSocket**,
 * against a loopback [MockWebServer], on real threads and real time.
 *
 * Every other test drives a mocked `WebSocket` and hand-invokes listener callbacks, which cannot
 * reproduce OkHttp's own semantics — and that blind spot is exactly what hid a bug: a fake socket
 * happily delivers `onClosed` for a server-initiated close, whereas `RealWebSocket.onReadClose`
 * calls `onClosing` and stops unless the client has already enqueued its own close frame. So this
 * test deliberately asserts the two things only the real stack can prove: that the client's *first*
 * frame on the wire is the `connect` req, and that a close initiated by the **server** is noticed.
 *
 * Real time, so every wait is bounded well below the timeouts it must not be confused with: the
 * server advertises `tickIntervalMs: 15_000`, putting the watchdog 30s out, and nothing here waits
 * longer than [AWAIT_SECONDS].
 */
class GatewayConnectionSocketTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var scope: CoroutineScope
    private var connection: GatewayConnection? = null

    /** The frames the *server* received from the client, in wire order. */
    private val clientFrames = LinkedBlockingQueue<String>()
    private val serverSocket = AtomicReference<WebSocket>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.set(webSocket)
                    // The gateway sends connect.challenge unsolicited, immediately after upgrade.
                    webSocket.send(challengeJson(nonce = "nonce-real", ts = 1_700_000_000_000L))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    clientFrames.put(text)
                }
            }),
        )
        server.start()

        // Same transport settings GatewayModule provides in production.
        client = OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        connection?.disconnect() // stop the backoff loop before the server goes away
        scope.cancel()
        server.shutdown()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `a real socket sends connect first, reaches Connected, and notices a server-initiated close`() {
        val identity = Ed25519.generate()
        val connection = GatewayConnection(
            role = GatewayClientInfo.ROLE_OPERATOR,
            mode = GatewayClientInfo.MODE_OPERATOR,
            scopes = listOf("operator.read", "operator.write"),
            serverUrl = server.url("/ws").toString(),
            clientVersion = "0.1-A1",
            platform = "Android",
            deviceFamily = "SM-A057F",
            webSocketFactory = client,
            deviceIdentityStore = object : DeviceIdentityStore {
                override suspend fun loadOrCreate(): DeviceIdentity = identity
            },
            tokenStore = object : GatewayDeviceTokenStore {
                override suspend fun get(deviceId: String, role: String): String? = null
                override suspend fun put(deviceId: String, role: String, token: String) = Unit
            },
            scope = scope,
        ).also { this.connection = it }

        connection.connect()

        // The very first thing the client puts on the wire must be the connect req — read off the
        // head of the queue, so "nothing preceded it" is proven by construction rather than asserted.
        val firstFrame = clientFrames.poll(AWAIT_SECONDS, TimeUnit.SECONDS)
        assertNotNull("the client sent no frame at all", firstFrame)
        val req = GatewayJson.decodeFromString(GatewayFrame.Req.serializer(), firstFrame!!)
        assertEquals("connect", req.method)
        assertEquals("req", req.type)

        val params = GatewayJson.decodeFromJsonElement(ConnectParams.serializer(), req.params!!)
        assertEquals("nonce-real", params.device.nonce)
        assertEquals(identity.deviceId, params.device.id)

        serverSocket.get().send(helloOkResJson(req.id))
        awaitState(connection, ConnState.Connected)

        // The gateway closes first — `systemctl restart openclaw-gateway`. OkHttp delivers only
        // onClosing here; before that override existed, this connection kept reading Connected
        // until the watchdog fired 30s later, so AWAIT_SECONDS passing at all is the assertion.
        serverSocket.get().close(1008, "policy violation")
        awaitState(connection, ConnState.Failed)
        assertTrue(
            "the failure must name the close, not a timeout: ${connection.lastError.value}",
            connection.lastError.value?.code == "SOCKET_CLOSED",
        )
    }

    private fun awaitState(connection: GatewayConnection, target: ConnState) = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) {
            connection.state.first { it == target }
        }
    }

    private fun challengeJson(nonce: String, ts: Long) = GatewayJson.encodeToString(
        GatewayFrame.Event.serializer(),
        GatewayFrame.Event(event = "connect.challenge", payload = buildJsonObject { put("nonce", nonce); put("ts", ts) }),
    )

    private fun helloOkResJson(id: String) = GatewayJson.encodeToString(
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
                        put("role", "operator")
                        putJsonArray("scopes") { add("operator.read"); add("operator.write") }
                    },
                )
                put(
                    "policy",
                    buildJsonObject {
                        put("maxPayload", 26_214_400)
                        put("maxBufferedBytes", 10_485_760)
                        // 15s => a 30s watchdog, twice AWAIT_SECONDS, so nothing here can be
                        // mistaken for the watchdog doing the work.
                        put("tickIntervalMs", 15_000)
                    },
                )
            },
        ),
    )

    private companion object {
        const val AWAIT_SECONDS = 5L
    }
}
