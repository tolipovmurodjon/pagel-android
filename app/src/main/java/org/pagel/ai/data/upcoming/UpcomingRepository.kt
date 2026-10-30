package org.pagel.ai.data.upcoming

import kotlinx.coroutines.flow.Flow
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.sync.stepEntities
import org.pagel.ai.data.sync.toEntity
import org.pagel.ai.data.tasks.TaskRepository
import java.time.Instant
import java.time.LocalDate

/** A date change made here, for its Undo: the task, its date before, and the outbox row that carries it. */
data class Moved(val id: String, val was: String?, val op: Long)

/** A finished task cleared from Completed, for its Undo: the task and the outbox row that carries its remove. */
data class Cleared(val id: String, val op: Long)

/**
 * Upcoming, Overdue and Completed (Phase 4, the web's /upcoming and /completed). Everything reads Room; every change is
 * a task-panel edit ([TaskRepository]: Room first, then one outbox PATCH, sent in the order made, offline too).
 * Undo takes a change back while it still waits on the phone, else sends the opposite change.
 * Conflict rule (TaskRepository's): a task with a change waiting keeps the phone's copy; once sent, pagel.uz wins.
 */
class UpcomingRepository(
    private val dao: PagelDao,
    private val api: PagelApi,
    private val tasks: TaskRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val dated: Flow<List<TaskEntity>> = dao.datedOpen()
    val completed: Flow<List<TaskEntity>> = dao.completed()

    fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(TASHKENT).toLocalDate()

    /**
     * A task dropped on another day: the day changes, its time stays. Null when it was already there, or when it is a
     * habit: a habit's due is its series (Repeat.datesBetween counts from it), so a new due would move every later day,
     * and on pagel.uz a due of today or earlier undoes today's tick (task-store.ts `habit_undone`). Habits owns habits.
     */
    suspend fun reschedule(id: String, day: LocalDate): Moved? {
        val t = dao.taskOnce(id) ?: return null
        if (!t.every.isNullOrBlank()) return null
        val due = movedTo(t.due, day)
        if (due == t.due) return null
        val op = tasks.due(id, due) ?: return null
        return Moved(id, t.due, op)
    }

    /**
     * A calendar block dropped on another time or day (round C): the new due as [droppedDue] made it. A habit is never
     * moved here (its due is its series, as [reschedule] says).
     */
    suspend fun moveTo(id: String, due: String): Moved? {
        val t = dao.taskOnce(id) ?: return null
        if (!t.every.isNullOrBlank() || due == t.due) return null
        val op = tasks.due(id, due) ?: return null
        return Moved(id, t.due, op)
    }

    /** Overdue → today, each keeping its time, in the order shown (oldest first). */
    suspend fun moveAllToToday(ids: List<String>): List<Moved> {
        val today = today()
        return ids.mapNotNull { reschedule(it, today) }
    }

    /** Undo of moves: newest first, so a task moved twice ends on its first date. */
    suspend fun undoMoves(moves: List<Moved>) {
        for (m in moves.asReversed()) if (!tasks.takeBack(m.id, m.op)) tasks.due(m.id, m.was)
    }

    /** Done from Upcoming. Returns the op for Undo. */
    suspend fun finish(id: String): Long? = tasks.finish(id)

    suspend fun undoFinish(id: String, op: Long) { if (!tasks.takeBack(id, op)) tasks.reopen(id) }

    /** Completed's untick: open again (pagel.uz's `undone`). Returns the op for Undo. */
    suspend fun reopen(id: String): Long? = tasks.reopen(id)

    suspend fun undoReopen(id: String, op: Long) { if (!tasks.takeBack(id, op)) tasks.finish(id) }

    /**
     * Completed's "Clear done": every finished task leaves Room at once (status dropped, so Completed empties in one
     * frame); each remove waits [org.pagel.ai.data.tasks.UNDO_WINDOW_MS] in the outbox, then pagel.uz gets it.
     */
    suspend fun clearDone(ids: List<String>): List<Cleared> = ids.mapNotNull { id -> tasks.delete(id)?.let { Cleared(id, it) } }

    /** Undo of a clear, newest first: each comes back done (taken back while waiting, else restore + done). */
    suspend fun undoClear(cleared: List<Cleared>) {
        for (c in cleared.asReversed()) tasks.undoDeleteDone(c.id, c.op)
    }

    /**
     * Finished tasks older than /sync's 14 days, newest first ([PAGE] of them), and pagel.uz's count of all of them
     * (null from a pagel.uz without it). Offline, or a pagel.uz without /api/v1/completed yet, it fails silently and
     * Completed shows what Room has.
     */
    suspend fun refreshCompleted(account: Account): Outcome<Int?> =
        when (val r = api.completed(account.serverUrl, account.token, PAGE)) {
            is Outcome.Failed -> r
            is Outcome.Ok -> {
                val now = clock()
                dao.applyCompleted(r.value.tasks.map { it.toEntity(now) }, r.value.tasks.flatMap { it.stepEntities() })
                Outcome.Ok(r.value.total)
            }
        }

    companion object {
        /** The web's /completed shows 200. */
        const val PAGE = 200
    }
}
