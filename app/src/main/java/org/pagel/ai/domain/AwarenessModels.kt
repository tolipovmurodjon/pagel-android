package org.pagel.ai.domain

/** The raw event kinds we sense. Stored as the enum name in Room. */
enum class EventType { APP_FG, APP_BG, SCREEN_ON, SCREEN_OFF, UNLOCK }

/** A single normalized phone event (Android-free, so it's unit-testable). */
data class UsageEvent(val ts: Long, val type: EventType, val pkg: String? = null)

/** A contiguous foreground session for one app. */
data class AppSession(val pkg: String, val startTs: Long, val endTs: Long) {
    val durationMs: Long get() = endTs - startTs
}

/** Per-app rollup for a day (pure result of folding sessions + events). */
data class DailyRollupItem(
    val pkg: String,
    val totalMs: Long,
    val opens: Int,
    val firstUseTs: Long,
    val lastUseTs: Long,
)

/** The day's overall shape. */
data class DayContext(
    val screenTimeMs: Long,
    val unlocks: Int,
    val firstUnlockTs: Long,
    val lastScreenOffTs: Long,
    val distinctApps: Int,
)
