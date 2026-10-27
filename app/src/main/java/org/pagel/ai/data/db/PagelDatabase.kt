package org.pagel.ai.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/** The phone's copy of pagel.uz. Schema exported to app/schemas; each new version ships with a migration test. */
@Database(
    entities = [ProjectEntity::class, TaskEntity::class, StepEntity::class, SyncStateEntity::class, TodayOrderEntity::class, OutboxEntity::class],
    version = 2,
    exportSchema = true,
    // v2 (Today + tick): two new tables, nothing changed in v1's, so Room writes the migration itself.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class PagelDatabase : RoomDatabase() {
    abstract fun dao(): PagelDao

    companion object {
        const val NAME = "pagel.db"
    }
}
