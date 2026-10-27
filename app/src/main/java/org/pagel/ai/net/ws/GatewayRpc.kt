package org.pagel.ai.net.ws

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/** Thrown when a `call()` resolves to a `res` frame with `ok:false`. Carries the gateway's [GatewayError] verbatim. */
class GatewayRpcException(val error: GatewayError?) : Exception(error?.message ?: "gateway RPC error")

/**
 * Correlates outbound `req` frames to their `res` by `id`. [send] hands a built [GatewayFrame.Req]
 * to the socket (returning `false` if the send failed outright); [onResponse] must be fed every
 * inbound `Res` frame the owning connection sees — [call] resolves the pending request matching
 * `res.id`, or times out after [timeoutMs] if the gateway never answers (default 30s per
 * `docs/gateway/protocol.md:754-765`, the documented per-RPC request timeout).
 */
class GatewayRpc(
    private val send: (GatewayFrame.Req) -> Boolean,
    private val nextId: () -> String,
    private val timeoutMs: Long = 30_000,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<GatewayFrame.Res>>()

    suspend fun call(method: String, params: JsonElement? = null): Result<JsonElement?> {
        val id = nextId()
        val deferred = CompletableDeferred<GatewayFrame.Res>()
        pending[id] = deferred
        try {
            if (!send(GatewayFrame.Req(id = id, method = method, params = params))) {
                return Result.failure(IllegalStateException("send failed for method \"$method\""))
            }
            val res = withTimeout(timeoutMs) { deferred.await() }
            return if (res.ok) Result.success(res.payload) else Result.failure(GatewayRpcException(res.error))
        } catch (e: TimeoutCancellationException) {
            return Result.failure(e)
        } finally {
            pending.remove(id)
        }
    }

    /** Feed an inbound `res` frame. A `res.id` with no matching pending call is ignored, not an error. */
    fun onResponse(res: GatewayFrame.Res) {
        pending.remove(res.id)?.complete(res)
    }

    /** Resolves every still-pending call as failed with [error] — e.g. the socket died — instead of
     *  leaving each to discover that independently via its own [timeoutMs]. Reuses the same
     *  `ok:false` path [onResponse] would take, so [call]'s result-mapping logic doesn't need a
     *  second failure shape to handle. A no-op if nothing is pending. */
    fun cancelAll(error: GatewayError) {
        val ids = pending.keys.toList()
        for (id in ids) {
            pending.remove(id)?.complete(GatewayFrame.Res(id = id, ok = false, error = error))
        }
    }
}
