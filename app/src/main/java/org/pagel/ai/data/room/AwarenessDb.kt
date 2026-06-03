package org.pagel.ai.data.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AwarenessEventEntity::class,
        AppSessionEntity::class,
        DailyRollupEntity::class,
        DailyContextEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AwarenessDb : RoomDatabase() {
    abstract fun dao(): AwarenessDao
}
