package org.pagel.ai.data

import org.pagel.ai.data.compass.CompassCrypto
import org.pagel.ai.data.compass.DeviceKeys
import org.pagel.ai.data.compass.KeyGone
import org.pagel.ai.data.compass.NeedsUnlock
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Keystore for tests (Robolectric has none): plain AES keys in memory with the same rules. [unlocked] stands for
 * "the Compass's own prompt authorised this cipher"; [gone] for a reset phone whose guard key is lost.
 */
class CompassTestKeys(var screenLock: Boolean = true) : DeviceKeys {
    var unlocked = true
    var gone = false
    private var guard = CompassCrypto.newDataKey()
    private val line = CompassCrypto.newDataKey()

    override fun hasScreenLock() = screenLock

    override fun cipherFor(wrapped: String?, fresh: Boolean): Cipher {
        if (fresh) { guard = CompassCrypto.newDataKey(); gone = false }
        if (gone) throw KeyGone()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (wrapped == null) c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(guard, "AES"))
        else c.init(Cipher.DECRYPT_MODE, SecretKeySpec(guard, "AES"), GCMParameterSpec(128, CompassCrypto.unb64(wrapped), 1, 12))
        return c
    }

    override fun wrap(cipher: Cipher, dataKey: ByteArray): String {
        if (!unlocked) throw NeedsUnlock()
        return CompassCrypto.b64(byteArrayOf(CompassCrypto.VERSION) + cipher.iv + cipher.doFinal(dataKey))
    }

    override fun unwrap(cipher: Cipher, wrapped: String): ByteArray {
        if (!unlocked) throw NeedsUnlock()
        val raw = CompassCrypto.unb64(wrapped)
        return cipher.doFinal(raw, 13, raw.size - 13)
    }

    override fun sealLine(plain: ByteArray) = CompassCrypto.seal(line, "line", plain)
    override fun openLine(blob: String) = CompassCrypto.open(line, "line", blob)
}
