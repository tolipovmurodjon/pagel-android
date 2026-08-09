package org.pagel.ai.net.ws

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.pagel.ai.net.identity.DeviceAuthBlock
import org.pagel.ai.net.identity.DeviceIdentityStore
import org.pagel.ai.net.identity.Ed25519
import org.pagel.ai.net.identity.base64UrlNoPad
import org.pagel.ai.net.identity.buildDeviceAuthPayloadV3
import org.pagel.ai.net.identity.publicKeyB64Url
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Lifecycle of one [GatewayConnection]. [AwaitingApproval] is reachable once Task 5 wires up
 *  `NOT_PAIRED`/`PAIRING_REQUIRED` handling; this task never transitions into it. */
enum class ConnState { Disconnected, Connecting, AwaitingApproval, Connected, Failed }

/**
 * Persists the per-`(deviceId, role)` token returned in `hello-ok.auth.deviceToken`, presented on
 * a future connect to skip re-approval (`docs/gateway/protocol.md` §1.2, `device.publicKey`
 * section). No concrete Android-backed implementation yet: nothing constructs a [GatewayConnection]
 * in production until `GatewayManager` (Task 6) assembles the operator/node pair and wires DI, so a
 * `SharedPreferences`/`EncryptedFile` implementation today would be dead, untested code.
 */
interface GatewayDeviceTokenStore {
    suspend fun get(deviceId: String, role: String): String?
    suspend fun put(deviceId: String, role: String, token: String)
}

@Serializable
data class ConnectClientInfo(
    val id: String,
    val version: String,
    val platform: String,
    val mode: String,
    val deviceFamily: String? = null,
)

/** `{ token?, bootstrapToken?, deviceToken?, password?, ... }` — Task 4 only ever populates
 *  [deviceToken], carried over from a prior `hello-ok`. Shared-token and bootstrap-pairing flows
 *  are out of scope for "construct a connection and complete a handshake". */
@Serializable
data class ConnectAuth(
    val token: String? = null,
    val bootstrapToken: String? = null,
    val deviceToken: String? = null,
)

@Serializable
data class ConnectDevice(
    val id: String,
    val publicKey: String,
    val signature: String,
    val signedAt: Long,
    val nonce: String,
)

/** `ConnectParamsSchema` (`schema-BuOFpc7K.js:3093-3130`) — the `params` of the client's `connect` req. */
@Serializable
data class ConnectParams(
    val minProtocol: Int,
    val maxProtocol: Int,
    val client: ConnectClientInfo,
    val role: String,
    val scopes: List<String> = emptyList(),
    val caps: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val permissions: Map<String, Boolean> = emptyMap(),
    val auth: ConnectAuth? = null,
    val device: ConnectDevice,
)

@Serializable
data class HelloOkAuth(
    val role: String,
    val scopes: List<String> = emptyList(),
    val deviceToken: String? = null,
    val issuedAtMs: Long? = null,
)

@Serializable
data class HelloOkPolicy(
    val maxPayload: Long,
    val maxBufferedBytes: Long,
    val tickIntervalMs: Long,
)

/** `HelloOkSchema` (`schema-BuOFpc7K.js:3131-3173`), modeled minimally: only the fields this phase
 *  acts on. `server`/`features`/`snapshot`/`controlUiTabs`/`pluginSurfaceUrls` pass through
 *  [GatewayJson]'s `ignoreUnknownKeys` untouched rather than being modeled and risking drift. */
@Serializable
data class HelloOk(
    val protocol: Int,
    val auth: HelloOkAuth,
    val policy: HelloOkPolicy,
)

