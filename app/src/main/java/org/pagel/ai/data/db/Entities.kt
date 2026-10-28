package org.pagel.ai.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The id prefix of a task made on the phone and not yet on pagel.uz (it gets the server's id once sent). */
const val LOCAL_ID = "local_"

/** How a row stands against the server. Phase 1 only reads (everything is SYNCED); local edits arrive with Today. */
object SyncStatus {
    const val SYNCED = "synced"
    const val PENDING = "pending"
}

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val name: String,
    val title: String,
    val color: String?,
    val sort: Int,
    val openCount: Int,
)

/** One task as pagel.uz has it. Conflict rule: the server wins by event order; a local edit is never dropped silently. */
@Entity(tableName = "tasks", indices = [Index("project"), Index("status")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val project: String,
    val phase: String,
    val sort: Double,
    val text: String,
    val title: String?,
    val status: String,
    val priority: Int,
    val star: Boolean,
    val due: String?,
    val every: String?,
    val doneWhen: String?,
    val doneAt: String?,
    val updatedAt: String,
    val serverVersion: Int,
    val syncStatus: String = SyncStatus.SYNCED,
    val lastModified: Long,
    /** The phase's place in its plan and whether it is the NOW phase (v3): the project screen groups by them. */
    @ColumnInfo(defaultValue = "0") val phaseSort: Int = 0,
    @ColumnInfo(defaultValue = "0") val phaseNow: Boolean = false,
)

@Entity(tableName = "steps", primaryKeys = ["taskId", "position"], indices = [Index("taskId")])
data class StepEntity(
    val taskId: String,
    val position: Int,
    val text: String,
    val title: String?,
    val done: Boolean,
)

/** The last server event id the phone has applied: the next sync asks only for what came after it. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: String = ID,
    val cursor: Long,
    val lastSyncAt: Long,
) {
    companion object {
        const val ID = "pagel"
    }
}

/** Today's order exactly as pagel.uz deals it (`GET /api/v1/today`), so the phone and the web never disagree. */
@Entity(tableName = "today_order")
data class TodayOrderEntity(
    @PrimaryKey val position: Int,
    val taskId: String,
)

/** A change made on the phone, waiting to reach pagel.uz. Sent in order by [org.pagel.ai.data.today.OutboxWorker]. */
@Entity(tableName = "outbox", indices = [Index("taskId")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val op: String,
    val createdAt: Long,
    val attempts: Int = 0,
    /** null while waiting; a message once pagel.uz refused it (the change was rolled back and the UI tells Boss). */
    val refused: String? = null,
    /** A [PATCH]'s JSON `{"set": {wire fields}, "was": {the phone's values before}}` (v3); `was` rolls a refusal back. */
    val body: String? = null,
    /**
     * pagel.uz answered 200 without naming the new fields in `applied`: an older server ignored them. The change stays
     * here ("waits for pagel.uz"), is tried again on the next pass, and is never rolled back or dropped.
     */
    @ColumnInfo(defaultValue = "0") val waitsForServer: Boolean = false,
) {
    companion object {
        const val DONE = "done"
        const val UNDONE = "undone"
        /** A task made on the phone; its row in `tasks` holds what is sent. */
        const val ADD = "add"
        /** Any edit from the task panel: `PATCH /api/v1/tasks/<id>` with [body]'s `set`. */
        const val PATCH = "patch"
        /** One Inbox choice (Phase 2): [taskId] holds the save's file, [body] the `POST /api/v1/inbox` JSON. */
        const val SORT = "sort"
    }
}

/**
 * One save waiting in the Inbox (Phase 2), as pagel.uz deals it (`GET /api/v1/inbox`). [position] is the phone's deck
 * order: the card on top keeps its place when the deck is refreshed, so a card never jumps under Boss's finger.
 * [sortedAs] is null while it waits; the choice once Boss sorted it here and the choice waits in the outbox.
 */
@Entity(tableName = "inbox_items", indices = [Index("position")])
data class InboxItemEntity(
    @PrimaryKey val file: String,
    val position: Int,
    val saved: String,
    val source: String,
    val text: String,
    val claim: String,
    val why: String,
    val project: String,
    val kind: String,
    val next: String,
    val note: String,
    val guessed: Boolean,
    val author: String,
    val posted: String,
    val later: String?,
    val hasPicture: Boolean,
    val sortedAs: String? = null,
)

/** The Inbox's day on the phone: the 20-a-day goal and its counts (pagel.uz's, plus choices still on their way). */
@Entity(tableName = "inbox_state")
data class InboxStateEntity(
    @PrimaryKey val id: String = ID,
    val day: String,
    val goal: Int,
    val sorted: Int,
    val keptToday: Int,
    val cap: Int,
    val minWords: Int,
    /** Saves on pagel.uz at the last refresh, less the ones sent since. */
    val total: Int,
    val fetchedAt: Long,
    /**
     * When a choice last reached pagel.uz (v5). A refresh whose GET left before it carries counts from before that
     * choice, so it may only raise them, never step the ring back (checker 2026-10-28 #3).
     */
    @ColumnInfo(defaultValue = "0") val sentAt: Long = 0,
) {
    companion object {
        const val ID = "inbox"
    }
}

/**
 * One day a habit was ticked (Phase 3): pagel.uz's history (`GET /api/v1/habits` ticks) plus the phone's own ticks
 * still on their way. Streaks and "Done today" are counted from these, so a tick shows its flame at once, offline.
 */
@Entity(tableName = "habit_ticks", primaryKeys = ["day", "taskId"], indices = [Index("taskId")])
data class HabitTickEntity(
    val day: String,
    val taskId: String,
)

/** A habit's reminder (Phase 3): on = a notification at its time, with a Done button. Only on this phone. */
@Entity(tableName = "habit_reminders")
data class HabitReminderEntity(
    @PrimaryKey val taskId: String,
    val enabled: Boolean,
)
