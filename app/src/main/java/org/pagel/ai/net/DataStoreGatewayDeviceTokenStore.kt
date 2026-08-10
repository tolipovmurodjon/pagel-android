package org.pagel.ai.net

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.pagel.ai.net.ws.GatewayDeviceTokenStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists `hello-ok.auth.deviceToken` per `(deviceId, role)` — see [GatewayDeviceTokenStore]'s
 * doc. Keyed `"$deviceId/$role"` so the operator and node connections' tokens, which share one
 * device identity's `deviceId` (see [GatewayManager]'s doc), land at different preference keys
 * and never overwrite each other — the exact property `DataStoreGatewayDeviceTokenStoreTest`
 * exists to prove.
 *
 * Takes an injected [DataStore] rather than a `Context` directly so this class stays
 * unit-testable on the plain JVM: this project has no Robolectric, and the mockable-Android jar
 * (`unitTests.isReturnDefaultValues`) returns default values for real `Context` methods, so a
 * `Context`-coupled DataStore can't be exercised in a plain `@Test`. [GatewayModule] supplies the
 * real, `Context`-backed instance in production; a test supplies one built with
 * `PreferenceDataStoreFactory.create` against a temp file.
 */
@Singleton
class DataStoreGatewayDeviceTokenStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : GatewayDeviceTokenStore {

    override suspend fun get(deviceId: String, role: String): String? =
        dataStore.data.first()[keyFor(deviceId, role)]

    override suspend fun put(deviceId: String, role: String, token: String) {
        dataStore.edit { it[keyFor(deviceId, role)] = token }
    }

    private fun keyFor(deviceId: String, role: String) = stringPreferencesKey("$deviceId/$role")
}
