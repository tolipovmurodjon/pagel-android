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

/**
 * Mints a short-lived ElevenLabs conversation token from Boss's brain (`GET /v1/voice/token`,
 * bearer-auth). The ElevenLabs API key never touches the phone — the server holds it and returns
 * only a 10-minute token. Reuses the same brain URL + bearer the user pasted for usage sync.
 */
@Singleton
class VoiceTokenClient @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SyncSettings,
) {
    suspend fun fetch(): Result<String> = withContext(Dispatchers.IO) {
        val (url, bearer) = settings.current()
        if (url.isBlank() || bearer.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Set the brain URL + bearer in Settings first"))
        }
        val endpoint = url.trimEnd('/') + "/v1/voice/token"
        runCatching {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $bearer")
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) error("token HTTP $code")
            val token = JSONObject(text).optString("token")
            if (token.isBlank()) error("no token in response")
            token
        }
    }
}
