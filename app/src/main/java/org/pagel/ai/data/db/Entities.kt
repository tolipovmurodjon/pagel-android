package org.pagel.ai.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The id prefix of a task made on the phone and not yet on pagel.uz (it gets the server's id once sent). */
const val LOCAL_ID = "local_"

/**
 * The phone's own ids pagel.uz has renamed (adoptServerId), kept in Room: a toast's Undo holds the id from the moment of
 * the add, and the add often reaches pagel.uz inside the toast's 5 s. Every write path resolves the id first
 * ([PagelDao.currentId]); a table, not memory, so process death inside the 5 s cannot lose the link.
 */
@Entity(tableName = "adopted_ids")
data class AdoptedIdEntity(@PrimaryKey val oldId: String, val newId: String, val savedAt: Long)

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
    /** Core round (v10): "lucide:<name>" or "emoji:<char>"; null = the project's default line icon. */
    @ColumnInfo(defaultValue = "NULL") val icon: String? = null,
    /** The life Area it belongs to (Health, Games…); null = no area. */
    @ColumnInfo(defaultValue = "NULL") val area: String? = null,
    /** A sub-project's parent (2 levels at most). */
    @ColumnInfo(defaultValue = "NULL") val parent: String? = null,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /** When it was finished (an ISO instant); null = still running. */
    @ColumnInfo(defaultValue = "NULL") val finishedAt: String? = null,
    /** "What I shipped": one line saved at the finish. */
    @ColumnInfo(defaultValue = "NULL") val shipped: String? = null,
    /** The project's 1-year goals and their milestones as pagel.uz sends them (JSON, [ProjectGoals]). */
    @ColumnInfo(defaultValue = "'[]'") val goals: String = "[]",
    /** Hidden on the phone while a delete or merge waits inside its Undo window. */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
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
    /** A habit done N times a day (v7, round B: "log food" 3x): each tap counts, the Nth moves it on. */
    @ColumnInfo(defaultValue = "1") val times: Int = 1,
    /** The habit's group (v8, round D): "namoz" for the five prayers; pagel.uz sends `group`, an older one none. */
    @ColumnInfo(defaultValue = "NULL") val habitGroup: String? = null,
    /** The project goal's milestone this task moves (v10, core round); null = none. */
    @ColumnInfo(defaultValue = "NULL") val milestone: String? = null,
    /**
     * Micro-steps (v13, core round 4): the funny tiny steps "Make it tiny" wrote, as a JSON list of strings. Separate from
     * the GTD [StepEntity] steps: disposable scaffolding for Focus mode, never shown in lists; synced as `micro`.
     */
    @ColumnInfo(defaultValue = "'[]'") val micro: String = "[]",
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

/**
 * Boss's own order for one day's Today (v13, core round 4): a fractional sort per task, so one drag is one PATCH of one
 * row (`today_sort` + `today_day`). Kept apart from [TaskEntity], so a sync never wipes it; a task without a row keeps
 * pagel.uz's place (its position in [TodayOrderEntity]).
 */
@Entity(tableName = "today_sorts")
data class TodaySortEntity(
    @PrimaryKey val taskId: String,
    val day: String,
    val sort: Double,
)

/** Today's screen choices for one day (v13): the full list open or folded to 3, and the energy tapped for What now. */
@Entity(tableName = "today_state")
data class TodayStateEntity(
    @PrimaryKey val id: String = ID,
    val day: String,
    val expanded: Boolean = false,
    /** "low", "ok" or "high"; null = not asked yet today. */
    val energy: String? = null,
) {
    companion object {
        const val ID = "today"
    }
}

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
        /** One capture into the Inbox (Inbox v3): [taskId] holds the local file `capture/<id>`, [body] a CaptureOp. */
        const val CAPTURE = "capture"
        /** A dump for the AI to split (Phase 5): [taskId] holds the sweep's id; its text lives in `sweeps`. */
        const val SWEEP = "sweep"
        /** A ritual closed on the phone (Phase 5): [body] is the `POST /api/v1/rituals` JSON, [taskId] its own id. */
        const val RITUAL = "ritual"
        /** A project change (core round): [taskId] = "project:<name>", [body] = `{"method", "path", "body", "hold"?}`. */
        const val PROJECT = "project"
        /** A use count (KPI): [body] = the `POST /api/v1/kpi` JSON. Plain numbers, never words. */
        const val KPI = "kpi"
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
    /** Taps that day (v7): a habit is done on a day once n reaches its times. */
    @ColumnInfo(defaultValue = "1") val n: Int = 1,
)

