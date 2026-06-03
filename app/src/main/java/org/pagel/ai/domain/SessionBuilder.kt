package org.pagel.ai.domain

/**
 * Folds a raw event stream into app foreground sessions. Pure (no Android), so it's unit-testable.
 * An APP_FG opens a session; the next APP_FG, an APP_BG, or a SCREEN_OFF closes it. Zero/negative
 * durations are dropped.
 */
class SessionBuilder {
    fun build(events: List<UsageEvent>): List<AppSession> {
        val sorted = events.sortedBy { it.ts }
        val out = ArrayList<AppSession>()
        var openPkg: String? = null
        var openStart = 0L

        fun close(end: Long) {
            val p = openPkg ?: return
            if (end > openStart) out.add(AppSession(p, openStart, end))
            openPkg = null
        }

        for (e in sorted) {
            when (e.type) {
                EventType.APP_FG -> {
                    close(e.ts)
                    if (e.pkg != null) {
                        openPkg = e.pkg
                        openStart = e.ts
                    }
                }
                EventType.APP_BG, EventType.SCREEN_OFF -> close(e.ts)
                else -> {}
            }
        }
        return out
    }
}
