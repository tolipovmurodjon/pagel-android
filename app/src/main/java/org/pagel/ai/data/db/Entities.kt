package org.pagel.ai.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
