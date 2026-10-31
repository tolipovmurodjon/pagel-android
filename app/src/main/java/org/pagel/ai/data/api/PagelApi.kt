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

    /** The AI's split: pagel.uz gives it 90 s; Cloudflare closes at 100. */
    private val slowHttp = http.newBuilder().readTimeout(100, TimeUnit.SECONDS).build()

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

    suspend fun inbox(baseUrl: String, token: String, limit: Int, seed: Long, olderThan: Int? = null): Outcome<InboxResponse> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/inbox?limit=$limit&seed=$seed" + (olderThan?.let { "&older_than=$it" } ?: ""))), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(InboxResponse.serializer(), it) } }
    }

    suspend fun sort(baseUrl: String, token: String, body: SortBody): Outcome<SortReply> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/inbox")).post(json.encodeToString(SortBody.serializer(), body).toRequestBody(JSON)), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(SortReply.serializer(), it) } }
    }

    /** One capture into the Inbox; pagel.uz answers the file it made (`inbox/<slug>.md`). */
    suspend fun capture(baseUrl: String, token: String, body: CaptureBody): Outcome<CaptureReply> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/inbox")).post(json.encodeToString(CaptureBody.serializer(), body).toRequestBody(JSON)), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(CaptureReply.serializer(), it) } }
    }

    /** Finished tasks newest first, a page at a time (`before` = the last done_at of the page before). */
    suspend fun completed(baseUrl: String, token: String, limit: Int, before: String? = null): Outcome<CompletedResponse> = call {
        val q = before?.let { "&before=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/completed?limit=$limit$q")), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(CompletedResponse.serializer(), it) } }
    }

    suspend fun habits(baseUrl: String, token: String, days: Int): Outcome<HabitsResponse> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/habits?days=$days")), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(HabitsResponse.serializer(), it) } }
    }

    /** Round D item 2: one project's quick notes (`?project=`), or the projects that have notes. */
    suspend fun notes(baseUrl: String, token: String, project: String): Outcome<QuickNotesReply> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/notes?project=" + java.net.URLEncoder.encode(project, "UTF-8"))), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(QuickNotesReply.serializer(), it) } }
    }

    suspend fun noteProjects(baseUrl: String, token: String): Outcome<NoteProjectsReply> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/notes")), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(NoteProjectsReply.serializer(), it) } }
    }

    /** The action goes first on the wire (`{"action":"triage",…}`), as the web posts it. */
    suspend fun triage(baseUrl: String, token: String, files: List<String>): Outcome<TriageReply> = call {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("action", kotlinx.serialization.json.JsonPrimitive("triage"))
            put("files", kotlinx.serialization.json.JsonArray(files.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/inbox")).post(body.toString().toRequestBody(JSON)), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(TriageReply.serializer(), it) } }
    }

    /** The rituals' state (Phase 5): the ritual day, closed tonight, the last review, the stuck projects. */
    suspend fun rituals(baseUrl: String, token: String): Outcome<RitualsResponse> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/rituals")), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(RitualsResponse.serializer(), it) } }
    }

    suspend fun ritual(baseUrl: String, token: String, body: RitualBody): Outcome<RitualReply> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/rituals")).post(json.encodeToString(RitualBody.serializer(), body).toRequestBody(JSON)), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(RitualReply.serializer(), it) } }
    }

    /** The AI splits a dump on pagel.uz (10-30 s, up to 90 s), so this call waits longer than the others. */
    suspend fun sweep(baseUrl: String, token: String, body: SweepBody): Outcome<SweepReply> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/sweep")).post(json.encodeToString(SweepBody.serializer(), body).toRequestBody(JSON)), token).build()
        slowHttp.newCall(req).execute().use { r -> read(r) { json.decodeFromString(SweepReply.serializer(), it) } }
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

    /** One project change or use count from the outbox (core round): [method] + [path] + a JSON [body]; the reply as JSON. */
    suspend fun send(baseUrl: String, token: String, method: String, path: String, body: String): Outcome<kotlinx.serialization.json.JsonObject> = call {
        val req = authed(Request.Builder().url(url(baseUrl, path)).method(method, body.toRequestBody(JSON)), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.parseToJsonElement(it.ifEmpty { "{}" }) as kotlinx.serialization.json.JsonObject } }
    }

    /** `POST /api/v1/tiny`: 3-6 funny small steps for a task, in its own language (a proposal; PATCH {micro} saves it). */
    suspend fun tiny(baseUrl: String, token: String, body: TinyBody): Outcome<TinyReply> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/tiny")).post(json.encodeToString(TinyBody.serializer(), body).toRequestBody(JSON)), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(TinyReply.serializer(), it) } }
    }

    /** `GET /api/v1/kpi/week`: the last 7 days of real use, counts only. */
    suspend fun kpiWeek(baseUrl: String, token: String): Outcome<KpiWeekReply> = call {
        val req = authed(Request.Builder().url(url(baseUrl, "/api/v1/kpi/week")), token).build()
        http.newCall(req).execute().use { r -> read(r) { json.decodeFromString(KpiWeekReply.serializer(), it) } }
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
