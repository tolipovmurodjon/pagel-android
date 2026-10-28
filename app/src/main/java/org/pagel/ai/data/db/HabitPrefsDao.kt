package org.pagel.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Habits 2.0 (round D): Boss's own touches on each habit and the streak freezes, both kept on this phone. */
@Dao
interface HabitPrefsDao {
    @Query("SELECT * FROM habit_prefs")
    fun prefs(): Flow<List<HabitPrefEntity>>

    @Query("SELECT * FROM habit_prefs WHERE taskId = :id")
    suspend fun prefOnce(id: String): HabitPrefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPref(p: HabitPrefEntity)

    @Query("SELECT * FROM habit_freezes")
    fun freezes(): Flow<List<HabitFreezeEntity>>

    @Query("SELECT * FROM habit_freezes")
    suspend fun freezesOnce(): List<HabitFreezeEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun freeze(f: HabitFreezeEntity): Long

    @Query("DELETE FROM habit_freezes WHERE day = :day AND taskId = :id")
    suspend fun unfreeze(day: String, id: String): Int
}
