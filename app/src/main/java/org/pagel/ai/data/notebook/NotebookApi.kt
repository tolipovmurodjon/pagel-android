package org.pagel.ai.data.notebook

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.api.PagelApi
import java.io.IOException
import java.net.URLEncoder

// pagel.uz's second-brain API (dashboard vault-api.ts, search.ts, recall-store.ts, quick-notes-api.ts, resource-api.ts).

@Serializable
data class FolderDto(val path: String, val allowed: Boolean = false, val set: Boolean = false, val locked: Boolean = false, val notes: Int = 0)

@Serializable
data class FoldersReply(val folders: List<FolderDto> = emptyList())

@Serializable
data class FolderSwitch(val path: String, val allowed: Boolean)

@Serializable
private data class FoldersBody(val folders: List<FolderSwitch>)

@Serializable
data class NoteCardDto(val path: String, val title: String, val hash: String = "", val version: Int = 1, @SerialName("updated_at") val updatedAt: String = "")

@Serializable
data class NotesReply(val notes: List<NoteCardDto> = emptyList())

@Serializable
data class NoteLinkDto(val target: String, val path: String? = null)

@Serializable
data class BacklinkDto(val path: String, val title: String = "")

@Serializable
data class NoteDto(
    val path: String,
    val title: String,
    val body: String,
    val hash: String = "",
    val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val links: List<NoteLinkDto> = emptyList(),
    val backlinks: List<BacklinkDto> = emptyList(),
)

@Serializable
data class FileDto(val path: String, val hash: String = "", val size: Long = 0)

@Serializable
data class ManifestReply(val files: List<FileDto> = emptyList())

/** A note written on the phone; [base] = the hash the phone last saw (absent for a new note: never sent as null). */
@Serializable
data class PutNoteBody(val path: String, val body: String, val base: String? = null)

@Serializable
data class PutNoteReply(val path: String, val hash: String = "", val version: Int = 1, val conflict: Boolean = false, val kept: String? = null, val unchanged: Boolean = false)

@Serializable
data class SearchHitDto(val kind: String, val id: String? = null, val path: String? = null, val title: String = "", val snippet: String = "", val project: String? = null, val updated: String? = null)

@Serializable
data class SearchReply(val results: List<SearchHitDto> = emptyList())

@Serializable
data class CardDto(
    val id: String,
    val question: String,
    val answer: String,
    val due: String,
    @SerialName("source_kind") val sourceKind: String = "",
    @SerialName("source_id") val sourceId: String = "",
)

@Serializable
data class DueReply(val due: Int = 0, val cards: List<CardDto> = emptyList())

@Serializable
private data class GradeBody(val grade: String)

@Serializable
data class QuickNoteDto(val text: String, val link: String? = null, val picture: String? = null, val day: String? = null)

@Serializable
data class QuickNotesReply(val project: String? = null, val notes: List<QuickNoteDto> = emptyList(), val projects: List<QuickProjectDto> = emptyList())

@Serializable
data class QuickProjectDto(val project: String, val count: Int = 0, val last: String? = null)

@Serializable
data class ResourceDto(val id: String, val title: String = "", val body: String = "", val url: String? = null, val topic: String? = null)

@Serializable
private data class ErrorBody(val error: String = "")

