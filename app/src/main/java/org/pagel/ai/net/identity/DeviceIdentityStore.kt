package org.pagel.ai.net.identity

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Loads this device's Ed25519 identity, generating and persisting one on first use. */
interface DeviceIdentityStore {
    suspend fun loadOrCreate(): DeviceIdentity
}

@Serializable
private data class DeviceIdentityRecord(
    val version: Int = 1,
    val deviceId: String,
    val publicKeyB64Url: String,
    val privateKeyB64Url: String,
    val createdAtMs: Long,
)

/**
 * Persists the device identity encrypted at rest via [EncryptedFile] at `filesDir/pagel-device-identity.json`.
 * `loadOrCreate()` returns the existing identity if present, else generates and persists once.
 */
@Singleton
class EncryptedFileDeviceIdentityStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : DeviceIdentityStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(ctx.filesDir, FILE_NAME)

    override suspend fun loadOrCreate(): DeviceIdentity = withContext(Dispatchers.IO) {
        read() ?: Ed25519.generate().also { write(it) }
    }

    private fun masterKey(): MasterKey =
        MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private fun encryptedFile(): EncryptedFile =
        EncryptedFile.Builder(ctx, file, masterKey(), EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
            .build()

    private fun read(): DeviceIdentity? {
        if (!file.exists()) return null
        val bytes = encryptedFile().openFileInput().use { it.readBytes() }
        val record = json.decodeFromString<DeviceIdentityRecord>(String(bytes, Charsets.UTF_8))
        return DeviceIdentity(
            deviceId = record.deviceId,
            publicKeyRaw = Base64.getUrlDecoder().decode(record.publicKeyB64Url),
            privateKeyRaw = Base64.getUrlDecoder().decode(record.privateKeyB64Url),
        )
    }

    private fun write(identity: DeviceIdentity) {
        // EncryptedFile refuses to write over a file that already exists.
        if (file.exists()) file.delete()
        val record = DeviceIdentityRecord(
            deviceId = identity.deviceId,
            publicKeyB64Url = identity.publicKeyB64Url(),
            privateKeyB64Url = base64UrlNoPad(identity.privateKeyRaw),
            createdAtMs = System.currentTimeMillis(),
        )
        encryptedFile().openFileOutput().use { it.write(json.encodeToString(record).toByteArray(Charsets.UTF_8)) }
    }

    private companion object {
        const val FILE_NAME = "pagel-device-identity.json"
    }
}
