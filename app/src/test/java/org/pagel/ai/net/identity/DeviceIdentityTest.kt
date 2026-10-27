package org.pagel.ai.net.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {

    @Test
    fun `deviceId is lowercase hex sha256 of the raw 32-byte public key`() {
        val id = Ed25519.generate()
        assertEquals(32, id.publicKeyRaw.size)
        assertEquals(64, id.deviceId.length)
        assertTrue(id.deviceId.all { it in "0123456789abcdef" })
        assertEquals(sha256Hex(id.publicKeyRaw), id.deviceId)
    }

    @Test
    fun `base64url encoding has no padding and no plus or slash`() {
        val encoded = base64UrlNoPad(ByteArray(32) { it.toByte() })
        assertTrue(!encoded.contains('='))
        assertTrue(!encoded.contains('+'))
        assertTrue(!encoded.contains('/'))
    }

    @Test
    fun `signature verifies against the public key and changes with the message`() {
        val id = Ed25519.generate()
        val sigA = Ed25519.sign(id.privateKeyRaw, "hello".toByteArray())
        val sigB = Ed25519.sign(id.privateKeyRaw, "hellp".toByteArray())
        assertEquals(64, sigA.size)
        assertNotEquals(base64UrlNoPad(sigA), base64UrlNoPad(sigB))
        assertTrue(Ed25519.verify(id.publicKeyRaw, "hello".toByteArray(), sigA))
        assertTrue(!Ed25519.verify(id.publicKeyRaw, "hello".toByteArray(), sigB))
    }

    @Test
    fun `two generated identities differ`() {
        assertNotEquals(Ed25519.generate().deviceId, Ed25519.generate().deviceId)
    }

    @Test
    fun `equals and hashCode compare key bytes by content, not array reference`() {
        val a = DeviceIdentity("id", byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val b = DeviceIdentity("id", byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
}
