package org.pagel.ai.data.compass

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The 60 s after Boss's fingerprint or PIN have passed: ask again. */
class NeedsUnlock : Exception("compass: unlock again")

/** The Keystore key that guards the data key is gone (a reset phone, a removed screen lock): the frame says so plainly. */
class KeyGone : Exception("compass: the key is gone")

/**
 * The phone's own locks for the Compass. The data key never leaves the phone in the clear: it is wrapped by a Keystore
 * key that works for ONE operation after the Compass's own unlock prompt (timeout 0 + a BiometricPrompt CryptoObject;
 * compass checker #9: a 60 s time-bound key also opened after the plain lock-screen unlock, so the prompt was UX, not
 * crypto). Robolectric has no Keystore, so tests use a fake with plain AES keys (CompassTestKeys).
 */
interface DeviceKeys {
    /** A PIN, pattern or password is set: without one the Keystore cannot guard the key. */
    fun hasScreenLock(): Boolean

    /**
     * The cipher the unlock prompt authorises. [wrapped] null: ENCRYPT, to wrap a new data key ([fresh] first replaces
     * a guard key that is gone, on a phone that lost it); else DECRYPT with the wrapped blob's IV. Throws [KeyGone].
     * Off Main.
     */
    fun cipherFor(wrapped: String?, fresh: Boolean = false): Cipher

    /** Wraps the data key with a cipher the prompt authorised; [NeedsUnlock] if it was not. Off Main. */
    fun wrap(cipher: Cipher, dataKey: ByteArray): String

    /** The data key back, same rules as [wrap]. Off Main. */
    fun unwrap(cipher: Cipher, wrapped: String): ByteArray

    /** Today's quiet line has its own Keystore key with no unlock (it shows on Today); it never leaves the phone. */
    fun sealLine(plain: ByteArray): String
    fun openLine(blob: String): ByteArray?
}

/**
 * [guardAlias]: the Compass's guard key by default; the locked journal (Phase 6b) passes its own, so one unlock never opens
 * the other's data key.
 */
class KeystoreDeviceKeys(private val ctx: Context, private val guardAlias: String = GUARD) : DeviceKeys {
    private val store: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    override fun hasScreenLock(): Boolean = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override fun cipherFor(wrapped: String?, fresh: Boolean): Cipher {
        if (fresh) runCatching { store.deleteEntry(guardAlias) }
        val c = Cipher.getInstance(GCM)
        try {
            if (wrapped == null) {
                c.init(Cipher.ENCRYPT_MODE, guard(create = true))
            } else {
                val raw = CompassCrypto.unb64(wrapped)
                c.init(Cipher.DECRYPT_MODE, guard(create = false), GCMParameterSpec(128, raw, 1, 12))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw KeyGone()
        }
        return c
    }

    override fun wrap(cipher: Cipher, dataKey: ByteArray): String =
        CompassCrypto.b64(byteArrayOf(CompassCrypto.VERSION) + cipher.iv + finish { cipher.doFinal(dataKey) })

    override fun unwrap(cipher: Cipher, wrapped: String): ByteArray {
        val raw = CompassCrypto.unb64(wrapped)
        return finish { cipher.doFinal(raw, 13, raw.size - 13) }
    }

    override fun sealLine(plain: ByteArray): String {
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.ENCRYPT_MODE, lineKey())
        return CompassCrypto.b64(byteArrayOf(CompassCrypto.VERSION) + c.iv + c.doFinal(plain))
    }

    override fun openLine(blob: String): ByteArray? = runCatching {
        val raw = CompassCrypto.unb64(blob)
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.DECRYPT_MODE, store.getKey(LINE, null) as SecretKey, GCMParameterSpec(128, raw, 1, 12))
        c.doFinal(raw, 13, raw.size - 13)
    }.getOrNull()

    private fun guard(create: Boolean): SecretKey {
        (store.getKey(guardAlias, null) as? SecretKey)?.let { return it }
        if (!create) throw KeyGone()
        val spec = KeyGenParameterSpec.Builder(guardAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // 0 = every use needs its own prompt with this cipher (CryptoObject); fingerprint or the screen-lock PIN.
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            // A new fingerprint must not make his goals unreadable; the screen lock still guards the key.
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun lineKey(): SecretKey = store.getKey(LINE, null) as? SecretKey ?: run {
        val spec = KeyGenParameterSpec.Builder(LINE, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    /** A cipher used without its prompt fails here; it never yields a key. */
    private inline fun finish(op: () -> ByteArray): ByteArray = try {
        op()
    } catch (_: UserNotAuthenticatedException) {
        throw NeedsUnlock()
    } catch (_: javax.crypto.IllegalBlockSizeException) {
        throw NeedsUnlock()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val GCM = "AES/GCM/NoPadding"
        /** v2: per-use keys (the 60 s v1 key never reached a phone: 0.3.29 was never built). */
        const val GUARD = "pagel_compass_guard_v2"
        const val LINE = "pagel_compass_line"
    }
}
