package org.pagel.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The Inbox on the phone (Phase 2). A choice changes Room at once (the card leaves, the ring moves) and waits in the
 * shared outbox as a [OutboxEntity.SORT] row; the sender writes the answer back here: sent → the card is gone, refused
 * → it comes back on top and the counts step back.
 */
@Dao
abstract class InboxDao {
    /** The cards still to sort, top first. */
    @Query("SELECT * FROM inbox_items WHERE sortedAs IS NULL ORDER BY position")
    abstract fun deck(): Flow<List<InboxItemEntity>>

    @Query("SELECT * FROM inbox_state WHERE id = '${InboxStateEntity.ID}'")
    abstract fun state(): Flow<InboxStateEntity?>

    @Query("SELECT * FROM inbox_state WHERE id = '${InboxStateEntity.ID}'")
    abstract suspend fun stateOnce(): InboxStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveState(row: InboxStateEntity)

    @Query("SELECT * FROM inbox_items WHERE file = :file")
    abstract suspend fun item(file: String): InboxItemEntity?

    @Query("SELECT * FROM inbox_items WHERE sortedAs IS NULL ORDER BY position LIMIT 1")
    abstract suspend fun topOnce(): InboxItemEntity?

    @Query("SELECT COUNT(*) FROM inbox_items WHERE sortedAs IS NULL")
    abstract suspend fun deckSize(): Int

    /** Cards sorted here whose choice has not reached pagel.uz yet: still counted in its total. */
    @Query("SELECT COUNT(*) FROM inbox_items WHERE sortedAs IS NOT NULL")
    abstract fun leavingCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(position), 0) FROM inbox_items")
    abstract suspend fun lastPosition(): Int

    @Query("SELECT COALESCE(MIN(position), 0) FROM inbox_items")
    abstract suspend fun firstPosition(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(rows: List<InboxItemEntity>)

    @Query("DELETE FROM inbox_items WHERE file = :file")
    abstract suspend fun delete(file: String)

    @Query("DELETE FROM inbox_items WHERE file NOT IN (:keep)")
    abstract suspend fun deleteExcept(keep: List<String>)

    @Query("UPDATE inbox_items SET sortedAs = :action WHERE file = :file")
    abstract suspend fun markSorted(file: String, action: String?)

    @Query("UPDATE inbox_items SET later = :later, position = :position WHERE file = :file")
    abstract suspend fun putBack(file: String, later: String?, position: Int)

    /** Files whose choice still waits in the outbox (not refused). */
    @Query("SELECT taskId FROM outbox WHERE op = '${OutboxEntity.SORT}' AND refused IS NULL")
    abstract suspend fun waitingFiles(): List<String>

    @Query("SELECT body FROM outbox WHERE op = '${OutboxEntity.SORT}' AND refused IS NULL ORDER BY id")
    abstract suspend fun waitingBodies(): List<String?>

    @Insert
    abstract suspend fun addToOutbox(row: OutboxEntity): Long

    @Query("DELETE FROM outbox WHERE id = :id AND refused IS NULL")
    abstract suspend fun takeBack(id: Long): Int

    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun removeFromOutbox(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    abstract suspend fun countAttempt(id: Long)

    @Query("UPDATE outbox SET refused = :message WHERE id = :id")
    abstract suspend fun markRefused(id: Long, message: String)

    /**
     * Boss's choice, on the phone at once: the card leaves the deck (Later sends it to the back, once), the day's counts
     * move, and the choice waits in the outbox. Returns the outbox row, for Drop's Undo.
     */
    @Transaction
    open suspend fun choose(file: String, action: String, body: String, today: String, keep: Boolean, at: Long): Long {
        if (action == LATER) putBack(file, today, lastPosition() + 1) else markSorted(file, action)
        stateOnce()?.let { s ->
            val fresh = s.day != today
            saveState(s.copy(day = today, sorted = (if (fresh) 0 else s.sorted) + 1, keptToday = (if (fresh) 0 else s.keptToday) + if (keep) 1 else 0))
        }
        return addToOutbox(OutboxEntity(taskId = file, op = OutboxEntity.SORT, createdAt = at, body = body))
    }

    /** Undo before the choice left the phone: the card is back on top. False if it was already sent. */
    @Transaction
    open suspend fun undo(opId: Long, file: String, keep: Boolean): Boolean {
        if (takeBack(opId) == 0) return false
        markSorted(file, null)
        stepBack(keep)
        return true
    }

    /**
     * pagel.uz took the choice. A Later stays in the deck (at the back); any other choice ends the card. The counts
     * follow the server's, plus the choices still waiting behind this one.
     */
    @Transaction
    open suspend fun sent(opId: Long, file: String, action: String, sorted: Int?, keptToday: Int?) {
        removeFromOutbox(opId)
        if (action != LATER) delete(file)
        val s = stateOnce() ?: return
        val waiting = waitingBodies()
        val waitingKeeps = waiting.count { it?.contains("\"action\":\"keep\"") == true }
        saveState(s.copy(
            sorted = sorted?.let { it + waiting.size } ?: s.sorted,
            keptToday = keptToday?.let { it + waitingKeeps } ?: s.keptToday,
            total = if (action != LATER) (s.total - 1).coerceAtLeast(0) else s.total,
        ))
    }

    /** pagel.uz refused the choice: the card comes back on top (a refused Later just loses its mark), [message] says why. */
    @Transaction
    open suspend fun refused(opId: Long, file: String, action: String, message: String) {
        val item = item(file)
        if (item != null) {
            if (action == LATER) putBack(file, null, item.position) else {
                markSorted(file, null)
                putBack(file, item.later, firstPosition() - 1)
            }
        }
        stepBack(action == KEEP)
        markRefused(opId, message)
    }

    /**
     * A fresh deck from pagel.uz. The card on top stays on top (it is on screen), cards whose choice is still on its way
     * stay as they are, and the rest takes the server's order behind them. Counts: the server's plus what still waits.
     */
    @Transaction
    open suspend fun refresh(items: List<InboxItemEntity>, state: InboxStateEntity) {
        val waiting = waitingFiles().toSet()
        val top = topOnce()?.takeIf { it.file !in waiting }
        val keepFiles = waiting + listOfNotNull(top?.file)
        deleteExcept(keepFiles.toList().ifEmpty { listOf("") })
        top?.let { t -> upsert(listOf((items.firstOrNull { it.file == t.file } ?: t).copy(position = 0, sortedAs = null))) }
        val fresh = items.filter { it.file !in keepFiles }
        upsert(fresh.mapIndexed { i, it -> it.copy(position = i + 1) })
        // A Later still on its way keeps its place at the back.
        var at = fresh.size + 1
        for (f in waiting) item(f)?.takeIf { it.sortedAs == null }?.let { putBack(it.file, it.later, at++) }
        val bodies = waitingBodies()
        saveState(state.copy(sorted = state.sorted + bodies.size, keptToday = state.keptToday + bodies.count { it?.contains("\"action\":\"keep\"") == true }))
    }
}

private const val LATER = "later"
private const val KEEP = "keep"

/** A choice taken back or refused: the day's counts step back by one. */
private suspend fun InboxDao.stepBack(keep: Boolean) {
    stateOnce()?.let { s -> saveState(s.copy(sorted = (s.sorted - 1).coerceAtLeast(0), keptToday = (s.keptToday - if (keep) 1 else 0).coerceAtLeast(0))) }
}
