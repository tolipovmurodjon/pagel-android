package org.pagel.ai.data.rituals

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.api.RitualBody
import org.pagel.ai.data.api.StuckDto
import org.pagel.ai.data.api.SweepBody
import org.pagel.ai.data.api.SweepRowDto
import org.pagel.ai.data.db.LOCAL_ID
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.RitualStateEntity
import org.pagel.ai.data.db.RitualsDao
import org.pagel.ai.data.db.SweepEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.ReminderPlanner
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.OutboxScheduler
import org.pagel.ai.data.today.TodayRepository
import java.util.UUID
import kotlinx.coroutines.sync.withLock

internal val ritualJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val rowsSerializer = ListSerializer(SweepRowDto.serializer())

fun SweepEntity.rowList(): List<SweepRowDto> = runCatching { ritualJson.decodeFromString(rowsSerializer, rows) }.getOrDefault(emptyList())
fun RitualStateEntity.stuckList(): List<StuckDto> = runCatching { ritualJson.decodeFromString(ListSerializer(StuckDto.serializer()), stuck) }.getOrDefault(emptyList())

/** What "Add N" made, for the done card and its Undo. */
data class Added(val sweep: String, val actions: Int, val someday: Int)

/** One task change a ritual made: the op that carries it (Undo takes it back while it waits) and what it did. */
data class RitualEdit(val taskId: String, val op: Long?, val kind: String, val project: String = "", val text: String = "", val at: Long = 0)

/** A closed ritual: its task changes, its own outbox row and the state before, so Undo puts all of it back. */
data class Closed(val edits: List<RitualEdit>, val ritualOp: Long, val before: RitualStateEntity, val token: String = "")

/**
 * The three rituals (Phase 5: the web's /sweep, /shutdown, /review), offline-first like every other edit. Every task
 * change is the same Room-first edit the task panel and Add make ([TaskRepository], [TodayRepository]): it answers at
 * once and waits in the outbox as `PATCH`/`POST /api/v1/tasks`, rolled back with a red toast if pagel.uz refuses it.
 * The ritual itself (its event and the "brain: shutdown" commit the web reads) is one more outbox row, sent after the
 * task changes. A dump waits in Room until pagel.uz's AI can read it; the rows come back for Boss to edit here.
 * Conflict rule: the same as the edits it makes (a task with a change waiting keeps the phone's copy until sent).
 */
