package org.pagel.ai.domain

/** One app's usage for a day, ready for the UI. */
data class AppUsage(
    val pkg: String,
    val label: String,
    val totalMs: Long,
    val opens: Int,
    val category: AppCategory = AppCategory.NEUTRAL,
)

/** A day's usage snapshot: per-app list + headline aggregates + focus. */
data class UsageSnapshot(
    val apps: List<AppUsage>,
    val activeMs: Long,
    val totalOpens: Int,
    val distinctApps: Int,
    val focusScore: Int = 0,
    val productiveMs: Long = 0,
    val distractingMs: Long = 0,
)
