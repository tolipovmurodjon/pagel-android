package org.pagel.ai.data.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Event-sourced awareness log + derived rollups. Raw events are short-lived (pruned ~7 days);
 * daily rollups/context are tiny and kept indefinitely. (Schema v1.)
 */

/** One raw phone event (app foreground/background, screen on/off, unlock). */
@Entity(tableName = "awareness_event", indices = [Index("ts"), Index("type")])
data class AwarenessEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,           // epoch millis, UTC
    val type: String,       // EventType name: APP_FG / APP_BG / SCREEN_ON / SCREEN_OFF / UNLOCK
    val pkg: String? = null,
    val intValue: Int? = null,
)

/** A contiguous foreground session for one app (derived from events). */
@Entity(tableName = "app_session", indices = [Index("startTs"), Index("pkg")])
data class AppSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val startTs: Long,
    val endTs: Long,
    val durationMs: Long,
)

/** Per-day, per-app usage rollup. Keyed by (day, pkg) so @Upsert replaces in place. */
@Entity(tableName = "daily_rollup", primaryKeys = ["day", "pkg"])
data class DailyRollupEntity(
    val day: String,        // "2026-06-03"
    val pkg: String,
    val totalMs: Long,
    val opens: Int,
    val firstUseTs: Long,
    val lastUseTs: Long,
)

/** The "shape of the day" — one row per day. */
@Entity(tableName = "daily_context")
data class DailyContextEntity(
    @PrimaryKey val day: String,
    val screenTimeMs: Long,
    val unlocks: Int,
    val firstUnlockTs: Long,
    val lastScreenOffTs: Long,
    val distinctApps: Int,
)
