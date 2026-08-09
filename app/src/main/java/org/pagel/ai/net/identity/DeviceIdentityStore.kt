package org.pagel.ai.net.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Loads this device's Ed25519 identity, generating and persisting one on first use. */
interface DeviceIdentityStore {
    suspend fun loadOrCreate(): DeviceIdentity
}

/**
 * Byte-level persistence for the identity record, kept behind an interface so
 * [DefaultDeviceIdentityStore]'s locking/idempotency logic is unit-testable with a fake — the real
 * implementation ([EncryptedFileDeviceIdentityFileIo]) needs an Android Keystore, unavailable in a
 * plain JVM unit test.
 */
interface DeviceIdentityFileIo {
    fun readBytes(): ByteArray?
    fun writeBytes(bytes: ByteArray)
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
 * JSON-encodes the device identity through the injected [DeviceIdentityFileIo].
 * `loadOrCreate()` returns the existing identity if present, else generates and persists once.
 *
 * Guarded by [mutex] so concurrent callers (e.g. the operator and node connections starting at
 * once against this same singleton — see Task 6) converge on one generated identity instead of
 * a last-write-wins race: the loser of the race re-checks [read] *inside* the lock and returns
 * the winner's persisted identity rather than generating its own.
 *
 * **Self-healing.** An unreadable stored identity is treated exactly like no stored identity: see
 * [read]. Nothing else in the app can recover from it — the only user-visible alternative is
 * "clear app data", which destroys the paired identity anyway *and* every other local table.
 */
@Singleton
class DefaultDeviceIdentityStore @Inject constructor(
    private val fileIo: DeviceIdentityFileIo,
) : DeviceIdentityStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    override suspend fun loadOrCreate(): DeviceIdentity = mutex.withLock {
        withContext(Dispatchers.IO) {
            read() ?: Ed25519.generate().also { write(it) }
        }
    }

    /**
     * `null` for *every* way the stored identity can be unusable, not just "absent": a truncated
     * or non-JSON file (`SerializationException` — a process kill mid-write used to be able to
     * leave one behind), a field that isn't base64url (`IllegalArgumentException`), or the
     * [EncryptedFile] refusing to open at all (`GeneralSecurityException`/`IOException` — the user
     * cleared credential storage, or the OEM Keystore misbehaved).
     *
     * All of those used to propagate out of [loadOrCreate], out of `GatewayConnection.runHandshake`,
     * and into Android's default handler — an unrecoverable crash on *every* connect attempt, with
     * "clear app data" as the only user-side fix. Returning `null` makes `loadOrCreate` regenerate
     * and re-persist instead. The cost is one re-pairing (`openclaw devices approve`); the
     * alternative is an app that can never start again.
     */
    private fun read(): DeviceIdentity? = runCatching {
        val bytes = fileIo.readBytes() ?: return@runCatching null
        val record = json.decodeFromString<DeviceIdentityRecord>(String(bytes, Charsets.UTF_8))
        DeviceIdentity(
            deviceId = record.deviceId,
            publicKeyRaw = Base64.getUrlDecoder().decode(record.publicKeyB64Url),
            privateKeyRaw = Base64.getUrlDecoder().decode(record.privateKeyB64Url),
        )
    }.getOrNull()

    private fun write(identity: DeviceIdentity) {
        val record = DeviceIdentityRecord(
            deviceId = identity.deviceId,
            publicKeyB64Url = identity.publicKeyB64Url(),
            privateKeyB64Url = base64UrlNoPad(identity.privateKeyRaw),
            createdAtMs = System.currentTimeMillis(),
        )
        fileIo.writeBytes(json.encodeToString(record).toByteArray(Charsets.UTF_8))
    }
}
