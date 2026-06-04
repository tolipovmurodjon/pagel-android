package org.pagel.ai.voice

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.pagel.ai.data.sync.SyncSettings
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class Doing(val label: String, val active: Boolean)

/** Polls the brain for what PAGEL is doing right now (the live "DOING" HUD during a task). */
@Singleton
class VoiceStatusClient @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SyncSettings,
) {
    suspend fun poll(): Doing? = withContext(Dispatchers.IO) {
        val (url, bearer) = settings.current()
        if (url.isBlank() || bearer.isBlank()) return@withContext null
        runCatching {
            val conn = (URL(url.trimEnd('/') + "/v1/voice/status").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Authorization", "Bearer $bearer")
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return@runCatching null
            val o = JSONObject(text)
            Doing(o.optString("label"), o.optBoolean("active"))
        }.getOrNull()
    }
}
