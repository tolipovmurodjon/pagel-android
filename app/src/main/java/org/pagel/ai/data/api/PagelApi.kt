package org.pagel.ai.data.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * pagel.uz's app API over OkHttp: pair once, then sync deltas and listen to the live stream with the phone's own
 * token. Every call runs on [io] and turns failures into [DataError]; callers never see an exception.
 */
class PagelApi(private val http: OkHttpClient, private val io: CoroutineDispatcher) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Streams stay open for minutes; the server pings every 20 s, so 60 s of silence means the line is dead. */
    private val streamHttp = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun pair(baseUrl: String, code: String, name: String): Outcome<PairResponse> = call {
        val body = json.encodeToString(PairRequest.serializer(), PairRequest(code, name)).toRequestBody(JSON)
        http.newCall(Request.Builder().url(url(baseUrl, "/api/v1/devices/pair")).post(body).build()).execute().use { r ->
            read(r) { json.decodeFromString(PairResponse.serializer(), it) }
        }
    }

    suspend fun sync(baseUrl: String, token: String, since: Long): Outcome<SyncResponse> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/sync?since=$since")), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(SyncResponse.serializer(), it) } }
    }

    suspend fun today(baseUrl: String, token: String): Outcome<TodayResponse> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/today")), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(TodayResponse.serializer(), it) } }
    }

    suspend fun setDone(baseUrl: String, token: String, id: String, done: Boolean, star: Boolean? = null): Outcome<TaskStatusDto> = call {
        val body = json.encodeToString(DonePatch.serializer(), DonePatch(done, star)).toRequestBody(JSON)
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/tasks/$id")).patch(body), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(TaskStatusDto.serializer(), it) } }
    }

    /** Any panel edit: [body] holds only the fields that change, in the server's wire names. */
    suspend fun patchTask(baseUrl: String, token: String, id: String, body: kotlinx.serialization.json.JsonObject): Outcome<PatchReply> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/tasks/$id")).patch(body.toString().toRequestBody(JSON)), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(PatchReply.serializer(), it) } }
    }

    suspend fun addTask(baseUrl: String, token: String, task: NewTaskBody): Outcome<CreatedTaskDto> = call {
        val body = json.encodeToString(NewTaskBody.serializer(), task).toRequestBody(JSON)
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/tasks")).post(body), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(CreatedTaskDto.serializer(), it) } }
    }

    suspend fun inbox(baseUrl: String, token: String, limit: Int, seed: Long): Outcome<InboxResponse> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/inbox?limit=$limit&seed=$seed")), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(InboxResponse.serializer(), it) } }
    }

    suspend fun sort(baseUrl: String, token: String, body: SortBody): Outcome<SortReply> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/inbox")).post(json.encodeToString(SortBody.serializer(), body).toRequestBody(JSON)), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(SortReply.serializer(), it) } }
    }

    /** A save's own picture as bytes; Ok(null) when the post has none (404), so the phone stops asking for it. */
    suspend fun picture(baseUrl: String, token: String, file: String): Outcome<ByteArray?> = call {
        val q = java.net.URLEncoder.encode(file, "UTF-8")
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/inbox/picture?file=$q")), token).build()
        http.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> Outcome.Ok(null)
                r.isSuccessful -> Outcome.Ok(r.body?.bytes())
                else -> Outcome.Failed(errorOf(r))
            }
        }
    }

    /**
     * Opens `GET /api/v1/stream?since=` and calls [onEvent] for every event until the stream ends or the coroutine is
     * cancelled. Returns why it ended; a normal close is [DataError.Network], so the caller reconnects.
     */
    suspend fun stream(baseUrl: String, token: String, since: Long, onEvent: suspend (SseEvent) -> Unit): DataError = withContext(io) {
        val req = try {
            authed(Request.Builder().url(url(baseUrl, "/api/v1/stream?since=$since")), token).header("Accept", "text/event-stream").build()
        } catch (_: IllegalArgumentException) {
            return@withContext DataError.Refused(0, "That server address is not a web address.")
        }
        val call = streamHttp.newCall(req)
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) return@withContext errorOf(r)
                val source = r.body?.source() ?: return@withContext DataError.Network
                val parser = SseParser()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    parser.feed(line)?.let { onEvent(it) }
                }
                DataError.Network
            }
        } catch (_: IOException) {
            DataError.Network
        } finally {
            call.cancel()
        }
    }

    private suspend fun <T> call(block: () -> Outcome<T>): Outcome<T> = withContext(io) {
        try {
            block()
        } catch (_: IOException) {
            Outcome.Failed(DataError.Network)
        } catch (_: kotlinx.serialization.SerializationException) {
            Outcome.Failed(DataError.Server(200))
        } catch (_: IllegalArgumentException) {
            Outcome.Failed(DataError.Refused(0, "That server address is not a web address."))
        }
    }

    private fun <T> read(r: Response, parse: (String) -> T): Outcome<T> =
        if (r.isSuccessful) Outcome.Ok(parse(r.body?.string().orEmpty())) else Outcome.Failed(errorOf(r))

    private fun errorOf(r: Response): DataError {
        val message = runCatching { json.decodeFromString(ErrorResponse.serializer(), r.body?.string().orEmpty()).error }.getOrDefault("")
        return when {
            r.code == 401 && message.isEmpty() -> DataError.Unauthorized
            r.code == 401 && message.startsWith("Send Authorization") -> DataError.Unauthorized
            r.code in 400..499 -> DataError.Refused(r.code, message.ifEmpty { "pagel.uz refused (${r.code})." })
            else -> DataError.Server(r.code)
        }
    }

    private fun authed(b: Request.Builder, token: String) =
        b.header("Authorization", "Bearer $token").header("X-Pagel-Actor", ACTOR)

    private fun url(base: String, path: String) = base.trimEnd('/') + path

    companion object {
        const val ACTOR = "pagel-android"
        private val JSON = "application/json".toMediaType()
    }
}
