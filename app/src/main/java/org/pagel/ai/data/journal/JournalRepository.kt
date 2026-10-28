package org.pagel.ai.data.journal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.compass.CompassCrypto
import org.pagel.ai.data.compass.CompassKey
import org.pagel.ai.data.compass.DeviceKeys
import org.pagel.ai.data.compass.KeyGone
import org.pagel.ai.data.compass.NeedsUnlock
import org.pagel.ai.data.compass.VaultState
import org.pagel.ai.data.today.OutboxScheduler
import org.pagel.ai.data.today.SendResult
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher

/** What one entry holds, sealed whole: its day, Boss's words, an optional mood emoji. pagel.uz never sees any of it. */
@Serializable
data class EntryBody(val day: String, val text: String, val mood: String? = null, val createdAt: Long = 0, val editedAt: Long = 0)

data class JournalEntry(val id: String, val body: EntryBody)

/** The unlocked journal: entries newest day first, and how many blobs this key cannot open. */
data class OpenJournal(val entries: List<JournalEntry> = emptyList(), val unreadable: Int = 0)

/** What pagel.uz keeps of one entry (journal-api.ts): the blob, deleted, the version this phone saw. Nothing else. */
@Serializable
data class SealedEntryBody(val blob: String, val deleted: Boolean, val base: Int)

@Serializable
private data class Version(val version: Int = 0)

@Serializable
private data class ErrorBody(val error: String = "")

/** The journal's one call, with the phone's own token (pagel.uz refuses the machine token here). Off Main. */
class JournalApi(private val http: OkHttpClient, private val io: CoroutineDispatcher) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun putEntry(base: String, token: String, id: String, body: SealedEntryBody): Outcome<Int> = withContext(io) {
        try {
            val req = Request.Builder().url(base.trimEnd('/') + "/api/v1/journal/entries/$id")
                .header("Authorization", "Bearer $token").header("X-Pagel-Actor", PagelApi.ACTOR)
                .put(json.encodeToString(SealedEntryBody.serializer(), body).toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    Outcome.Ok(json.decodeFromString(Version.serializer(), text).version)
                } else {
                    val msg = runCatching { json.decodeFromString(ErrorBody.serializer(), text).error }.getOrDefault("")
                    Outcome.Failed(
                        when {
                            r.code == 401 -> DataError.Unauthorized
                            r.code in 400..499 -> DataError.Refused(r.code, msg.ifEmpty { "pagel.uz refused (${r.code})." })
                            else -> DataError.Server(r.code)
                        },
                    )
                }
            }
        } catch (_: IOException) {
            Outcome.Failed(DataError.Network)
        } catch (_: kotlinx.serialization.SerializationException) {
            Outcome.Failed(DataError.Server(200))
        } catch (_: IllegalArgumentException) {
            Outcome.Failed(DataError.Refused(0, "That server address is not a web address."))
        }
    }
}

/**
 * The locked journal (Phase 6b, Boss 2026-10-28: "closed, nobody reads it, the most safe place for my inner things; I
 * don't want AI to be trained on or know my feelings"). Built exactly like the Compass: its own data key, wrapped by its
 * OWN Keystore key that opens only through the journal's unlock prompt; every entry sealed with AES-256-GCM (its id as
 * associated data) before Room (journal.db) and before the outbox; words in memory only while open; wiped 60 s after
 * leaving. Never indexed, searched, synced to the vault, sent to any AI, shown in a notification, logged or put in a
 * crash report (CrashLog hides any crash through a `.journal.` class).
 *
 * Conflict rule: the phone is the only writer and its copy wins; pagel.uz is the sealed backup. A refused sealed write
 * stays on the phone (never rolled back or dropped).
 */
