package org.pagel.ai.crash

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Sends saved crashes, newest first, to `POST <base>/api/v1/app/crash` with the phone's own device token.
 * A file is deleted once the server has it (2xx) or can never take it (400/413/422); anything else (no network,
 * 401, 5xx) keeps it for the next try. Blocking: call it from a worker, never from Main.
 */
class CrashUploader(private val http: OkHttpClient) {
    data class Outcome(val sent: Int, val kept: Int)

    fun upload(dir: File, baseUrl: String, token: String): Outcome {
        val files = CrashLog.pending(dir)
        var sent = 0
        for ((i, f) in files.withIndex()) {
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/api/v1/app/crash")
                .header("Authorization", "Bearer $token")
                .header("X-Pagel-Actor", "pagel-android")
                .post(f.readText().toRequestBody(JSON))
                .build()
            val code = try {
                http.newCall(req).execute().use { it.code }
            } catch (_: IOException) {
                return Outcome(sent, files.size - i)
            }
            when {
                code in 200..299 -> { f.delete(); sent++ }
                code == 400 || code == 413 || code == 422 -> f.delete()
                else -> return Outcome(sent, files.size - i)
            }
        }
        return Outcome(sent, 0)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
