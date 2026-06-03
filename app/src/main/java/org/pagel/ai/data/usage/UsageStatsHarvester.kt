package org.pagel.ai.data.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pagel.ai.data.room.AwarenessDao
import org.pagel.ai.data.room.AwarenessEventEntity
import org.pagel.ai.domain.EventType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Incrementally harvests the OS usage-event stream into Room. The OS already records these events;
 * we just sweep everything newer than our watermark (max stored ts) into the local log. Events
 * expire from the OS in a few days, so this must run at least every ~30 min (WorkManager).
 */
@Singleton
class UsageStatsHarvester @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: AwarenessDao,
) {
    /** @return number of new events stored. */
    suspend fun harvest(): Int = withContext(Dispatchers.IO) {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = (dao.latestEventTs() ?: (now - 24L * 60 * 60 * 1000)) + 1

        val stream = usm.queryEvents(from, now)
        val rows = ArrayList<AwarenessEventEntity>()
        val e = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(e)
            val type = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> EventType.APP_FG
                UsageEvents.Event.ACTIVITY_PAUSED -> EventType.APP_BG
                UsageEvents.Event.SCREEN_INTERACTIVE -> EventType.SCREEN_ON
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> EventType.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_HIDDEN -> EventType.UNLOCK
                else -> null
            } ?: continue
            val pkg = if (type == EventType.APP_FG || type == EventType.APP_BG) e.packageName else null
            rows.add(AwarenessEventEntity(ts = e.timeStamp, type = type.name, pkg = pkg))
        }
        if (rows.isNotEmpty()) dao.insertEvents(rows)
        rows.size
    }
}
