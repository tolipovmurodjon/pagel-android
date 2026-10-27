package org.pagel.ai.net

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * No Robolectric in this project, so these run the real `datastore-preferences` implementation on
 * the plain JVM via `PreferenceDataStoreFactory.create` against a temp file — the standard
 * Context-free way to test a DataStore — rather than mocking `DataStore` itself.
 */
class DataStoreGatewayDeviceTokenStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): DataStoreGatewayDeviceTokenStore {
        val file = File(tmp.newFolder(), "gateway-token-test.preferences_pb")
        val dataStore: androidx.datastore.core.DataStore<Preferences> =
            PreferenceDataStoreFactory.create { file }
        return DataStoreGatewayDeviceTokenStore(dataStore)
    }

    @Test
    fun `get returns null before anything is put`() = runTest {
        val store = newStore()
        assertNull(store.get("device-1", "operator"))
    }

    @Test
    fun `put then get round-trips the token`() = runTest {
        val store = newStore()
        store.put("device-1", "operator", "tok-abc")
        assertEquals("tok-abc", store.get("device-1", "operator"))
    }

    @Test
    fun `two roles under the same deviceId do not overwrite each other`() = runTest {
        val store = newStore()
        store.put("device-1", "operator", "tok-operator")
        store.put("device-1", "node", "tok-node")

        assertEquals("tok-operator", store.get("device-1", "operator"))
        assertEquals("tok-node", store.get("device-1", "node"))
    }

    @Test
    fun `a later put for one role does not clobber the other role's token`() = runTest {
        val store = newStore()
        store.put("device-1", "operator", "tok-operator-v1")
        store.put("device-1", "node", "tok-node")
        store.put("device-1", "operator", "tok-operator-v2")

        assertEquals("tok-operator-v2", store.get("device-1", "operator"))
        assertEquals("tok-node", store.get("device-1", "node"))
    }

    @Test
    fun `different deviceIds with the same role do not collide either`() = runTest {
        val store = newStore()
        store.put("device-1", "node", "tok-device-1")
        store.put("device-2", "node", "tok-device-2")

        assertEquals("tok-device-1", store.get("device-1", "node"))
        assertEquals("tok-device-2", store.get("device-2", "node"))
    }
}
