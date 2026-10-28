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
 * key that works only within [UNLOCK_SECONDS] of Boss's fingerprint or screen-lock PIN. Robolectric has no Keystore, so
 * tests use a fake with plain AES keys (CompassTestKeys).
 */
interface DeviceKeys {
    /** A PIN, pattern or password is set: without one the Keystore cannot guard the key. */
    fun hasScreenLock(): Boolean

    /** Makes the guarding key if it is missing (no unlock needed to make it). Off Main. */
    fun prepare()

    /** Wraps the data key; throws [NeedsUnlock] outside the unlock window, [KeyGone] if the guard is gone. Off Main. */
    fun wrap(dataKey: ByteArray): String

    /** The data key back; the same throws as [wrap]. Off Main. */
    fun unwrap(wrapped: String): ByteArray

    /** Today's quiet line has its own Keystore key with no unlock (it shows on Today); it never leaves the phone. */
    fun sealLine(plain: ByteArray): String
    fun openLine(blob: String): ByteArray?

    companion object {
        const val UNLOCK_SECONDS = 60
    }
}

class KeystoreDeviceKeys(private val ctx: Context) : DeviceKeys {
    private val store: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    override fun hasScreenLock(): Boolean = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override fun prepare() {
        if (store.getKey(GUARD, null) != null) return
        val spec = KeyGenParameterSpec.Builder(GUARD, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(DeviceKeys.UNLOCK_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            // A new fingerprint must not make his goals unreadable; the screen lock still guards the key.
            .setInvalidatedByBiometricEnrollment(false)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    override fun wrap(dataKey: ByteArray): String {
        val c = Cipher.getInstance(GCM)
        guarded { c.init(Cipher.ENCRYPT_MODE, guard()) }
        return CompassCrypto.b64(byteArrayOf(CompassCrypto.VERSION) + c.iv + c.doFinal(dataKey))
    }

    override fun unwrap(wrapped: String): ByteArray {
        val raw = CompassCrypto.unb64(wrapped)
        val c = Cipher.getInstance(GCM)
        guarded { c.init(Cipher.DECRYPT_MODE, guard(), GCMParameterSpec(128, raw, 1, 12)) }
        return c.doFinal(raw, 13, raw.size - 13)
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

    private fun guard(): SecretKey = store.getKey(GUARD, null) as? SecretKey ?: throw KeyGone()

    private fun lineKey(): SecretKey = store.getKey(LINE, null) as? SecretKey ?: run {
        val spec = KeyGenParameterSpec.Builder(LINE, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private inline fun guarded(init: () -> Unit) {
        try {
            init()
        } catch (_: UserNotAuthenticatedException) {
            throw NeedsUnlock()
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw KeyGone()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val GCM = "AES/GCM/NoPadding"
        const val GUARD = "pagel_compass_guard"
        const val LINE = "pagel_compass_line"
    }
}
