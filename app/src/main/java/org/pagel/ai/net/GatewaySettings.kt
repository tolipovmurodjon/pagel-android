package org.pagel.ai.net

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

private val Context.gatewaySettingsDataStore by preferencesDataStore(name = "pagel_gateway_settings")

/**
 * The gateway server URL, editable from `GatewayDebugScreen` so a different host (e.g. the TLS
 * alternative `wss://100.64.0.1:8443`) can be tried without a Gradle rebuild. Own DataStore file,
 * deliberately distinct from both [org.pagel.ai.data.sync.SyncSettings]'s `"pagel_sync"` and
 * [DataStoreGatewayDeviceTokenStore]'s `"pagel_gateway"` — three unrelated concerns, three files,
 * no key collisions possible.
 *
 * **Read once, at [GatewayManager] construction — not live.** `GatewayModule.provideGatewayConnectionConfig`
 * reads [currentUrl] synchronously when Hilt first builds the [GatewayManager] singleton (normally
 * when `GatewayViewModel` is first created), and [GatewayConnectionConfig.serverUrl] is baked into
 * both connections at that point — it is never re-read afterward. Saving a new URL here later does
 * not retarget an already-built [GatewayManager]; the debug screen says so next to the field.
 */
@Singleton
class GatewaySettings @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val urlKey = stringPreferencesKey("server_url")

    /** Boss's phone is `100.64.0.2`, the server is `100.64.0.1` on the Headscale tailnet — plan
     *  Task 8 Step 3. TLS alternative: `wss://100.64.0.1:8443`. */
    val defaultUrl: String = "ws://100.64.0.1:18789"

    suspend fun currentUrl(): String =
        ctx.gatewaySettingsDataStore.data.first()[urlKey]?.let(::gatewayUrlOrNull) ?: defaultUrl

    /**
     * Persists [url] only if OkHttp can actually open it, and reports which happened.
     *
     * A rejected URL is *not* stored, which is the whole point: `Request.Builder().url(...)` throws
     * on a scheme-less string like `100.64.0.1:18789`, and a stored bad value would be re-read into
     * [GatewayConnectionConfig] at every cold start — a crash Boss could only escape by clearing
     * app data. `GatewayConnection.connect()` guards the same case independently, in case a bad
     * value predates this check.
     */
    suspend fun saveUrl(url: String): Boolean {
        val valid = gatewayUrlOrNull(url) ?: return false
        ctx.gatewaySettingsDataStore.edit { it[urlKey] = valid }
        return true
    }
}

/**
 * The trimmed [raw] if it is a URL OkHttp will accept for a WebSocket, else `null`. `ws`/`wss` are
 * mapped to `http`/`https` first because that is exactly what `Request.Builder.url(String)` does
 * before parsing, so this accepts precisely the set that won't throw there.
 */
fun gatewayUrlOrNull(raw: String): String? {
    val trimmed = raw.trim()
    val asHttp = when {
        trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
        trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
        else -> trimmed
    }
    return trimmed.takeIf { asHttp.toHttpUrlOrNull() != null }
}
