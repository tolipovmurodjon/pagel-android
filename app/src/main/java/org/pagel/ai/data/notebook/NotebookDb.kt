package org.pagel.ai.data.notebook

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One vault folder and its switch, as pagel.uz lists it. [locked] = Kundalik: shown greyed, never opened. */
@Entity(tableName = "vault_folders")
data class VaultFolderEntity(
    @PrimaryKey val path: String,
    val allowed: Boolean,
    /** Boss switched this folder himself (else it follows its parent). */
    val switched: Boolean,
    val locked: Boolean,
    val notes: Int,
    val syncStatus: String = SYNCED,
    val lastModified: Long = 0,
)

/**
 * One note from an allowed folder. [body] is null until it is opened once (the list carries titles only); [hash] is
 * pagel.uz's copy the phone last saw (the next write sends it as `base`); [links] and [backlinks] are the reader's
 * JSON (NoteLinkDto / BacklinkDto lists). A note written on the phone is PENDING until pagel.uz takes it.
 */
@Entity(tableName = "vault_notes")
data class VaultNoteEntity(
    @PrimaryKey val path: String,
    val title: String,
    val body: String?,
    val hash: String,
    val updatedAt: String,
    val links: String = "[]",
    val backlinks: String = "[]",
    val syncStatus: String = SYNCED,
    val lastModified: Long = 0,
    val serverVersion: Int = 0,
    /** pagel.uz's refusal of the last write, said once on the note (null while fine). Its words stay on the phone. */
    val refused: String? = null,
)

/** A picture in an allowed folder (the manifest's files): the reader resolves `![[x.png]]` against these. */
@Entity(tableName = "vault_files")
data class VaultFileEntity(@PrimaryKey val path: String)

/** A recall card due now; [gradedAt] is set when Boss graded it here and the grade waits to be sent. */
@Entity(tableName = "recall_cards")
data class RecallCardEntity(
    @PrimaryKey val id: String,
    val question: String,
    val answer: String,
    val due: String,
    val sourceKind: String,
    val sourceId: String,
    val gradedAt: Long? = null,
)

/** The Inbox's quick notes, kept for offline search (GET /api/v1/notes). [n] is the line's place from the oldest. */
@Entity(tableName = "quick_notes", primaryKeys = ["project", "n"])
data class QuickNoteEntity(val project: String, val n: Int, val text: String, val day: String?, val link: String?)

/** A second-brain change waiting for pagel.uz: [what] FOLDER (ref = path, body = "true"/"false"), NOTE (ref = path), GRADE (ref = card id, body = grade). */
@Entity(tableName = "notebook_outbox")
data class NotebookOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val what: String,
    val ref: String,
    val body: String?,
    val createdAt: Long,
    val attempts: Int = 0,
) {
    companion object {
        const val FOLDER = "folder"
        const val NOTE = "note"
        const val GRADE = "grade"
    }
}

const val SYNCED = "synced"
const val PENDING = "pending"

@Dao
abstract class NotebookDao {
    @Query("SELECT * FROM vault_folders ORDER BY path")
    abstract fun folders(): Flow<List<VaultFolderEntity>>

    @Query("SELECT * FROM vault_folders WHERE path = :path")
    abstract suspend fun folder(path: String): VaultFolderEntity?

    @Query("SELECT * FROM vault_folders")
    abstract suspend fun foldersOnce(): List<VaultFolderEntity>

    @Upsert
    abstract suspend fun upsertFolders(rows: List<VaultFolderEntity>)

    @Query("DELETE FROM vault_folders WHERE syncStatus != 'pending'")
    abstract suspend fun clearSyncedFolders()

    /** pagel.uz's folder list wins, except switches still waiting to be sent. */
    @Transaction
    open suspend fun replaceFolders(rows: List<VaultFolderEntity>) {
        val waiting = folderPaths(PENDING).toSet()
        clearSyncedFolders()
        upsertFolders(rows.filter { it.path !in waiting })
    }

    @Query("SELECT path FROM vault_folders WHERE syncStatus = :status")
    abstract suspend fun folderPaths(status: String): List<String>

    @Query("SELECT * FROM vault_notes ORDER BY updatedAt DESC, path")
    abstract fun notes(): Flow<List<VaultNoteEntity>>

    @Query("SELECT * FROM vault_notes")
    abstract suspend fun notesOnce(): List<VaultNoteEntity>

