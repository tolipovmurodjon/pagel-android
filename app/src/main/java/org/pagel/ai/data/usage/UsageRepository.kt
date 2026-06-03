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
import org.pagel.ai.domain.EventType
import org.pagel.ai.domain.FocusScore
import org.pagel.ai.domain.SessionBuilder
import org.pagel.ai.domain.UsageEvent
import org.pagel.ai.domain.UsageSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads recent app usage from the OS event stream (rolling last 24h). Time AND open-counts come
 * from the SAME source — paired ACTIVITY_RESUMED/PAUSED events folded by [SessionBuilder] — so they
 * always agree (no daily-bucket spillover, no midnight-reset emptiness). Categorizes + scores focus.
 */
@Singleton
class UsageRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val categorizer: AppCategorizer,
) {
    private val focus = FocusScore()

    suspend fun today(): UsageSnapshot = withContext(Dispatchers.IO) {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val start = now - WINDOW_MS

        // Normalize the OS event stream into domain events.
        val events = ArrayList<UsageEvent>()
        val stream = usm.queryEvents(start, now)
        val e = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(e)
            val type = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> EventType.APP_FG
                UsageEvents.Event.ACTIVITY_PAUSED -> EventType.APP_BG
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> EventType.SCREEN_OFF
                else -> null
            } ?: continue
            val pkg = if (type == EventType.APP_FG || type == EventType.APP_BG) e.packageName else null
            events.add(UsageEvent(e.timeStamp, type, pkg))
        }
        // Close any app still in the foreground at `now` so its in-progress time is counted.
        events.add(UsageEvent(now, EventType.SCREEN_OFF, null))

        val sessions = SessionBuilder().build(events)
        val opens = events
            .asSequence()
            .filter { it.type == EventType.APP_FG && it.pkg != null }
            .groupingBy { it.pkg!! }
            .eachCount()

        val pm = ctx.packageManager
        val self = ctx.packageName
        val apps = sessions
            .filter { it.pkg != self }
            .groupBy { it.pkg }
            .map { (pkg, ss) ->
                AppUsage(
                    pkg = pkg,
                    label = labelFor(pm, pkg),
                    totalMs = ss.sumOf { it.durationMs },
                    opens = opens[pkg] ?: 0,
                    category = categorizer.categorize(pkg),
                )
            }
            .filter { it.totalMs > 1_000L }
            .sortedByDescending { it.totalMs }

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
    } catch (ex: PackageManager.NameNotFoundException) {
        pkg
    }

    private companion object {
        const val WINDOW_MS = 24L * 60 * 60 * 1000 // rolling last 24 hours
    }
}
