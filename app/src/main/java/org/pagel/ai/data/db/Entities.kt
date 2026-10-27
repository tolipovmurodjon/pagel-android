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
    }
}
