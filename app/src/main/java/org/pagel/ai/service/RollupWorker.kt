package org.pagel.ai.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.pagel.ai.data.room.AwarenessDao
import org.pagel.ai.data.room.DailyContextEntity
import org.pagel.ai.data.room.DailyRollupEntity
import org.pagel.ai.domain.EventType
import org.pagel.ai.domain.RollupComputer
import org.pagel.ai.domain.SessionBuilder
import org.pagel.ai.domain.UsageEvent
import java.time.LocalDate
import java.time.ZoneId

/** Folds today's raw events into daily rollups + context, then prunes events older than 7 days. */
@HiltWorker
class RollupWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val dao: AwarenessDao,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = try {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        val events = dao.eventsBetween(dayStart, now).map {
            UsageEvent(it.ts, runCatching { EventType.valueOf(it.type) }.getOrDefault(EventType.APP_BG), it.pkg)
        }
        val sessions = SessionBuilder().build(events)
        val computer = RollupComputer()
        val day = today.toString()

        dao.upsertRollups(
            computer.rollups(sessions, events).map {
                DailyRollupEntity(day, it.pkg, it.totalMs, it.opens, it.firstUseTs, it.lastUseTs)
            },
        )
        val c = computer.context(sessions, events)
        dao.upsertContext(
            DailyContextEntity(day, c.screenTimeMs, c.unlocks, c.firstUnlockTs, c.lastScreenOffTs, c.distinctApps),
        )
        dao.pruneEventsBefore(now - 7L * 24 * 60 * 60 * 1000)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
