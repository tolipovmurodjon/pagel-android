package org.pagel.ai.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * No Robolectric, no Android Keystore on the plain JVM — same reasoning as
 * [org.pagel.ai.net.identity.DeviceIdentityStoreTest]: [DefaultGatewayAuthTokenStore]'s trim/blank-
 * clears/self-heal logic is exercised against an in-memory fake of [GatewayAuthTokenFileIo] rather
 * than the real [EncryptedFileGatewayAuthTokenFileIo].
 */
class DefaultGatewayAuthTokenStoreTest {

    private class FakeGatewayAuthTokenFileIo : GatewayAuthTokenFileIo {
        var bytes: ByteArray? = null
        var readFailure: Exception? = null

        override fun readBytes(): ByteArray? {
            readFailure?.let { throw it }
            return bytes
        }

        override fun writeBytes(bytes: ByteArray) {
            this.bytes = bytes
        }

        override fun delete() {
            bytes = null
        }
    }

    @Test
    fun `get returns null before anything is stored`() = runBlocking {
        val store = DefaultGatewayAuthTokenStore(FakeGatewayAuthTokenFileIo())
        assertNull(store.get())
    }

    @Test
    fun `put then get round-trips the token`() = runBlocking {
        val store = DefaultGatewayAuthTokenStore(FakeGatewayAuthTokenFileIo())
        store.put("gw-shared-secret")
        assertEquals("gw-shared-secret", store.get())
    }

    @Test
    fun `put trims surrounding whitespace`() = runBlocking {
        val store = DefaultGatewayAuthTokenStore(FakeGatewayAuthTokenFileIo())
        store.put("  gw-shared-secret  \n")
        assertEquals("gw-shared-secret", store.get())
    }

    @Test
    fun `a blank token clears any stored value instead of persisting empty bytes`() = runBlocking {
        val fileIo = FakeGatewayAuthTokenFileIo()
        val store = DefaultGatewayAuthTokenStore(fileIo)
        store.put("gw-shared-secret")
        store.put("   ")
        assertNull(store.get())
        assertNull("blank input must delete, not write empty bytes", fileIo.bytes)
    }

    @Test
    fun `an unreadable file is treated as no token configured rather than thrown`() = runBlocking {
        // Mirrors DeviceIdentityStoreTest's "keystore refuses to open the file" case: the user
        // cleared credential storage, or the OEM Keystore misbehaved. The debug screen must still
        // load with an empty token field, not crash.
        val fileIo = FakeGatewayAuthTokenFileIo().apply {
            readFailure = GeneralSecurityException("keyset not found")
        }
        val store = DefaultGatewayAuthTokenStore(fileIo)
        assertNull(store.get())
    }
}
