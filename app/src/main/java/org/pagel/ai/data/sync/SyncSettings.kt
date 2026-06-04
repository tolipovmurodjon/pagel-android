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

    /** Stable default brain base — serves both /v1/phone/usage and /v1/voice/token. */
    val defaultUrl: String = "https://humopedia.com/pagel-voice"

    suspend fun current(): Pair<String, String> {
        val p = ctx.syncDataStore.data.first()
        val stored = p[urlKey]?.trim().orEmpty()
        // Heal stale endpoints: blank, or an old ephemeral cloudflare quick-tunnel → use the stable base.
        val url = if (stored.isBlank() || stored.contains("trycloudflare")) defaultUrl else stored
        return url to (p[bearerKey] ?: "")
    }

    suspend fun save(url: String, bearer: String) {
        ctx.syncDataStore.edit {
            it[urlKey] = url.trim()
            it[bearerKey] = bearer.trim()
        }
    }
}
