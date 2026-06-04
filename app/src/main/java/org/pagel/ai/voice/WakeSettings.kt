package org.pagel.ai.voice

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.wakeDataStore by preferencesDataStore(name = "pagel_wake")

/** Hands-free ("Hey PAGEL") preferences: armed at all, and whether to only listen on charger. */
@Singleton
class WakeSettings @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val armedKey = booleanPreferencesKey("hands_free_armed")
    private val chargerOnlyKey = booleanPreferencesKey("only_when_charging")

    val armed: Flow<Boolean> = ctx.wakeDataStore.data.map { it[armedKey] ?: false }
    val chargerOnly: Flow<Boolean> = ctx.wakeDataStore.data.map { it[chargerOnlyKey] ?: true }

    suspend fun chargerOnlyNow(): Boolean = ctx.wakeDataStore.data.first()[chargerOnlyKey] ?: true

    suspend fun setArmed(v: Boolean) = ctx.wakeDataStore.edit { it[armedKey] = v }.let {}
    suspend fun setChargerOnly(v: Boolean) = ctx.wakeDataStore.edit { it[chargerOnlyKey] = v }.let {}
}
