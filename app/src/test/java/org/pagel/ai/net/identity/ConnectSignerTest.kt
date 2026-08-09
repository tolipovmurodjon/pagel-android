package org.pagel.ai.net.identity

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectSignerTest {

    @Test
    fun `payload matches the upstream v3 field order exactly`() {
        val payload = buildDeviceAuthPayloadV3(
            deviceId = "abc123", clientId = "pagel", clientMode = "operator",
            role = "operator", scopes = listOf("operator.read", "operator.write"),
            signedAtMs = 1700000000000L, token = "tok", nonce = "nonce-1",
            platform = "Android", deviceFamily = "SM-A057F",
        )
        assertEquals(
            "v3|abc123|pagel|operator|operator|operator.read,operator.write|1700000000000|tok|nonce-1|android|sm-a057f",
            payload,
        )
    }

    @Test
    fun `null token becomes an empty slot, not a dropped field`() {
        val payload = buildDeviceAuthPayloadV3(
            deviceId = "d", clientId = "c", clientMode = "node", role = "node",
            scopes = emptyList(), signedAtMs = 1L, token = null, nonce = "n",
            platform = null, deviceFamily = null,
        )
        assertEquals("v3|d|c|node|node||1||n||", payload)
    }

    @Test
    fun `normalizer is ASCII-only and trims`() {
        assertEquals("android", normalizeDeviceMetadataForAuth("  Android  "))
        assertEquals("", normalizeDeviceMetadataForAuth(null))
        assertEquals("", normalizeDeviceMetadataForAuth("   "))
        // Non-ASCII uppercase must be left untouched — String.lowercase() would change these.
        assertEquals("ÄÖÜ-x", normalizeDeviceMetadataForAuth("ÄÖÜ-X"))
        assertEquals("i̇stanbul", normalizeDeviceMetadataForAuth("i̇stanbul"))
    }

    @Test
    fun `scopes join with comma and no spaces`() {
        val payload = buildDeviceAuthPayloadV3(
            deviceId = "d", clientId = "c", clientMode = "operator", role = "operator",
            scopes = listOf("a", "b", "c"), signedAtMs = 2L, token = "", nonce = "n",
            platform = "", deviceFamily = "",
        )
        assertEquals("v3|d|c|operator|operator|a,b,c|2||n||", payload)
    }
}