    @Query("SELECT * FROM vault_notes WHERE path = :path")
    abstract fun note(path: String): Flow<VaultNoteEntity?>

    @Query("SELECT * FROM vault_notes WHERE path = :path")
    abstract suspend fun noteOnce(path: String): VaultNoteEntity?

    @Upsert
    abstract suspend fun upsertNote(row: VaultNoteEntity)

    @Upsert
    abstract suspend fun upsertNotes(rows: List<VaultNoteEntity>)

    @Query("DELETE FROM vault_notes WHERE path IN (:paths) AND syncStatus != 'pending'")
    abstract suspend fun dropNotes(paths: List<String>)

    @Query("SELECT path FROM vault_files")
    abstract suspend fun filesOnce(): List<String>

    @Query("DELETE FROM vault_files")
    abstract suspend fun clearFiles()

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    abstract suspend fun insertFiles(rows: List<VaultFileEntity>)

    @Transaction
    open suspend fun replaceFiles(paths: List<String>) {
        clearFiles()
        insertFiles(paths.map(::VaultFileEntity))
    }

    @Query("SELECT * FROM recall_cards ORDER BY due, id")
    abstract fun cards(): Flow<List<RecallCardEntity>>

    @Query("DELETE FROM recall_cards WHERE gradedAt IS NULL")
    abstract suspend fun clearUngradedCards()

    @Upsert
    abstract suspend fun upsertCards(rows: List<RecallCardEntity>)

    /** pagel.uz's due list wins, except cards graded here whose grade is not sent yet. */
    @Transaction
    open suspend fun replaceCards(rows: List<RecallCardEntity>) {
        val graded = gradedIds().toSet()
        clearUngradedCards()
        upsertCards(rows.filter { it.id !in graded })
    }

    @Query("SELECT id FROM recall_cards WHERE gradedAt IS NOT NULL")
    abstract suspend fun gradedIds(): List<String>

    @Query("UPDATE recall_cards SET gradedAt = :at WHERE id = :id")
    abstract suspend fun markGraded(id: String, at: Long)

    @Query("DELETE FROM recall_cards WHERE id = :id")
    abstract suspend fun dropCard(id: String)

    @Query("SELECT * FROM quick_notes")
    abstract suspend fun quickNotesOnce(): List<QuickNoteEntity>

    @Query("SELECT * FROM quick_notes WHERE project = :project AND n = :n")
    abstract suspend fun quickNote(project: String, n: Int): QuickNoteEntity?

    @Query("DELETE FROM quick_notes WHERE project = :project")
    abstract suspend fun clearQuickNotes(project: String)

    @Upsert
    abstract suspend fun upsertQuickNotes(rows: List<QuickNoteEntity>)

    @Transaction
    open suspend fun replaceQuickNotes(project: String, rows: List<QuickNoteEntity>) {
        clearQuickNotes(project)
        upsertQuickNotes(rows)
    }

    @Insert
    abstract suspend fun enqueue(op: NotebookOutboxEntity): Long

    @Query("SELECT * FROM notebook_outbox ORDER BY id")
    abstract suspend fun outbox(): List<NotebookOutboxEntity>

    @Query("SELECT COUNT(*) FROM notebook_outbox")
    abstract fun waiting(): Flow<Int>

    @Query("DELETE FROM notebook_outbox WHERE id = :id")
    abstract suspend fun sent(id: Long)

    @Query("UPDATE notebook_outbox SET attempts = attempts + 1 WHERE id = :id")
    abstract suspend fun attempt(id: Long)

    /** A newer write of the same note or switch replaces the one still waiting (the newest copy is what counts). */
    @Query("DELETE FROM notebook_outbox WHERE what = :what AND ref = :ref")
    abstract suspend fun dropWaiting(what: String, ref: String)
}

/**
 * The second brain on the phone (Phase 6b): its own file, apart from pagel.db, so the task database's versions (round
 * D) never collide with it. Holds only what pagel.uz already holds for Boss's allowed folders; never the journal, the
 * Compass or Kundalik. Schema exported.
 */
@Database(
    entities = [VaultFolderEntity::class, VaultNoteEntity::class, VaultFileEntity::class, RecallCardEntity::class, QuickNoteEntity::class, NotebookOutboxEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NotebookDatabase : RoomDatabase() {
    abstract fun dao(): NotebookDao

    companion object {
        const val NAME = "notebook.db"
    }
}
