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
    @Query("SELECT * FROM projects ORDER BY sort")
    abstract fun projects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM tasks WHERE project = :project AND status = 'open' ORDER BY sort")
    abstract fun openTasks(project: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    abstract fun task(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY position")
    abstract fun steps(taskId: String): Flow<List<StepEntity>>

    /** Today in the server's order; a just-ticked task stays in the list (status done) so the screen can fold it. */
    @Query("SELECT t.* FROM today_order o JOIN tasks t ON t.id = o.taskId ORDER BY o.position")
    abstract fun todayTasks(): Flow<List<TaskEntity>>

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
        renameInOutbox(old, new)
        deleteTasks(listOf(old))
        upsertTasks(listOf(local.copy(id = new)))
        dropOrderIfServerHasIt(old, new)
        renameInToday(old, new)
    }

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

    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun removeFromOutbox(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    abstract suspend fun countAttempt(id: Long)

    @Query("UPDATE outbox SET refused = :message WHERE id = :id")
    abstract suspend fun markRefused(id: Long, message: String)

    /** Undo before it was sent: the waiting change is simply taken back. Returns how many rows went. */
    @Query("DELETE FROM outbox WHERE taskId = :taskId AND refused IS NULL AND op != '${OutboxEntity.ADD}'")
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

    @Query("DELETE FROM steps")
    abstract suspend fun deleteAllSteps()

    /**
     * Applies one server answer in a single transaction, so the UI never sees half a sync. A full copy replaces
     * everything (cleared, not "delete where id not in": Android 11's SQLite takes at most 999 ids in one query);
     * a delta replaces only the tasks it names and deletes the ones the server no longer has.
     */
    @Transaction
    open suspend fun applySync(full: Boolean, projects: List<ProjectEntity>, tasks: List<TaskEntity>, steps: List<StepEntity>, removed: List<String>, state: SyncStateEntity) {
        upsertProjects(projects)
        deleteProjectsExcept(projects.map { it.name })
        // Conflict rule (RULES.md): a task with a tick still waiting in the outbox keeps the phone's version until
        // pagel.uz has it; the server's copy wins on the next sync after that.
        val pending = pendingIds().toSet()
        val incoming = tasks.filter { it.id !in pending }
        if (full) {
            deleteSyncedTasks()
            deleteAllSteps()
        } else {
            removed.chunked(MAX_IDS).forEach { deleteTasks(it); deleteStepsOf(it) }
            tasks.map { it.id }.chunked(MAX_IDS).forEach { deleteStepsOf(it) }
        }
        upsertTasks(incoming)
        upsertSteps(steps)
        saveSyncState(state)
    }

}
