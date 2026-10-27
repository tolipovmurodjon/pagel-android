@file:OptIn(ExperimentalCoroutinesApi::class)

package org.pagel.ai.net.ws

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRpcTest {

    @Test
    fun `call correlates the response to its request by id`() = runTest {
        var sentId: String? = null
        val rpc = GatewayRpc(send = { req -> sentId = req.id; true }, nextId = { "req-1" })

        val pending = async { rpc.call("ping") }
        runCurrent()
        assertEquals("req-1", sentId)

        rpc.onResponse(GatewayFrame.Res(id = "req-1", ok = true, payload = JsonPrimitive("pong")))
        val result = pending.await()

        assertTrue(result.isSuccess)
        assertEquals(JsonPrimitive("pong"), result.getOrNull())
    }

    @Test
    fun `an ok-false response surfaces the gateway error, not a generic failure`() = runTest {
        val rpc = GatewayRpc(send = { true }, nextId = { "req-1" })

        val pending = async { rpc.call("connect") }
        runCurrent()
        rpc.onResponse(GatewayFrame.Res(id = "req-1", ok = false, error = GatewayError(code = "NOT_PAIRED", message = "wait for approval")))
        val result = pending.await()

        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as GatewayRpcException).error
        assertEquals("NOT_PAIRED", error?.code)
    }

    @Test
    fun `call times out when no response ever arrives`() = runTest {
        val rpc = GatewayRpc(send = { true }, nextId = { "req-1" }, timeoutMs = 5_000)

        val pending = async { rpc.call("connect") }
        advanceTimeBy(5_001)
        val result = pending.await()

        assertTrue(result.isFailure)
    }

    @Test
    fun `a response with an id that matches nothing pending is ignored, not an error`() = runTest {
        val rpc = GatewayRpc(send = { true }, nextId = { "req-1" })
        // No pending call at all — must not throw.
        rpc.onResponse(GatewayFrame.Res(id = "unrelated", ok = true, payload = JsonPrimitive("noop")))

        val pending = async { rpc.call("ping") }
        runCurrent()
        rpc.onResponse(GatewayFrame.Res(id = "also-unrelated", ok = true))
        rpc.onResponse(GatewayFrame.Res(id = "req-1", ok = true, payload = JsonPrimitive("pong")))
        val result = pending.await()

        assertEquals(JsonPrimitive("pong"), result.getOrNull())
    }

    @Test
    fun `send failure fails the call without sending an id anyone will answer`() = runTest {
        val rpc = GatewayRpc(send = { false }, nextId = { "req-1" })
        val result = rpc.call("connect")
        assertTrue(result.isFailure)
    }
}
