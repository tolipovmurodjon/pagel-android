package org.pagel.ai.data.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDataStore by preferencesDataStore(name = "pagel_sync")

/** Stores the brain ingest endpoint + bearer the user pastes once. */
@Singleton
class SyncSettings @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val urlKey = stringPreferencesKey("endpoint_url")
    private val bearerKey = stringPreferencesKey("bearer")

    suspend fun current(): Pair<String, String> {
        val p = ctx.syncDataStore.data.first()
        return (p[urlKey] ?: "") to (p[bearerKey] ?: "")
    }

    suspend fun save(url: String, bearer: String) {
        ctx.syncDataStore.edit {
            it[urlKey] = url.trim()
            it[bearerKey] = bearer.trim()
        }
    }
}
