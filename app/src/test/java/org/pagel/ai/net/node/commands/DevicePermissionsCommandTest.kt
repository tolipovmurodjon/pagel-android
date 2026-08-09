@file:OptIn(ExperimentalCoroutinesApi::class)

package org.pagel.ai.net.node.commands

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.net.node.NodeCommands

class DevicePermissionsCommandTest {

    @Test
    fun `name is the exact declared wire command`() {
        val command = DevicePermissionsCommand(FakeDeviceInfoSource())
        assertEquals("device.permissions", command.name)
        assertTrue("must be a real entry in NodeCommands DECLARED", command.name in NodeCommands.DECLARED)
    }

    /** The whole reason this command exists (Phase D task doc): foreground-only ("While Using")
     *  must never read the same as truly backgrounded ("Allow all the time"). */
    @Test
    fun `foreground granted but background denied are reported independently, not collapsed`() = runTest {
        val permissions = DevicePermissionsSnapshot(
            location = LocationPermissionSnapshot(foreground = true, background = false),
            camera = false, microphone = false, notificationListener = false,
            postNotifications = false, activityRecognition = false, usageStats = false,
        )
        val command = DevicePermissionsCommand(FakeDeviceInfoSource(permissions = permissions))

        val location = command.invoke(JsonObject(emptyMap())).jsonObject["location"]!!.jsonObject

        assertTrue("foreground must read true", location["foreground"]!!.jsonPrimitive.content.toBoolean())
        assertFalse("background must read false, not inherit foreground's true", location["background"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `both foreground and background granted are reported as true independently`() = runTest {
        val permissions = DevicePermissionsSnapshot(
            location = LocationPermissionSnapshot(foreground = true, background = true),
            camera = false, microphone = false, notificationListener = false,
            postNotifications = false, activityRecognition = false, usageStats = false,
        )
        val command = DevicePermissionsCommand(FakeDeviceInfoSource(permissions = permissions))

        val location = command.invoke(JsonObject(emptyMap())).jsonObject["location"]!!.jsonObject

        assertTrue(location["foreground"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(location["background"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `neither location permission granted reports both false`() = runTest {
        val permissions = DevicePermissionsSnapshot(
            location = LocationPermissionSnapshot(foreground = false, background = false),
            camera = false, microphone = false, notificationListener = false,
            postNotifications = false, activityRecognition = false, usageStats = false,
        )
        val command = DevicePermissionsCommand(FakeDeviceInfoSource(permissions = permissions))

        val location = command.invoke(JsonObject(emptyMap())).jsonObject["location"]!!.jsonObject

        assertFalse(location["foreground"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(location["background"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `the non-location grants are carried through verbatim`() = runTest {
        val permissions = DevicePermissionsSnapshot(
            location = LocationPermissionSnapshot(foreground = false, background = false),
            camera = true, microphone = true, notificationListener = true,
            postNotifications = false, activityRecognition = true, usageStats = true,
        )
        val command = DevicePermissionsCommand(FakeDeviceInfoSource(permissions = permissions))

        val result = command.invoke(JsonObject(emptyMap())).jsonObject

        assertTrue(result["camera"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["microphone"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["notificationListener"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(result["postNotifications"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["activityRecognition"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["usageStats"]!!.jsonPrimitive.content.toBoolean())
    }
}
