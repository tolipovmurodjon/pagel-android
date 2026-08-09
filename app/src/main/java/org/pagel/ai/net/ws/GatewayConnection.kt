package org.pagel.ai.net.ws

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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

/**
 * Lifecycle of one [GatewayConnection]. [AwaitingApproval] means the device is known to the
 * gateway but still needs an explicit `openclaw devices approve` — not terminal, the connection
 * keeps retrying with the same backoff as [Failed] (see [GatewayConnection.scheduleReconnect]).
 */
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

/** `{ token?, bootstrapToken?, deviceToken?, password?, ... }` — two independent gates, both
 *  presentable at once: [token] is the gateway's shared `gateway.auth.mode = "token"` secret, the
 *  same value for every client, configured on the debug screen (see [GatewayConnection]'s
 *  `authToken` constructor param) — "you may talk to this gateway at all." [deviceToken] is this
 *  device's own per-`(deviceId, role)` pairing token, carried over from a prior `hello-ok` —
 *  "you are specifically this device."
 *
 *  The gateway resolves the *signed* device-auth slot as
 *  `auth.token ?? auth.deviceToken ?? auth.bootstrapToken ?? null` (`resolveSignatureToken`,
 *  `message-handler-CzwI6JjW.js`). [token] is **first** — so whenever a shared gateway secret is
 *  configured, that is what must go into `buildDeviceAuthPayloadV3`'s `token` param, ahead of
 *  [deviceToken].
 *
 *  An earlier revision of this doc asserted the opposite, from a truncated read of that function,
 *  and the code followed it: we signed an empty slot while the gateway rebuilt the string with the
 *  secret in it. The handshake then failed as `device signature invalid`, which points at the
 *  crypto and not at the token slot — three live debugging rounds. `bootstrapToken`/`password`
 *  flows remain out of scope for this phase. */
@Serializable
data class ConnectAuth(
    val token: String? = null,
    val bootstrapToken: String? = null,
    val deviceToken: String? = null,
) {
    // Never print token material — same rule (and same reason) as DeviceIdentity.toString(): the
    // first `Timber.d("connect: $params")` someone adds would otherwise put a live device token in
    // logcat and in every crash report. Serialization uses the generated serializer, not toString(),
    // so redacting here cannot affect the wire format.
    override fun toString(): String =
        "ConnectAuth(token=${token.redactedIfPresent()}, bootstrapToken=${bootstrapToken.redactedIfPresent()}, deviceToken=${deviceToken.redactedIfPresent()})"
}

