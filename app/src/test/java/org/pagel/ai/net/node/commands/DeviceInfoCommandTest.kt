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

class DeviceInfoCommandTest {

    @Test
    fun `name is the exact declared wire command`() {
        val command = DeviceInfoCommand(FakeDeviceInfoSource())
        assertEquals("device.info", command.name)
        assertTrue("must be a real entry in NodeCommands DECLARED", command.name in NodeCommands.DECLARED)
    }

    @Test
    fun `carries the device identity snapshot's fields verbatim`() = runTest {
        val identity = DeviceIdentitySnapshot(
            manufacturer = "samsung",
            model = "SM-A057F",
            androidVersion = "15",
            sdkInt = 35,
            appVersionName = "0.1-A1",
            appVersionCode = 7,
            timezone = "Asia/Tashkent",
        )
        val command = DeviceInfoCommand(FakeDeviceInfoSource(identity = identity))

        val result = command.invoke(JsonObject(emptyMap())).jsonObject

        assertEquals("samsung", result["manufacturer"]!!.jsonPrimitive.content)
        assertEquals("SM-A057F", result["model"]!!.jsonPrimitive.content)
        assertEquals("15", result["androidVersion"]!!.jsonPrimitive.content)
        assertEquals(35, result["sdkInt"]!!.jsonPrimitive.content.toInt())
        assertEquals("0.1-A1", result["appVersion"]!!.jsonPrimitive.content)
        assertEquals(7L, result["appVersionCode"]!!.jsonPrimitive.content.toLong())
        assertEquals("Asia/Tashkent", result["timezone"]!!.jsonPrimitive.content)
    }
}
