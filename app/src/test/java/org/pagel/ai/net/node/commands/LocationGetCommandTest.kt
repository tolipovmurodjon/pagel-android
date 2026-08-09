@file:OptIn(ExperimentalCoroutinesApi::class)

package org.pagel.ai.net.node.commands

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.net.node.NodeCommandError
import org.pagel.ai.net.node.NodeCommands

class LocationGetCommandTest {

    /** Runs [block], expecting it to throw [NodeCommandError], and asserts its `code` matches
     *  [expectedCode] — every error path in [LocationGetCommand] must use its own stable code, never
     *  fall through to the dispatcher's generic `COMMAND_ERROR`. */
    private suspend fun assertNodeCommandError(expectedCode: String, block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (e: NodeCommandError) {
            e
        }
        assertNotNull("expected a NodeCommandError with code=$expectedCode but none was thrown", error)
        assertEquals(expectedCode, error!!.code)
    }

    @Test
    fun `name is the exact declared wire command`() {
        val command = LocationGetCommand(FakeLocationSource())
        assertEquals("location.get", command.name)
        assertTrue("must be a real entry in NodeCommands DECLARED", command.name in NodeCommands.DECLARED)
    }

    // --- error paths, in the task doc's own "map them honestly" order ---

    @Test
    fun `no foreground permission reports LOCATION_PERMISSION_REQUIRED`() = runTest {
        val command = LocationGetCommand(FakeLocationSource(hasForegroundPermission = false))
        assertNodeCommandError("LOCATION_PERMISSION_REQUIRED") { command.invoke(JsonObject(emptyMap())) }
    }

    @Test
    fun `foreground-only permission while backgrounded reports LOCATION_BACKGROUND_UNAVAILABLE`() = runTest {
        // The exact bug this project exists to fix: "While Using" granted, but we're backgrounded.
        val command = LocationGetCommand(
            FakeLocationSource(hasForegroundPermission = true, hasBackgroundPermission = false, appInForeground = false),
        )
        assertNodeCommandError("LOCATION_BACKGROUND_UNAVAILABLE") { command.invoke(JsonObject(emptyMap())) }
    }

