@file:OptIn(ExperimentalCoroutinesApi::class)

package org.pagel.ai.net.node.commands

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.net.node.NodeCommands

class DeviceStatusCommandTest {

    @Test
    fun `name is the exact declared wire command`() {
        val command = DeviceStatusCommand(FakeDeviceInfoSource())
        assertEquals("device.status", command.name)
        assertTrue("must be a real entry in NodeCommands DECLARED", command.name in NodeCommands.DECLARED)
    }

    @Test
    fun `a 78 percent battery serialises as a 0point78 fraction, never 78`() = runTest {
        val source = FakeDeviceInfoSource(battery = BatterySnapshot(0.78, BatteryChargeState.CHARGING, lowPowerModeEnabled = false))
        val command = DeviceStatusCommand(source)

        val result = command.invoke(JsonObject(emptyMap())).jsonObject

        val level = result["battery"]!!.jsonObject["level"]!!.jsonPrimitive.double
        assertEquals(0.78, level, 0.0)
        assertTrue("must be a fraction, not a percentage", level <= 1.0)
    }

    @Test
    fun `battery state maps unplugged charging and full independently for each charging state`() = runTest {
        val cases = listOf(
            BatteryChargeState.UNPLUGGED to "unplugged",
            BatteryChargeState.CHARGING to "charging",
            BatteryChargeState.FULL to "full",
        )
        for ((state, wireValue) in cases) {
            val source = FakeDeviceInfoSource(battery = BatterySnapshot(0.5, state, lowPowerModeEnabled = false))
            val command = DeviceStatusCommand(source)

            val result = command.invoke(JsonObject(emptyMap())).jsonObject

            assertEquals(
                "BatteryChargeState.$state must serialise as \"$wireValue\"",
                wireValue,
                result["battery"]!!.jsonObject["state"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `lowPowerModeEnabled is carried through untouched`() = runTest {
        val source = FakeDeviceInfoSource(battery = BatterySnapshot(0.5, BatteryChargeState.UNPLUGGED, lowPowerModeEnabled = true))
        val command = DeviceStatusCommand(source)

        val result = command.invoke(JsonObject(emptyMap())).jsonObject

        assertTrue(result["battery"]!!.jsonObject["lowPowerModeEnabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `thermal state is nested under thermal state, matching the official app's shape`() = runTest {
        val source = FakeDeviceInfoSource(thermal = ThermalState.SEVERE)
        val command = DeviceStatusCommand(source)

        val result = command.invoke(JsonObject(emptyMap())).jsonObject

        assertEquals("severe", result["thermal"]!!.jsonObject["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `storage carries totalBytes freeBytes and usedBytes verbatim`() = runTest {
        val source = FakeDeviceInfoSource(storage = StorageSnapshot(totalBytes = 116_599_373_824, freeBytes = 20_045_778_944, usedBytes = 96_553_594_880))
        val command = DeviceStatusCommand(source)

        val result = command.invoke(JsonObject(emptyMap())).jsonObject
        val storage = result["storage"]!!.jsonObject

        assertEquals(116_599_373_824L, storage["totalBytes"]!!.jsonPrimitive.content.toLong())
        assertEquals(20_045_778_944L, storage["freeBytes"]!!.jsonPrimitive.content.toLong())
        assertEquals(96_553_594_880L, storage["usedBytes"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `network reports connected type and metered`() = runTest {
        val source = FakeDeviceInfoSource(network = NetworkSnapshot(connected = false, type = NetworkType.CELLULAR, metered = true))
        val command = DeviceStatusCommand(source)

        val result = command.invoke(JsonObject(emptyMap())).jsonObject
        val network = result["network"]!!.jsonObject

        assertEquals(false, network["connected"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("cellular", network["type"]!!.jsonPrimitive.content)
        assertEquals(true, network["metered"]!!.jsonPrimitive.content.toBoolean())
    }
}
