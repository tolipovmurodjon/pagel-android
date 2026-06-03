package org.pagel.ai.domain

/** One app's usage for a day, ready for the UI. */
data class AppUsage(
    val pkg: String,
    val label: String,
    val totalMs: Long,
    val opens: Int,
)

/** A day's usage snapshot: per-app list + headline aggregates. */
data class UsageSnapshot(
    val apps: List<AppUsage>,
    val activeMs: Long,
    val totalOpens: Int,
    val distinctApps: Int,
)
