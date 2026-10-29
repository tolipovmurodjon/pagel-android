package org.pagel.ai.data.compass

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.db.LOCAL_ID
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.today.OutboxScheduler
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.data.today.TodayRepository
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher

/** Where the Compass stands on this phone. */
enum class VaultState { READING, NO_LOCK, NEW, LOCKED, LOST, OPEN }

/**
 * The data key while the Compass is open, in memory only. Leaving the Compass (another screen, the app in the
 * background) starts a 60 s timer; coming back stops it; when it runs out the key is wiped and the screen locks.
 */
class CompassKey(private val scope: CoroutineScope) {
    private val now = MutableStateFlow<ByteArray?>(null)
    val key: StateFlow<ByteArray?> = now.asStateFlow()
    private var timer: Job? = null

    fun open(k: ByteArray) {
        timer?.cancel()
        now.value = k
    }

    fun lock() {
        timer?.cancel()
        val k = now.value
        now.value = null
        k?.fill(0)
    }

    fun leave(afterMs: Long = AUTO_LOCK_MS) {
        timer?.cancel()
        if (now.value == null) return
        timer = scope.launch { delay(afterMs); lock() }
    }

    fun stay() { timer?.cancel() }

    companion object {
        const val AUTO_LOCK_MS = 60_000L
    }
}

/** What the unlocked Compass shows: the goals and notes opened in memory, and how many blobs this key cannot open. */
data class Opened(val goals: List<Goal> = emptyList(), val notes: List<Note> = emptyList(), val unreadable: Int = 0)

/** A goal's live progress as plain counts (Boss: "counts only, never AI words"). */
data class GoalProgress(val projects: List<String> = emptyList(), val doneThisWeek: Int = 0, val habitId: String? = null, val hitDays: Set<String> = emptySet())

/** The age report's plain numbers for one goal year. */
data class YearNumbers(val tasksDone: Int = 0, val habitDays: Int = 0, val bestRun: Int = 0, val namazDays: Int = 0)

/**
 * The Compass (Phase 6a). Room holds only ciphertext (compass.db); the words live in memory while the key is open.
 * Every write is sealed, saved to Room, then queued for pagel.uz (CompassWorker), which stores the blob only.
 * Conflict rule: the phone is the only writer and its copy wins; pagel.uz is the sealed backup a new phone recovers
 * from with Boss's passphrase. A sealed write pagel.uz refuses stays on the phone (never rolled back or dropped).
 */