class RitualsRepository(
    private val dao: PagelDao,
    private val rituals: RitualsDao,
    private val api: PagelApi,
    private val today: TodayRepository,
    private val tasks: TaskRepository,
    private val scheduler: OutboxScheduler,
    private val reminders: ReminderPlanner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val state: Flow<RitualStateEntity> = rituals.state().map { it ?: RitualStateEntity() }
    val openTasks: Flow<List<TaskEntity>> = rituals.openTasks()
    val sweep: Flow<SweepEntity?> = rituals.openSweep()
    fun sweep(id: String): Flow<SweepEntity?> = rituals.sweep(id)

    fun ritualDay(): String = Rituals.ritualDay(clock())
    fun now(): Long = clock()

    private suspend fun stateNow() = rituals.stateOnce() ?: RitualStateEntity()

    /** pagel.uz's view (closed tonight on the web? the last review, the stuck projects); offline the saved one stays. */
    suspend fun refresh(account: Account): Outcome<Unit> = when (val r = api.rituals(account.serverUrl, account.token)) {
        is Outcome.Failed -> r
        is Outcome.Ok -> {
            val s = stateNow()
            val v = r.value
            rituals.saveState(s.copy(
                shutDay = Rituals.later(s.shutDay, if (v.shutToday) v.day.ifEmpty { null } else null),
                lastReview = Rituals.later(s.lastReview, v.lastReview),
                stuck = ritualJson.encodeToString(ListSerializer(StuckDto.serializer()), v.stuck),
                fetchedAt = clock(),
            ))
            Outcome.Ok(Unit)
        }
    }

    // ── Empty your head ──────────────────────────────────────────────────────────────────────────────────────────

    /** The box as Boss types: saved to Room (a draft), so a closed app or a dead battery loses nothing. Returns its id. */
    suspend fun saveDraft(id: String?, text: String): String {
        val now = clock()
        val row = id?.let { rituals.sweepOnce(it) }
        if (row != null && row.state != SweepEntity.DRAFT) return row.id
        val sid = row?.id ?: newId("sw")
        rituals.upsertSweep(SweepEntity(sid, text, SweepEntity.DRAFT, createdAt = row?.createdAt ?: now, updatedAt = now))
        return sid
    }

    /**
     * "Turn into steps": the dump waits for pagel.uz's AI (now if online, else when the network is back). Every split gets
     * a fresh id: pagel.uz keeps a split's rows 24 h by id, so an edited dump sent under its old id came back with the old
     * rows (checker P5 #1). Returns the new id.
     */
    suspend fun split(id: String): String? {
        val row = rituals.sweepOnce(id) ?: return null
        if (row.dump.isBlank()) return null
        val now = clock()
        val fresh = newId("sw")
        rituals.takeBackSweep(id)
        rituals.replaceSweep(id, row.copy(id = fresh, state = SweepEntity.WAITING, rows = "[]", message = null, updatedAt = now), OutboxEntity(taskId = fresh, op = OutboxEntity.SWEEP, createdAt = now))
        scheduler.kick()
        return fresh
    }

    /** "Back to the text": the rows go (as on the web) and the dump is a draft again; a read still waiting is taken back. */
    suspend fun backToText(id: String) {
        rituals.takeBackSweep(id)
        val row = rituals.sweepOnce(id) ?: return
        rituals.upsertSweep(row.copy(state = SweepEntity.DRAFT, rows = "[]", message = null, updatedAt = clock()))
    }

    /** Boss's edits of the rows (kind, words, project, removed): Room at once. */
    suspend fun setRows(id: String, rows: List<SweepRowDto>) {
        val row = rituals.sweepOnce(id) ?: return
        if (row.state != SweepEntity.READY) return
        rituals.upsertSweep(row.copy(rows = ritualJson.encodeToString(rowsSerializer, rows), updatedAt = clock()))
    }

    /**
     * "Add N": every kept row becomes a task in one go (Do = P1, Someday = P3, as the web's saveRows), each on the phone
     * at once and sent as `POST /api/v1/tasks`. Null when nothing is kept or a row is too long for pagel.uz.
     */
    suspend fun add(id: String): Added? {
        val row = rituals.sweepOnce(id) ?: return null
        if (row.state != SweepEntity.READY && row.state != SweepEntity.ADDING) return null
        val keep = Rituals.kept(row.rowList())
        if (keep.isEmpty() || keep.any(Rituals::tooLong)) return null
        // One claim, compare-and-set: a second tap (or a second coroutine) finds the dump already being added and stops
        // (checker P5 #2: two taps 80 ms apart made 6 tasks). A claim left by a killed app is taken again after a minute.
        if (rituals.claimAdd(id, clock(), clock() - STALE_ADD_MS) == 0) return null
        // Each task's id is written as soon as it exists, so a claim taken again skips the rows already added.
        val ids = rituals.sweepOnce(id)?.addedIds.orEmpty().split(',').filter(String::isNotBlank).toMutableList()
        for (r in keep.drop(ids.size)) {
            val text = r.text.replace(Regex("\\s+"), " ").trim().take(240)
            ids += today.add(text, r.project, if (Kind.of(r.kind) == Kind.ACTION) 1 else 3, due = null, every = null, onToday = false)
            rituals.setAddedIds(id, ids.joinToString(","))
        }
        val now = clock()
        rituals.upsertSweep((rituals.sweepOnce(id) ?: row).copy(state = SweepEntity.ADDED, addedIds = ids.joinToString(","), updatedAt = now))
        rituals.pruneAdded(now - 7 * 86_400_000L)
        val actions = keep.count { Kind.of(it.kind) == Kind.ACTION }
        return Added(id, actions, keep.size - actions)
    }

    /** Undo of "Add N": a task still on the phone is taken back (pagel.uz never hears of it), a sent one is deleted. */
    suspend fun undoAdd(id: String) {
        val row = rituals.sweepOnce(id) ?: return
        for (t in row.addedIds.split(',').filter(String::isNotBlank)) {
            if (t.startsWith(LOCAL_ID)) {
                val op = rituals.waitingAdd(t) ?: continue
                if (rituals.takeBackOp(op) > 0) dao.dropLocalTask(t, keep = -1)
            } else {
                tasks.delete(t)
            }
        }
        rituals.upsertSweep(row.copy(state = SweepEntity.READY, addedIds = "", updatedAt = clock()))
    }

    // ── Close the day ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The evening shutdown (review.ts shutdown): each open star is kept (nothing changes), pushed to Later (unstarred,
     * stays in its plan) or dropped (removed, with the delete's 5 s Undo); each pick gets a star. Then the shutdown event.
     */
    suspend fun closeDay(stars: List<TaskEntity>, moves: Map<String, Move>, picks: List<String>, token: String = newId("tk")): Closed = once(token) {
        val before = stateNow()
        val edits = mutableListOf<RitualEdit>()
        var pushed = 0
        var dropped = 0
        for (t in stars) when (moves[t.id] ?: Move.KEEP) {
            Move.KEEP -> {}
            Move.LATER -> { edits += RitualEdit(t.id, tasks.star(t.id, false), "later"); pushed++ }
            Move.DROP -> { edits += RitualEdit(t.id, tasks.delete(t.id), "drop"); dropped++ }
        }
        // No cap on picks: Today deals the stars 3 at a time (the web's shutdown, Boss 2026-10-27: "keep my current ones").
        val picked = picks.distinct()
        for (id in picked) edits += RitualEdit(id, tasks.star(id, true), "pick")
        val kept = stars.count { (moves[it.id] ?: Move.KEEP) == Move.KEEP }
        val day = ritualDay()
        val op = queueRitual(RitualBody("shutdown", newId("sd"), day, kept = kept, pushed = pushed, dropped = dropped, picked = picked.size))
        rituals.saveState(before.copy(shutDay = Rituals.later(before.shutDay, day)))
        reminders.replan()
        Closed(edits, op, before, token)
    }

    /** The weekly review's finish (review.ts finishReview): a next step for each stuck project, someday tasks dropped. */
    suspend fun finishReview(adds: Map<String, String>, drops: List<String>, token: String = newId("tk")): Closed = once(token) {
        val before = stateNow()
        val edits = mutableListOf<RitualEdit>()
        val added = adds.mapValues { it.value.replace(Regex("\\s+"), " ").trim() }.filterValues(String::isNotEmpty)
        for ((project, text) in added) {
            val at = clock()
            edits += RitualEdit(today.add(text.take(240), project, 1, due = null, every = null, onToday = false), null, "add", project, text.take(240), at)
        }
        for (id in drops) edits += RitualEdit(id, tasks.delete(id), "drop")
        val day = ritualDay()
        val op = queueRitual(RitualBody("review", newId("rv"), day, added = added.size, dropped = drops.size))
        rituals.saveState(before.copy(lastReview = Rituals.later(before.lastReview, day)))
        Closed(edits, op, before, token)
    }

    /**
     * A ritual is closed once per screen: the same token (one per opened ritual) a second time, even at the same moment,
     * gets the first result back instead of a second set of changes and a second event (checker P5 #2). Undo frees it.
     */
    private val closing = kotlinx.coroutines.sync.Mutex()
    private val closedBy = java.util.concurrent.ConcurrentHashMap<String, Closed>()
    private suspend fun once(token: String, block: suspend () -> Closed): Closed = closing.withLock {
        closedBy[token] ?: block().also { closedBy[token] = it }
    }

    /** Undo of a closed ritual: each change taken back while it waits, else the opposite is sent; the state goes back. */
    suspend fun undo(c: Closed) {
        for (e in c.edits.asReversed()) when (e.kind) {
            "later" -> if (e.op == null || !tasks.takeBack(e.taskId, e.op)) tasks.star(e.taskId, true)
            "pick" -> if (e.op == null || !tasks.takeBack(e.taskId, e.op)) tasks.star(e.taskId, false)
            "drop" -> if (e.op != null) tasks.undoDelete(e.taskId, e.op)
            "add" -> undoNewTask(e)
        }
        // The ritual's own event is taken back only while it waits; once on pagel.uz it stays in the history.
        rituals.takeBackOp(c.ritualOp)
        closedBy.remove(c.token)
        val now = stateNow()
        rituals.saveState(now.copy(shutDay = c.before.shutDay, lastReview = c.before.lastReview))
        reminders.replan()
    }

    /** A task the ritual added: taken back while its add waits; once pagel.uz named it, found by its words and deleted. */
    private suspend fun undoNewTask(e: RitualEdit) {
        val op = rituals.waitingAdd(e.taskId)
        if (op != null && rituals.takeBackOp(op) > 0) { dao.dropLocalTask(e.taskId, keep = -1); return }
        if (dao.taskOnce(e.taskId) != null && e.taskId.startsWith(LOCAL_ID)) return
        // Only a task made since the ritual (adoptServerId keeps the phone's lastModified), never an older twin.
        rituals.adoptedId(e.project, e.text, e.at)?.let { tasks.delete(it) }
    }

    // ── The evening reminder ─────────────────────────────────────────────────────────────────────────────────────

    suspend fun setEvening(on: Boolean, at: String? = null) {
        val s = stateNow()
        rituals.saveState(s.copy(eveningOn = on, eveningAt = at ?: s.eveningAt))
        reminders.replan()
    }

    private suspend fun queueRitual(body: RitualBody): Long {
        val op = rituals.addOp(OutboxEntity(taskId = body.id, op = OutboxEntity.RITUAL, createdAt = clock(), body = ritualJson.encodeToString(RitualBody.serializer(), body)))
        scheduler.kick()
        return op
    }

    private fun newId(prefix: String) = prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(16)

    companion object {
        /** A claim on "Add N" older than this was left by a killed app: it may be taken again. */
        const val STALE_ADD_MS = 60_000L
    }
}

