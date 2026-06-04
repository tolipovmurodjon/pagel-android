package org.pagel.ai.voice

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.voiceDataStore by preferencesDataStore(name = "pagel_voice_prefs")

/** Remembers which voice Boss chose, so the picker shows it after an app restart. */
@Singleton
class VoicePrefs @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val voiceIdKey = stringPreferencesKey("selected_voice_id")

    suspend fun selectedId(): String? = ctx.voiceDataStore.data.first()[voiceIdKey]

    suspend fun setSelectedId(id: String) = ctx.voiceDataStore.edit { it[voiceIdKey] = id }.let {}
}