    @Test
    fun `backgrounded with background permission granted proceeds normally, not LOCATION_BACKGROUND_UNAVAILABLE`() = runTest {
        // The whole point of declaring ACCESS_BACKGROUND_LOCATION: once Boss grants "Allow all the
        // time", a backgrounded request must succeed, not be refused the way the official app's is.
        val source = FakeLocationSource(hasForegroundPermission = true, hasBackgroundPermission = true, appInForeground = false)
        val command = LocationGetCommand(source)

        val result = command.invoke(JsonObject(emptyMap())).jsonObject

        assertEquals(FakeLocationSource.DEFAULT_FIX.lat, result["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `location services disabled at the OS level reports LOCATION_DISABLED`() = runTest {
        val command = LocationGetCommand(FakeLocationSource(locationEnabled = false))
        assertNodeCommandError("LOCATION_DISABLED") { command.invoke(JsonObject(emptyMap())) }
    }

    @Test
    fun `a fresh-fix timeout reports LOCATION_TIMEOUT`() = runTest {
        val command = LocationGetCommand(FakeLocationSource(freshFixFailure = LocationFixFailure.Timeout()))
        assertNodeCommandError("LOCATION_TIMEOUT") { command.invoke(JsonObject(emptyMap())) }
    }

    @Test
    fun `no providers available reports LOCATION_UNAVAILABLE`() = runTest {
        val command = LocationGetCommand(FakeLocationSource(freshFixFailure = LocationFixFailure.Unavailable()))
        assertNodeCommandError("LOCATION_UNAVAILABLE") { command.invoke(JsonObject(emptyMap())) }
    }

    // --- success mapping ---

    @Test
    fun `success maps every field including isPrecise and source`() = runTest {
        val fix = LocationFix(
            lat = 48.20849, lon = 16.37208, accuracyMeters = 12.5f, altitudeMeters = 182.0,
            speedMps = 3.5f, headingDeg = 270f, timestampMs = 1_700_000_000_000L,
            isPrecise = true, fixSource = LocationFixSource.GPS,
        )
        val command = LocationGetCommand(FakeLocationSource(freshFix = fix))

        val result = command.invoke(JsonObject(emptyMap())).jsonObject

        assertEquals(48.20849, result["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(16.37208, result["lon"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(12.5, result["accuracyMeters"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(182.0, result["altitudeMeters"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(3.5, result["speedMps"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(270.0, result["headingDeg"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("2023-11-14T22:13:20Z", result["timestamp"]!!.jsonPrimitive.content)
        assertTrue(result["isPrecise"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("gps", result["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nullable fix fields serialize as JSON null, not zero`() = runTest {
        val fix = LocationFix(
            lat = 1.0, lon = 2.0, accuracyMeters = null, altitudeMeters = null,
            speedMps = null, headingDeg = null, timestampMs = 0L,
            isPrecise = false, fixSource = LocationFixSource.UNKNOWN,
        )
        val command = LocationGetCommand(FakeLocationSource(freshFix = fix))

        val result = command.invoke(JsonObject(emptyMap())).jsonObject

        assertEquals(JsonNull, result["accuracyMeters"])
        assertEquals(JsonNull, result["altitudeMeters"])
        assertEquals(JsonNull, result["speedMps"])
        assertEquals(JsonNull, result["headingDeg"])
    }

    // --- caching / params ---

    @Test
    fun `a cached fix within maxAgeMs is used and requestFix is never called`() = runTest {
        val cached = FakeLocationSource.DEFAULT_FIX
        val source = FakeLocationSource(cachedFix = cached)
        val command = LocationGetCommand(source)
        val params = buildJsonObject { put("maxAgeMs", 15_000) }

        val result = command.invoke(params).jsonObject

        assertEquals(0, source.requestFixCallCount)
        assertEquals(15_000L, source.lastMaxAgeMsRequested)
        assertEquals(cached.lat, result["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `no cached fix falls through to requestFix`() = runTest {
        val source = FakeLocationSource(cachedFix = null)
        val command = LocationGetCommand(source)

        command.invoke(JsonObject(emptyMap()))

        assertEquals(1, source.requestFixCallCount)
    }

    @Test
    fun `absent maxAgeMs param passes 0 to the source, never a guessed positive window`() = runTest {
        // Whether a real LocationSource treats 0 as "never satisfied" is that implementation's own
        // concern (AndroidLocationSource.lastKnownFix filters by age); what this command must
        // guarantee is that an absent maxAgeMs is never silently widened into tolerating a stale fix.
        val source = FakeLocationSource(cachedFix = null)
        val command = LocationGetCommand(source)

        command.invoke(JsonObject(emptyMap()))

        assertEquals(0L, source.lastMaxAgeMsRequested)
    }

    @Test
    fun `absent timeoutMs falls back to the command's own default`() = runTest {
        val source = FakeLocationSource(cachedFix = null)
        val command = LocationGetCommand(source)

        command.invoke(JsonObject(emptyMap()))

        assertEquals(10_000L, source.lastTimeoutMsRequested)
    }

    @Test
    fun `explicit timeoutMs is honoured`() = runTest {
        val source = FakeLocationSource(cachedFix = null)
        val command = LocationGetCommand(source)
        val params = buildJsonObject { put("timeoutMs", 3_000) }

        command.invoke(params)

        assertEquals(3_000L, source.lastTimeoutMsRequested)
    }

    @Test
    fun `desiredAccuracy string params map to the right enum value`() = runTest {
        val source = FakeLocationSource(cachedFix = null)
        val command = LocationGetCommand(source)

        command.invoke(buildJsonObject { put("desiredAccuracy", "coarse") })
        assertEquals(DesiredAccuracy.COARSE, source.lastDesiredAccuracyRequested)

        command.invoke(buildJsonObject { put("desiredAccuracy", "precise") })
        assertEquals(DesiredAccuracy.PRECISE, source.lastDesiredAccuracyRequested)

        command.invoke(JsonObject(emptyMap()))
        assertEquals(DesiredAccuracy.BALANCED, source.lastDesiredAccuracyRequested)

        command.invoke(buildJsonObject { put("desiredAccuracy", "nonsense") })
        assertEquals(DesiredAccuracy.BALANCED, source.lastDesiredAccuracyRequested)
    }
}
