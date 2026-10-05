package org.pagel.ai.net.node.commands

/** One transition from UsageStatsManager: [type] is one of the constants in [UsageMath]. Screen events carry no package. */
data class UsageEvent(val pkg: String, val type: Int, val atMs: Long)

/**
 * Pure math behind screen time: turns usage events into foreground milliseconds per package.
 * The numbers are copied from `UsageEvents.Event` so this file runs in a plain JVM test.
 *
 * Two rules keep the total honest (a live test on 2026-10-05 showed 755 min in a 600 min day without them):
 * only ONE app is in front at a time, so a new RESUMED closes whatever was open; and when the screen turns
 * off or the phone shuts down, everything open closes, because Android often sends no PAUSED for that.
 */
object UsageMath {
    const val RESUMED = 1
    const val PAUSED = 2
    const val SCREEN_OFF = 16
    const val SHUTDOWN = 26

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
        fun closeAll(at: Long, except: String? = null) {
            for ((pkg, from) in open.entries.toList()) {
                if (pkg == except) continue
                add(pkg, from, at)
                open.remove(pkg)
            }
        }
        for (e in events.sortedBy { it.atMs }) {
            when (e.type) {
                SCREEN_OFF, SHUTDOWN -> closeAll(e.atMs)
                RESUMED -> {
                    closeAll(e.atMs, except = e.pkg)         // one app in front at a time
                    if (e.pkg !in exclude && !open.containsKey(e.pkg)) open[e.pkg] = e.atMs
                }
                // A pause with no resume in the window means the app was already in front when the window began.
                PAUSED -> if (e.pkg !in exclude) add(e.pkg, open.remove(e.pkg) ?: startMs, e.atMs)
            }
        }
        closeAll(endMs)
        return total
    }
}
