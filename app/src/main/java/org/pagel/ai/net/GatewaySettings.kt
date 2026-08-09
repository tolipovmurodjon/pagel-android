package org.pagel.ai.net

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
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
        ctx.gatewaySettingsDataStore.data.first()[urlKey]?.trim()?.takeIf { it.isNotBlank() } ?: defaultUrl

    suspend fun saveUrl(url: String) {
        ctx.gatewaySettingsDataStore.edit { it[urlKey] = url.trim() }
    }
}
