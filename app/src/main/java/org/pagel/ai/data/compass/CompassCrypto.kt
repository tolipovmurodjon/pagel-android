package org.pagel.ai.data.compass

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.CharBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Compass's seal (Boss 2026-10-28: "my goals are sacred, no AI ever will read them"). Every goal, answer and report
 * is sealed on the phone with AES-256-GCM under one data key, a fresh 12-byte nonce per seal, and the item's own id as
 * associated data together with its kind, parent and dates ([aad]): a blob moved to another item or level does not open. The envelope is base64 of [1][nonce 12][ciphertext+tag
 * 16]; pagel.uz checks only that shape (compass-api.ts `isSealed`) and never sees a word.
 *
 * The data key itself is kept wrapped by the Keystore (DeviceKeys) and, only if Boss sets one, by his recovery
 * passphrase through Argon2id (64 MB, 3 passes; BouncyCastle, already a dependency): [1][2][log2 KB][passes][salt 16][nonce 12][key+tag].
 */
object CompassCrypto {
    const val VERSION: Byte = 1
    private const val NONCE = 12
    private const val TAG_BYTES = 16
    private const val SALT = 16
    /** Argon2id cost: 2^16 KB = 64 MB of memory, 3 passes; about a second on the A05s, once per recovery. */
    const val ARGON_MEMORY_LOG2_KB = 16
    const val ARGON_PASSES = 3
    private const val KDF_ARGON2ID: Byte = 2
    private const val RECOVERY_AAD = "pagel-compass-recovery"
    private val random = SecureRandom()

    fun newDataKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    /** Seals [plain] under [key], bound to [boundTo] (an item's [aad]). */
    fun seal(key: ByteArray, boundTo: String, plain: ByteArray): String {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        return b64(byteArrayOf(VERSION) + nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce, boundTo.toByteArray(Charsets.UTF_8), plain))
    }

    /** The plain bytes, or null when the blob is not ours, was changed, is bound to something else, or another key's. */
    fun open(key: ByteArray, boundTo: String, blob: String): ByteArray? = runCatching {
        val raw = unb64(blob)
        require(raw.size >= 1 + NONCE + TAG_BYTES && raw[0] == VERSION)
        gcm(Cipher.DECRYPT_MODE, key, raw.copyOfRange(1, 1 + NONCE), boundTo.toByteArray(Charsets.UTF_8), raw.copyOfRange(1 + NONCE, raw.size))
    }.getOrNull()

    /**
     * What a blob is bound to (the AEAD's associated data): the item's id AND its kind, parent and dates, so pagel.uz
     * cannot move a sealed goal to another level, parent or season unseen: it then fails to open (compass checker #10).
     */
    fun aad(id: String, kind: String, parent: String?, starts: String?, ends: String?): String =
        listOf(id, kind, parent.orEmpty(), starts.orEmpty(), ends.orEmpty()).joinToString("\n")

    /**
     * The data key sealed by Boss's own passphrase, for a new phone (optional, default off): Argon2id, 64 MB, 3 passes
     * (compass checker #9: the blob sits on a shared server, so offline guessing must be expensive). The cost is stored
     * in the blob, so a later change still opens old ones.
     */
    fun sealWithPassphrase(dataKey: ByteArray, passphrase: CharArray, memoryLog2Kb: Int = ARGON_MEMORY_LOG2_KB, passes: Int = ARGON_PASSES): String {
        val salt = ByteArray(SALT).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val k = derive(passphrase, salt, memoryLog2Kb, passes)
        try {
            val head = byteArrayOf(VERSION, KDF_ARGON2ID, memoryLog2Kb.toByte(), passes.toByte())
            return b64(head + salt + nonce + gcm(Cipher.ENCRYPT_MODE, k, nonce, RECOVERY_AAD.toByteArray(), dataKey))
        } finally {
            k.fill(0)
        }
    }

    /** The data key, or null for a wrong passphrase or a blob that is not a recovery key. */
    fun openWithPassphrase(blob: String, passphrase: CharArray): ByteArray? = runCatching {
        val raw = unb64(blob)
        require(raw.size >= 4 + SALT + NONCE + TAG_BYTES && raw[0] == VERSION && raw[1] == KDF_ARGON2ID)
        val mem = raw[2].toInt()
        val passes = raw[3].toInt()
        require(mem in 10..20 && passes in 1..10)
        val k = derive(passphrase, raw.copyOfRange(4, 4 + SALT), mem, passes)
        try {
            gcm(Cipher.DECRYPT_MODE, k, raw.copyOfRange(4 + SALT, 4 + SALT + NONCE), RECOVERY_AAD.toByteArray(), raw.copyOfRange(4 + SALT + NONCE, raw.size))
        } finally {
            k.fill(0)
        }
    }.getOrNull()

    private fun derive(passphrase: CharArray, salt: ByteArray, memoryLog2Kb: Int, passes: Int): ByteArray {
        val buf = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(passes)
                .withMemoryPowOfTwo(memoryLog2Kb)
                .withParallelism(1)
                .withSalt(salt)
                .build()
            val out = ByteArray(32)
            Argon2BytesGenerator().apply { init(params) }.generateBytes(bytes, out)
            return out
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