/** The second brain's calls, with the phone's own token. Off Main, no exceptions out. */
class NotebookApi(private val http: OkHttpClient, private val io: CoroutineDispatcher) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    suspend fun folders(base: String, token: String): Outcome<FoldersReply> = get(base, token, "/api/v1/vault/folders") { json.decodeFromString(FoldersReply.serializer(), it) }

    /** Only the phone flips a folder's switch (pagel.uz refuses the machine token here). The answer is the whole list. */
    suspend fun setFolders(base: String, token: String, switches: List<FolderSwitch>): Outcome<FoldersReply> =
        send(base, token, "/api/v1/vault/folders", "PUT", json.encodeToString(FoldersBody.serializer(), FoldersBody(switches))) { json.decodeFromString(FoldersReply.serializer(), it) }

    suspend fun notes(base: String, token: String): Outcome<NotesReply> = get(base, token, "/api/v1/vault/notes?limit=5000") { json.decodeFromString(NotesReply.serializer(), it) }

    suspend fun note(base: String, token: String, path: String): Outcome<NoteDto> = get(base, token, "/api/v1/vault/notes/" + segments(path)) { json.decodeFromString(NoteDto.serializer(), it) }

    suspend fun manifest(base: String, token: String): Outcome<ManifestReply> = get(base, token, "/api/v1/vault/manifest") { json.decodeFromString(ManifestReply.serializer(), it) }

    suspend fun putNote(base: String, token: String, body: PutNoteBody): Outcome<PutNoteReply> =
        send(base, token, "/api/v1/vault/notes", "PUT", json.encodeToString(PutNoteBody.serializer(), body)) { json.decodeFromString(PutNoteReply.serializer(), it) }

    /** One picture from an allowed folder (≤ 2 MB on pagel.uz). */
    suspend fun file(base: String, token: String, path: String): Outcome<ByteArray> = call {
        http.newCall(authed(base, "/api/v1/vault/files/" + segments(path), token).get().build()).execute().use { r ->
            if (r.isSuccessful) Outcome.Ok(r.body?.bytes() ?: ByteArray(0)) else Outcome.Failed(failure(r))
        }
    }

    suspend fun search(base: String, token: String, q: String, limit: Int = 30): Outcome<SearchReply> =
        get(base, token, "/api/v1/search?q=" + enc(q) + "&limit=$limit") { json.decodeFromString(SearchReply.serializer(), it) }

    suspend fun due(base: String, token: String, limit: Int = 20): Outcome<DueReply> = get(base, token, "/api/v1/recall/due?limit=$limit") { json.decodeFromString(DueReply.serializer(), it) }

    suspend fun grade(base: String, token: String, id: String, grade: String): Outcome<Unit> =
        send(base, token, "/api/v1/recall/" + enc(id) + "/grade", "POST", json.encodeToString(GradeBody.serializer(), GradeBody(grade))) { }

    /** Without [project]: the projects that have quick notes; with it: that project's notes, newest first. */
    suspend fun quickNotes(base: String, token: String, project: String? = null): Outcome<QuickNotesReply> =
        get(base, token, "/api/v1/notes" + (project?.let { "?project=" + enc(it) } ?: "")) { json.decodeFromString(QuickNotesReply.serializer(), it) }

    suspend fun resource(base: String, token: String, id: String): Outcome<ResourceDto> = get(base, token, "/api/v1/resources/" + enc(id)) { json.decodeFromString(ResourceDto.serializer(), it) }

    private suspend fun <T> get(base: String, token: String, path: String, parse: (String) -> T): Outcome<T> = call {
        http.newCall(authed(base, path, token).get().build()).execute().use { r -> read(r, parse) }
    }

    private suspend fun <T> send(base: String, token: String, path: String, method: String, body: String, parse: (String) -> T): Outcome<T> = call {
        http.newCall(authed(base, path, token).method(method, body.toRequestBody(JSON)).build()).execute().use { r -> read(r, parse) }
    }

    private fun authed(base: String, path: String, token: String) = Request.Builder().url(base.trimEnd('/') + path)
        .header("Authorization", "Bearer $token").header("X-Pagel-Actor", PagelApi.ACTOR)

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
        if (r.isSuccessful) Outcome.Ok(parse(r.body?.string().orEmpty())) else Outcome.Failed(failure(r))

    private fun failure(r: Response): DataError {
        val message = runCatching { json.decodeFromString(ErrorBody.serializer(), r.body?.string().orEmpty()).error }.getOrDefault("")
        return when {
            r.code == 401 -> DataError.Unauthorized
            r.code in 400..499 -> DataError.Refused(r.code, message.ifEmpty { "pagel.uz refused (${r.code})." })
            else -> DataError.Server(r.code)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** A vault path in a URL: each folder its own segment, each segment encoded once (pagel.uz decodes it once). */
        fun segments(path: String): String = path.split('/').joinToString("/") { enc(it) }
    }
}
