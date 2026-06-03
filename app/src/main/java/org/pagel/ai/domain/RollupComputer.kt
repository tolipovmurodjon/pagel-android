package org.pagel.ai.domain

/**
 * Folds sessions + events into a day's per-app rollups and overall context. Pure (no Android).
 */
class RollupComputer {

    fun rollups(sessions: List<AppSession>, events: List<UsageEvent>): List<DailyRollupItem> {
        val opens = events
            .asSequence()
            .filter { it.type == EventType.APP_FG && it.pkg != null }
            .groupingBy { it.pkg!! }
            .eachCount()

        return sessions
            .groupBy { it.pkg }
            .map { (pkg, ss) ->
                DailyRollupItem(
                    pkg = pkg,
                    totalMs = ss.sumOf { it.durationMs },
                    opens = opens[pkg] ?: 0,
                    firstUseTs = ss.minOf { it.startTs },
                    lastUseTs = ss.maxOf { it.endTs },
                )
            }
            .sortedByDescending { it.totalMs }
    }

    fun context(sessions: List<AppSession>, events: List<UsageEvent>): DayContext {
        val sorted = events.sortedBy { it.ts }
        var screenOn = 0L
        var on = -1L
        for (e in sorted) {
            when (e.type) {
                EventType.SCREEN_ON -> on = e.ts
                EventType.SCREEN_OFF -> if (on >= 0) { screenOn += e.ts - on; on = -1 }
                else -> {}
            }
        }
        val unlockTimes = sorted.filter { it.type == EventType.UNLOCK }
        val screenOffTimes = sorted.filter { it.type == EventType.SCREEN_OFF }
        return DayContext(
            screenTimeMs = screenOn,
            unlocks = unlockTimes.size,
            firstUnlockTs = unlockTimes.minOfOrNull { it.ts } ?: 0L,
            lastScreenOffTs = screenOffTimes.maxOfOrNull { it.ts } ?: 0L,
            distinctApps = sessions.map { it.pkg }.distinct().size,
        )
    }
}
