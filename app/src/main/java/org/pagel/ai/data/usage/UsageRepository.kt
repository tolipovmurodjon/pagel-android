package org.pagel.ai.data.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pagel.ai.domain.AppUsage
import org.pagel.ai.domain.UsageSnapshot
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads today's app usage from the OS [UsageStatsManager]. Foreground time comes from the
 * aggregated stats; launch counts are derived from ACTIVITY_RESUMED events. Read-only.
 */
@Singleton
class UsageRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    suspend fun today(): UsageSnapshot = withContext(Dispatchers.IO) {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        // launch counts per package
        val opens = HashMap<String, Int>()
        val events = usm.queryEvents(start, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                opens[e.packageName] = (opens[e.packageName] ?: 0) + 1
            }
        }

        val pm = ctx.packageManager
        val self = ctx.packageName
        val apps = usm.queryAndAggregateUsageStats(start, now).values
            .asSequence()
            .filter { it.totalTimeInForeground > 1_000L && it.packageName != self }
            .map { us ->
                AppUsage(
                    pkg = us.packageName,
                    label = labelFor(pm, us.packageName),
                    totalMs = us.totalTimeInForeground,
                    opens = opens[us.packageName] ?: 0,
                )
            }
            .sortedByDescending { it.totalMs }
            .toList()

        UsageSnapshot(
            apps = apps,
            activeMs = apps.sumOf { it.totalMs },
            totalOpens = apps.sumOf { it.opens },
            distinctApps = apps.size,
        )
    }

    private fun labelFor(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