private fun String?.redactedIfPresent(): String = if (this == null) "null" else "<redacted>"

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
 * **Reconnect.** Every path that ends an attempt without success — [fail] and
 * [enterAwaitingApproval] alike — schedules the next attempt via [scheduleReconnect], which is
 * disconnect-then-connect on a timer through the same public, generation-guarded [connect] /
 * [disconnect] entry points any other caller uses (see "Stale-attempt safety" below: this is
 * exactly the retry loop that section was written for). The delay is [reconnectPolicy]'s
 * exponential backoff (1s to 2s to ... capped at 30s, reset to 1s on the next [handleHelloOk]),
 * except when the gateway hands back `UNAVAILABLE` with `details.reason == "startup-sidecars"`, in
 * which case [startupSidecarsRetryDelay] honours the server's own `retryAfterMs` instead. A
 * `NOT_PAIRED` error code or a `PAIRING_REQUIRED` `details.code` ([isPairingRequired]) is not a
 * hard failure — it lands in [ConnState.AwaitingApproval] instead of [ConnState.Failed], but still
 * retries on the same schedule. [ConnState.AwaitingApproval] is sticky against the 1008 close the
 * gateway sends right after the `NOT_PAIRED` `res` (`Listener.onClosed`'s
 * [ConnState.AwaitingApproval] guard, symmetric with its [ConnState.Failed] one) — without it, the
 * settled state during the *entire* backoff wait between retries would read `Disconnected` instead
 * of `AwaitingApproval`, not just for an instant. There is no resume/replay ("Events are not replayed... refresh
 * state... before continuing", `docs/gateway/index.md:325-327`): [seqGaps] only flags that a gap
 * happened so the caller can refetch; this class never requests replay.
 *
 * A post-`hello-ok` watchdog ([armWatchdog]) closes the socket with code 4000 and fails the
 * connection (feeding the same reconnect path) if 2x the server's advertised
 * `hello-ok.policy.tickIntervalMs` passes with no inbound frame at all.
 *
 * **Stale-attempt safety.** [connect] can be called again (Task 5's reconnect loop is exactly this,
 * on a timer) while a previous attempt's socket is still unwinding — a late `onFailure`/`onClosed`
 * from a superseded socket, or that attempt's own [challengeTimeoutMs]/[rpcTimeoutMs] elapsing,
 * must never mutate state that now belongs to a newer attempt. Every attempt is one [Attempt]
 * value, created by [connect] and carrying its own [generation] tag, `connect.challenge` deferred,
 * and [GatewayRpc]; [Listener] and every internal mutator ([fail], [enterAwaitingApproval],
 * [handleHelloOk], [handleEvent], [armWatchdog]) is handed that [Attempt] and no-ops if its
 * [Attempt.gen] no longer matches [generation]'s current value. Because each of those touches only
 * *its own* attempt's deferred and RPC, a late callback can never cancel a newer attempt's
 * `connect.challenge` wait (which would surface as a bare [CancellationException] inside
 * [runHandshake] — no [fail], and so no retry ever scheduled). [connect] and [disconnect]
 * additionally cancel the previous attempt's [handshakeJob] outright, so a superseded attempt's
 * coroutine stops making forward progress (including sending frames) as soon as possible rather
 * than merely being ignored once it gets there.
 *
 * **Thread safety.** [connect] and [disconnect] are plain (non-suspend) functions that can be called
 * from different threads (e.g. a UI thread calling [connect] while a lifecycle callback calls
 * [disconnect]), and [Listener]'s callbacks run on OkHttp's own dispatcher thread. Every mutator of
 * the `_state`/`_lastError`/`currentAttempt`/`webSocket` group — [connect], [disconnect], [fail],
 * [enterAwaitingApproval], [handleHelloOk] — takes `synchronized(this)` around *both* its generation
 * check and its writes, so the check-then-act can't be interleaved with a [connect] that rewrites
 * the same fields from another thread. It is cheap to hold: no body does I/O, suspends, or blocks
 * (`CompletableDeferred.cancel`/`GatewayRpc.cancelAll` dispatch their waiters rather than running
 * them inline, and `scope.launch` only schedules), so an OkHttp dispatcher thread never waits long
 * on this lock. [generation], [webSocket], [currentAttempt], and [handshakeJob] are
 * `@Volatile`/[AtomicInteger] so reads from the paths that deliberately stay outside the lock
 * ([Listener.onMessage], [sendReq], [armWatchdog]'s timer) see the latest write rather than a stale
 * cached value.
 *
 * **Exceptions.** [runHandshake] is a root coroutine on an app-lifetime scope: an escaping throwable
 * would reach Android's default handler and kill the process. Everything after the challenge wait —
 * [DeviceIdentityStore.loadOrCreate] on a corrupt/unreadable identity file, [GatewayDeviceTokenStore.get]
 * on a corrupt DataStore, signing — is therefore wrapped and reported as a `HANDSHAKE_ERROR`
 * [fail], which retries on the normal backoff. [CancellationException] is rethrown untouched:
 * a cancelled attempt is [disconnect]/[connect] doing its job, not a failure.
 */
class GatewayConnection(
    private val role: String,
    private val mode: String,
    private val scopes: List<String>,
    private var serverUrl: String,
    /** The gateway's shared `gateway.auth.mode = "token"` secret, sent as `auth.token` — see
     *  [ConnectAuth]'s doc for how this differs from the per-device [tokenStore] token. `null` when
     *  Boss hasn't configured one (or the gateway isn't in token mode). Read fresh by every
     *  [connect]/reconnect attempt, so [updateConnectionParams] can retarget it without rebuilding
     *  this instance — see that method's doc. `@Volatile`: read from the handshake coroutine, which
     *  does not hold [Any.synchronized]'s lock the way [connect]/[updateConnectionParams] do. */
    @Volatile private var authToken: String? = null,
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
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) {
    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<GatewayError?>(null)
    val lastError: StateFlow<GatewayError?> = _lastError.asStateFlow()

    /**
     * The exact string that was Ed25519-signed for the most recent handshake attempt, for on-device
     * diagnosis of `device signature invalid`.
     *
     * That error means the gateway rebuilt this string from our own frame and got something
     * different — so the only way to find the culprit is to read both. Nothing else surfaces it: the
     * payload never travels the wire (only the signature does), and the gateway's rejection names
     * the signature rather than the field that actually differed.
     *
     * The `token` slot is redacted before exposure. It carries the device token once we hold one,
     * and this value is rendered on screen and read aloud in chat during debugging.
     */
    private val _lastSignedPayload = MutableStateFlow<String?>(null)
    val lastSignedPayload: StateFlow<String?> = _lastSignedPayload.asStateFlow()

    private val _events = MutableSharedFlow<GatewayFrame.Event>(extraBufferCapacity = 64)
    val events: SharedFlow<GatewayFrame.Event> = _events.asSharedFlow()

    /** Gap between the previous inbound event's `seq` and this one's. `seq` is per-socket,
     *  monotonic, and resets on every new connection (`docs/gateway/protocol.md` §1.5) — there is
     *  no replay protocol, so this only flags that a gap happened; the caller is responsible for
     *  refetching current state (`health`, `system-presence`, ...) itself (Phase B). */
    data class SeqGap(val expected: Long, val actual: Long)

    private val _seqGaps = MutableSharedFlow<SeqGap>(extraBufferCapacity = 8)
    val seqGaps: SharedFlow<SeqGap> = _seqGaps.asSharedFlow()

    /** Bumped on every [connect] and [disconnect]; the single source of truth for "which attempt is
     *  current." See the class doc's "Stale-attempt safety" section. */
    private val generation = AtomicInteger(0)

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var handshakeJob: Job? = null

    /** The attempt [connect] most recently started. Only [disconnect] reads it — everything else is
     *  handed its own [Attempt] directly, which is the whole point (see "Stale-attempt safety"). */
    @Volatile private var currentAttempt: Attempt? = null

    /** The pending [scheduleReconnect] timer, if any. Deliberately *not* touched by [connect] or
     *  [disconnect] — see [scheduleReconnect]'s doc for why: the `gen != generation.get()` check
     *  inside its delayed continuation is what has to do that job, so it stays load-bearing rather
     *  than redundant with an eager cancel. */
    @Volatile private var reconnectJob: Job? = null

    /** The post-`hello-ok` tick-timeout watchdog, if armed. See [armWatchdog]. */
    @Volatile private var watchdogJob: Job? = null

    /** Attempt index fed to [reconnectPolicy]; incremented on every backoff-driven retry, reset to
     *  0 by [handleHelloOk] on success. Not reset by [disconnect] — an explicit disconnect mid-
     *  backoff-streak followed by a manual reconnect that also fails resumes the streak's delay
     *  rather than restarting at 1s; this is a deliberate simplification for Task 5, not a bug. */
    @Volatile private var reconnectAttempt: Int = 0

    /** Learned from `hello-ok.policy.tickIntervalMs`; drives [armWatchdog]. Never hardcoded — see
     *  the class doc. */
    @Volatile private var tickIntervalMs: Long = 0

    /** The last inbound event's `seq`, per attempt (reset in [connect]). See [SeqGap]. */
    @Volatile private var lastSeq: Long? = null

    private data class ChallengePayload(val nonce: String, val ts: Long)

    /** One [connect] attempt's private state: the generation tag that identifies it, the deferred
     *  its `connect.challenge` arrives on, and the [GatewayRpc] that owns its in-flight `connect`
     *  req. Handed whole to [Listener] and every internal mutator so none of them has to read a
     *  shared field that a newer attempt may already have replaced. */
    private inner class Attempt(val gen: Int) {
        val challengeDeferred = CompletableDeferred<ChallengePayload>()
        val rpc = GatewayRpc(send = { req -> sendReq(gen, req) }, nextId = idGenerator, timeoutMs = rpcTimeoutMs)
    }

    /** Opens the socket and runs the handshake once. No-op if already connecting/connected — a
     *  caller that wants to retarget a *live* connection at a new [serverUrl]/[authToken] must
     *  [disconnect] first (see [updateConnectionParams]'s doc; [GatewayManager]'s two-arg `start`
     *  does exactly that).
     *
     *  A [serverUrl] OkHttp can't parse (Boss typing `100.64.0.1:18789` with no scheme into the
     *  debug screen) lands in [ConnState.Failed] with an `INVALID_URL` [lastError] instead of
     *  throwing out of this call — which, since [connect] runs on the caller's thread (the main
     *  thread, from the CONNECT button), used to mean an app crash. Deliberately *not*
     *  [scheduleReconnect]ed: nothing changes [serverUrl] *during* a reconnect streak (only an
     *  explicit [updateConnectionParams] + fresh [connect] does), so no amount of automatic retrying
     *  can heal a bad one — the CONNECT button is the retry. */
    fun connect() {
        synchronized(this) {
            if (_state.value == ConnState.Connecting || _state.value == ConnState.Connected) return
            val request = runCatching { Request.Builder().url(serverUrl).build() }.getOrNull()
            if (request == null) {
                _lastError.value = GatewayError("INVALID_URL", "not a URL OkHttp can open: \"$serverUrl\"")
                _state.value = ConnState.Failed
                return
            }
            handshakeJob?.cancel() // any leftover attempt (e.g. one that just failed) stops here, not later
            watchdogJob?.cancel() // the previous attempt's watchdog, if any, belongs to a dead generation
            watchdogJob = null

            val attempt = Attempt(generation.incrementAndGet())
            currentAttempt = attempt

            lastSeq = null // seq is per-socket (docs/gateway/protocol.md §1.5) — this is a new socket
            _lastError.value = null
            _state.value = ConnState.Connecting

            webSocket = webSocketFactory.newWebSocket(request, Listener(attempt))
            handshakeJob = scope.launch { runHandshake(attempt) }
        }
    }

    /** Closes the socket, if any, cancels its in-flight attempt, and returns to [ConnState.Disconnected].
     *  Deliberately does *not* touch [reconnectJob] — see that field's doc. */
    fun disconnect() {
        synchronized(this) {
            generation.incrementAndGet() // invalidate the in-flight attempt, if any, before touching anything else
            handshakeJob?.cancel()
            handshakeJob = null
            watchdogJob?.cancel()
            watchdogJob = null
            webSocket?.close(1000, "client disconnect")
            webSocket = null
            currentAttempt?.challengeDeferred?.cancel()
            currentAttempt = null
            _state.value = ConnState.Disconnected
        }
    }

    /**
     * Updates the URL and gateway auth token this connection uses for its *next* [connect] call —
     * including the ones [scheduleReconnect] fires automatically, since those call the same no-arg
     * [connect] and so pick up whatever is set here. This is the fix for "applies on next app
     * launch": [GatewayManager]'s two-arg `start` calls this (via [disconnect] first) so a URL or
     * token Boss just typed takes effect on this CONNECT tap, not the next cold start.
     *
     * Deliberately does *not* itself [connect]/[disconnect] anything — call it on a connection that
     * is [ConnState.Connecting] or [ConnState.Connected] and nothing changes until the caller also
     * [disconnect]s (see [connect]'s no-op guard): calling this mid-attempt would otherwise let a
     * handshake already under way sign and send a frame built from a mix of the old and new values.
     */
    fun updateConnectionParams(serverUrl: String, authToken: String?) {
        synchronized(this) {
            this.serverUrl = serverUrl
            this.authToken = authToken
        }
    }

    /** See the class doc's "Exceptions" section for why the whole body is wrapped. */
    private suspend fun runHandshake(attempt: Attempt) {
        try {
            runHandshakeOrThrow(attempt)
        } catch (e: CancellationException) {
            throw e // a superseded/disconnected attempt, not a failure — must stay ordinary cancellation
        } catch (t: Throwable) {
            fail(attempt, GatewayError("HANDSHAKE_ERROR", "${t.javaClass.simpleName}: ${t.message ?: "no message"}"))
        }
    }

    private suspend fun runHandshakeOrThrow(attempt: Attempt) {
        val challenge = try {
            // TimeoutCancellationException is a CancellationException, so this catch has to sit
            // inside runHandshake's rethrow, not after it.
            withTimeout(challengeTimeoutMs) { attempt.challengeDeferred.await() }
        } catch (e: TimeoutCancellationException) {
            fail(attempt, GatewayError("CHALLENGE_TIMEOUT", "no connect.challenge within ${challengeTimeoutMs}ms"))
            return
        }

        val identity = deviceIdentityStore.loadOrCreate()
        val presentedToken = tokenStore.get(identity.deviceId, role)
        val gatewayAuthToken = authToken // snapshot: see the authToken property's @Volatile doc
        val signedAtMs = clock()

        // Mirrors the gateway's own resolveSignatureToken, which decides what goes in the payload's
        // token slot when it rebuilds this string to verify us:
        //
        //   connectParams.auth?.token ?? connectParams.auth?.deviceToken
        //     ?? connectParams.auth?.bootstrapToken ?? null
        //
        // `auth.token` — the shared gateway secret — comes FIRST, ahead of the device token. That is
        // easy to get backwards, and getting it backwards is silent: the handshake fails as
        // `device signature invalid`, pointing at the crypto rather than at the token slot. It cost
        // three live rounds. Before we sent auth.token at all the server resolved null here and our
        // empty slot happened to agree; adding the gateway token is what broke the match.
        //
        // Verified against the live gateway with a Node probe: signing the gateway token in this
        // slot returns ok=true, signing an empty slot returns device-signature.
        val signatureToken = gatewayAuthToken ?: presentedToken

        val payload = buildDeviceAuthPayloadV3(
            deviceId = identity.deviceId,
            clientId = GatewayClientInfo.ID,
            clientMode = mode,
            role = role,
            scopes = scopes,
            signedAtMs = signedAtMs,
            token = signatureToken,
            nonce = challenge.nonce,
            platform = platform,
            deviceFamily = deviceFamily,
        )
        val signature = Ed25519.sign(identity.privateKeyRaw, payload.toByteArray(Charsets.UTF_8))
        // Payload + public key + signature together are exactly the three inputs the gateway feeds
        // to `crypto.verify`. Emitting all three lets that verification be reproduced off-device,
        // which splits `device signature invalid` into its only two possible causes: either Node
        // rejects this triple (our encoding is wrong somewhere BouncyCastle tolerates), or it
        // accepts it (the gateway is rebuilding a different payload string). None of the three is
        // secret — the public key and signature both travel the wire in clear, and the token slot
        // is redacted below.
        _lastSignedPayload.value = buildString {
            // Must be signatureToken, NOT presentedToken: the slot now normally carries the shared
            // gateway secret, and redacting against the wrong value would render it on screen.
            append(redactTokenSlot(payload, signatureToken))
            append("\n\npub  ").append(identity.publicKeyB64Url())
            append("\nsig  ").append(base64UrlNoPad(signature))
        }
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
            // gatewayAuthToken (auth.token, the shared gateway secret) and presentedToken
            // (auth.deviceToken, this device's own pairing token) are independent gates and can
            // both be present at once — see ConnectAuth's doc. Critically, gatewayAuthToken must
            // NEVER be fed into buildDeviceAuthPayloadV3 above: the gateway resolves the *signed*
            // slot as deviceToken ?? bootstrapToken ?? null and never reads auth.token for that.
            auth = if (gatewayAuthToken != null || presentedToken != null) {
                ConnectAuth(token = gatewayAuthToken, deviceToken = presentedToken)
            } else {
                null
            },
        )

        val result = attempt.rpc.call("connect", GatewayJson.encodeToJsonElement(ConnectParams.serializer(), params))
        result.fold(
            onSuccess = { helloOkPayload -> handleHelloOk(attempt, identity.deviceId, helloOkPayload) },
            onFailure = { t ->
                val error = errorFrom(t)
                if (isPairingRequired(error)) enterAwaitingApproval(attempt, error) else fail(attempt, error)
            },
        )
    }

    private fun handleHelloOk(attempt: Attempt, deviceId: String, payload: JsonElement?) {
        val obj = payload as? JsonObject
        val hello = obj?.let { runCatching { GatewayJson.decodeFromJsonElement(HelloOk.serializer(), it) }.getOrNull() }
        if (hello == null) {
            fail(attempt, GatewayError("INVALID_HELLO_OK", "hello-ok payload was missing or malformed"))
            return
        }
        synchronized(this) {
            if (attempt.gen != generation.get()) return // superseded before hello-ok arrived — not our state to set
            reconnectAttempt = 0 // a successful handshake resets the backoff curve for the *next* failure
            tickIntervalMs = hello.policy.tickIntervalMs
            armWatchdog(attempt)
            _state.value = ConnState.Connected
        }
        hello.auth.deviceToken?.let { token ->
            // Another root coroutine: a DataStore write that throws (corrupt file, disk full) must
            // not reach Android's default handler. Losing the token costs one re-approval, nothing more.
            scope.launch { runCatching { tokenStore.put(deviceId, role, token) } }
        }
    }

    private fun errorFrom(t: Throwable): GatewayError = when (t) {
        is GatewayRpcException -> t.error ?: GatewayError("UNKNOWN", t.message ?: "unknown gateway error")
        is TimeoutCancellationException -> GatewayError("RPC_TIMEOUT", "connect RPC timed out")
        else -> GatewayError("UNKNOWN", t.message ?: "unknown gateway error")
    }

    /** True when [error] means "the device needs an explicit pairing approval" rather than a hard
     *  failure — protocol-reference-handshake.md §1.6, two distinct signals that mean the same
     *  thing to this client:
     *  - top-level `error.code == "NOT_PAIRED"`, from the closed six-value `ErrorCodes` enum
     *    (`schema-BuOFpc7K.js:2646-2659`): "Device exists but still needs an explicit pairing
     *    approval." Documented retryable, "yes, poll."
     *  - `error.details.code == "PAIRING_REQUIRED"`, a *separate* namespace specific to connect
     *    failures (`connect-error-details.ts`; full table `docs/gateway/troubleshooting.md:347-356`):
     *    "needs approval... show pending state, poll." Also `docs/gateway/protocol.md:802-806`:
     *    "Keep reconnecting with the same bootstrap token until the request is approved."
     */
    private fun isPairingRequired(error: GatewayError): Boolean {
        if (error.code == "NOT_PAIRED") return true
        val details = error.details as? JsonObject ?: return false
        return (details["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "PAIRING_REQUIRED"
    }

    /** `UNAVAILABLE` with `details.reason == "startup-sidecars"` carries the gateway's own
     *  `retryAfterMs` — `docs/gateway/protocol.md:117-120`: "Retry within your connection budget
     *  instead of treating it as a terminal handshake failure." Returns `null` (fall back to
     *  [reconnectPolicy]) for every other error, including other `UNAVAILABLE` reasons. */
    private fun startupSidecarsRetryDelay(error: GatewayError): Long? {
        val details = error.details as? JsonObject ?: return null
        val reason = (details["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return error.retryAfterMs.takeIf { reason == "startup-sidecars" }
    }

    /** Fails the connection outright, and immediately unblocks anything waiting on this attempt —
     *  the pending `connect.challenge` wait and any in-flight RPC — rather than leaving them to
     *  discover the failure only when their own [challengeTimeoutMs]/[rpcTimeoutMs] independently
     *  elapse. Safe to call more than once per attempt: cancelling an already-completed
     *  [Attempt.challengeDeferred] and clearing an already-empty [GatewayRpc] pending map are both
     *  no-ops. A no-op entirely if [attempt] no longer names the current attempt — see the class
     *  doc. Schedules the next attempt via [scheduleReconnect]: per the plan's Done-when #5
     *  ("killing the network and restoring it reconnects without a restart"), every failure here is
     *  worth retrying — this task doesn't special-case the troubleshooting table's "stop and
     *  surface" codes (`AUTH_TOKEN_MISMATCH`, `NOT_LINKED`, ...); see the Task 5 report.
     *
     *  Also idempotent per attempt via [alreadySettled]: one real failure usually arrives twice (a
     *  `res` error and *then* the socket dying, or [armWatchdog]'s own `close(4000)` coming back as
     *  a close callback). Without the early-out the second one consumes another step of the backoff
     *  curve, turning 1s, 2s, 4s into 1s, 4s, 16s. */
    private fun fail(attempt: Attempt, error: GatewayError) {
        synchronized(this) {
            if (attempt.gen != generation.get() || alreadySettled()) return
            _lastError.value = error
            _state.value = ConnState.Failed
            attempt.challengeDeferred.cancel()
            attempt.rpc.cancelAll(error)
            scheduleReconnect(attempt.gen, error)
        }
    }

    /** Like [fail], but for [isPairingRequired] errors: lands in [ConnState.AwaitingApproval]
     *  instead of [ConnState.Failed] — not terminal, still retries via [scheduleReconnect] so the
     *  UI can show "waiting for Boss to run `openclaw devices approve`" rather than a dead end. */
    private fun enterAwaitingApproval(attempt: Attempt, error: GatewayError) {
        synchronized(this) {
            if (attempt.gen != generation.get() || alreadySettled()) return
            _lastError.value = error
            _state.value = ConnState.AwaitingApproval
            attempt.challengeDeferred.cancel()
            attempt.rpc.cancelAll(error)
            scheduleReconnect(attempt.gen, error)
        }
    }

    /** True when the current attempt has already ended *and* already scheduled its retry. Both
     *  [ConnState.Failed] and [ConnState.AwaitingApproval] count: every [generation] bump is
     *  followed, inside the same lock, by a `Connecting`/`Disconnected` write, so observing either
     *  of these under a matching generation means *this* attempt settled, never an older one.
     *  Covering [ConnState.AwaitingApproval] as well is what keeps Task 5's stickiness intact if a
     *  socket failure lands after the gateway's `NOT_PAIRED` + 1008 sequence. Callers hold the lock. */
    private fun alreadySettled(): Boolean =
        _state.value == ConnState.Failed || _state.value == ConnState.AwaitingApproval

    /**
     * Schedules the next [connect] attempt after this attempt's failure. Honours
     * [startupSidecarsRetryDelay] when the gateway says so, otherwise [reconnectPolicy]'s
     * exponential backoff (which also advances [reconnectAttempt] — the Elvis operator below short-
     * circuits that increment when the server-provided delay is used instead, so a startup-sidecars
     * retry doesn't consume a step of the exponential curve).
     *
     * Reconnect is disconnect-then-connect on a timer, through the public, generation-guarded
     * [connect] / [disconnect] API — never touching [webSocket]/[rpc]/[challengeDeferred] directly.
     * [connect] and [disconnect] deliberately do *not* eagerly cancel [reconnectJob] (see that
     * field's doc): the `gen != generation.get()` check below, run right before this timer's
     * [disconnect]+[connect] pair, is what makes a superseded retry (a manual [disconnect] during
     * the backoff window, or a newer attempt already under way for some other reason) a no-op
     * instead of resurrecting stale state.
     */
    private fun scheduleReconnect(gen: Int, error: GatewayError) {
        reconnectJob?.cancel() // replace any earlier still-pending timer for this same generation
        val delayMs = startupSidecarsRetryDelay(error) ?: reconnectPolicy.nextDelayMs(reconnectAttempt++)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (gen != generation.get()) return@launch // superseded — see the doc above
            disconnect()
            connect()
        }
    }

    /** (Re)arms the tick-timeout watchdog: `docs/gateway/protocol.md:761` — "Tick-timeout close —
     *  code 4000 when silence exceeds tickIntervalMs * 2." First armed by [handleHelloOk] once the
     *  server's real `policy.tickIntervalMs` is known (the pre-`hello-ok` phase already has its own
     *  [challengeTimeoutMs]/[rpcTimeoutMs] budgets, so there's nothing for a watchdog to add there),
     *  then re-armed by [Listener.onMessage] on every subsequent inbound frame so genuine liveness
     *  keeps resetting the clock. Expiry closes the socket with 4000 and runs the same [fail] ->
     *  [scheduleReconnect] path as any other failure. */
    private fun armWatchdog(attempt: Attempt) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            val budgetMs = tickIntervalMs * 2
            delay(budgetMs)
            if (attempt.gen != generation.get()) return@launch
            webSocket?.close(4000, "tick timeout: no inbound frame within ${budgetMs}ms")
            fail(attempt, GatewayError("TICK_TIMEOUT", "no inbound frame within ${budgetMs}ms"))
        }
    }

    private fun sendReq(gen: Int, req: GatewayFrame.Req): Boolean {
        if (gen != generation.get()) return false // a superseded attempt's RPC must not write to today's socket
        val socket = webSocket ?: return false
        return socket.send(GatewayJson.encodeToString(GatewayFrame.Req.serializer(), req))
    }

    private fun handleEvent(attempt: Attempt, frame: GatewayFrame.Event) {
        if (attempt.gen != generation.get()) return
        if (frame.event == "connect.challenge") {
            val obj = frame.payload as? JsonObject
            val nonce = (obj?.get("nonce") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val ts = (obj?.get("ts") as? JsonPrimitive)?.content?.toLongOrNull() ?: clock()
            if (nonce != null) attempt.challengeDeferred.complete(ChallengePayload(nonce, ts))
            return // handshake-internal — not surfaced on the public events flow
        }
        frame.seq?.let(::checkSeqGap)
        _events.tryEmit(frame)
    }

    /** See [SeqGap]. */
    private fun checkSeqGap(seq: Long) {
        val previous = lastSeq
        if (previous != null && seq != previous + 1) {
            _seqGaps.tryEmit(SeqGap(expected = previous + 1, actual = seq))
        }
        lastSeq = seq
    }

    /** One per [connect] attempt, holding that attempt's [Attempt] — every callback checks
     *  [Attempt.gen] against the *current* [generation] before touching shared state, so a callback
     *  arriving from a socket that's since been superseded (a new [connect], or [disconnect]) is a
     *  no-op, and every mutation it does make targets its own attempt's deferred/RPC rather than
     *  whatever the newest attempt happens to have installed. See the class doc. */
    private inner class Listener(private val attempt: Attempt) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Nothing to do: the handshake proceeds once connect.challenge arrives.
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (attempt.gen != generation.get()) return
            // Any inbound frame is liveness: reset the tick-timeout watchdog, once armed (post
            // hello-ok — see armWatchdog's doc for why not before).
            if (_state.value == ConnState.Connected) armWatchdog(attempt)
            when (val frame = parseGatewayFrame(text)) {
                is GatewayFrame.Event -> handleEvent(attempt, frame)
                is GatewayFrame.Res -> attempt.rpc.onResponse(frame)
                is GatewayFrame.Req, null -> Unit // gateway never sends req to the client; unknown types are ignored
            }
        }

        /**
         * The gateway closed first (`systemctl restart openclaw-gateway`, a bare 1008 auth
         * rejection, a preauth timeout). OkHttp 4.12's `RealWebSocket.onReadClose` delivers
         * [onClosed] *only* if the client has already enqueued its own close frame; otherwise it
         * calls this and stops. Without this override — [WebSocketListener]'s default is a no-op —
         * a server-initiated close was invisible to this class, and [state] kept reading
         * [ConnState.Connected] until [armWatchdog] fired 2x `tickIntervalMs` (30-60s) later.
         *
         * Flushing our own close is all that's needed: it completes the close handshake, and OkHttp
         * then delivers [onClosed], which already handles every case correctly. Deliberately *not*
         * routed straight into [fail] — on the gateway's `NOT_PAIRED`-then-1008 sequence that would
         * clobber [ConnState.AwaitingApproval], which [onClosed]'s guard exists to preserve.
         */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(attempt, GatewayError("SOCKET_FAILURE", t.message ?: "socket failure"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (attempt.gen != generation.get()) return
            // A close while we were still mid-handshake or live is the server (or the network)
            // ending the connection out from under us — e.g. code 1008 policy violation — not a
            // clean shutdown. [disconnect] already sets Disconnected (and bumps [generation])
            // before closing the socket, so a close that follows our own [disconnect] call never
            // reaches here at all.
            //
            // Reached in production only because [onClosing] flushes the client's close frame; a
            // server-initiated close never gets here on its own (OkHttp 4.12 `onReadClose`).
            //
            // The gateway's own unpaired-connect handling (`message-handler-CzwI6JjW.js`) always
            // sends the `NOT_PAIRED` `res` *before* the 1008 close, never a bare close alone — so by
            // the time this callback runs for that case, [enterAwaitingApproval] has already set
            // [ConnState.AwaitingApproval] for this same generation. This close must not downgrade
            // that: hence the [ConnState.AwaitingApproval] guard below, symmetric with the existing
            // [ConnState.Failed] one. A close that arrives with *no* preceding `res.error` (e.g. a
            // genuinely bare 1008, or the network dying mid-handshake) still lands in
            // [ConnState.Failed] via the `Connecting`/`Connected` branch below, same as any other
            // unexplained close.
            if (_state.value == ConnState.Connecting || _state.value == ConnState.Connected) {
                fail(attempt, GatewayError("SOCKET_CLOSED", "socket closed (code=$code): ${reason.ifBlank { "no reason given" }}"))
            } else if (_state.value != ConnState.Failed && _state.value != ConnState.AwaitingApproval) {
                _state.value = ConnState.Disconnected
            }
        }
    }

    /**
     * Blanks the `token` slot (index 7) of a v3 payload so the rest can be shown on screen.
     *
     * Splits on `|` with `limit` unset and rejoins, rather than replacing the token's text, because
     * an empty token is the normal case and `String.replace("", …)` would corrupt every position.
     * Falls back to the untouched string only when the shape isn't the expected 11 fields.
     */
    private fun redactTokenSlot(payload: String, token: String?): String {
        if (token.isNullOrEmpty()) return payload
        val parts = payload.split('|')
        if (parts.size != 11) return payload
        return parts.mapIndexed { i, p -> if (i == TOKEN_SLOT) "<redacted:${p.length}>" else p }
            .joinToString("|")
    }

    private companion object {
        /** `PROTOCOL_VERSION` (`docs/gateway/protocol.md:717-750`). Global Constraints for this
         *  phase pin both connections to v4 — no N-1 node tolerance yet. */
        const val PROTOCOL_VERSION = 4

        /** `v3|deviceId|clientId|clientMode|role|scopes|signedAtMs|`**`token`**`|nonce|platform|deviceFamily` */
        const val TOKEN_SLOT = 7
    }
}
