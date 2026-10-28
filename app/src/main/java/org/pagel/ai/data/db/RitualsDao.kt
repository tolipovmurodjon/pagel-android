package org.pagel.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** The rituals' tables (Phase 5): the dumps of "Empty your head" and the rituals' state. */
@Dao
abstract class RitualsDao {
    /** Every open task: Close the day and the weekly review read their lists from these. */
    @Query("SELECT * FROM tasks WHERE status = 'open'")
    abstract fun openTasks(): Flow<List<TaskEntity>>

    /** The waiting add of a task made on the phone (its Undo takes it back before it leaves). */
    @Query("SELECT id FROM outbox WHERE taskId = :taskId AND op = '${OutboxEntity.ADD}' AND refused IS NULL LIMIT 1")
    abstract suspend fun waitingAdd(taskId: String): Long?

    /** A task the phone added, under the id pagel.uz gave it (adoptServerId keeps its project and words). */
    @Query("SELECT id FROM tasks WHERE project = :project AND text = :text AND status = 'open' AND id NOT LIKE '${LOCAL_ID}%' ORDER BY lastModified DESC LIMIT 1")
    abstract suspend fun adoptedId(project: String, text: String): String?

    /** The dump on screen: the newest one not yet added (a draft, waiting, reading, ready or failed). */
    @Query("SELECT * FROM sweeps WHERE state != '${SweepEntity.ADDED}' ORDER BY createdAt DESC LIMIT 1")
    abstract fun openSweep(): Flow<SweepEntity?>

    @Query("SELECT * FROM sweeps WHERE id = :id")
    abstract fun sweep(id: String): Flow<SweepEntity?>

    @Query("SELECT * FROM sweeps WHERE id = :id")
    abstract suspend fun sweepOnce(id: String): SweepEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSweep(row: SweepEntity)

    @Query("UPDATE sweeps SET state = :state, message = :message, updatedAt = :at WHERE id = :id")
    abstract suspend fun setSweepState(id: String, state: String, message: String?, at: Long)

    /** The AI's rows arrived: kept only if Boss has not moved on (the dump is still waiting or being read). */
    @Query("UPDATE sweeps SET state = '${SweepEntity.READY}', rows = :rows, message = NULL, updatedAt = :at WHERE id = :id AND state IN ('${SweepEntity.WAITING}', '${SweepEntity.READING}')")
    abstract suspend fun sweepRead(id: String, rows: String, at: Long): Int

    /** Old added dumps: their Undo is long gone. */
    @Query("DELETE FROM sweeps WHERE state = '${SweepEntity.ADDED}' AND updatedAt < :before")
    abstract suspend fun pruneAdded(before: Long)

    @Query("DELETE FROM sweeps WHERE id = :id")
    abstract suspend fun deleteSweep(id: String)

    /** A dump and its outbox row as one write, so the sender never finds one without the other. */
    @Transaction
    open suspend fun queueSweep(row: SweepEntity, op: OutboxEntity) {
        upsertSweep(row)
        addOp(op)
    }

    @Insert
    abstract suspend fun addOp(row: OutboxEntity): Long

    @Query("DELETE FROM outbox WHERE op = '${OutboxEntity.SWEEP}' AND taskId = :id AND refused IS NULL")
    abstract suspend fun takeBackSweep(id: String): Int

    @Query("SELECT * FROM ritual_state WHERE id = '${RitualStateEntity.ID}'")
    abstract fun state(): Flow<RitualStateEntity?>

    @Query("SELECT * FROM ritual_state WHERE id = '${RitualStateEntity.ID}'")
    abstract suspend fun stateOnce(): RitualStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveState(row: RitualStateEntity)

    @Query("DELETE FROM outbox WHERE id = :id AND refused IS NULL")
    abstract suspend fun takeBackOp(id: Long): Int

    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun removeFromOutbox(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    abstract suspend fun countAttempt(id: Long)

    @Query("UPDATE outbox SET refused = :message WHERE id = :id")
    abstract suspend fun markRefused(id: Long, message: String)
}
