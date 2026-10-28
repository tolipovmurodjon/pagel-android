package org.pagel.ai.data

import org.pagel.ai.data.compass.CompassCrypto
import org.pagel.ai.data.compass.DeviceKeys
import org.pagel.ai.data.compass.KeyGone
import org.pagel.ai.data.compass.NeedsUnlock

/**
 * The Keystore for tests (Robolectric has none): plain AES keys in memory with the same rules. [unlocked] stands for
 * "within 60 s of the fingerprint or PIN"; [gone] for a reset phone whose guard key is lost.
 */
class CompassTestKeys(var screenLock: Boolean = true) : DeviceKeys {
    var unlocked = true
    var gone = false
    private val guard = CompassCrypto.newDataKey()
    private val line = CompassCrypto.newDataKey()

    override fun hasScreenLock() = screenLock
    override fun prepare() {}

    override fun wrap(dataKey: ByteArray): String {
        check()
        return CompassCrypto.seal(guard, "guard", dataKey)
    }

    override fun unwrap(wrapped: String): ByteArray {
        check()
        return CompassCrypto.open(guard, "guard", wrapped) ?: throw KeyGone()
    }

    override fun sealLine(plain: ByteArray) = CompassCrypto.seal(line, "line", plain)
    override fun openLine(blob: String) = CompassCrypto.open(line, "line", blob)

    private fun check() {
        if (gone) throw KeyGone()
        if (!unlocked) throw NeedsUnlock()
    }
}