/**
 * One WebSocket connection to the gateway for a single `role`/`mode`/`scopes` triple — the protocol
 * allows exactly one role per connection, so an operator and a node connection (Task 6) are two
 * independent instances of this class sharing one [DeviceIdentityStore].
 *
 * Handshake order (`docs/gateway/protocol.md` §1.2, verified live against
 * `message-handler-CzwI6JjW.js` / `server-ws-runtime-*.js` on 2026-08-09):
 * open WS → await the server's unsolicited `connect.challenge` event (nonce/ts, [challengeTimeoutMs]
 * budget) → build the v3 device-auth payload from the *exact* values also placed on the wire → sign
 * → send `connect` as a `req` frame → await the correlated `res` (`ok:true` → `payload` is `hello-ok`;
 * `ok:false` → `error` is a [GatewayError]).
 *
 * Reconnect/backoff and the `AwaitingApproval` state are Task 5. This class makes exactly one
 * connection attempt per [connect] call.
 *
 * **Stale-attempt safety.** [connect] can be called again (Task 5's reconnect loop is exactly this,
 * on a timer) while a previous attempt's socket is still unwinding — a late `onFailure`/`onClosed`
 * from a superseded socket, or that attempt's own [challengeTimeoutMs]/[rpcTimeoutMs] elapsing,
 * must never mutate state that now belongs to a newer attempt. Every attempt is tagged with the
 * value [generation] held at the moment it started; [Listener] and every internal mutator
 * ([fail], [handleHelloOk], [handleEvent], [sendReq]) take that captured value and no-op if it no
 * longer matches [generation]'s current value. [connect] and [disconnect] additionally cancel the
 * previous attempt's [handshakeJob] outright, so a superseded attempt's coroutine stops making
 * forward progress (including sending frames) as soon as possible rather than merely being ignored
 * once it gets there.
 *
 * **Thread safety.** [connect] and [disconnect] are plain (non-suspend) functions that can be called
 * from different threads (e.g. a UI thread calling [connect] while a lifecycle callback calls
 * [disconnect]), and [Listener]'s callbacks run on OkHttp's own dispatcher thread. The
 * check-then-act in [connect] (read [state], then decide whether to start a new attempt) is made
 * atomic with `synchronized(this)` around both [connect] and [disconnect] — cheap here since neither
 * body does I/O or suspends. [generation], [webSocket], [rpc], [challengeDeferred], and
 * [handshakeJob] are `@Volatile`/[AtomicInteger] so a read from any other thread (in particular,
 * [Listener]'s callbacks, which are *not* themselves synchronized, to avoid blocking OkHttp's
 * dispatcher on this object's lock) always sees the latest write rather than a stale cached value.
 * No code path needs more than one of those fields to be consistent with another at the instant it's
 * read — each attempt's own [generation] value, captured once as an immutable constructor/closure
 * parameter, is what ties a callback back to "its" attempt, not the shared fields' relative timing.
 */