class JournalRepository(
    private val dao: JournalDao,
    private val api: JournalApi,
    private val keys: DeviceKeys,
    private val key: CompassKey,
    private val scheduler: OutboxScheduler,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()

    fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(TASHKENT).toLocalDate()

    val state: Flow<VaultState> = combine(dao.vault(), dao.count(), key.key) { v, n, k ->
        when {
            k != null -> VaultState.OPEN
            v?.wrapped != null -> VaultState.LOCKED
            n > 0 -> VaultState.LOST
            !keys.hasScreenLock() -> VaultState.NO_LOCK
            else -> VaultState.NEW
        }
    }.flowOn(io)

    val opened: Flow<OpenJournal> = combine(dao.entries(), key.key) { rows, k ->
        if (k == null) return@combine OpenJournal()
        var bad = 0
        val out = mutableListOf<JournalEntry>()
        for (row in rows) {
            val plain = CompassCrypto.open(k, aad(row.id), row.blob)
            if (plain == null) { bad++; continue }
            val text = plain.toString(Charsets.UTF_8)
            plain.fill(0)
            val body = runCatching { json.decodeFromString(EntryBody.serializer(), text) }.getOrNull()
            if (body == null) bad++ else out += JournalEntry(row.id, body)
        }
        OpenJournal(out.sortedWith(compareByDescending<JournalEntry> { it.body.day }.thenByDescending { it.body.createdAt }), bad)
    }.flowOn(Dispatchers.Default)

    // ── Unlock (the Compass's pattern, the journal's own keys) ──────────────────────────────────────────────────────

    suspend fun cipherForUnlock(): Cipher = withContext(io) {
        val v = vaultNow()
        val wrapped = v.wrapped ?: return@withContext keys.cipherFor(null, fresh = dao.count().first() > 0)
        try {
            keys.cipherFor(wrapped)
        } catch (e: KeyGone) {
            dao.upsertVault(v.copy(wrapped = null))
            throw e
        }
    }

    suspend fun create(cipher: Cipher) = withContext(io) {
        val dek = CompassCrypto.newDataKey()
        dao.upsertVault(vaultNow().copy(wrapped = keys.wrap(cipher, dek)))
        key.open(dek)
    }

    suspend fun unlock(cipher: Cipher) = withContext(io) {
        val wrapped = vaultNow().wrapped ?: throw KeyGone()
        key.open(keys.unwrap(cipher, wrapped))
    }

    /** A new journal on a phone whose key is gone: the old sealed entries stay unread and leave this phone. */
    suspend fun startOver(cipher: Cipher) = withContext(io) {
        for (row in dao.entries().first()) dao.upsert(row.copy(deleted = true, syncStatus = JournalEntryEntity.SYNCED))
        create(cipher)
    }

    fun lock() = key.lock()
    fun leave() = key.leave()
    fun stay() = key.stay()

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Seals and saves one entry (new when [id] is empty); returns its id. */
    suspend fun save(id: String, day: String, text: String, mood: String?): String = withContext(io) {
        val k = key.key.value ?: throw NeedsUnlock()
        val eid = id.ifEmpty { newId() }
        val old = dao.entry(eid)
        val now = clock()
        val created = if (old == null) now else openBody(k, old)?.createdAt ?: now
        val bytes = json.encodeToString(EntryBody.serializer(), EntryBody(day, text, mood, created, now)).toByteArray(Charsets.UTF_8)
        val blob = CompassCrypto.seal(k, aad(eid), bytes)
        bytes.fill(0)
        dao.upsert(JournalEntryEntity(eid, blob, sort = old?.sort ?: now, lastModified = now, serverVersion = old?.serverVersion ?: 0))
        queue(eid)
        eid
    }

    suspend fun delete(id: String) = withContext(io) {
        val old = dao.entry(id) ?: return@withContext
        dao.upsert(old.copy(deleted = true, syncStatus = JournalEntryEntity.PENDING, lastModified = clock()))
        queue(id)
    }

    private fun openBody(k: ByteArray, row: JournalEntryEntity): EntryBody? =
        CompassCrypto.open(k, aad(row.id), row.blob)?.let { p -> runCatching { json.decodeFromString(EntryBody.serializer(), p.toString(Charsets.UTF_8)) }.getOrNull().also { p.fill(0) } }

    private suspend fun queue(id: String) {
        dao.dropWaiting(id)
        dao.enqueue(JournalOutboxEntity(ref = id, createdAt = clock()))
        scheduler.kick()
    }

    fun nudge() = scheduler.kick()

    private suspend fun vaultNow() = dao.vaultOnce() ?: JournalVaultEntity()

    private fun newId(): String {
        val abc = "abcdefghijkmnpqrstuvwxyz23456789"
        return "j_" + (1..14).map { abc[random.nextInt(abc.length)] }.joinToString("")
    }

    /**
     * Sends the waiting sealed entries in order. pagel.uz without the journal yet (404/405) or a token it does not take
     * here (403): everything waits, nothing is lost. Any other refusal leaves the phone's copy as it is.
     */
    suspend fun sendAll(account: Account): SendResult = withContext(io) {
        for (op in dao.outbox()) {
            dao.attempt(op.id)
            val row = dao.entry(op.ref)
            val out: Outcome<Int> = if (row == null) Outcome.Ok(0) else {
                val r = api.putEntry(account.serverUrl, account.token, row.id, SealedEntryBody(row.blob, row.deleted, row.serverVersion))
                if (r is Outcome.Ok) { dao.setServerVersion(row.id, r.value); dao.markSynced(row.id, row.lastModified) }
                r
            }
            when (out) {
                is Outcome.Ok -> dao.sent(op.id)
                is Outcome.Failed -> when (val e = out.error) {
                    is DataError.Refused -> if (e.status in WAIT_FOR_SERVER) return@withContext SendResult.ALL_SENT else dao.sent(op.id)
                    DataError.Unauthorized -> return@withContext SendResult.SIGNED_OUT
                    else -> return@withContext SendResult.TRY_LATER
                }
            }
        }
        SendResult.ALL_SENT
    }

    companion object {
        val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")
        private val WAIT_FOR_SERVER = setOf(403, 404, 405)
        /** The guard key's alias: its own, never the Compass's (one unlock never opens the other). */
        const val GUARD = "pagel_journal_guard_v1"

        /** What a blob is bound to: the journal and the entry's id, so a blob moved to another entry or to the Compass fails to open. */
        fun aad(id: String) = "journal\n$id"

        /** The moods Boss can pick (optional; sealed with the entry). */
        val MOODS = listOf("😊", "🙂", "😐", "😔", "😢", "😠", "😴", "🙏")
    }
}
