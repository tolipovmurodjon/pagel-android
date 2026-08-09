package org.pagel.ai.data.sync

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.pagel.ai.data.spend.SpendStore
import org.pagel.ai.domain.SpendEvent
import org.pagel.ai.domain.UsageSnapshot
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes a privacy-filtered daily usage summary to Boss's own Oracle brain. Sends aggregates +
 * app list (label/pkg/time/opens/category) only — no raw events, no notification text. HttpURLConnection
 * (no networking dependency). Bearer-auth over HTTPS.
 */
@Singleton
class BrainSync @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SyncSettings,
    private val spendStore: SpendStore,
) {
    suspend fun push(snap: UsageSnapshot, day: String): Result<Unit> = withContext(Dispatchers.IO) {
        val (url, bearer) = settings.current()
        if (url.isBlank() || bearer.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Sync not configured"))
        }
        val endpoint = url.trimEnd('/') + "/v1/phone/usage"
        val spend = spendStore.today()
        runCatching {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $bearer")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(buildJson(snap, day, spend).toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) error("HTTP $code")
        }
    }

    private fun buildJson(snap: UsageSnapshot, day: String, spend: List<SpendEvent>): String {
        val apps = JSONArray()
        snap.apps.take(40).forEach { a ->
            apps.put(
                JSONObject()
                    .put("label", a.label)
                    .put("pkg", a.pkg)
                    .put("totalMs", a.totalMs)
                    .put("opens", a.opens)
                    .put("category", a.category.name),
            )
        }
        val spendArr = JSONArray()
        spend.forEach { s ->
            // No "raw" — that's the first 140 chars of the literal bank notification text
            // (SpendParser.kt). Sending it would contradict the "no notification text" promise above
            // and SettingsScreen's "only the amount syncs".
            spendArr.put(JSONObject().put("ts", s.ts).put("amount", s.amount))
        }
        return JSONObject()
            .put("day", day)
            .put("focusScore", snap.focusScore)
            .put("activeMs", snap.activeMs)
            .put("productiveMs", snap.productiveMs)
            .put("distractingMs", snap.distractingMs)
            .put("distinctApps", snap.distinctApps)
            .put("totalOpens", snap.totalOpens)
            .put("apps", apps)
            .put("spend", spendArr)
            .put("spendTotal", spend.sumOf { it.amount })
            .toString()
    }
}
