package org.pagel.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Ids per DELETE … IN (…): under the 999 variables Android 11's SQLite allows. */
private const val MAX_IDS = 500

@Dao
abstract class PagelDao {
    /** Every project the phone shows (one inside a delete's or a merge's Undo window is hidden). */
    @Query("SELECT * FROM projects WHERE hidden = 0 ORDER BY sort")
    abstract fun projects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE name = :name")
    abstract suspend fun projectOnce(name: String): ProjectEntity?

    @Query("SELECT name FROM projects")
    abstract suspend fun projectNames(): List<String>

    @Query("SELECT * FROM projects ORDER BY sort")
    abstract suspend fun projectsOnce(): List<ProjectEntity>

    @Query("SELECT id FROM tasks WHERE project = :project ORDER BY phaseNow DESC, phaseSort, sort")
    abstract suspend fun taskIdsOf(project: String): List<String>

    /** Projects with a change still waiting in the outbox: they keep the phone's copy until pagel.uz has it. */
    @Query("SELECT DISTINCT substr(taskId, 9) FROM outbox WHERE op = '${OutboxEntity.PROJECT}' AND refused IS NULL")
    abstract suspend fun pendingProjects(): List<String>

    @Query("SELECT id FROM tasks WHERE project = :project AND status = 'open' ORDER BY phaseNow DESC, phaseSort, sort")
    abstract suspend fun openTaskIds(project: String): List<String>

    @Query("UPDATE tasks SET project = :into, lastModified = :at WHERE id IN (:ids)")
    abstract suspend fun moveTasksLocal(ids: List<String>, into: String, at: Long)

    /** A project's open tasks, its NOW phase first, then in plan order (the web's plan page). */
    @Query("SELECT * FROM tasks WHERE project = :project AND status = 'open' ORDER BY phaseNow DESC, phaseSort, sort")
    abstract fun openTasks(project: String): Flow<List<TaskEntity>>

