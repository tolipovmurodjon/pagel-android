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
        if (full) {
            deleteAllTasks()
            deleteAllSteps()
        } else {
            removed.chunked(MAX_IDS).forEach { deleteTasks(it); deleteStepsOf(it) }
            tasks.map { it.id }.chunked(MAX_IDS).forEach { deleteStepsOf(it) }
        }
        upsertTasks(tasks)
        upsertSteps(steps)
        saveSyncState(state)
    }

}
