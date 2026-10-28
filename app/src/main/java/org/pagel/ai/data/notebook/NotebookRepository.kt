package org.pagel.ai.data.notebook

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.today.OutboxScheduler
import org.pagel.ai.data.today.SendResult
import java.time.Instant

/** A note as the reader draws it: its words (null until fetched once), where its links go, who links to it. */
data class OpenNote(
    val path: String,
    val title: String,
    val body: String?,
    val links: List<NoteLinkDto>,
    val backlinks: List<BacklinkDto>,
    val pending: Boolean,
    val refused: String?,
)

/**
 * The second brain on the phone (Phase 6b): Boss's allowed vault folders, the notes in them, the recall cards due, the
 * Inbox's quick notes, and one search over all of it plus tasks and steps. The UI reads Room only; every write goes to
 * Room first, then to the outbox (NotebookWorker).
 *
 * Conflict rules: a folder switch and a recall grade: the phone's tap is sent as is; a refused switch rolls back with a
 * notice. A note: pagel.uz keeps both copies when it has a newer one than the phone saw (its "(phone)" copy, vault-api.ts
 * conflictPath), so a note written here is never lost; a refused note keeps its words on the phone, marked, never dropped.
 */
class NotebookRepository(
    private val dao: NotebookDao,
    private val api: NotebookApi,
    private val tasks: PagelDao,
    private val scheduler: OutboxScheduler,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val links = ListSerializer(NoteLinkDto.serializer())
    private val backs = ListSerializer(BacklinkDto.serializer())
    private val said = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val pictures = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 24
    }

    /** One-off lines for a toast: a refused switch rolled back, a note kept as a second copy. */
    val notices: SharedFlow<String> = said.asSharedFlow()

    val folders: Flow<List<VaultFolderEntity>> = dao.folders()
    val notes: Flow<List<VaultNoteEntity>> = dao.notes()
    val waiting: Flow<Int> = dao.waiting()

    /** The cards still to review on this phone (a card graded here leaves the list at once). */
    val cards: Flow<List<RecallCard>> = dao.cards().map { rows ->
        rows.filter { it.gradedAt == null }.map { RecallCard(it.id, it.question, it.answer, it.due, it.sourceKind, it.sourceId) }
    }

    fun note(path: String): Flow<OpenNote?> = dao.note(path).map { r ->
        r?.let {
            OpenNote(
                it.path, it.title, it.body,
                runCatching { json.decodeFromString(links, it.links) }.getOrDefault(emptyList()),
                runCatching { json.decodeFromString(backs, it.backlinks) }.getOrDefault(emptyList()),
                it.syncStatus == PENDING, it.refused,
            )
        }
    }

    fun nowIso(): String = Instant.ofEpochMilli(clock()).toString()

    // ── Pull ────────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Folders, the notes list, the vault's pictures, the cards due and the quick notes, each on its own: one that fails
     * (offline, an older pagel.uz) leaves the others and the saved copy as they are. Never a spinner: the screen shows
     * Room while this runs.
     */
    suspend fun refresh(account: Account) = withContext(io) {
        (api.folders(account.serverUrl, account.token) as? Outcome.Ok)?.let { dao.replaceFolders(it.value.folders.map(::folderRow)) }
        refreshNotes(account)
        (api.manifest(account.serverUrl, account.token) as? Outcome.Ok)?.let { dao.replaceFiles(it.value.files.map { f -> f.path }) }
        (api.due(account.serverUrl, account.token) as? Outcome.Ok)?.let { r ->
            dao.replaceCards(r.value.cards.map { RecallCardEntity(it.id, it.question, it.answer, it.due, it.sourceKind, it.sourceId) })
        }
        refreshQuickNotes(account)
    }

    private suspend fun refreshNotes(account: Account) {
        val list = (api.notes(account.serverUrl, account.token) as? Outcome.Ok)?.value?.notes ?: return
        val old = dao.notesOnce().associateBy { it.path }
        val rows = list.mapNotNull { c ->
            val o = old[c.path]
            when {
                NoteTemplates.isLocked(c.path) -> null
                o?.syncStatus == PENDING -> null
                o != null && o.hash == c.hash -> o.copy(title = c.title, updatedAt = c.updatedAt, serverVersion = c.version)
                // pagel.uz has a newer copy: the words are fetched again when the note is opened.
                else -> VaultNoteEntity(c.path, c.title, null, c.hash, c.updatedAt, serverVersion = c.version)
            }
        }
        dao.upsertNotes(rows)
        val keep = list.mapTo(HashSet()) { it.path }
        dao.dropNotes(old.keys.filter { it !in keep })
    }

    /** Quick notes of the 12 projects with the newest ones; [QuickNoteEntity.n] counts from the oldest, as search.ts. */
    private suspend fun refreshQuickNotes(account: Account) {
        val projects = (api.quickNotes(account.serverUrl, account.token) as? Outcome.Ok)?.value?.projects ?: return
        for (p in projects.take(12)) {
            val notes = (api.quickNotes(account.serverUrl, account.token, p.project) as? Outcome.Ok)?.value?.notes ?: continue
            dao.replaceQuickNotes(p.project, notes.mapIndexed { k, n -> QuickNoteEntity(p.project, notes.size - k, n.text, n.day, n.link) })
        }
    }

    /** The note's words from pagel.uz (a note written here and not sent yet keeps the phone's words). */
    suspend fun open(account: Account, path: String) = withContext(io) {
        val mine = dao.noteOnce(path)
        if (mine?.syncStatus == PENDING) return@withContext
        when (val r = api.note(account.serverUrl, account.token, path)) {
            is Outcome.Ok -> dao.upsertNote(noteRow(r.value, mine))
            is Outcome.Failed -> if ((r.error as? DataError.Refused)?.status == 404 && mine != null) dao.dropNotes(listOf(path))
        }
    }

    private fun noteRow(n: NoteDto, mine: VaultNoteEntity?) = VaultNoteEntity(
        n.path, n.title, n.body, n.hash, n.updatedAt.ifEmpty { mine?.updatedAt.orEmpty() },
        json.encodeToString(links, n.links), json.encodeToString(backs, n.backlinks), SYNCED, clock(), n.version,
    )

    /** A vault picture (≤ 2 MB), kept in memory for the last 24 opened. Null offline or for a closed folder. */
    suspend fun picture(account: Account, path: String): ByteArray? = withContext(io) {
        synchronized(pictures) { pictures[path] }?.let { return@withContext it }
        val bytes = (api.file(account.serverUrl, account.token, path) as? Outcome.Ok)?.value ?: return@withContext null
        synchronized(pictures) { pictures[path] = bytes }
        bytes
    }

    suspend fun files(): List<String> = withContext(io) { dao.filesOnce() }

    suspend fun resource(account: Account, id: String): ResourceDto? = withContext(io) { (api.resource(account.serverUrl, account.token, id) as? Outcome.Ok)?.value }

    suspend fun quickNote(project: String, n: Int): QuickNoteEntity? = withContext(io) { dao.quickNote(project, n) }

    // ── Writes (Room first, then the outbox) ────────────────────────────────────────────────────────────────────────

    /**
     * Opens or closes a folder (only the phone can). Its subfolders that follow it switch with it on screen at once;
     * pagel.uz's answer then sets every folder. Kundalik never opens: false, and nothing is written.
     */
    suspend fun setAllowed(path: String, on: Boolean): Boolean = withContext(io) {
        val row = dao.folder(path) ?: return@withContext false
        if (row.locked || NoteTemplates.isLocked(path)) {
            said.tryEmit("Kundalik stays closed. It never leaves the phone as text.")
            return@withContext false
        }
        val now = clock()
        val all = dao.foldersOnce()
        val followers = all.filter { it.path.startsWith("$path/") && !it.switched && !it.locked }.map { it.copy(allowed = on, lastModified = now) }
        dao.upsertFolders(followers + row.copy(allowed = on, switched = true, syncStatus = PENDING, lastModified = now))
        dao.dropWaiting(NotebookOutboxEntity.FOLDER, path)
        dao.enqueue(NotebookOutboxEntity(what = NotebookOutboxEntity.FOLDER, ref = path, body = on.toString(), createdAt = now))
        scheduler.kick()
        true
    }

    /**
     * Saves a note's words on the phone (new or edited) and queues them for pagel.uz. Returns the path. A Kundalik path
     * is refused here, before anything is written.
     */
    suspend fun save(path: String, body: String): String = withContext(io) {
        require(!NoteTemplates.isLocked(path)) { "Kundalik is closed" }
        val now = clock()
        val old = dao.noteOnce(path)
        val title = Markdown.titleOf(path, body)
        dao.upsertNote(
            old?.copy(title = title, body = body, syncStatus = PENDING, lastModified = now, refused = null, updatedAt = Instant.ofEpochMilli(now).toString())
                ?: VaultNoteEntity(path, title, body, hash = "", updatedAt = Instant.ofEpochMilli(now).toString(), syncStatus = PENDING, lastModified = now),
        )
        dao.dropWaiting(NotebookOutboxEntity.NOTE, path)
        dao.enqueue(NotebookOutboxEntity(what = NotebookOutboxEntity.NOTE, ref = path, body = null, createdAt = now))
        scheduler.kick()
        path
    }

    /** One recall grade: the card leaves the review at once; the grade waits in the outbox until pagel.uz takes it. */
    suspend fun grade(cardId: String, grade: Grade) = withContext(io) {
        val now = clock()
        dao.markGraded(cardId, now)
        dao.dropWaiting(NotebookOutboxEntity.GRADE, cardId)
        dao.enqueue(NotebookOutboxEntity(what = NotebookOutboxEntity.GRADE, ref = cardId, body = grade.wire, createdAt = now))
        scheduler.kick()
    }

    fun nudge() = scheduler.kick()

    // ── Search ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The phone's own copy, searched at once (offline too): tasks, steps, notes it has, quick notes. */
    suspend fun searchHere(q: String): List<Hit> {
        val t = withContext(io) { tasks.searchableTasks() }
        val s = withContext(io) { tasks.allSteps() }
        val n = withContext(io) { dao.notesOnce() }
        val qn = withContext(io) { dao.quickNotesOnce() }
        return withContext(Dispatchers.Default) {
            SearchLogic.local(
                q,
                t.map { LocalTask(it.id, it.text, it.title, it.project, it.updatedAt) },
                s.map { LocalStep(it.taskId, it.position, it.text) },
                n.map { LocalNote(it.path, it.title, it.body?.let(Markdown::stripFrontMatter), it.updatedAt) },
                qn.map { LocalQuickNote(it.project, it.n, it.text, it.day) },
            )
        }
    }

    /** pagel.uz's answer (it also has Resources and notes the phone never opened); null offline. */
    suspend fun searchThere(account: Account, q: String): List<Hit>? = withContext(io) {
        val r = api.search(account.serverUrl, account.token, q) as? Outcome.Ok ?: return@withContext null
        r.value.results.mapNotNull { h ->
            val kind = HitKind.of(h.kind) ?: return@mapNotNull null
            val ref = (if (kind == HitKind.NOTE) h.path else h.id) ?: return@mapNotNull null
            if (kind == HitKind.NOTE && NoteTemplates.isLocked(ref)) return@mapNotNull null
            Hit(kind, ref, h.title, h.snippet, h.project, h.updated)
        }
    }

    // ── Send ────────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sends the waiting changes in order. pagel.uz down or offline: TRY_LATER, everything stays. An endpoint an older
     * pagel.uz does not have (404/405 on a write): it waits for the next pass, nothing is lost.
     */
    suspend fun sendAll(account: Account): SendResult = withContext(io) {
        var folderChanged = false
        for (op in dao.outbox()) {
            dao.attempt(op.id)
            val out: Outcome<*> = when (op.what) {
                NotebookOutboxEntity.FOLDER -> sendFolder(account, op).also { if (it is Outcome.Ok) folderChanged = true }
                NotebookOutboxEntity.NOTE -> sendNote(account, op)
                NotebookOutboxEntity.GRADE -> {
                    val r = api.grade(account.serverUrl, account.token, op.ref, op.body.orEmpty())
                    if (r is Outcome.Ok || (r as? Outcome.Failed)?.error.let { it is DataError.Refused && it.status == 404 }) dao.dropCard(op.ref)
                    r
                }
                else -> Outcome.Ok(Unit)
            }
            when (out) {
                is Outcome.Ok -> dao.sent(op.id)
                is Outcome.Failed -> when (val e = out.error) {
                    is DataError.Refused -> if (e.status == 405 || (e.status == 404 && op.what != NotebookOutboxEntity.GRADE)) return@withContext SendResult.ALL_SENT else {
                        refused(op, e.message)
                        dao.sent(op.id)
                    }
                    DataError.Unauthorized -> return@withContext SendResult.SIGNED_OUT
                    else -> return@withContext SendResult.TRY_LATER
                }
            }
        }
        if (folderChanged) refreshNotes(account)
        SendResult.ALL_SENT
    }

    private suspend fun sendFolder(account: Account, op: NotebookOutboxEntity): Outcome<*> {
        val on = op.body == "true"
        val r = api.setFolders(account.serverUrl, account.token, listOf(FolderSwitch(op.ref, on)))
        if (r is Outcome.Ok) {
            dao.folder(op.ref)?.let { dao.upsertFolders(listOf(it.copy(syncStatus = SYNCED))) }
            dao.replaceFolders(r.value.folders.map(::folderRow))
        }
        return r
    }

    private suspend fun sendNote(account: Account, op: NotebookOutboxEntity): Outcome<*> {
        val row = dao.noteOnce(op.ref) ?: return Outcome.Ok(Unit)
        val body = row.body ?: return Outcome.Ok(Unit)
        val r = api.putNote(account.serverUrl, account.token, PutNoteBody(row.path, body, row.hash.ifEmpty { null }))
        if (r !is Outcome.Ok) return r
        val reply = r.value
        val now = dao.noteOnce(row.path)
        if (reply.path == row.path) {
            // Edited again while this was on its way: keep it waiting (its own outbox row sends it) with the new base.
            val still = now != null && now.lastModified != row.lastModified
            dao.upsertNote((now ?: row).copy(hash = reply.hash, serverVersion = reply.version, syncStatus = if (still) PENDING else SYNCED, refused = null))
        } else {
            // pagel.uz had a newer copy than the phone saw: it kept both. The phone's words live on as the "(phone)" note.
            dao.upsertNote(row.copy(path = reply.path, title = Markdown.titleOf(reply.path, body), hash = reply.hash, serverVersion = reply.version, syncStatus = SYNCED, refused = null))
            dao.upsertNote(row.copy(body = null, syncStatus = SYNCED, refused = null))
            said.tryEmit("pagel.uz had a newer copy. Yours is kept as ${reply.path.substringAfterLast('/').removeSuffix(".md")}.")
        }
        return r
    }

    /** A write pagel.uz refused: a switch rolls back; a note keeps its words here, marked; a grade's card is dropped. */
    private suspend fun refused(op: NotebookOutboxEntity, message: String) {
        when (op.what) {
            NotebookOutboxEntity.FOLDER -> dao.folder(op.ref)?.let {
                dao.upsertFolders(listOf(it.copy(allowed = op.body != "true", syncStatus = SYNCED)))
                said.tryEmit(message)
            }
            NotebookOutboxEntity.NOTE -> dao.noteOnce(op.ref)?.let {
                dao.upsertNote(it.copy(refused = message))
                said.tryEmit("Not saved on pagel.uz: $message")
            }
            NotebookOutboxEntity.GRADE -> dao.dropCard(op.ref)
        }
    }

    private fun folderRow(f: FolderDto) = VaultFolderEntity(f.path, f.allowed && !f.locked, f.set, f.locked, f.notes, SYNCED, clock())
}