    /** A project page: its open tasks and the ones done lately (sync keeps 14 days of them), in plan order. */
    @Query("SELECT * FROM tasks WHERE project = :project AND status IN ('open', 'done') ORDER BY phaseNow DESC, phaseSort, sort")
    abstract fun projectTasks(project: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY position")
    abstract suspend fun stepsOnce(taskId: String): List<StepEntity>

    /** A task's steps rewritten as one list (the panel always sends the whole list with its ticks). */
    @Transaction
    open suspend fun replaceSteps(taskId: String, steps: List<StepEntity>) {
        deleteStepsOf(listOf(taskId))
        upsertSteps(steps)
    }

    @Query("UPDATE tasks SET syncStatus = 'pending', lastModified = :at WHERE id = :id")
    abstract suspend fun markPending(id: String, at: Long)

    /** Edits of this task an older pagel.uz did not understand yet: the panel shows a cloud-off mark while any wait. */
    @Query("SELECT COUNT(*) FROM outbox WHERE taskId = :taskId AND waitsForServer = 1 AND refused IS NULL")
    abstract fun heldCount(taskId: String): Flow<Int>

    @Query("UPDATE outbox SET waitsForServer = 1 WHERE id = :id")
    abstract suspend fun markWaitsForServer(id: Long)

    /** One waiting change taken back before it left (a delete's Undo inside its 5 s). Returns 1 if it was still here. */
    @Query("DELETE FROM outbox WHERE id = :id AND refused IS NULL")
    abstract suspend fun takeBackOp(id: Long): Int

    @Query("SELECT * FROM outbox WHERE id = :id")
    abstract suspend fun outboxRow(id: Long): OutboxEntity?

    @Query("SELECT COUNT(*) FROM today_order WHERE taskId = :taskId")
    abstract suspend fun onTodayCount(taskId: String): Int

    @Query("SELECT * FROM tasks WHERE id = :id")
    abstract fun task(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY position")
    abstract fun steps(taskId: String): Flow<List<StepEntity>>

    /** Today in the server's order; a just-ticked task stays in the list (status done) so the screen can fold it. */
    @Query("SELECT t.* FROM today_order o JOIN tasks t ON t.id = o.taskId ORDER BY o.position")
    abstract fun todayTasks(): Flow<List<TaskEntity>>

    /** Tasks (not habits) finished since [since] (an ISO instant, the start of Tashkent's day): Today's "2 of 5 ✓". */
    @Query("SELECT COUNT(*) FROM tasks WHERE status = 'done' AND (every IS NULL OR every = '') AND doneAt >= :since")
    abstract fun doneCountSince(since: String): Flow<Int>

    /** Open tasks pinned to Today by their star (not habits): the panel's "Pin to Today" stops at 3, as the web. */
    @Query("SELECT COUNT(*) FROM tasks WHERE star = 1 AND status = 'open' AND (every IS NULL OR every = '')")
    abstract fun starredOpenCount(): Flow<Int>

    @Query("DELETE FROM today_order")
    abstract suspend fun clearTodayOrder()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTodayOrder(rows: List<TodayOrderEntity>)

    /** Tasks on Today with a change still waiting (new ones, ticks, undos), in their order. */
    @Query("SELECT o.taskId FROM today_order o JOIN tasks t ON t.id = o.taskId WHERE t.syncStatus = '${SyncStatus.PENDING}' ORDER BY o.position")
    abstract suspend fun pendingTodayIds(): List<String>

    /**
     * The server's order, plus every task with a waiting change kept at the end: pagel.uz unstars a ticked task, so
     * without this a sync between a tick and its Undo dropped the task from Today for good (Boss 2026-10-27: "Undo did
     * not work").
     */
    @Transaction
    open suspend fun replaceTodayOrder(ids: List<String>) {
        val kept = pendingTodayIds().filter { it !in ids }
        clearTodayOrder()
        insertTodayOrder((ids + kept).mapIndexed { i, id -> TodayOrderEntity(i, id) })
    }

    /** Undo brings a task back to Today even if a sync already took it off. */
    @Query("INSERT INTO today_order(position, taskId) SELECT (SELECT COALESCE(MAX(position), -1) + 1 FROM today_order), :taskId WHERE NOT EXISTS (SELECT 1 FROM today_order WHERE taskId = :taskId)")
    abstract suspend fun ensureOnToday(taskId: String)

    @Query("INSERT INTO today_order(position, taskId) VALUES((SELECT COALESCE(MAX(position), -1) + 1 FROM today_order), :taskId)")
    abstract suspend fun appendToToday(taskId: String)

    @Query("SELECT * FROM tasks WHERE id = :id")
    abstract suspend fun taskOnce(id: String): TaskEntity?

    @Query("UPDATE outbox SET taskId = :new WHERE taskId = :old")
    abstract suspend fun renameInOutbox(old: String, new: String)

    @Query("DELETE FROM today_order WHERE taskId = :old AND EXISTS (SELECT 1 FROM today_order WHERE taskId = :new)")
    abstract suspend fun dropOrderIfServerHasIt(old: String, new: String)

    @Query("UPDATE today_order SET taskId = :new WHERE taskId = :old")
    abstract suspend fun renameInToday(old: String, new: String)

    @Query("DELETE FROM today_order WHERE taskId = :taskId")
    abstract suspend fun removeFromToday(taskId: String)

    @Query("DELETE FROM outbox WHERE taskId = :taskId AND refused IS NULL AND id != :keep")
    abstract suspend fun dropWaitingExcept(taskId: String, keep: Long)

    /**
     * pagel.uz created the task the phone added: the local row takes the server's id everywhere (task, Today, waiting
     * ticks), so a tick made before sending reaches the right task. A sync that already brought the server's row wins
     * its place on Today.
     */
    @Transaction
    open suspend fun adoptServerId(old: String, new: String) {
        val local = taskOnce(old) ?: return
        recordAdopted(AdoptedIdEntity(old, new, System.currentTimeMillis()))
        pruneAdopted(System.currentTimeMillis() - 86_400_000L)
        renameInOutbox(old, new)
        deleteTasks(listOf(old))
        upsertTasks(listOf(local.copy(id = new)))
        dropOrderIfServerHasIt(old, new)
        renameInToday(old, new)
        renameInSweeps(old, new)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun recordAdopted(row: AdoptedIdEntity)

    @Query("DELETE FROM adopted_ids WHERE savedAt < :before")
    abstract suspend fun pruneAdopted(before: Long)

    @Query("SELECT newId FROM adopted_ids WHERE oldId = :id")
    abstract suspend fun adoptedId(id: String): String?

    /** The id a task answers to now: the server's, once pagel.uz renamed the phone's own. */
    open suspend fun currentId(id: String): String = adoptedId(id) ?: id

    /** A dump's added tasks keep their ids once pagel.uz names them, so its Undo finds them (Phase 5). Ids are unique. */
    @Query("UPDATE sweeps SET addedIds = REPLACE(addedIds, :old, :new) WHERE addedIds LIKE '%' || :old || '%'")
    abstract suspend fun renameInSweeps(old: String, new: String)

    /** pagel.uz refused a task the phone added: it leaves the phone (task, Today, its waiting ticks); [keep] says why. */
    @Transaction
    open suspend fun dropLocalTask(taskId: String, keep: Long) {
        deleteTasks(listOf(taskId))
        removeFromToday(taskId)
        dropWaitingExcept(taskId, keep)
    }

    @Query("UPDATE tasks SET status = :status, doneAt = :doneAt, syncStatus = 'pending', lastModified = :at WHERE id = :id")
    abstract suspend fun setStatusLocal(id: String, status: String, doneAt: String?, at: Long)

    @Query("UPDATE tasks SET syncStatus = 'synced' WHERE id = :id AND NOT EXISTS (SELECT 1 FROM outbox WHERE taskId = :id)")
    abstract suspend fun markSyncedIfIdle(id: String)

    @Insert
    abstract suspend fun addToOutbox(row: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE refused IS NULL ORDER BY id")
    abstract suspend fun waitingOutbox(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE refused IS NOT NULL ORDER BY id")
    abstract fun refusedOutbox(): Flow<List<OutboxEntity>>

    /** Changes still waiting to reach pagel.uz (Today's "↑ 2 waiting" chip). */
    @Query("SELECT COUNT(*) FROM outbox WHERE refused IS NULL")
    abstract fun waitingCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun removeFromOutbox(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    abstract suspend fun countAttempt(id: Long)

    @Query("UPDATE outbox SET refused = :message WHERE id = :id")
    abstract suspend fun markRefused(id: Long, message: String)

    /**
     * Undo of a tick before it was sent: the waiting tick is simply taken back. Only ticks: a panel edit waiting for the
     * same task stays. Returns how many rows went.
     */
    @Query("DELETE FROM outbox WHERE taskId = :taskId AND refused IS NULL AND op IN ('${OutboxEntity.DONE}', '${OutboxEntity.UNDONE}')")
    abstract suspend fun takeBackWaiting(taskId: String): Int

    @Query("SELECT id FROM tasks WHERE syncStatus = 'pending'")
    abstract suspend fun pendingIds(): List<String>

    @Query("DELETE FROM tasks WHERE syncStatus != 'pending'")
    abstract suspend fun deleteSyncedTasks()

    @Query("SELECT COUNT(*) FROM tasks")
    abstract suspend fun taskCount(): Int

    @Query("SELECT * FROM sync_state WHERE id = '${SyncStateEntity.ID}'")
    abstract suspend fun syncState(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertProjects(rows: List<ProjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertTasks(rows: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSteps(rows: List<StepEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveSyncState(row: SyncStateEntity)

    @Query("DELETE FROM projects WHERE name NOT IN (:keep)")
    abstract suspend fun deleteProjectsExcept(keep: List<String>)

    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    abstract suspend fun deleteTasks(ids: List<String>)

    @Query("DELETE FROM steps WHERE taskId IN (:taskIds)")
    abstract suspend fun deleteStepsOf(taskIds: List<String>)

    @Query("DELETE FROM tasks")
    abstract suspend fun deleteAllTasks()

    /** Every step except those of a task with a change still waiting (its steps are the phone's until sent). */
    @Query("DELETE FROM steps WHERE taskId NOT IN (SELECT id FROM tasks WHERE syncStatus = 'pending')")
    abstract suspend fun deleteSyncedSteps()

    /**
     * Applies one server answer in a single transaction, so the UI never sees half a sync. A full copy replaces
     * everything (cleared, not "delete where id not in": Android 11's SQLite takes at most 999 ids in one query);
     * a delta replaces only the tasks it names and deletes the ones the server no longer has.
     */
    @Transaction
    open suspend fun applySync(full: Boolean, projects: List<ProjectEntity>, tasks: List<TaskEntity>, steps: List<StepEntity>, removed: List<String>, state: SyncStateEntity) {
        // A project with a change still waiting keeps the phone's copy (the same rule as tasks, below).
        val mine = pendingProjects().toSet()
        upsertProjects(projects.filter { it.name !in mine })
        deleteProjectsExcept(projects.map { it.name } + mine)
        // Conflict rule (RULES.md): a task with a tick still waiting in the outbox keeps the phone's version until
        // pagel.uz has it; the server's copy wins on the next sync after that.
        val pending = pendingIds().toSet()
        val incoming = tasks.filter { it.id !in pending }
        if (full) {
            deleteSyncedSteps()
            deleteSyncedTasks()
        } else {
            removed.chunked(MAX_IDS).forEach { deleteTasks(it); deleteStepsOf(it) }
            incoming.map { it.id }.chunked(MAX_IDS).forEach { deleteStepsOf(it) }
        }
        upsertTasks(incoming)
        upsertSteps(steps.filter { it.taskId !in pending })
        saveSyncState(state)
    }

    // ── Upcoming · Completed · Overdue (Phase 4) ──────────────────────────────────────────────────────────────────

    /** Open tasks with a date (the web's upcoming + overdue read only dated tasks), habits included. */
    @Query("SELECT * FROM tasks WHERE status = 'open' AND due IS NOT NULL AND due != ''")
    abstract fun datedOpen(): Flow<List<TaskEntity>>

    /** Finished tasks, newest first (the web's /completed). */
    @Query("SELECT * FROM tasks WHERE status = 'done' AND doneAt IS NOT NULL ORDER BY doneAt DESC")
    abstract fun completed(): Flow<List<TaskEntity>>

    /**
     * A page of finished tasks from `GET /api/v1/completed` (older than /sync's 14 days): added or refreshed, never
     * deleting anything, and never over a task with a change still waiting (the same rule as [applySync]).
     */
    @Transaction
    open suspend fun applyCompleted(tasks: List<TaskEntity>, steps: List<StepEntity>) {
        val pending = pendingIds().toSet()
        val incoming = tasks.filter { it.id !in pending }
        incoming.map { it.id }.chunked(MAX_IDS).forEach { deleteStepsOf(it) }
        upsertTasks(incoming)
        upsertSteps(steps.filter { it.taskId !in pending })
    }

    // ── Habits (Phase 3) ──────────────────────────────────────────────────────────────────────────────────────────

    /** Open habits: repeating tasks, as the web's habitsView reads them (views.ts). */
    @Query("SELECT * FROM tasks WHERE every IS NOT NULL AND every != '' AND status = 'open'")
    abstract fun habits(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE every IS NOT NULL AND every != '' AND status = 'open'")
    abstract suspend fun habitsOnce(): List<TaskEntity>

    @Query("SELECT * FROM habit_ticks WHERE day >= :since")
    abstract fun habitTicks(since: String): Flow<List<HabitTickEntity>>

    @Query("SELECT * FROM habit_ticks WHERE taskId = :taskId")
    abstract suspend fun habitTicksOf(taskId: String): List<HabitTickEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addHabitTick(row: HabitTickEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addHabitTicks(rows: List<HabitTickEntity>)

    /** A day's taps of a count habit (n), written over the old row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setHabitTick(row: HabitTickEntity)

    @Query("DELETE FROM habit_ticks WHERE day = :day AND taskId = :taskId")
    abstract suspend fun deleteHabitTick(day: String, taskId: String)

    /**
     * pagel.uz's ticks replace the phone's, except for a habit with a change still waiting in the outbox: its ticks
     * stay the phone's until pagel.uz has the change (the same rule as [applySync]).
     */
    @Transaction
    open suspend fun replaceHabitTicks(rows: List<HabitTickEntity>, keep: Set<String> = emptySet()) {
        val waiting = waitingOutbox().map { it.taskId }.toSet() + keep
        deleteSettledHabitTicksExcept(keep.toList())
        addHabitTicks(rows.filter { it.taskId !in waiting })
    }

    @Query("DELETE FROM habit_ticks WHERE taskId NOT IN (SELECT taskId FROM outbox WHERE refused IS NULL) AND taskId NOT IN (:keep)")
    abstract suspend fun deleteSettledHabitTicksExcept(keep: List<String>)

    /** A habit's tick as one write: the new date, the day counted and the outbox row, so no screen sees half of it. */
    @Transaction
    open suspend fun tickHabit(task: TaskEntity, tick: HabitTickEntity, op: OutboxEntity): Long {
        upsertTasks(listOf(task))
        if (tick.n > 0) setHabitTick(tick) else deleteHabitTick(tick.day, tick.taskId)
        return addToOutbox(op)
    }

    @Query("SELECT * FROM habit_reminders")
    abstract fun reminders(): Flow<List<HabitReminderEntity>>

    @Query("SELECT * FROM habit_reminders")
    abstract suspend fun remindersOnce(): List<HabitReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setReminder(row: HabitReminderEntity)
}
