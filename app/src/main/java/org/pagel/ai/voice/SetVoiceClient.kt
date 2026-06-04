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

/** Tells the brain to switch PAGEL's voice (it PATCHes the ElevenLabs agent server-side). */
@Singleton
class SetVoiceClient @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SyncSettings,
) {
    suspend fun set(voiceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val (url, bearer) = settings.current()
        if (url.isBlank() || bearer.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Set the brain URL + bearer first"))
        }
        val endpoint = url.trimEnd('/') + "/v1/voice/set-voice"
        runCatching {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $bearer")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(JSONObject().put("voiceId", voiceId).toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) error("HTTP $code")
        }
    }
}
