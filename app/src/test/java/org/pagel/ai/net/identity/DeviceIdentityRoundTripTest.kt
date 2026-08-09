package org.pagel.ai.net.identity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live gateway rejects our handshake with `device signature invalid` while every field of the
 * signed payload is verifiably correct. A signature that is well-formed but does not verify has one
 * remaining explanation: the private key we sign with no longer matches the public key we present.
 *
 * Nothing covered that. [DeviceIdentityTest] only ever signs with a freshly generated in-memory
 * identity, and [DeviceIdentityStoreTest] exercises concurrency, not persistence fidelity. The one
 * path that actually runs in production — generate, persist, reload, sign, verify — was untested.
 */
class DeviceIdentityRoundTripTest {

    /** In-memory stand-in for the real [EncryptedFileDeviceIdentityFileIo]. */
    private class FakeFileIo : DeviceIdentityFileIo {
        private var bytes: ByteArray? = null
        override fun readBytes(): ByteArray? = bytes
        override fun writeBytes(bytes: ByteArray) { this.bytes = bytes }
    }

    @Test
    fun `a signature made with the RELOADED private key verifies against the RELOADED public key`() = runBlocking {
        val io = FakeFileIo()

        val created = DefaultDeviceIdentityStore(io).loadOrCreate()
        // A second store over the same bytes is exactly what a cold app start does.
        val reloaded = DefaultDeviceIdentityStore(io).loadOrCreate()

        assertEquals("deviceId must survive a reload", created.deviceId, reloaded.deviceId)
        assertArrayEquals("public key must survive a reload", created.publicKeyRaw, reloaded.publicKeyRaw)
        assertArrayEquals("private key must survive a reload", created.privateKeyRaw, reloaded.privateKeyRaw)

        val payload = "v3|${reloaded.deviceId}|openclaw-android|ui|operator||1786284670621||nonce|android|sm-a057f"
        val sig = Ed25519.sign(reloaded.privateKeyRaw, payload.toByteArray(Charsets.UTF_8))

        assertTrue(
            "signature from the reloaded private key must verify against the reloaded public key",
            Ed25519.verify(reloaded.publicKeyRaw, payload.toByteArray(Charsets.UTF_8), sig),
        )
        assertTrue(
            "and against the originally generated public key — they are the same keypair",
            Ed25519.verify(created.publicKeyRaw, payload.toByteArray(Charsets.UTF_8), sig),
        )
    }

    @Test
    fun `deviceId is the sha256 of the public key we actually present`() = runBlocking {
        val reloaded = DefaultDeviceIdentityStore(FakeFileIo()).loadOrCreate()
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(reloaded.publicKeyRaw).joinToString("") { "%02x".format(it) }
        // The gateway recomputes this from the publicKey we send and rejects a mismatch outright,
        // so a divergence here would surface as `device identity mismatch`, not a bad signature.
        assertEquals(expected, reloaded.deviceId)
    }
}
