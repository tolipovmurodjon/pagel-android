package org.pagel.ai.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/** The phone's copy of pagel.uz. Schema exported to app/schemas; each new version ships with a migration test. */
@Database(
    entities = [ProjectEntity::class, TaskEntity::class, StepEntity::class, SyncStateEntity::class, TodayOrderEntity::class, OutboxEntity::class, InboxItemEntity::class, InboxStateEntity::class, HabitTickEntity::class, HabitReminderEntity::class, SweepEntity::class, RitualStateEntity::class, HabitPrefEntity::class, HabitFreezeEntity::class, InboxSortedEntity::class, QuickNoteEntity::class, NamozMarkEntity::class, NamozLearnEntity::class, NamozSettingsEntity::class, AdoptedIdEntity::class, TodaySortEntity::class, TodayStateEntity::class],
    version = 14,
    exportSchema = true,
    // v2 (Today + tick): two new tables, nothing changed in v1's, so Room writes the migration itself.
    // v3 (the task panel): four added columns with defaults (tasks.phaseSort/phaseNow, outbox.body/waitsForServer).
    // v4 (the Inbox): two new tables (inbox_items, inbox_state), nothing changed in v3's.
    // v5 (Habits): two new tables (habit_ticks, habit_reminders) and inbox_state.sentAt with a default.
    // v6 (Rituals): two new tables (sweeps, ritual_state), nothing changed in v5's.
    // v7 (round B): tasks.times and habit_ticks.n with defaults.
    // v8 (round D, Habits 2.0): two new tables (habit_prefs, habit_freezes) and tasks.habitGroup with a NULL default.
    // v9 (round D item 2, Inbox "where did it go"): two new tables (inbox_sorted, quick_notes), nothing changed in v8's.
    // v10 (core round, projects): projects.icon/area/parent/archived/finishedAt/shipped/goals/hidden, tasks.milestone, defaults.
    // v11 (the Namoz page): three new tables (namoz_marks, namoz_learning, namoz_settings), nothing changed in v10's.
    // v12 (round D checker 2): the adopted_ids table.
    // v14 (Today GTD): tasks.context with a NULL default.
    // v13 (core round 4, Today's starting machine): tasks.micro with a '[]' default; two new tables (today_sorts, today_state).
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14)],
)
abstract class PagelDatabase : RoomDatabase() {
    abstract fun dao(): PagelDao
    abstract fun inbox(): InboxDao
    abstract fun rituals(): RitualsDao
    abstract fun habitPrefs(): HabitPrefsDao
    abstract fun inboxLog(): InboxLogDao
    abstract fun namoz(): NamozDao

    companion object {
        const val NAME = "pagel.db"
        /** The schema version the app opens (migration tests read it, so a new version is one edit). */
        const val VERSION = 14
    }
}
