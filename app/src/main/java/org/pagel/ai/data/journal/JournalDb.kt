package org.pagel.ai.data.journal

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One journal entry, sealed. The phone keeps exactly what pagel.uz keeps: an opaque id (j_…) and the ciphertext. The
 * day, the mood and the words live only inside the blob; there is no column for any of them on purpose
 * (JournalSchemaTest checks it). [sort] is the phone's own write order.
 */
@Entity(tableName = "journal_entries")
data class JournalEntryEntity(
    @PrimaryKey val id: String,
    val blob: String,
    val sort: Long,
    val deleted: Boolean = false,
    val syncStatus: String = PENDING,
    val lastModified: Long,
    val serverVersion: Int = 0,
) {
    companion object {
        const val PENDING = "pending"
        const val SYNCED = "synced"
    }
}

/** An entry waiting for pagel.uz (its id only; the sealed row is read when it is sent). */
@Entity(tableName = "journal_outbox")
data class JournalOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ref: String,
    val createdAt: Long,
    val attempts: Int = 0,
)

/** The journal's data key, wrapped by its own Keystore key (never the Compass's). */
@Entity(tableName = "journal_vault")
data class JournalVaultEntity(@PrimaryKey val id: String = ID, val wrapped: String? = null) {
    companion object {
        const val ID = "vault"
    }
}

@Dao
interface JournalDao {
    @Query("SELECT * FROM journal_entries WHERE deleted = 0 ORDER BY sort DESC, id")
    fun entries(): Flow<List<JournalEntryEntity>>

    @Query("SELECT * FROM journal_entries WHERE id = :id")
    suspend fun entry(id: String): JournalEntryEntity?

    @Query("SELECT COUNT(*) FROM journal_entries WHERE deleted = 0")
    fun count(): Flow<Int>

    @Upsert
    suspend fun upsert(row: JournalEntryEntity)

    @Query("UPDATE journal_entries SET syncStatus = 'synced' WHERE id = :id AND lastModified = :at")
    suspend fun markSynced(id: String, at: Long)

    @Query("UPDATE journal_entries SET serverVersion = :version WHERE id = :id")
    suspend fun setServerVersion(id: String, version: Int)

    @Query("SELECT * FROM journal_vault WHERE id = 'vault'")
    fun vault(): Flow<JournalVaultEntity?>

    @Query("SELECT * FROM journal_vault WHERE id = 'vault'")
    suspend fun vaultOnce(): JournalVaultEntity?

    @Upsert
    suspend fun upsertVault(v: JournalVaultEntity)

    @Insert
    suspend fun enqueue(op: JournalOutboxEntity)

    @Query("SELECT * FROM journal_outbox ORDER BY id")
    suspend fun outbox(): List<JournalOutboxEntity>

    @Query("DELETE FROM journal_outbox WHERE id = :id")
    suspend fun sent(id: Long)

    @Query("DELETE FROM journal_outbox WHERE ref = :ref")
    suspend fun dropWaiting(ref: String)

    @Query("UPDATE journal_outbox SET attempts = attempts + 1 WHERE id = :id")
    suspend fun attempt(id: Long)
}

/**
 * The locked journal's own database file (journal.db): only ciphertext is ever in it, apart from pagel.db, compass.db
 * and notebook.db, and kept out of every backup and phone-to-phone transfer (journal rules in res/xml). Schema exported.
 */
@Database(entities = [JournalEntryEntity::class, JournalOutboxEntity::class, JournalVaultEntity::class], version = 1, exportSchema = true)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun dao(): JournalDao

    companion object {
        const val NAME = "journal.db"
    }
}
