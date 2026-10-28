package org.pagel.ai.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/** The phone's copy of pagel.uz. Schema exported to app/schemas; each new version ships with a migration test. */
@Database(
    entities = [ProjectEntity::class, TaskEntity::class, StepEntity::class, SyncStateEntity::class, TodayOrderEntity::class, OutboxEntity::class, InboxItemEntity::class, InboxStateEntity::class, HabitTickEntity::class, HabitReminderEntity::class],
    version = 5,
    exportSchema = true,
    // v2 (Today + tick): two new tables, nothing changed in v1's, so Room writes the migration itself.
    // v3 (the task panel): four added columns with defaults (tasks.phaseSort/phaseNow, outbox.body/waitsForServer).
    // v4 (the Inbox): two new tables (inbox_items, inbox_state), nothing changed in v3's.
    // v5 (Habits): two new tables (habit_ticks, habit_reminders) and inbox_state.sentAt with a default.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class PagelDatabase : RoomDatabase() {
    abstract fun dao(): PagelDao
    abstract fun inbox(): InboxDao

    companion object {
        const val NAME = "pagel.db"
    }
}
