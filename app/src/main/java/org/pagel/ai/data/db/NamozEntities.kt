package org.pagel.ai.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * How a prayer was prayed (v11, the Namoz page): Boss's own choice from a long press, kept with the day's tick.
 * [how] is one of [ON_TIME], [LATER], [MOSQUE]. The tick itself is the habit's tick (habit_ticks + outbox); this mark
 * stays on this phone (pagel.uz has no field for it yet).
 */
@Entity(tableName = "namoz_marks", primaryKeys = ["day", "prayer"])
data class NamozMarkEntity(
    val day: String,
    val prayer: String,
    val how: String,
) {
    companion object {
        const val ON_TIME = "on_time"
        const val LATER = "later"
        const val MOSQUE = "mosque"
    }
}

/** One line of Boss's own learning list (v11): text he wrote, ticked when learned. The app writes no text here. */
@Entity(tableName = "namoz_learning")
data class NamozLearnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val learned: Boolean = false,
    val createdAt: Long,
)

/**
 * The Namoz page's settings (v11), one row. Everything optional starts off: reminders live per prayer in
 * habit_reminders (off until he turns one on), the Qaza counter is off and never computed for him.
 */
@Entity(tableName = "namoz_settings")
data class NamozSettingsEntity(
    @PrimaryKey val id: Int = 1,
    /** true = the phone's last known place; false = Tashkent. */
    @ColumnInfo(defaultValue = "0") val autoLocation: Boolean = false,
    @ColumnInfo(defaultValue = "'uzbekistan'") val method: String = "uzbekistan",
    /** true = Hanafi Asr (default), false = Shafi'i. */
    @ColumnInfo(defaultValue = "1") val hanafi: Boolean = true,
    /** Minutes added to Bomdod, Peshin, Asr, Shom, Xufton, comma separated. */
    @ColumnInfo(defaultValue = "'0,0,0,0,0'") val offsets: String = "0,0,0,0,0",
    @ColumnInfo(defaultValue = "0") val qazaOn: Boolean = false,
    @ColumnInfo(defaultValue = "0") val qaza: Int = 0,
    @ColumnInfo(defaultValue = "1") val onToday: Boolean = true,
    /** The last known place when [autoLocation] is on (0, 0 = none yet: Tashkent is used). */
    @ColumnInfo(defaultValue = "0") val lat: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val lon: Double = 0.0,
)

@Dao
interface NamozDao {
    @Query("SELECT * FROM namoz_marks WHERE day >= :from")
    fun marks(from: String): Flow<List<NamozMarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMark(m: NamozMarkEntity)

    @Query("DELETE FROM namoz_marks WHERE day = :day AND prayer = :prayer")
    suspend fun clearMark(day: String, prayer: String): Int

    @Query("SELECT * FROM namoz_learning ORDER BY learned ASC, createdAt DESC")
    fun learning(): Flow<List<NamozLearnEntity>>

    @Insert
    suspend fun addLearn(l: NamozLearnEntity): Long

    @Query("UPDATE namoz_learning SET learned = :learned WHERE id = :id")
    suspend fun setLearned(id: Long, learned: Boolean): Int

    @Query("DELETE FROM namoz_learning WHERE id = :id")
    suspend fun deleteLearn(id: Long): Int

    @Query("SELECT * FROM namoz_settings WHERE id = 1")
    fun settings(): Flow<NamozSettingsEntity?>

    @Query("SELECT * FROM namoz_settings WHERE id = 1")
    suspend fun settingsOnce(): NamozSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSettings(s: NamozSettingsEntity)
}