class GatewayConnection(
    private val role: String,
    private val mode: String,
    private val scopes: List<String>,
    private val serverUrl: String,
    private val clientVersion: String,
    private val platform: String,
    private val deviceFamily: String?,
    private val webSocketFactory: WebSocket.Factory,
    private val deviceIdentityStore: DeviceIdentityStore,
    private val tokenStore: GatewayDeviceTokenStore,
    private val scope: CoroutineScope,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val challengeTimeoutMs: Long = 15_000,
    private val rpcTimeoutMs: Long = 30_000,
) {
    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<GatewayError?>(null)
    val lastError: StateFlow<GatewayError?> = _lastError.asStateFlow()

    private val _events = MutableSharedFlow<GatewayFrame.Event>(extraBufferCapacity = 64)
    val events: SharedFlow<GatewayFrame.Event> = _events.asSharedFlow()

    /** Bumped on every [connect] and [disconnect]; the single source of truth for "which attempt is
     *  current." See the class doc's "Stale-attempt safety" section. */
    private val generation = AtomicInteger(0)

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var rpc: GatewayRpc? = null
    @Volatile private var challengeDeferred: CompletableDeferred<ChallengePayload>? = null
    @Volatile private var handshakeJob: Job? = null

    private data class ChallengePayload(val nonce: String, val ts: Long)

    /** Opens the socket and runs the handshake once. No-op if already connecting/connected. */
    fun connect() {
        synchronized(this) {
            if (_state.value == ConnState.Connecting || _state.value == ConnState.Connected) return
            handshakeJob?.cancel() // any leftover attempt (e.g. one that just failed) stops here, not later
            val myGeneration = generation.incrementAndGet()

            _lastError.value = null
            _state.value = ConnState.Connecting

            val cd = CompletableDeferred<ChallengePayload>()
            challengeDeferred = cd
            val rpcForThisAttempt = GatewayRpc(send = { req -> sendReq(myGeneration, req) }, nextId = idGenerator, timeoutMs = rpcTimeoutMs)
            rpc = rpcForThisAttempt
            webSocket = webSocketFactory.newWebSocket(Request.Builder().url(serverUrl).build(), Listener(myGeneration))

            handshakeJob = scope.launch { runHandshake(myGeneration, cd, rpcForThisAttempt) }
        }
    }

    /** Closes the socket, if any, cancels its in-flight attempt, and returns to [ConnState.Disconnected]. */
    fun disconnect() {
        synchronized(this) {
            generation.incrementAndGet() // invalidate the in-flight attempt, if any, before touching anything else
            handshakeJob?.cancel()
            handshakeJob = null
            webSocket?.close(1000, "client disconnect")
            webSocket = null
            rpc = null
            challengeDeferred?.cancel()
            challengeDeferred = null
            _state.value = ConnState.Disconnected
        }
    }

    private suspend fun runHandshake(myGeneration: Int, cd: CompletableDeferred<ChallengePayload>, activeRpc: GatewayRpc) {
        val challenge = try {
            withTimeout(challengeTimeoutMs) { cd.await() }
        } catch (e: TimeoutCancellationException) {
            fail(myGeneration, GatewayError("CHALLENGE_TIMEOUT", "no connect.challenge within ${challengeTimeoutMs}ms"))
            return
        }

        val identity = deviceIdentityStore.loadOrCreate()
        val presentedToken = tokenStore.get(identity.deviceId, role)
        val signedAtMs = clock()

        val payload = buildDeviceAuthPayloadV3(
            deviceId = identity.deviceId,
            clientId = GatewayClientInfo.ID,
            clientMode = mode,
            role = role,
            scopes = scopes,
            signedAtMs = signedAtMs,
            token = presentedToken,
            nonce = challenge.nonce,
            platform = platform,
            deviceFamily = deviceFamily,
        )
        val signature = Ed25519.sign(identity.privateKeyRaw, payload.toByteArray(Charsets.UTF_8))
        val device = DeviceAuthBlock(
            id = identity.deviceId,
            publicKey = identity.publicKeyB64Url(),
            signature = base64UrlNoPad(signature),
            signedAt = signedAtMs,
            nonce = challenge.nonce,
        )

        val params = ConnectParams(
            minProtocol = PROTOCOL_VERSION,
            maxProtocol = PROTOCOL_VERSION,
            client = ConnectClientInfo(
                id = GatewayClientInfo.ID,
                version = clientVersion,
                platform = platform,
                mode = mode,
                deviceFamily = deviceFamily,
            ),
            role = role,
            scopes = scopes,
            device = ConnectDevice(
                id = device.id,
                publicKey = device.publicKey,
                signature = device.signature,
                signedAt = device.signedAt,
                nonce = device.nonce,
            ),
            auth = presentedToken?.let { ConnectAuth(deviceToken = it) },
        )

        val result = activeRpc.call("connect", GatewayJson.encodeToJsonElement(ConnectParams.serializer(), params))
        result.fold(
            onSuccess = { helloOkPayload -> handleHelloOk(myGeneration, identity.deviceId, helloOkPayload) },
            onFailure = { t -> fail(myGeneration, errorFrom(t)) },
        )
    }

    private fun handleHelloOk(gen: Int, deviceId: String, payload: JsonElement?) {
        if (gen != generation.get()) return // superseded before hello-ok arrived — not our state to set
        val obj = payload as? JsonObject
        val hello = obj?.let { runCatching { GatewayJson.decodeFromJsonElement(HelloOk.serializer(), it) }.getOrNull() }
        if (hello == null) {
            fail(gen, GatewayError("INVALID_HELLO_OK", "hello-ok payload was missing or malformed"))
            return
        }
        hello.auth.deviceToken?.let { token ->
            scope.launch { tokenStore.put(deviceId, role, token) }
        }
        _state.value = ConnState.Connected
    }

    private fun errorFrom(t: Throwable): GatewayError = when (t) {
        is GatewayRpcException -> t.error ?: GatewayError("UNKNOWN", t.message ?: "unknown gateway error")
        is TimeoutCancellationException -> GatewayError("RPC_TIMEOUT", "connect RPC timed out")
        else -> GatewayError("UNKNOWN", t.message ?: "unknown gateway error")
    }

    /** Fails the connection outright, and immediately unblocks anything waiting on this attempt —
     *  the pending `connect.challenge` wait and any in-flight RPC — rather than leaving them to
     *  discover the failure only when their own [challengeTimeoutMs]/[rpcTimeoutMs] independently
     *  elapse. Safe to call more than once per attempt: cancelling an already-completed
     *  [challengeDeferred] and clearing an already-empty [GatewayRpc] pending map are both no-ops.
     *  A no-op entirely if [gen] no longer names the current attempt — see the class doc. */
    private fun fail(gen: Int, error: GatewayError) {
        if (gen != generation.get()) return
        _lastError.value = error
        _state.value = ConnState.Failed
        challengeDeferred?.cancel()
        rpc?.cancelAll(error)
    }

    private fun sendReq(gen: Int, req: GatewayFrame.Req): Boolean {
        if (gen != generation.get()) return false // a superseded attempt's RPC must not write to today's socket
        val socket = webSocket ?: return false
        return socket.send(GatewayJson.encodeToString(GatewayFrame.Req.serializer(), req))
    }

    private fun handleEvent(gen: Int, frame: GatewayFrame.Event) {
        if (gen != generation.get()) return
        if (frame.event == "connect.challenge") {
            val obj = frame.payload as? JsonObject
            val nonce = (obj?.get("nonce") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val ts = (obj?.get("ts") as? JsonPrimitive)?.content?.toLongOrNull() ?: clock()
            if (nonce != null) challengeDeferred?.complete(ChallengePayload(nonce, ts))
            return // handshake-internal — not surfaced on the public events flow
        }
        _events.tryEmit(frame)
    }

    /** One per [connect] attempt, tagged with the [generation] value that was current when it was
     *  created ([myGeneration]) — every callback checks that value against the *current*
     *  [generation] before touching shared state, so a callback arriving from a socket that's since
     *  been superseded (a new [connect], or [disconnect]) is a no-op. See the class doc. */
    private inner class Listener(private val myGeneration: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Nothing to do: the handshake proceeds once connect.challenge arrives.
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (myGeneration != generation.get()) return
            when (val frame = parseGatewayFrame(text)) {
                is GatewayFrame.Event -> handleEvent(myGeneration, frame)
                is GatewayFrame.Res -> rpc?.onResponse(frame)
                is GatewayFrame.Req, null -> Unit // gateway never sends req to the client; unknown types are ignored
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (myGeneration != generation.get()) return
            fail(myGeneration, GatewayError("SOCKET_FAILURE", t.message ?: "socket failure"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (myGeneration != generation.get()) return
            // A close while we were still mid-handshake or live is the server (or the network)
            // ending the connection out from under us — e.g. code 1008 policy violation, the
            // pairing-required/unauthorized case — not a clean shutdown. [disconnect] already sets
            // Disconnected (and bumps [generation]) before closing the socket, so a close that
            // follows our own [disconnect] call never reaches here at all.
            if (_state.value == ConnState.Connecting || _state.value == ConnState.Connected) {
                fail(myGeneration, GatewayError("SOCKET_CLOSED", "socket closed (code=$code): ${reason.ifBlank { "no reason given" }}"))
            } else if (_state.value != ConnState.Failed) {
                _state.value = ConnState.Disconnected
            }
        }
    }

    private companion object {
        /** `PROTOCOL_VERSION` (`docs/gateway/protocol.md:717-750`). Global Constraints for this
         *  phase pin both connections to v4 — no N-1 node tolerance yet. */
        const val PROTOCOL_VERSION = 4
    }
}
