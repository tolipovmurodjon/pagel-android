package org.pagel.ai.net.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
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
}
