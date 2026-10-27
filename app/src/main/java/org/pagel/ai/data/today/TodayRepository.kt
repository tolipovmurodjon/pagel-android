package org.pagel.ai.data.today

import kotlinx.coroutines.flow.Flow
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.api.NewTaskBody
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.db.LOCAL_ID
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.SyncStatus
import org.pagel.ai.data.db.TaskEntity

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

    suspend fun tick(id: String, done: Boolean) {
        val now = clock()
        dao.setStatusLocal(id, if (done) "done" else "open", if (done) java.time.Instant.ofEpochMilli(now).toString() else null, now)
        // Undo before the tick left the phone: take the waiting change back; pagel.uz never saw it.
        if (!done && dao.takeBackWaiting(id) > 0) {
            dao.markSyncedIfIdle(id)
            return
        }
        dao.addToOutbox(OutboxEntity(taskId = id, op = if (done) OutboxEntity.DONE else OutboxEntity.UNDONE, createdAt = now))
        scheduler.kick()
    }

    /**
     * A new task (Add task fast): it is on the phone at once, on Today when [onToday], and waits in the outbox; once sent
     * it takes pagel.uz's id. Returns the phone's id for it.
     */
    suspend fun add(text: String, project: String, priority: Int, due: String?, every: String?, onToday: Boolean): String {
        val now = clock()
        val id = LOCAL_ID + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        dao.upsertTasks(listOf(TaskEntity(
            id = id, project = project, phase = "", sort = Double.MAX_VALUE, text = text, title = null, status = "open",
            priority = priority, star = false, due = due, every = every, doneWhen = null, doneAt = null,
            updatedAt = java.time.Instant.ofEpochMilli(now).toString(), serverVersion = 0, syncStatus = SyncStatus.PENDING, lastModified = now,
        )))
        if (onToday) dao.appendToToday(id)
        dao.addToOutbox(OutboxEntity(taskId = id, op = OutboxEntity.ADD, createdAt = now))
        scheduler.kick()
        return id
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
 */
class OutboxSender(private val api: PagelApi, private val dao: PagelDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun sendAll(account: Account): SendResult {
        for (op in dao.waitingOutbox()) {
            dao.countAttempt(op.id)
            if (op.op == OutboxEntity.ADD) {
                // An add renames (or drops) the changes queued after it, so the rest is read again, fresh.
                return when (val r = sendAdd(account, op)) {
                    SendResult.ALL_SENT -> sendAll(account)
                    else -> r
                }
            }
            when (val out = api.setDone(account.serverUrl, account.token, op.taskId, op.op == OutboxEntity.DONE)) {
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
        return SendResult.ALL_SENT
    }

    /** One new task: created → it takes the server's id; refused → it leaves the phone and the UI says why. */
    private suspend fun sendAdd(account: Account, op: OutboxEntity): SendResult {
        val t = dao.taskOnce(op.taskId) ?: run { dao.removeFromOutbox(op.id); return SendResult.ALL_SENT }
        return when (val out = api.addTask(account.serverUrl, account.token, NewTaskBody(t.project, t.text, t.priority, t.due, t.every))) {
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
