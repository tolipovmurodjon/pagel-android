package org.pagel.ai.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import okhttp3.WebSocket
import org.pagel.ai.net.identity.DeviceIdentityStore
import org.pagel.ai.net.ws.ConnState
import org.pagel.ai.net.ws.GatewayClientInfo
import org.pagel.ai.net.ws.GatewayConnection
import org.pagel.ai.net.ws.GatewayDeviceTokenStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wire/identity metadata shared by both connections a [GatewayManager] owns. It's the same
 * physical device talking to the same gateway host, so `serverUrl`/`clientVersion`/`platform`/
 * `deviceFamily` are identical for both — only `role`, `client.mode`, and `scopes` differ (fixed
 * per connection in [GatewayManager], not configurable here), because those three are the axis the
 * protocol actually multiplexes on (see [GatewayManager]'s doc).
 */
data class GatewayConnectionConfig(
    val serverUrl: String,
    val clientVersion: String,
    val platform: String,
    val deviceFamily: String?,
)

/** The two roles a [GatewayManager] tracks, read together — see [GatewayManager.status]. */
data class GatewayStatus(val operator: ConnState, val node: ConnState)

/**
 * Owns the two [GatewayConnection]s a single device identity needs: one `role:"operator"` (chat,
 * dashboard, settings) and one `role:"node"` (sensors, canvas, talk). The protocol allows exactly
 * one role per socket (`ConnectParamsSchema.role` is a single string, not an array), so "the app is
 * connected" isn't one connection — it's this pair, sharing one [DeviceIdentityStore] and one
 * [GatewayDeviceTokenStore] (already keyed by `(deviceId, role)`, so the two connections' tokens
 * land at different keys and never collide — see [GatewayConnection.handleHelloOk]).
 *
 * **Exposed status is a pair, never a collapsed boolean.** Operator and node pairing approvals
 * happen independently on the gateway (`openclaw devices approve` per device+role), so "operator
 * `Connected` while node is still `AwaitingApproval`" is a normal, expected state, not a glitch —
 * collapsing that into a single "connected: Boolean" would hide which half needs Boss's attention.
 * [operatorState] / [nodeState] are exposed individually for callers that only care about one role,
 * and [status] combines both into one [GatewayStatus] for callers (e.g. a settings screen) that
 * want to render "operator: Connected · node: waiting for approval" without composing two flows
 * themselves.
 *
 * **Starting both is genuinely concurrent, not sequential.** [start] calls both connections'
 * [GatewayConnection.connect] back-to-back with nothing awaited in between — `connect()` itself is
 * a plain, non-suspending function that only *launches* the handshake coroutine, so the two
 * handshakes (including each one's own [DeviceIdentityStore.loadOrCreate] call, deep inside
 * `runHandshake`) genuinely run concurrently against the shared, mutex-guarded
 * [DeviceIdentityStore] — the concurrency [DefaultDeviceIdentityStore]'s mutex exists to serialize
 * safely, per its own doc ("the operator and node connections starting at once against this same
 * singleton — see Task 6").
 */
@Singleton
class GatewayManager @Inject constructor(
    webSocketFactory: WebSocket.Factory,
    deviceIdentityStore: DeviceIdentityStore,
    tokenStore: GatewayDeviceTokenStore,
    config: GatewayConnectionConfig,
    private val scope: CoroutineScope,
) {
    private val operatorConnection = GatewayConnection(
        role = GatewayClientInfo.ROLE_OPERATOR,
        mode = GatewayClientInfo.MODE_OPERATOR,
        scopes = OPERATOR_SCOPES,
        serverUrl = config.serverUrl,
        clientVersion = config.clientVersion,
        platform = config.platform,
        deviceFamily = config.deviceFamily,
        webSocketFactory = webSocketFactory,
        deviceIdentityStore = deviceIdentityStore,
        tokenStore = tokenStore,
        scope = scope,
    )

    /** `scopes: []` — required for node pairing auto-approve eligibility: "a fresh `role: node`
     *  device pairing request with no requested scopes." Never add scopes here. */
    private val nodeConnection = GatewayConnection(
        role = GatewayClientInfo.ROLE_NODE,
        mode = GatewayClientInfo.MODE_NODE,
        scopes = emptyList(),
        serverUrl = config.serverUrl,
        clientVersion = config.clientVersion,
        platform = config.platform,
        deviceFamily = config.deviceFamily,
        webSocketFactory = webSocketFactory,
        deviceIdentityStore = deviceIdentityStore,
        tokenStore = tokenStore,
        scope = scope,
    )

    val operatorState: StateFlow<ConnState> = operatorConnection.state
    val nodeState: StateFlow<ConnState> = nodeConnection.state

    /** See the class doc's "Exposed status" section. */
    val status: StateFlow<GatewayStatus> =
        combine(operatorConnection.state, nodeConnection.state, ::GatewayStatus)
            .stateIn(scope, SharingStarted.Eagerly, GatewayStatus(operatorConnection.state.value, nodeConnection.state.value))

    /** Starts both connections. See the class doc's "genuinely concurrent" section — do not insert
     *  anything here that would make the second [GatewayConnection.connect] wait on the first. */
    fun start() {
        operatorConnection.connect()
        nodeConnection.connect()
    }

    fun stop() {
        operatorConnection.disconnect()
        nodeConnection.disconnect()
    }

    private companion object {
        val OPERATOR_SCOPES = listOf("operator.read", "operator.write")
    }
}
