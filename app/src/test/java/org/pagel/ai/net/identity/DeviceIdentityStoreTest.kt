package org.pagel.ai.net.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicInteger

class DeviceIdentityStoreTest {

    /**
     * In-memory stand-in for [EncryptedFileDeviceIdentityFileIo] — a real [androidx.security.crypto.EncryptedFile]
     * needs an Android Keystore, unavailable in a plain JVM unit test, so [DefaultDeviceIdentityStore]'s
     * concurrency behavior is exercised against this fake instead. The read-side delay widens the
     * race window: without a lock in the store, concurrent callers would all observe no stored
     * identity before any of them has written one.
     */
    private class FakeDeviceIdentityFileIo : DeviceIdentityFileIo {
        @Volatile private var bytes: ByteArray? = null
        val writeCount = AtomicInteger(0)

        override fun readBytes(): ByteArray? {
            Thread.sleep(5)
            return bytes
        }

        override fun writeBytes(bytes: ByteArray) {
            writeCount.incrementAndGet()
            this.bytes = bytes
        }
    }

    @Test
    fun `concurrent loadOrCreate calls converge on one identity instead of last-write-wins`() {
        val fileIo = FakeDeviceIdentityFileIo()
        val store = DefaultDeviceIdentityStore(fileIo)

        val deviceIds = runBlocking {
            (1..20)
                .map { async(Dispatchers.Default) { store.loadOrCreate().deviceId } }
                .awaitAll()
        }

        assertEquals("all callers should see the same persisted identity", 1, deviceIds.distinct().size)
        assertEquals("identity should be generated exactly once", 1, fileIo.writeCount.get())
    }

    /** Serves whatever bytes it is given (or throws [readFailure]) until a successful write, which
     *  is what actually repairs the file on a real device. */
    private class StubDeviceIdentityFileIo(
        private var stored: ByteArray?,
        private var readFailure: Exception? = null,
    ) : DeviceIdentityFileIo {
        override fun readBytes(): ByteArray? {
            readFailure?.let { throw it }
            return stored
        }

        override fun writeBytes(bytes: ByteArray) {
            stored = bytes
            readFailure = null
        }
    }

    /**
     * Every way the stored identity can be unusable must self-heal into a fresh one. Before this,
     * each of these threw out of `loadOrCreate()` -> `GatewayConnection.runHandshake` -> Android's
     * default handler: a crash on every connect attempt, escapable only by clearing app data (which
     * destroys the paired identity regardless).
     */
    @Test
    fun `an unreadable stored identity is regenerated rather than thrown`() {
        val cases = mapOf(
            // What a process kill between EncryptedFile's delete and its write used to leave behind.
            "truncated json" to StubDeviceIdentityFileIo("{\"deviceId\":".toByteArray()),
            "not json at all" to StubDeviceIdentityFileIo(byteArrayOf(0x00, 0x01, 0x02, 0x03)),
            // Well-formed JSON, but a key field isn't base64url — Base64.decode throws IllegalArgumentException.
            "unparseable key material" to StubDeviceIdentityFileIo(
                ("{\"version\":1,\"deviceId\":\"abc\",\"publicKeyB64Url\":\"!! not base64 !!\"," +
                    "\"privateKeyB64Url\":\"?????\",\"createdAtMs\":1}").toByteArray(),
            ),
            // The user cleared credential storage, or the OEM Keystore misbehaved.
            "keystore refuses to open the file" to StubDeviceIdentityFileIo(
                stored = null,
                readFailure = GeneralSecurityException("keyset not found"),
            ),
            "disk read error" to StubDeviceIdentityFileIo(stored = null, readFailure = IOException("EIO")),
        )

        for ((label, fileIo) in cases) {
            val store = DefaultDeviceIdentityStore(fileIo)
            val identity = runBlocking { store.loadOrCreate() }

            assertEquals("$label: deviceId is the hex SHA-256 of the public key", 64, identity.deviceId.length)
            assertEquals("$label: the regenerated identity must be persisted and re-read, not re-rolled",
                identity, runBlocking { store.loadOrCreate() })
        }
    }
}
