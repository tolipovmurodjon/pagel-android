package org.pagel.ai.data.compass

import org.bouncycastle.crypto.generators.SCrypt
import java.nio.CharBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Compass's seal (Boss 2026-10-28: "my goals are sacred, no AI ever will read them"). Every goal, answer and report
 * is sealed on the phone with AES-256-GCM under one data key, a fresh 12-byte nonce per seal, and the item's own id as
 * associated data (a blob moved to another id does not open). The envelope is base64 of [1][nonce 12][ciphertext+tag
 * 16]; pagel.uz checks only that shape (compass-api.ts `isSealed`) and never sees a word.
 *
 * The data key itself is kept wrapped by the Keystore (DeviceKeys) and, only if Boss sets one, by his recovery
 * passphrase through scrypt (N 2^15, r 8, p 1; BouncyCastle, already a dependency): [1][log2 N][salt 16][nonce 12][key+tag].
 */
object CompassCrypto {
    const val VERSION: Byte = 1
    private const val NONCE = 12
    private const val TAG_BYTES = 16
    private const val SALT = 16
    /** scrypt cost: 2^15 × 8 × 128 bytes = 32 MB, about a third of a second on the A05s, once per recovery. */
    const val SCRYPT_LOG_N = 15
    private const val RECOVERY_AAD = "pagel-compass-recovery"
    private val random = SecureRandom()

    fun newDataKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    /** Seals [plain] under [key] for the item [id]. */
    fun seal(key: ByteArray, id: String, plain: ByteArray): String {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        return b64(byteArrayOf(VERSION) + nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce, id.toByteArray(Charsets.UTF_8), plain))
    }

    /** The plain bytes, or null when the blob is not ours, was changed, belongs to another id or another key. */
    fun open(key: ByteArray, id: String, blob: String): ByteArray? = runCatching {
        val raw = unb64(blob)
        require(raw.size >= 1 + NONCE + TAG_BYTES && raw[0] == VERSION)
        gcm(Cipher.DECRYPT_MODE, key, raw.copyOfRange(1, 1 + NONCE), id.toByteArray(Charsets.UTF_8), raw.copyOfRange(1 + NONCE, raw.size))
    }.getOrNull()

    /** The data key sealed by Boss's own passphrase, for a new phone (optional, default off). */
    fun sealWithPassphrase(dataKey: ByteArray, passphrase: CharArray, logN: Int = SCRYPT_LOG_N): String {
        val salt = ByteArray(SALT).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val k = derive(passphrase, salt, logN)
        try {
            return b64(byteArrayOf(VERSION, logN.toByte()) + salt + nonce + gcm(Cipher.ENCRYPT_MODE, k, nonce, RECOVERY_AAD.toByteArray(), dataKey))
        } finally {
            k.fill(0)
        }
    }

    /** The data key, or null for a wrong passphrase or a blob that is not a recovery key. */
    fun openWithPassphrase(blob: String, passphrase: CharArray): ByteArray? = runCatching {
        val raw = unb64(blob)
        require(raw.size >= 2 + SALT + NONCE + TAG_BYTES && raw[0] == VERSION)
        val logN = raw[1].toInt()
        require(logN in 10..20)
        val k = derive(passphrase, raw.copyOfRange(2, 2 + SALT), logN)
        try {
            gcm(Cipher.DECRYPT_MODE, k, raw.copyOfRange(2 + SALT, 2 + SALT + NONCE), RECOVERY_AAD.toByteArray(), raw.copyOfRange(2 + SALT + NONCE, raw.size))
        } finally {
            k.fill(0)
        }
    }.getOrNull()

    private fun derive(passphrase: CharArray, salt: ByteArray, logN: Int): ByteArray {
        val buf = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        try {
            return SCrypt.generate(bytes, salt, 1 shl logN, 8, 1, 32)
        } finally {
            bytes.fill(0)
        }
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        c.updateAAD(aad)
        return c.doFinal(input)
    }

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
