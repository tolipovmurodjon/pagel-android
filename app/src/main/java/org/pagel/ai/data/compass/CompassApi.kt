package org.pagel.ai.data.compass

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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

/**
 * What pagel.uz keeps of one item: never a word, only the sealed blob (dashboard compass-api.ts). [base] is the version
 * this phone last saw: pagel.uz refuses (409) a write over a version it did not see, so no other device's copy is
 * overwritten unseen (compass checker: integrity).
 */
@Serializable
data class SealedItemBody(val kind: String, val parent: String?, val blob: String, val starts: String?, val ends: String?, val deleted: Boolean, val base: Int)

@Serializable
data class PulledItem(val id: String, val kind: String, val parent: String? = null, val blob: String, val starts: String? = null, val ends: String? = null, val version: Int = 1, val deleted: Boolean = false)

@Serializable
data class PulledLink(val project: String, val goal: String? = null)

@Serializable
data class CompassPull(val items: List<PulledItem> = emptyList(), val links: List<PulledLink> = emptyList(), val recovery: String? = null)

@Serializable
private data class LinkBody(val goal: String?)

@Serializable
private data class RecoveryBody(val blob: String?)

@Serializable
private data class Version(val version: Int = 0)

@Serializable
private data class ErrorBody(val error: String = "")

/** The Compass's calls, with the phone's own token (pagel.uz refuses the machine token here). Off Main, no exceptions out. */
class CompassApi(private val http: OkHttpClient, private val io: CoroutineDispatcher) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    suspend fun pull(base: String, token: String): Outcome<CompassPull> = call {
        http.newCall(authed(base, "/api/v1/compass", token).get().build()).execute().use { r -> read(r) { json.decodeFromString(CompassPull.serializer(), it) } }
    }

    /** One item, whole: a retry is the same write. Returns pagel.uz's version. */
    suspend fun putItem(base: String, token: String, id: String, body: SealedItemBody): Outcome<Int> = call {
        val b = json.encodeToString(SealedItemBody.serializer(), body).toRequestBody(JSON)
        http.newCall(authed(base, "/api/v1/compass/items/$id", token).put(b).build()).execute().use { r ->
            read(r) { json.decodeFromString(Version.serializer(), it).version }
        }
    }

    suspend fun putLink(base: String, token: String, project: String, goal: String?): Outcome<Unit> = call {
        val b = json.encodeToString(LinkBody.serializer(), LinkBody(goal)).toRequestBody(JSON)
        http.newCall(authed(base, "/api/v1/compass/links/$project", token).put(b).build()).execute().use { r -> read(r) { } }
    }

    suspend fun putRecovery(base: String, token: String, blob: String?): Outcome<Unit> = call {
        val b = json.encodeToString(RecoveryBody.serializer(), RecoveryBody(blob)).toRequestBody(JSON)
        http.newCall(authed(base, "/api/v1/compass/recovery", token).put(b).build()).execute().use { r -> read(r) { } }
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

    private fun <T> read(r: Response, parse: (String) -> T): Outcome<T> {
        if (r.isSuccessful) return Outcome.Ok(parse(r.body?.string().orEmpty()))
        val message = runCatching { json.decodeFromString(ErrorBody.serializer(), r.body?.string().orEmpty()).error }.getOrDefault("")
        return Outcome.Failed(
            when {
                r.code == 401 -> DataError.Unauthorized
                r.code in 400..499 -> DataError.Refused(r.code, message.ifEmpty { "pagel.uz refused (${r.code})." })
                else -> DataError.Server(r.code)
            },
        )
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
