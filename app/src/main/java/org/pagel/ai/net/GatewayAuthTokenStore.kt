package org.pagel.ai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the gateway's shared `gateway.auth.mode = "token"` secret — a single, long-lived value
 * with full gateway access, the same for every client that presents it. This is a *different* gate
 * from [org.pagel.ai.net.ws.GatewayDeviceTokenStore]'s per-`(deviceId, role)` pairing token: the
 * gateway resolves the signed device-auth slot as `deviceToken ?? bootstrapToken ?? null` and never
 * looks at this value for that purpose (see [org.pagel.ai.net.ws.ConnectAuth]'s doc) — it only ever
 * travels as `auth.token` on the wire, read fresh at every CONNECT tap.
 */
interface GatewayAuthTokenStore {
    suspend fun get(): String?

    /** A blank [token] clears any stored value rather than persisting empty bytes. */
    suspend fun put(token: String)
}

/**
 * Byte-level persistence for the token, kept behind an interface so [DefaultGatewayAuthTokenStore]'s
 * trim/blank-clears/self-heal logic is unit-testable with a fake — the real implementation
 * ([EncryptedFileGatewayAuthTokenFileIo]) needs an Android Keystore, unavailable in a plain JVM unit
 * test. Mirrors [org.pagel.ai.net.identity.DeviceIdentityFileIo]'s split for the same reason.
 */
interface GatewayAuthTokenFileIo {
    fun readBytes(): ByteArray?
    fun writeBytes(bytes: ByteArray)
    fun delete()
}

/**
 * Treats this like the private key, not like [GatewaySettings]'s plain-DataStore-backed URL: a full-
 * access shared secret, encrypted at rest through the injected [GatewayAuthTokenFileIo].
 *
 * **Self-healing.** Same reasoning as [org.pagel.ai.net.identity.DefaultDeviceIdentityStore.read]:
 * an unreadable file (corrupt bytes, a Keystore that refuses to open) is treated as "no token
 * configured" rather than thrown — the alternative is a debug screen that can never load again
 * short of clearing app data, for a value Boss can just retype.
 */
@Singleton
class DefaultGatewayAuthTokenStore @Inject constructor(
    private val fileIo: GatewayAuthTokenFileIo,
) : GatewayAuthTokenStore {

    override suspend fun get(): String? = withContext(Dispatchers.IO) {
        runCatching { fileIo.readBytes() }.getOrNull()
            ?.toString(Charsets.UTF_8)
            ?.trim()
            ?.ifEmpty { null }
    }

    override suspend fun put(token: String) {
        withContext(Dispatchers.IO) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) fileIo.delete() else fileIo.writeBytes(trimmed.toByteArray(Charsets.UTF_8))
        }
    }
}
