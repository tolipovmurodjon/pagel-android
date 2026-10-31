package org.pagel.ai.data.today

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.pagel.ai.data.db.TodayStateEntity
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.api.NewTaskBody
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.inbox.InboxSender
import org.pagel.ai.data.inbox.SortStep
import org.pagel.ai.data.db.LOCAL_ID
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.SyncStatus
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.tasks.NEW_FIELDS
import org.pagel.ai.data.tasks.UNDO_WINDOW_MS
import org.pagel.ai.data.tasks.applyWas
import org.pagel.ai.data.tasks.sentOf
import org.pagel.ai.data.tasks.wasOf

/** An event's name or reason: lower-case words joined by "_" (what_now_pick, too_big), never free text. */
val EVENT_WORD = Regex("[a-z][a-z_]{1,31}")

/** Wakes the outbox sender (WorkManager in the app, a counter in tests). */
fun interface OutboxScheduler {
    fun kick()
}

/**
 * Today and the tick (NATIVE-PLAN Phase 2). A tick changes Room at once, so the screen answers in one frame
 * (rules/respond-first-save-after); the change then waits in the outbox until pagel.uz has it.
 * Conflict rule: a task with a waiting tick keeps the phone's state (PagelDao.applySync skips it); once sent, the
 * server's copy wins again. A refused tick is rolled back and shown to Boss, never dropped silently.
 */
