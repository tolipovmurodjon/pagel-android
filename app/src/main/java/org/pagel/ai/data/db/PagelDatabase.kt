package org.pagel.ai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** The phone's copy of pagel.uz. Schema exported to app/schemas; each new version ships with a migration test. */
@Database(
    entities = [ProjectEntity::class, TaskEntity::class, StepEntity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PagelDatabase : RoomDatabase() {
    abstract fun dao(): PagelDao

    companion object {
        const val NAME = "pagel.db"
    }
}
