package org.pagel.ai.data.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pagel.ai.domain.AppUsage
import org.pagel.ai.domain.FocusScore
import org.pagel.ai.domain.UsageSnapshot
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads today's app usage from the OS [UsageStatsManager]. Foreground time comes from the
 * aggregated stats; launch counts are derived from ACTIVITY_RESUMED events. Categorizes each app
 * and computes a focus score. Read-only.
 */
@Singleton
class UsageRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val categorizer: AppCategorizer,
) {
    private val focus = FocusScore()
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
                    category = categorizer.categorize(us.packageName),
                )
            }
            .sortedByDescending { it.totalMs }
            .toList()

        val fr = focus.compute(apps.map { it.category to it.totalMs })
        UsageSnapshot(
            apps = apps,
            activeMs = apps.sumOf { it.totalMs },
            totalOpens = apps.sumOf { it.opens },
            distinctApps = apps.size,
            focusScore = fr.score,
            productiveMs = fr.productiveMs,
            distractingMs = fr.distractingMs,
        )
    }

    private fun labelFor(pm: PackageManager, pkg: String): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
        }
        pm.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