/**
 * Boss's own touches on a habit (v8, round D), kept on this phone: the emoji he picked, the habit it follows ("after
 * coffee"), the line he wrote about who he is ("I'm someone who trains 3x a week") and a timed habit's minutes when its
 * title does not say. Null = not set (the title decides).
 */
@Entity(tableName = "habit_prefs")
data class HabitPrefEntity(
    @PrimaryKey val taskId: String,
    val emoji: String? = null,
    val after: String? = null,
    val identity: String? = null,
    val minutes: Int? = null,
)

/** A streak freeze (v8, round D, Duolingo's idea): a sick or travel day that holds the chain; 2 a month. */
@Entity(tableName = "habit_freezes", primaryKeys = ["day", "taskId"])
data class HabitFreezeEntity(
    val day: String,
    val taskId: String,
)

/**
 * One Inbox decision of the day (v9, round D item 2, Boss: "where did it go"): what was sorted, where it went (a project's
 * tasks or notes, Later), the line Boss wrote, and its outbox row (Undo while it waits on the phone).
 */
@Entity(tableName = "inbox_sorted", indices = [Index("day")])
data class InboxSortedEntity(
    @PrimaryKey val file: String,
    val day: String,
    val action: String,
    val claim: String,
    val project: String?,
    val line: String?,
    val due: String?,
    val opId: Long,
    val at: Long,
)

/** A project's quick notes as pagel.uz last sent them (v9, round D item 2): readable offline. */
@Entity(tableName = "quick_notes", primaryKeys = ["project", "position"])
data class QuickNoteEntity(
    val project: String,
    val position: Int,
    val text: String,
    val link: String?,
    val picture: String?,
    val day: String?,
)

/** A habit's reminder (Phase 3): on = a notification at its time, with a Done button. Only on this phone. */
@Entity(tableName = "habit_reminders")
data class HabitReminderEntity(
    @PrimaryKey val taskId: String,
    val enabled: Boolean,
)

/**
 * One "Empty your head" dump (Phase 5, the web's /sweep). Saved as Boss types ([state] draft), so nothing he wrote is
 * ever lost; "Turn into steps" makes it waiting: the outbox sends it to pagel.uz's AI when online (reading), and the rows
 * come back as JSON ([rows], `[{kind, text, project}]`) that he edits here before "Add N" turns them into tasks (added,
 * with the phone's ids of those tasks in [addedIds], for Undo). A refusal (the AI could not split it, the day's cap)
 * is failed with pagel.uz's words in [message]; the dump stays for another try.
 */
@Entity(tableName = "sweeps")
data class SweepEntity(
    @PrimaryKey val id: String,
    val dump: String,
    val state: String,
    val rows: String = "[]",
    val message: String? = null,
    val addedIds: String = "",
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val DRAFT = "draft"
        const val WAITING = "waiting"
        const val READING = "reading"
        const val READY = "ready"
        /** "Add N" is running: claimed once (compare-and-set), so a second tap adds nothing. */
        const val ADDING = "adding"
        const val FAILED = "failed"
        const val ADDED = "added"
    }
}

/**
 * The rituals' state on the phone (Phase 5): what pagel.uz said at the last refresh (`GET /api/v1/rituals`) merged with
 * what was closed here since ([shutDay], [lastReview] are ritual days: until 05:00 it is still last night), the stuck
 * projects as JSON (`[{project, title}]`), and the evening "Close the day" reminder (only on this phone).
 */
@Entity(tableName = "ritual_state")
data class RitualStateEntity(
    @PrimaryKey val id: String = ID,
    val shutDay: String? = null,
    val lastReview: String? = null,
    val stuck: String = "[]",
    val fetchedAt: Long = 0,
    val eveningOn: Boolean = true,
    /** Boss's own day close (brain card me/body-tracking); a phone that saved a time keeps it. */
    val eveningAt: String = "20:45",
) {
    companion object {
        const val ID = "rituals"
    }
}