class TodayRepository(
    private val dao: PagelDao,
    private val scheduler: OutboxScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val today: Flow<List<TaskEntity>> = dao.todayTasks()
    val refused: Flow<List<OutboxEntity>> = dao.refusedOutbox()
    /** Every open dated task (a closed day peeks at tomorrow's stars). */
    val dated: Flow<List<TaskEntity>> = dao.datedOpen()

    /** Changes still on the phone, waiting for pagel.uz. */
    val waiting: Flow<Int> = dao.waitingCount()

    /** Tasks finished today in Tashkent (on Today or anywhere else), for Today's progress bar. */
    fun doneToday(): Flow<Int> {
        val start = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.of("Asia/Tashkent")).toLocalDate()
            .atStartOfDay(java.time.ZoneId.of("Asia/Tashkent")).toInstant()
        return dao.doneCountSince(start.toString())
    }

    suspend fun tick(id: String, done: Boolean) {
        val now = clock()
        dao.setStatusLocal(id, if (done) "done" else "open", if (done) java.time.Instant.ofEpochMilli(now).toString() else null, now)
        if (!done) dao.ensureOnToday(id)
        // Undo before the tick left the phone: take the waiting change back; pagel.uz never saw it.
        if (!done && dao.takeBackWaiting(id) > 0) {
            dao.markSyncedIfIdle(id)
            return
        }
        dao.addToOutbox(OutboxEntity(taskId = id, op = if (done) OutboxEntity.DONE else OutboxEntity.UNDONE, createdAt = now))
        // KPI: the time from opening the app to the day's first tick (a plain number, no words).
        if (done) {
            val day = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Tashkent")).toLocalDate().toString()
            org.pagel.ai.data.projects.Kpi.firstTick(now, day)?.let { ms ->
                dao.addToOutbox(OutboxEntity(taskId = "kpi", op = OutboxEntity.KPI, createdAt = now,
                    body = """{"method":"POST","path":"/api/v1/kpi","body":{"day":"$day","first_tick_ms":$ms}}"""))
            }
        }
        scheduler.kick()
    }

    /**
     * A new task (Add task fast): it is on the phone at once, on Today when [onToday], and waits in the outbox; once sent
     * it takes pagel.uz's id. Returns the phone's id for it.
     */
    suspend fun add(text: String, project: String, priority: Int, due: String?, every: String?, onToday: Boolean, group: String? = null): String {
        val now = clock()
        val id = LOCAL_ID + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        dao.upsertTasks(listOf(TaskEntity(
            id = id, project = project, phase = "", sort = Double.MAX_VALUE, text = text, title = null, status = "open",
            priority = priority, star = false, due = due, every = every, doneWhen = null, doneAt = null,
            updatedAt = java.time.Instant.ofEpochMilli(now).toString(), serverVersion = 0, syncStatus = SyncStatus.PENDING, lastModified = now,
            habitGroup = group,
        )))
        if (onToday) dao.appendToToday(id)
        dao.addToOutbox(OutboxEntity(taskId = id, op = OutboxEntity.ADD, createdAt = now))
        scheduler.kick()
        return id
    }

    /** The day in Tashkent, where pagel.uz counts its days. */
    fun day(): String = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.of("Asia/Tashkent")).toLocalDate().toString()

    /** Boss's own order for [day] (the full list's drag), by task id. */
    fun todaySorts(day: String): Flow<Map<String, Double>> = dao.todaySorts(day).map { rows -> rows.associate { it.taskId to it.sort } }

    /** The task's own sort on [day], or null while it keeps pagel.uz's place. */
    suspend fun todaySortOf(id: String, day: String): Double? = dao.todaySortsOnce(day).firstOrNull { it.taskId == id }?.sort

    /**
     * Gives every open row of [day]'s Today that has no sort of its own its current place ([keys]), on the phone only
     * (no PATCH: pagel.uz keeps its own place for them). Without it a row that kept pagel.uz's place moved up one when a
     * task above it was ticked, while a dragged row kept its sort, so the two drifted past each other (checker round 2 #3).
     */
    suspend fun pinSorts(day: String, keys: Map<String, Double>) {
        val have = dao.todaySortsOnce(day).mapTo(HashSet()) { it.taskId }
        val missing = keys.filterKeys { it !in have }
        if (missing.isNotEmpty()) dao.addTodaySorts(missing.map { (id, sort) -> org.pagel.ai.data.db.TodaySortEntity(id, day, sort) })
    }

    /** Today's screen state, only while it is still that day: a new day starts folded, with energy not asked. */
    fun state(day: String): Flow<TodayStateEntity> = dao.todayState().map { s -> s?.takeIf { it.day == day } ?: TodayStateEntity(day = day) }

    suspend fun setExpanded(on: Boolean) {
        val d = day()
        val s = dao.todayStateOnce()?.takeIf { it.day == d } ?: TodayStateEntity(day = d)
        dao.saveTodayState(s.copy(expanded = on))
    }

    suspend fun setEnergy(energy: String) {
        val d = day()
        val s = dao.todayStateOnce()?.takeIf { it.day == d } ?: TodayStateEntity(day = d)
        dao.saveTodayState(s.copy(energy = energy))
    }

    /**
     * One plain event for the weekly review (core round 4): what_now_pick, what_now_skip, stuck (+ its reason word),
     * micro_done, today_reorder, cal_move. Names and fixed words only, never free text; it rides the KPI post.
     */
    suspend fun event(name: String, reason: String? = null) {
        require(name.matches(EVENT_WORD) && (reason == null || reason.matches(EVENT_WORD))) { "events are fixed words" }
        val now = clock()
        val r = if (reason == null) "" else ",\"reason\":\"$reason\""
        dao.addToOutbox(OutboxEntity(taskId = "kpi", op = OutboxEntity.KPI, createdAt = now,
            body = """{"method":"POST","path":"/api/v1/kpi","body":{"day":"${day()}","event":"$name"$r}}"""))
        scheduler.kick()
    }

    /** Boss saw the "could not save" notice: the refused change leaves the outbox. */
    suspend fun acknowledge(op: OutboxEntity) {
        dao.removeFromOutbox(op.id)
        dao.markSyncedIfIdle(op.taskId)
    }
}

/** What one pass of the outbox ended with. */
enum class SendResult { ALL_SENT, TRY_LATER, SIGNED_OUT }

/**
 * Sends waiting changes to pagel.uz in the order they were made. Stops at the first network or server failure so
 * the order holds (WorkManager retries with backoff); a refusal (4xx) rolls that one change back and goes on.
 * A delete inside its Undo window holds only its own task's later changes, never the whole queue.
 */