class CompassRepository(
    private val dao: CompassDao,
    private val api: CompassApi,
    private val keys: DeviceKeys,
    private val key: CompassKey,
    private val tasks: PagelDao,
    private val adder: TodayRepository,
    private val scheduler: OutboxScheduler,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()

    fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(TASHKENT).toLocalDate()
    fun now(): Long = clock()

    val state: Flow<VaultState> = combine(dao.vault(), dao.itemCount(), key.key) { v, n, k ->
        when {
            k != null -> VaultState.OPEN
            v?.wrapped != null -> VaultState.LOCKED
            n > 0 -> VaultState.LOST
            !keys.hasScreenLock() -> VaultState.NO_LOCK
            else -> VaultState.NEW
        }
    }.flowOn(io)

    val vault: Flow<CompassVaultEntity> = dao.vault().map { it ?: CompassVaultEntity() }

    val opened: Flow<Opened> = combine(dao.items(), key.key) { items, k ->
        if (k == null) return@combine Opened()
        var bad = 0
        val goals = mutableListOf<Goal>()
        val notes = mutableListOf<Note>()
        for (row in items) {
            val plain = CompassCrypto.open(k, CompassCrypto.aad(row.id, row.kind, row.parent, row.starts, row.ends), row.blob)
            if (plain == null) { bad++; continue }
            val text = plain.toString(Charsets.UTF_8)
            plain.fill(0)
            val level = Level.of(row.kind)
            if (level != null) {
                val b = runCatching { json.decodeFromString(GoalBody.serializer(), text) }.getOrNull()
                if (b == null) bad++ else goals.add(Goal(row.id, level, row.parent, row.starts, row.ends, b))
            } else if (row.kind in NoteKind.all) {
                val b = runCatching { json.decodeFromString(NoteBody.serializer(), text) }.getOrNull()
                if (b == null) bad++ else notes.add(Note(row.id, row.kind, row.starts, b))
            }
        }
        Opened(goals, notes, bad)
    }.flowOn(Dispatchers.Default)

    /** Projects → the goal each serves (ids only). */
    val links: Flow<Map<String, String>> = dao.links().map { all -> all.mapNotNull { l -> l.goal?.let { l.project to it } }.toMap() }

    /** Today's quiet line, opened with the line key; null while it is off. */
    val line: Flow<String?> = dao.vault().map { v ->
        if (v?.lineOn == true && v.line != null) keys.openLine(v.line)?.toString(Charsets.UTF_8) else null
    }.flowOn(io)

    // ── Unlock ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The cipher the Compass's own unlock prompt authorises (one use, CryptoObject): DECRYPT for a locked Compass;
     * ENCRYPT for a new one, or for a phone whose guard key is gone (made fresh). A guard that is gone turns a locked
     * Compass to LOST and throws [KeyGone].
     */
    suspend fun cipherForUnlock(): Cipher = withContext(io) {
        val v = vaultNow()
        val wrapped = v.wrapped
        if (wrapped == null) return@withContext keys.cipherFor(null, fresh = dao.itemCount().first() > 0)
        try {
            keys.cipherFor(wrapped)
        } catch (e: KeyGone) {
            dao.upsertVault(v.copy(wrapped = null))
            throw e
        }
    }

    /** Right after a successful prompt on a new Compass: a fresh data key, wrapped by the Keystore, opened. */
    suspend fun create(cipher: Cipher) = withContext(io) {
        val dek = CompassCrypto.newDataKey()
        val wrapped = keys.wrap(cipher, dek)
        dao.upsertVault(vaultNow().copy(wrapped = wrapped))
        key.open(dek)
    }

    /** Right after a successful prompt: the data key back. */
    suspend fun unlock(cipher: Cipher) = withContext(io) {
        val wrapped = vaultNow().wrapped ?: throw KeyGone()
        key.open(keys.unwrap(cipher, wrapped))
    }

    fun lock() = key.lock()
    fun leave() = key.leave()
    fun stay() = key.stay()

    /** A new phone: Boss's recovery passphrase opens the sealed copy from pagel.uz. False for a wrong passphrase. */
    suspend fun recover(passphrase: CharArray, cipher: Cipher): Boolean = withContext(io) {
        val v = vaultNow()
        val blob = v.serverRecovery ?: v.recovery ?: return@withContext false
        val dek = CompassCrypto.openWithPassphrase(blob, passphrase) ?: return@withContext false
        dao.upsertVault(v.copy(wrapped = keys.wrap(cipher, dek), recovery = blob))
        key.open(dek)
        true
    }

    /** A new Compass on a phone whose old key is gone: the old sealed items stay on pagel.uz, unread, and leave this phone. */
    suspend fun startOver(cipher: Cipher) = withContext(io) {
        val old = dao.items().first()
        dao.upsertItems(old.map { it.copy(deleted = true, syncStatus = CompassItemEntity.SYNCED) })
        create(cipher)
    }

    /** Sets (or with null, takes away) the recovery passphrase. */
    suspend fun setRecovery(passphrase: CharArray?) = withContext(io) {
        val k = key.key.value ?: throw NeedsUnlock()
        val blob = passphrase?.let { CompassCrypto.sealWithPassphrase(k, it) }
        dao.upsertVault(vaultNow().copy(recovery = blob))
        queue(CompassOutboxEntity.RECOVERY, "recovery")
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Seals and saves one goal; returns its id. A new weekly lead action becomes a habit through the normal habits path
     * (a weekly repeating task) under a NEUTRAL title, "🧭 Compass step" (a number when there are several): the step's
     * own words stay sealed in the goal and show only in the unlocked Compass (lead's decision 2026-10-29, Boss's wall
     * "no AI ever will read them"; compass checker #2: the words used to reach pagel.uz, the MCP and the habit bell).
     */
    suspend fun saveGoal(goal: Goal, habitProject: String = "me"): String = withContext(io) {
        val k = key.key.value ?: throw NeedsUnlock()
        val id = goal.id.ifEmpty { newId("g") }
        var body = if (goal.body.createdAt == 0L) goal.body.copy(createdAt = clock()) else goal.body
        if (body.leadAction.isNotBlank() && body.habitId == null && goal.level == Level.SEASON) {
            val taken = opened.first().goals.mapNotNull { it.body.habitTitle }.toSet()
            val title = CompassRules.stepTitle(taken)
            body = body.copy(habitTitle = title, habitId = adder.add(title, habitProject, priority = 2, due = today().toString(), every = "week", onToday = false))
        }
        write(k, id, goal.level.wire, goal.parent, json.encodeToString(GoalBody.serializer(), body), goal.starts, goal.ends)
        val v = vaultNow()
        if (v.lineGoal == id && v.lineOn) dao.upsertVault(v.copy(line = keys.sealLine(CompassRules.firstWords(body.title).toByteArray())))
        id
    }

    /** A goal leaves the Compass (its sealed blob is marked deleted on pagel.uz too). */
    suspend fun deleteGoal(id: String) = withContext(io) {
        if (dao.markDeleted(id, clock()) == 0) return@withContext
        queue(CompassOutboxEntity.ITEM, id)
    }

    /** One sealed note (the weekly answers, a review, the age report, a change's why); [day] is its date. */
    suspend fun saveNote(kind: String, day: String, body: NoteBody, id: String = ""): String = withContext(io) {
        require(kind in NoteKind.all)
        val k = key.key.value ?: throw NeedsUnlock()
        val nid = id.ifEmpty { newId("e") }
        write(k, nid, kind, body.goalId, json.encodeToString(NoteBody.serializer(), body), day, null)
        nid
    }

    /** Which goal a project serves (null unlinks). Only the goal's id is stored and sent. */
    suspend fun link(project: String, goal: String?) = withContext(io) {
        dao.upsertLink(CompassLinkEntity(project, goal, clock()))
        queue(CompassOutboxEntity.LINK, project)
    }

    /** Today's quiet line: on with a goal's first words (sealed by the line key, on this phone only), or off. */
    suspend fun setLine(on: Boolean, goal: Goal?) = withContext(io) {
        val v = vaultNow()
        val g = goal ?: v.lineGoal?.let { id -> opened.first().goals.firstOrNull { it.id == id } }
        dao.upsertVault(v.copy(lineOn = on && g != null, lineGoal = g?.id, line = g?.let { keys.sealLine(CompassRules.firstWords(it.body.title).toByteArray()) }))
    }

    suspend fun setBirthday(on: Boolean) = withContext(io) { dao.upsertVault(vaultNow().copy(birthdayOn = on)) }
    suspend fun setReview(on: Boolean) = withContext(io) { dao.upsertVault(vaultNow().copy(reviewOn = on)) }

    private suspend fun write(k: ByteArray, id: String, kind: String, parent: String?, plain: String, starts: String?, ends: String?) {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val blob = CompassCrypto.seal(k, CompassCrypto.aad(id, kind, parent, starts, ends), bytes)
        bytes.fill(0)
        val now = clock()
        if (dao.updateSealed(id, kind, parent, blob, starts, ends, now) == 0) {
            dao.insertItem(CompassItemEntity(id, kind, parent, blob, starts, ends, sort = now, lastModified = now))
        }
        queue(CompassOutboxEntity.ITEM, id)
    }

    private suspend fun queue(what: String, ref: String) {
        dao.enqueue(CompassOutboxEntity(what = what, ref = ref, createdAt = clock()))
        scheduler.kick()
    }

    /** Opening the Compass sends anything still waiting (pagel.uz may have learned the Compass since). */
    fun nudge() = scheduler.kick()

    private suspend fun vaultNow() = dao.vaultOnce() ?: CompassVaultEntity()

    private fun newId(prefix: String): String {
        val abc = "abcdefghijkmnpqrstuvwxyz23456789"
        return prefix + "_" + (1..12).map { abc[random.nextInt(abc.length)] }.joinToString("")
    }

    // ── pagel.uz ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * A phone with no key of its own asks pagel.uz once for the sealed copy: if goals come back, the Compass is LOST
     * until the recovery passphrase opens them (or Boss starts over).
     */
    suspend fun pullIfNew(account: Account) = withContext(io) {
        val v = vaultNow()
        if (v.wrapped != null || v.pulledAt > 0) return@withContext
        val out = api.pull(account.serverUrl, account.token) as? Outcome.Ok<CompassPull> ?: return@withContext
        val now = clock()
        dao.upsertItems(out.value.items.mapIndexed { i, p ->
            CompassItemEntity(p.id, p.kind, p.parent, p.blob, p.starts, p.ends, sort = i.toLong(), deleted = p.deleted, syncStatus = CompassItemEntity.SYNCED, lastModified = now, serverVersion = p.version)
        })
        for (l in out.value.links) dao.upsertLink(CompassLinkEntity(l.project, l.goal, now))
        dao.upsertVault(vaultNow().copy(serverRecovery = out.value.recovery, pulledAt = now))
    }

    /**
     * Sends the waiting changes in order. pagel.uz without the Compass yet (404/405) or a phone token it does not take
     * here (403): everything waits for the next change or the next open, nothing is lost. Any other refusal of a sealed
     * write leaves the phone's copy as it is.
     */
    suspend fun sendAll(account: Account): SendResult = withContext(io) {
        for (op in dao.outbox()) {
            dao.attempt(op.id)
            val out: Outcome<*> = when (op.what) {
                CompassOutboxEntity.ITEM -> sendItem(account, op.ref)
                CompassOutboxEntity.LINK -> api.putLink(account.serverUrl, account.token, op.ref, dao.link(op.ref)?.goal)
                CompassOutboxEntity.RECOVERY -> api.putRecovery(account.serverUrl, account.token, vaultNow().recovery)
                else -> Outcome.Ok(Unit)
            }
            when (out) {
                is Outcome.Ok -> dao.sent(op.id)
                is Outcome.Failed -> when (val e = out.error) {
                    is DataError.Refused -> when {
                        e.status in WAIT_FOR_SERVER -> return@withContext SendResult.ALL_SENT
                        // A conflict is never dropped (re-check N1): it waits and is tried again.
                        e.status == 409 -> return@withContext SendResult.TRY_LATER
                        else -> dao.sent(op.id)
                    }
                    DataError.Unauthorized -> return@withContext SendResult.SIGNED_OUT
                    else -> return@withContext SendResult.TRY_LATER
                }
            }
        }
        SendResult.ALL_SENT
    }

    /**
     * One sealed item to pagel.uz. On a 409 the phone takes the version pagel.uz holds and sends its own copy over it:
     * this phone is the only writer, so that copy is an earlier write of its own whose reply was lost (re-check N1:
     * the change used to be dropped and pagel.uz's copy froze). Twice stale in one pass → kept, tried later.
     */
    private suspend fun sendItem(account: Account, id: String): Outcome<*> {
        repeat(3) {
            val row = dao.item(id) ?: return Outcome.Ok(Unit)
            val r = api.putItem(account.serverUrl, account.token, row.id, SealedItemBody(row.kind, row.parent, row.blob, row.starts, row.ends, row.deleted, row.serverVersion))
            if (r !is Outcome.Ok) return r
            dao.setServerVersion(row.id, r.value.version)
            if (!r.value.stale) {
                dao.markSynced(row.id, r.value.version, row.lastModified)
                return r
            }
        }
        return Outcome.Failed(DataError.Server(409))
    }

    // ── Counts from the rest of PAGEL (plain numbers only) ──────────────────────────────────────────────────────────

    /** Each goal's linked projects, tasks done in them this week, and its lead-action habit's days. */
    fun progress(day: LocalDate = today()): Flow<Map<String, GoalProgress>> {
        val since = day.minusDays(120).toString()
        return combine(opened, links, tasks.completed(), tasks.habits(), tasks.habitTicks(since)) { o, l, done, habits, ticks ->
            val season = CompassRules.seasonOf(day)
            val weekStart = CompassRules.weekStart(season, CompassRules.weekOf(season, day))
            val doneDays = done.map { it.project to tashkentDay(it.doneAt) }
            o.goals.associate { g ->
                val projects = l.filterValues { it == g.id }.keys.sorted()
                val n = doneDays.count { (p, d) -> p in projects && d != null && !d.isBefore(weekStart) && d.isBefore(weekStart.plusDays(7)) }
                // A habit made on the phone takes pagel.uz's id once sent; find it again by its own words.
                val hid = g.body.habitId?.let { h ->
                    if (!h.startsWith(LOCAL_ID) || habits.any { it.id == h }) h else habits.firstOrNull { it.text == g.body.habitTitle && it.every == "week" }?.id ?: h
                }
                g.id to GoalProgress(projects, n, hid, ticks.filter { it.taskId == hid && it.n > 0 }.mapTo(HashSet()) { it.day })
            }
        }.flowOn(Dispatchers.Default)
    }

    /** The age report's numbers for the goal year [start]..[end] (namaz days count from 1 Nov 2026, when tracking starts). */
    fun yearNumbers(start: LocalDate, end: LocalDate): Flow<YearNumbers> =
        combine(tasks.completed(), tasks.habits(), tasks.habitTicks(start.toString())) { done, habits, ticks ->
            val inYear = { d: LocalDate? -> d != null && !d.isBefore(start) && !d.isAfter(end) }
            val doneN = done.count { it.every.isNullOrBlank() && inYear(tashkentDay(it.doneAt)) }
            val days = ticks.filter { it.n > 0 && inYear(runCatching { LocalDate.parse(it.day) }.getOrNull()) }.mapTo(sortedSetOf<String>()) { it.day }
            var best = 0
            var run = 0
            var prev: LocalDate? = null
            for (d in days.map { LocalDate.parse(it) }) {
                run = if (prev != null && d == prev.plusDays(1)) run + 1 else 1
                best = maxOf(best, run)
                prev = d
            }
            val namaz = habits.filter { NAMAZ.containsMatchIn(it.text) || NAMAZ.containsMatchIn(it.title.orEmpty()) }.mapTo(HashSet()) { it.id }
            val namazDays = ticks.filter { it.taskId in namaz && it.n > 0 && it.day >= NAMAZ_FROM && inYear(runCatching { LocalDate.parse(it.day) }.getOrNull()) }.mapTo(HashSet()) { it.day }.size
            YearNumbers(doneN, days.size, best, namazDays)
        }.flowOn(Dispatchers.Default)

    private fun tashkentDay(iso: String?): LocalDate? = iso?.let { runCatching { Instant.parse(it).atZone(TASHKENT).toLocalDate() }.getOrNull() }

    companion object {
        val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")
        /** Not deployed yet (404, 405) or not this phone's to write (403): wait, keep everything. */
        private val WAIT_FOR_SERVER = setOf(403, 404, 405)
        private val NAMAZ = Regex("namo?z|namaz|bomdod|peshin|xufton|shom|asr\\b", RegexOption.IGNORE_CASE)
        /** Namaz tracking starts on his 19th birthday (Boss 2026-10-28). */
        const val NAMAZ_FROM = "2026-11-01"
    }
}
