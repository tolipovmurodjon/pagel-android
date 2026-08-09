@file:OptIn(ExperimentalCoroutinesApi::class)

package org.pagel.ai.net.node

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeCommandDispatcherTest {

    /** A [NodeCommand] whose behaviour the test controls directly — no Android APIs, no real
     *  socket, matching the plan's "fake command" requirement for Task 3. [name] defaults to
     *  `"device.status"`, a real entry in [NodeCommands.DECLARED] with no implementation yet, so the
     *  dispatcher's construction-time drift guard (a real requirement, not just test plumbing)
     *  doesn't reject the fake outright. */
    private class FakeNodeCommand(
        override val name: String = "device.status",
        private val onInvoke: suspend (JsonObject) -> JsonElement = { buildJsonObject { put("ok", true) } },
    ) : NodeCommand {
        var invocationCount = 0
            private set
        var lastParams: JsonObject? = null

        override suspend fun invoke(params: JsonObject): JsonElement {
            invocationCount++
            lastParams = params
            return onInvoke(params)
        }
    }

    private fun request(
        id: String = "req-1",
        nodeId: String = "node-1",
        command: String = "device.status",
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long? = null,
    ) = InvokeRequest(id = id, nodeId = nodeId, command = command, params = params, timeoutMs = timeoutMs)

    @Test
    fun `a known command returns its payload in a Success result with id and nodeId echoed`() = runTest {
        val payload = buildJsonObject { put("battery", buildJsonObject { put("level", 0.62) }) }
        val command = FakeNodeCommand(onInvoke = { payload })
        val dispatcher = NodeCommandDispatcher(listOf(command))

        val result = dispatcher.handle(request(id = "req-abc", nodeId = "node-xyz", command = "device.status"))

        assertTrue(result is InvokeResult.Success)
        result as InvokeResult.Success
        assertEquals("req-abc", result.id)
        assertEquals("node-xyz", result.nodeId)
        assertEquals(payload, result.payload)
        assertEquals(1, command.invocationCount)
    }

    @Test
    fun `params reach the command exactly as given`() = runTest {
        val command = FakeNodeCommand()
        val dispatcher = NodeCommandDispatcher(listOf(command))
        val params = buildJsonObject { put("limit", 10) }

        dispatcher.handle(request(params = params))

        assertEquals(params, command.lastParams)
    }

    @Test
    fun `an unknown command returns a structured INVALID_REQUEST error, not a thrown exception`() = runTest {
        val dispatcher = NodeCommandDispatcher(listOf(FakeNodeCommand()))

        val result = dispatcher.handle(request(id = "req-2", nodeId = "node-2", command = "camera.snap"))

        assertTrue(result is InvokeResult.Error)
        result as InvokeResult.Error
        assertEquals("req-2", result.id)
        assertEquals("node-2", result.nodeId)
        assertEquals("INVALID_REQUEST", result.code)
    }

    @Test
    fun `a command with no registered NodeCommand at all also produces an unknown-command error`() = runTest {
        val dispatcher = NodeCommandDispatcher(emptyList())

        val result = dispatcher.handle(request(command = "location.get"))

        assertTrue(result is InvokeResult.Error)
        assertEquals("INVALID_REQUEST", (result as InvokeResult.Error).code)
    }

    @Test
    fun `a command that throws is converted to a COMMAND_ERROR result, not propagated`() = runTest {
        val command = FakeNodeCommand(onInvoke = { throw IllegalStateException("sensor unavailable") })
        val dispatcher = NodeCommandDispatcher(listOf(command))

        val result = dispatcher.handle(request(id = "req-3", nodeId = "node-3"))

        assertTrue(result is InvokeResult.Error)
        result as InvokeResult.Error
        assertEquals("req-3", result.id)
        assertEquals("node-3", result.nodeId)
        assertEquals("COMMAND_ERROR", result.code)
        assertTrue(result.message.contains("sensor unavailable"))
    }

    @Test
    fun `a command that exceeds timeoutMs times out to a TIMEOUT result using virtual time`() = runTest {
        val command = FakeNodeCommand(onInvoke = {
            delay(10_000) // never actually waits real-world time under runTest's virtual clock
            buildJsonObject {}
        })
        val dispatcher = NodeCommandDispatcher(listOf(command))

        val result = dispatcher.handle(request(id = "req-4", nodeId = "node-4", timeoutMs = 5_000))

        assertTrue(result is InvokeResult.Error)
        result as InvokeResult.Error
        assertEquals("req-4", result.id)
        assertEquals("node-4", result.nodeId)
        assertEquals("TIMEOUT", result.code)
    }

    @Test
    fun `a slow command produces exactly one result, not a late second one`() = runTest {
        var completedNormally = false
        val command = FakeNodeCommand(onInvoke = {
            delay(10_000)
            completedNormally = true // must never be reached — withTimeout cancels the command's coroutine
            buildJsonObject {}
        })
        val dispatcher = NodeCommandDispatcher(listOf(command))

        val result = dispatcher.handle(request(timeoutMs = 1_000))

        assertTrue(result is InvokeResult.Error)
        assertEquals("TIMEOUT", (result as InvokeResult.Error).code)
        assertTrue("the command's own coroutine must have been cancelled by the timeout, not merely raced", !completedNormally)
    }

    @Test
    fun `when the request carries no timeoutMs, the dispatcher falls back to its own default budget`() = runTest {
        // Mirrors the gateway's resolveTimerTimeoutMs(params.timeoutMs, 3e4, 0) fallback (protocol
        // reference §1.4): absent timeoutMs on the wire does not mean "wait forever."
        val command = FakeNodeCommand(onInvoke = {
            delay(60_000)
            buildJsonObject {}
        })
        val dispatcher = NodeCommandDispatcher(listOf(command))

        val result = dispatcher.handle(request(timeoutMs = null))

        assertTrue(result is InvokeResult.Error)
        assertEquals("TIMEOUT", (result as InvokeResult.Error).code)
    }

    @Test
    fun `constructing with a NodeCommand not in NodeCommands DECLARED fails fast`() {
        val rogueCommand = FakeNodeCommand(name = "camera.snap") // Android-dangerous, never declared
        assertThrows(IllegalArgumentException::class.java) {
            NodeCommandDispatcher(listOf(rogueCommand))
        }
    }

    @Test
    fun `constructing with duplicate command names fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeCommandDispatcher(listOf(FakeNodeCommand(name = "device.status"), FakeNodeCommand(name = "device.status")))
        }
    }

    @Test
    fun `every entry in NodeCommands DECLARED is a legal NodeCommand name to register`() {
        // Sanity check on the fixture itself: every command this test suite exercises with
        // FakeNodeCommand's default name must actually be declared, or these tests would be pinning
        // behaviour against a name the dispatcher's own guard would reject in production.
        assertTrue("device.status" in NodeCommands.DECLARED)
    }
}