class OutboxSender(
    private val api: PagelApi,
    private val dao: PagelDao,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Sends the Inbox's choices (Phase 2); without it they wait. */
    private val inbox: InboxSender? = null,
    /** Sends the rituals and the dumps for the AI (Phase 5); without it they wait. */
    private val rituals: org.pagel.ai.data.rituals.RitualSender? = null,
) {
    suspend fun sendAll(account: Account): SendResult {
        val held = mutableSetOf<String>()
        // A dump waits for the AI 10-30 s: it goes after everything else, so a tick made meanwhile is never behind it.
        val sweeps = mutableListOf<OutboxEntity>()
        var later = false
        // Inbox choices go in the order they were made: one held (a Drop inside its Undo) holds the ones after it.
        var sortsHeld = false
        for (op in dao.waitingOutbox()) {
            if (op.taskId in held) continue
            if (op.op == OutboxEntity.SORT || op.op == OutboxEntity.CAPTURE) {
                if (sortsHeld) continue
                val sender = inbox ?: continue
                when (sender.send(account, op)) {
                    SortStep.SENT -> {}
                    SortStep.HOLD -> { sortsHeld = true; held += op.taskId }
                    SortStep.SIGNED_OUT -> return SendResult.SIGNED_OUT
                    SortStep.TRY_LATER -> return SendResult.TRY_LATER
                }
                continue
            }
            if (op.op == OutboxEntity.SWEEP) { sweeps += op; continue }
            if (op.op == OutboxEntity.RITUAL) {
                val sender = rituals ?: continue
                when (sender.send(account, op)) {
                    org.pagel.ai.data.rituals.RitualStep.SENT -> {}
                    org.pagel.ai.data.rituals.RitualStep.LATER -> later = true
                    org.pagel.ai.data.rituals.RitualStep.SIGNED_OUT -> return SendResult.SIGNED_OUT
                    org.pagel.ai.data.rituals.RitualStep.TRY_LATER -> return SendResult.TRY_LATER
                }
                continue
            }
            if (op.op == OutboxEntity.PROJECT || op.op == OutboxEntity.KPI) {
                when (sendRaw(account, op)) {
                    Patch.SENT -> {}
                    Patch.HOLD -> held += op.taskId
                    Patch.SIGNED_OUT -> return SendResult.SIGNED_OUT
                    Patch.TRY_LATER -> return SendResult.TRY_LATER
                }
                continue
            }
            if (op.op == OutboxEntity.PATCH) {
                when (sendPatch(account, op)) {
                    Patch.SENT -> {}
                    Patch.HOLD -> held += op.taskId
                    Patch.SIGNED_OUT -> return SendResult.SIGNED_OUT
                    Patch.TRY_LATER -> return SendResult.TRY_LATER
                }
                continue
            }
            dao.countAttempt(op.id)
            if (op.op == OutboxEntity.ADD) {
                // An add renames (or drops) the changes queued after it, so the rest is read again, fresh.
                return when (val r = sendAdd(account, op)) {
                    SendResult.ALL_SENT -> sendAll(account)
                    else -> r
                }
            }
            // pagel.uz unstars a task when it is ticked, and a sync may already have copied that. A task on Today with no
            // date of today or earlier was there only by its star, so an Undo gives the star back and it returns.
            val star = if (op.op == OutboxEntity.UNDONE) dao.taskOnce(op.taskId)?.let { t -> if (t.due.isNullOrBlank() || t.due.take(10) > tashkentDay()) true else null } else null
            when (val out = api.setDone(account.serverUrl, account.token, op.taskId, op.op == OutboxEntity.DONE, star)) {
                is Outcome.Ok -> {
                    dao.removeFromOutbox(op.id)
                    dao.markSyncedIfIdle(op.taskId)
                }
                is Outcome.Failed -> when (val e = out.error) {
                    is DataError.Refused -> {
                        // Roll the phone back to what pagel.uz still has, then keep the row so the UI can say why.
                        val back = if (op.op == OutboxEntity.DONE) "open" else "done"
                        dao.setStatusLocal(op.taskId, back, null, clock())
                        dao.markRefused(op.id, e.message)
                    }
                    DataError.Unauthorized -> return SendResult.SIGNED_OUT
                    else -> return SendResult.TRY_LATER
                }
            }
        }
        for (op in sweeps) {
            val sender = rituals ?: break
            when (sender.split(account, op)) {
                org.pagel.ai.data.rituals.RitualStep.SENT -> {}
                // pagel.uz's AI is busy with another dump: this one is read on a later pass.
                org.pagel.ai.data.rituals.RitualStep.LATER -> later = true
                org.pagel.ai.data.rituals.RitualStep.SIGNED_OUT -> return SendResult.SIGNED_OUT
                org.pagel.ai.data.rituals.RitualStep.TRY_LATER -> return SendResult.TRY_LATER
            }
        }
        // A delete still inside its Undo window: come back for it (WorkManager's first retry is ~10 s later).
        return if (held.isEmpty() && !later) SendResult.ALL_SENT else SendResult.TRY_LATER
    }

    private enum class Patch { SENT, HOLD, SIGNED_OUT, TRY_LATER }

    /**
     * One panel edit. Sent → gone from the outbox, unless it carried a new field the server did not name in `applied`
     * (an older pagel.uz): then it stays, marked as waiting for the server, never rolled back. Refused → the phone
     * goes back to `was` and the row stays so the shell can say why.
     */
    private suspend fun sendPatch(account: Account, op: OutboxEntity): Patch {
        val set = sentOf(op) ?: run { dao.removeFromOutbox(op.id); return Patch.SENT }
        if ("remove" in set && clock() - op.createdAt < UNDO_WINDOW_MS) return Patch.HOLD
        // Still the phone's own id: its add has not reached pagel.uz yet (it goes first and renames this op).
        if (op.taskId.startsWith(LOCAL_ID)) return Patch.HOLD
        dao.countAttempt(op.id)
        return when (val out = api.patchTask(account.serverUrl, account.token, op.taskId, set)) {
            is Outcome.Ok -> {
                val understood = out.value.applied
                if (understood == null && set.keys.any { it in NEW_FIELDS }) {
                    if (!op.waitsForServer) dao.markWaitsForServer(op.id)
                } else {
                    dao.removeFromOutbox(op.id)
                    dao.markSyncedIfIdle(op.taskId)
                }
                Patch.SENT
            }
            is Outcome.Failed -> when (val e = out.error) {
                is DataError.Refused -> {
                    wasOf(op)?.let { dao.applyWas(op.taskId, it, clock()) }
                    dao.markRefused(op.id, e.message)
                    Patch.SENT
                }
                DataError.Unauthorized -> Patch.SIGNED_OUT
                else -> Patch.TRY_LATER
            }
        }
    }

    /**
     * A project change or a use count: `{"method", "path", "body", "hold"?}`. A delete or a merge holds for its Undo
     * window. Refused → the row stays refused (the shell says why) and the next sync puts pagel.uz's copy back, since a
     * refused op no longer pins the project (PagelDao.pendingProjects).
     */
    private suspend fun sendRaw(account: Account, op: OutboxEntity): Patch {
        val o = op.body?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }.getOrNull() }
            ?: run { dao.removeFromOutbox(op.id); return Patch.SENT }
        val hold = (o["hold"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        if (hold && clock() - op.createdAt < UNDO_WINDOW_MS) return Patch.HOLD
        val method = (o["method"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "POST"
        val path = (o["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: run { dao.removeFromOutbox(op.id); return Patch.SENT }
        dao.countAttempt(op.id)
        return when (val out = api.send(account.serverUrl, account.token, method, path, (o["body"] ?: kotlinx.serialization.json.JsonObject(emptyMap())).toString())) {
            is Outcome.Ok -> { dao.removeFromOutbox(op.id); Patch.SENT }
            is Outcome.Failed -> when (val e = out.error) {
                is DataError.Refused -> { dao.markRefused(op.id, e.message); Patch.SENT }
                DataError.Unauthorized -> Patch.SIGNED_OUT
                else -> Patch.TRY_LATER
            }
        }
    }

    private fun tashkentDay() = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.of("Asia/Tashkent")).toLocalDate().toString()

    /** One new task: created → it takes the server's id; refused → it leaves the phone and the UI says why. */
    private suspend fun sendAdd(account: Account, op: OutboxEntity): SendResult {
        val t = dao.taskOnce(op.taskId) ?: run { dao.removeFromOutbox(op.id); return SendResult.ALL_SENT }
        return when (val out = api.addTask(account.serverUrl, account.token, NewTaskBody(t.project, t.text, t.priority, t.due, t.every, clientId = op.taskId, group = t.habitGroup))) {
            is Outcome.Ok -> {
                dao.adoptServerId(op.taskId, out.value.id)
                dao.removeFromOutbox(op.id)
                dao.markSyncedIfIdle(out.value.id)
                SendResult.ALL_SENT
            }
            is Outcome.Failed -> when (val e = out.error) {
                is DataError.Refused -> {
                    dao.dropLocalTask(op.taskId, keep = op.id)
                    dao.markRefused(op.id, e.message)
                    SendResult.ALL_SENT
                }
                DataError.Unauthorized -> SendResult.SIGNED_OUT
                else -> SendResult.TRY_LATER
            }
        }
    }
}
