package org.pagel.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Round D item 2: the day's Inbox decisions ("Sorted today") and the projects' quick notes, both on this phone. */
@Dao
abstract class InboxLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun logSorted(s: InboxSortedEntity)

    @Query("SELECT * FROM inbox_sorted WHERE day = :day ORDER BY at DESC")
    abstract fun sortedOn(day: String): Flow<List<InboxSortedEntity>>

    @Query("DELETE FROM inbox_sorted WHERE file = :file")
    abstract suspend fun unlog(file: String)

    @Query("DELETE FROM inbox_sorted WHERE day < :before")
    abstract suspend fun forgetBefore(before: String)

    @Query("SELECT * FROM quick_notes WHERE project = :project ORDER BY position")
    abstract fun notes(project: String): Flow<List<QuickNoteEntity>>

    @Query("SELECT project, COUNT(*) AS count FROM quick_notes GROUP BY project ORDER BY project")
    abstract fun noteCounts(): Flow<List<NoteCount>>

    @Query("DELETE FROM quick_notes WHERE project = :project")
    abstract suspend fun clearNotes(project: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putNotes(n: List<QuickNoteEntity>)

    /** pagel.uz's notes replace the saved ones in one step, so the list never shows half of each. */
    @Transaction
    open suspend fun replaceNotes(project: String, n: List<QuickNoteEntity>) {
        clearNotes(project)
        putNotes(n)
    }
}

data class NoteCount(val project: String, val count: Int)
