package org.pagel.ai.data.spend

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.pagel.ai.domain.SpendEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private val Context.spendDataStore by preferencesDataStore(name = "pagel_spend")

/** Today's parsed bank-notification amounts, on-device only (synced as aggregates to the brain). */
@Singleton
class SpendStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val key = stringPreferencesKey("events")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun add(event: SpendEvent) {
        ctx.spendDataStore.edit { p ->
            val kept = (decode(p[key]) + event).filter { isToday(it.ts) }.takeLast(60)
            p[key] = json.encodeToString(kept)
        }
    }

    suspend fun today(): List<SpendEvent> =
        decode(ctx.spendDataStore.data.first()[key]).filter { isToday(it.ts) }

    private fun decode(s: String?): List<SpendEvent> =
        if (s.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<SpendEvent>>(s) }.getOrDefault(emptyList())

    private fun isToday(ts: Long): Boolean {
        val z = ZoneId.systemDefault()
        return Instant.ofEpochMilli(ts).atZone(z).toLocalDate() == LocalDate.now(z)
    }
}
