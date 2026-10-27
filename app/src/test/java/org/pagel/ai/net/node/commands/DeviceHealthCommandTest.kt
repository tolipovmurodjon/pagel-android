@file:OptIn(ExperimentalCoroutinesApi::class)

package org.pagel.ai.net.node.commands

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.net.node.NodeCommands

class DeviceHealthCommandTest {

    @Test
    fun `name is the exact declared wire command`() {
        val command = DeviceHealthCommand(FakeDeviceInfoSource())
        assertEquals("device.health", command.name)
        assertTrue("must be a real entry in NodeCommands DECLARED", command.name in NodeCommands.DECLARED)
    }

    @Test
    fun `carries memory and uptime verbatim, disjoint from device status's fields`() = runTest {
        val memory = MemorySnapshot(totalBytes = 6_000_000_000, availableBytes = 1_500_000_000, lowMemory = true, thresholdBytes = 400_000_000)
        val command = DeviceHealthCommand(FakeDeviceInfoSource(memory = memory, uptime = 987_654_321L))

        val result = command.invoke(JsonObject(emptyMap())).jsonObject
        val memoryJson = result["memory"]!!.jsonObject

        assertEquals(6_000_000_000L, memoryJson["totalBytes"]!!.jsonPrimitive.content.toLong())
        assertEquals(1_500_000_000L, memoryJson["availableBytes"]!!.jsonPrimitive.content.toLong())
        assertTrue(memoryJson["lowMemory"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(400_000_000L, memoryJson["thresholdBytes"]!!.jsonPrimitive.content.toLong())
        assertEquals(987_654_321L, result["uptimeMs"]!!.jsonPrimitive.content.toLong())

        // device.status already owns battery/thermal/storage/network — device.health must not
        // duplicate any of them (see this command's class doc).
        assertTrue("battery" !in result)
        assertTrue("thermal" !in result)
        assertTrue("storage" !in result)
        assertTrue("network" !in result)
    }
}
