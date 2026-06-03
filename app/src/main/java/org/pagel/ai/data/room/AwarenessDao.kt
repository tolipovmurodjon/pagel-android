package org.pagel.ai.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AwarenessDao {

    // --- raw events ---
    @Insert
    suspend fun insertEvents(events: List<AwarenessEventEntity>)

    /** Watermark for incremental harvest — newest event we already stored, or null if empty. */
    @Query("SELECT MAX(ts) FROM awareness_event")
    suspend fun latestEventTs(): Long?

    @Query("SELECT * FROM awareness_event WHERE ts BETWEEN :from AND :to ORDER BY ts ASC")
    suspend fun eventsBetween(from: Long, to: Long): List<AwarenessEventEntity>

    @Query("DELETE FROM awareness_event WHERE ts < :ts")
    suspend fun pruneEventsBefore(ts: Long)

    @Query("SELECT COUNT(*) FROM awareness_event")
    fun observeEventCount(): Flow<Int>

    @Query("SELECT MAX(ts) FROM awareness_event")
    fun observeLatestEventTs(): Flow<Long?>

    // --- sessions ---
    @Insert
    suspend fun insertSessions(sessions: List<AppSessionEntity>)

    @Query("DELETE FROM app_session WHERE startTs BETWEEN :from AND :to")
    suspend fun deleteSessionsBetween(from: Long, to: Long)

    // --- rollups / context (upsert by primary key) ---
    @Upsert
    suspend fun upsertRollups(rollups: List<DailyRollupEntity>)

    @Upsert
    suspend fun upsertContext(ctx: DailyContextEntity)

    @Query("SELECT * FROM daily_rollup WHERE day = :day ORDER BY totalMs DESC")
    fun observeDay(day: String): Flow<List<DailyRollupEntity>>

    @Query("SELECT * FROM daily_context WHERE day = :day")
    fun observeDayContext(day: String): Flow<DailyContextEntity?>
}
