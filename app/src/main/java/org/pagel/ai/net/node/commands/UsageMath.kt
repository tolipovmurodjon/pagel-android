package org.pagel.ai.net.node.commands

/** One foreground transition from UsageStatsManager: [type] is [UsageMath.RESUMED] or [UsageMath.PAUSED]. */
data class UsageEvent(val pkg: String, val type: Int, val atMs: Long)

/**
 * Pure math behind screen time: turns resume/pause events into foreground milliseconds per package.
 * `UsageEvents.Event.ACTIVITY_RESUMED` is 1 and `ACTIVITY_PAUSED` is 2; they are copied here so this
 * file runs in a plain JVM test.
 */
object UsageMath {
    const val RESUMED = 1
    const val PAUSED = 2

    fun foregroundMs(
        events: List<UsageEvent>,
        startMs: Long,
        endMs: Long,
        exclude: Set<String> = emptySet(),
    ): Map<String, Long> {
        val open = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        fun add(pkg: String, from: Long, to: Long) {
            val ms = to.coerceAtMost(endMs) - from.coerceAtLeast(startMs)
            if (ms > 0) total[pkg] = (total[pkg] ?: 0L) + ms
        }
        for (e in events.sortedBy { it.atMs }) {
            if (e.pkg in exclude) continue
            when (e.type) {
                RESUMED -> if (!open.containsKey(e.pkg)) open[e.pkg] = e.atMs
                // A pause with no resume in the window means the app was already in front when the window began.
                PAUSED -> add(e.pkg, open.remove(e.pkg) ?: startMs, e.atMs)
            }
        }
        for ((pkg, from) in open) add(pkg, from, endMs)
        return total
    }
}
