package org.pagel.ai.data.compass

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One goal or note, sealed. The phone keeps exactly what pagel.uz keeps: the opaque id (g_… goals, e_… notes), the
 * kind, the parent's id, two dates and the ciphertext. There is no column for a word on purpose
 * (CompassSchemaTest checks it). [sort] is the phone's own order; [lastModified] marks which write a reply confirms.
 */
@Entity(tableName = "compass_items")
data class CompassItemEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val parent: String?,
    val blob: String,
    val starts: String?,
    val ends: String?,
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

/** Which goal a project serves: the goal's id only ([goal] null = unlinked, waiting to be sent). */
@Entity(tableName = "compass_links")
data class CompassLinkEntity(@PrimaryKey val project: String, val goal: String?, val lastModified: Long)

/** A Compass change waiting for pagel.uz: [what] is ITEM, LINK or RECOVERY, [ref] the item id or project. */
@Entity(tableName = "compass_outbox")
data class CompassOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val what: String,
    val ref: String,
    val createdAt: Long,
    val attempts: Int = 0,
) {
    companion object {
        const val ITEM = "item"
        const val LINK = "link"
        const val RECOVERY = "recovery"
    }
}

/**
 * The Compass's own settings on this phone: the data key wrapped by the Keystore ([wrapped]), the optional recovery
 * blob, pagel.uz's recovery blob seen on a new phone ([serverRecovery]), Today's line (its goal id and its words sealed
 * by the line key), the birthday reminder switch.
 */
@Entity(tableName = "compass_vault")
data class CompassVaultEntity(
    @PrimaryKey val id: String = ID,
    val wrapped: String? = null,
    val recovery: String? = null,
    val serverRecovery: String? = null,
    val lineOn: Boolean = false,
    val lineGoal: String? = null,
    val line: String? = null,
    val birthdayOn: Boolean = false,
    val pulledAt: Long = 0,
    /** The season-review reminder (the first day of week 13, 09:00); on, but it rings only for a season with goals. */
    val reviewOn: Boolean = true,
) {
    companion object {
        const val ID = "vault"
    }
}

@Dao
interface CompassDao {
    @Query("SELECT * FROM compass_items WHERE deleted = 0 ORDER BY sort, id")
    fun items(): Flow<List<CompassItemEntity>>

    @Query("SELECT * FROM compass_items WHERE id = :id")
    suspend fun item(id: String): CompassItemEntity?

    /** The seasons Boss wrote goals for (only kinds and dates, never words): the review reminder rings only for those. */
    @Query("SELECT DISTINCT starts FROM compass_items WHERE deleted = 0 AND kind = :kind AND starts IS NOT NULL")
    suspend fun seasonStarts(kind: String): List<String>

    @Query("SELECT COUNT(*) FROM compass_items WHERE deleted = 0")
    fun itemCount(): Flow<Int>

    @Upsert
    suspend fun upsertItem(item: CompassItemEntity)

    @Upsert
    suspend fun upsertItems(items: List<CompassItemEntity>)

    /**
     * A new seal over an existing row, leaving serverVersion alone: the sender may have just stored pagel.uz's newer
     * version, and writing back a copy read before it would undo it (compass re-check N2). Returns rows changed.
     */
    @Query("UPDATE compass_items SET kind = :kind, parent = :parent, blob = :blob, starts = :starts, ends = :ends, deleted = 0, syncStatus = 'pending', lastModified = :at WHERE id = :id")
    suspend fun updateSealed(id: String, kind: String, parent: String?, blob: String, starts: String?, ends: String?, at: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: CompassItemEntity): Long

    /** Deleted, without touching serverVersion (same reason as [updateSealed]). */
    @Query("UPDATE compass_items SET deleted = 1, syncStatus = 'pending', lastModified = :at WHERE id = :id")
    suspend fun markDeleted(id: String, at: Long): Int

    @Query("UPDATE compass_items SET syncStatus = 'synced', serverVersion = :version WHERE id = :id AND lastModified = :at")
    suspend fun markSynced(id: String, version: Int, at: Long)

    /** pagel.uz's version after a write, whatever was edited since (the next write sends it as its base). */
    @Query("UPDATE compass_items SET serverVersion = :version WHERE id = :id")
    suspend fun setServerVersion(id: String, version: Int)

    @Query("SELECT * FROM compass_links")
    fun links(): Flow<List<CompassLinkEntity>>

    @Query("SELECT * FROM compass_links WHERE project = :project")
    suspend fun link(project: String): CompassLinkEntity?

    @Upsert
    suspend fun upsertLink(link: CompassLinkEntity)

    @Query("SELECT * FROM compass_vault WHERE id = 'vault'")
    fun vault(): Flow<CompassVaultEntity?>

    @Query("SELECT * FROM compass_vault WHERE id = 'vault'")
    suspend fun vaultOnce(): CompassVaultEntity?

    @Upsert
    suspend fun upsertVault(vault: CompassVaultEntity)

    @Insert
    suspend fun enqueue(op: CompassOutboxEntity)

    @Query("SELECT * FROM compass_outbox ORDER BY id")
    suspend fun outbox(): List<CompassOutboxEntity>

    @Query("SELECT COUNT(*) FROM compass_outbox")
    fun waiting(): Flow<Int>

    @Query("DELETE FROM compass_outbox WHERE id = :id")
    suspend fun sent(id: Long)

    @Query("UPDATE compass_outbox SET attempts = attempts + 1 WHERE id = :id")
    suspend fun attempt(id: Long)
}

/**
 * The Compass lives in its own database file (compass.db), apart from pagel.db: only ciphertext is ever in it, and
 * the task database's versions (rounds C and D) never collide with it. Schema exported; v1 is checked by CompassSchemaTest.
 */
@Database(
    entities = [CompassItemEntity::class, CompassLinkEntity::class, CompassOutboxEntity::class, CompassVaultEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CompassDatabase : RoomDatabase() {
    abstract fun dao(): CompassDao

    companion object {
        const val NAME = "compass.db"
    }
}