/** What one ritual row in the outbox ended with. LATER = not now, without holding the rest (the AI is busy). */
enum class RitualStep { SENT, LATER, SIGNED_OUT, TRY_LATER }

/**
 * Sends the rituals' outbox rows. A ritual: `POST /api/v1/rituals` (pagel.uz answers a repeat from what happened). A
 * dump: `POST /api/v1/sweep`; its rows land in Room for Boss to edit. A refused dump (the AI could not split it, the
 * day's cap) is shown on the dump with pagel.uz's words and waits for Boss's "Try again", never retried by itself.
 */
class RitualSender(private val api: PagelApi, private val dao: RitualsDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun send(account: Account, op: OutboxEntity): RitualStep {
        val body = op.body?.let { runCatching { ritualJson.decodeFromString(RitualBody.serializer(), it) }.getOrNull() }
            ?: run { dao.removeFromOutbox(op.id); return RitualStep.SENT }
        dao.countAttempt(op.id)
        return when (val out = api.ritual(account.serverUrl, account.token, body)) {
            is Outcome.Ok -> { dao.removeFromOutbox(op.id); RitualStep.SENT }
            is Outcome.Failed -> when (val e = out.error) {
                // 404: a pagel.uz from before the rituals: the event waits for it (checker P5 #10).
                is DataError.Refused -> if (e.status == 404) RitualStep.LATER else {
                    // The shell says it in red; the task changes before it stand on their own.
                    dao.markRefused(op.id, e.message); RitualStep.SENT
                }
                DataError.Unauthorized -> RitualStep.SIGNED_OUT
                else -> RitualStep.TRY_LATER
            }
        }
    }

    suspend fun split(account: Account, op: OutboxEntity): RitualStep {
        val row = dao.sweepOnce(op.taskId)
        if (row == null || row.state !in setOf(SweepEntity.WAITING, SweepEntity.READING)) { dao.removeFromOutbox(op.id); return RitualStep.SENT }
        dao.countAttempt(op.id)
        dao.setSweepState(row.id, SweepEntity.READING, null, clock())
        return when (val out = api.sweep(account.serverUrl, account.token, SweepBody(row.id, row.dump))) {
            is Outcome.Ok -> {
                dao.sweepRead(row.id, ritualJson.encodeToString(rowsSerializer, out.value.rows), clock())
                dao.removeFromOutbox(op.id)
                RitualStep.SENT
            }
            is Outcome.Failed -> when (val e = out.error) {
                is DataError.Refused -> if (e.status == 404) { waitAgain(row.id); RitualStep.LATER } else {
                    if (dao.sweepOnce(row.id)?.state == SweepEntity.READING) dao.setSweepState(row.id, SweepEntity.FAILED, e.message, clock())
                    dao.removeFromOutbox(op.id)
                    RitualStep.SENT
                }
                DataError.Unauthorized -> { waitAgain(row.id); RitualStep.SIGNED_OUT }
                // 503: pagel.uz's AI reads another dump; this one goes on a later pass.
                is DataError.Server -> { waitAgain(row.id); if (e.status == 503) RitualStep.LATER else RitualStep.TRY_LATER }
                DataError.Network -> { waitAgain(row.id); RitualStep.TRY_LATER }
            }
        }
    }

    private suspend fun waitAgain(id: String) {
        if (dao.sweepOnce(id)?.state == SweepEntity.READING) dao.setSweepState(id, SweepEntity.WAITING, null, clock())
    }
}
