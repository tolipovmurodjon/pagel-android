package org.pagel.ai.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The wire shapes of pagel.uz's app API (dashboard src/lib/sync.ts, src/lib/devices.ts). Unknown fields are ignored,
// so the server can add fields before the app knows them.

@Serializable
data class PairRequest(val code: String, val name: String)

@Serializable
data class PairResponse(val id: String, val name: String, val token: String)

@Serializable
data class ErrorResponse(val error: String = "")

@Serializable
data class SyncStepDto(val text: String, val title: String? = null, val done: Boolean = false)

@Serializable
data class SyncTaskDto(
    val id: String,
    val project: String,
    val phase: String = "",
    @SerialName("phase_sort") val phaseSort: Int = 0,
    @SerialName("phase_now") val phaseNow: Boolean = false,
    val sort: Double = 0.0,
    val text: String,
    val title: String? = null,
    val status: String,
    val priority: Int = 2,
    val star: Boolean = false,
    val due: String? = null,
    val every: String? = null,
    @SerialName("done_when") val doneWhen: String? = null,
    @SerialName("done_at") val doneAt: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val version: Int = 1,
    val steps: List<SyncStepDto> = emptyList(),
)

@Serializable
data class SyncProjectDto(val name: String, val title: String, val color: String? = null, val sort: Int = 0, val open: Int = 0)

@Serializable
data class SyncResponse(
    val cursor: Long,
    val full: Boolean,
    val projects: List<SyncProjectDto> = emptyList(),
    val tasks: List<SyncTaskDto> = emptyList(),
    val removed: List<String> = emptyList(),
)

/** `GET /api/v1/today`: the web's Today in its dealt order (overdue, due soon, stars; it shows 3 at a time). */
@Serializable
data class TodayItemDto(val id: String)

@Serializable
data class TodayResponse(val day: String = "", @SerialName("shown_at_once") val shownAtOnce: Int = 3, val tasks: List<TodayItemDto> = emptyList())

/** `PATCH /api/v1/tasks/<id>`: done true finishes a task (on a habit: today's tick), false opens it again. */
@Serializable
data class DonePatch(val done: Boolean, val star: Boolean? = null)

/** `POST /api/v1/tasks`: null fields are left out (the JSON writer skips defaults), as the server expects. */
@Serializable
data class NewTaskBody(val project: String, val text: String, val priority: Int, val due: String? = null, val every: String? = null)

/** The created task; only its id is needed here, the next sync brings the rest. */
@Serializable
data class CreatedTaskDto(val id: String)

@Serializable
data class TaskStatusDto(val id: String, val status: String = "")

/**
 * The reply to a panel edit: the task plus `applied`, the fields this pagel.uz understood (dashboard task-api.ts).
 * An older server has no `applied` and ignores fields it does not know, so null means "not understood yet".
 */
@Serializable
data class PatchReply(val id: String = "", val status: String = "", val applied: List<String>? = null)

/** One save as `GET /api/v1/inbox` sends it (dashboard src/lib/inbox-api.ts). `kind` is the AI's guess, read only if [guessed]. */
@Serializable
data class InboxItemDto(
    val file: String,
    val saved: String = "",
    val source: String = "",
    val text: String = "",
    val claim: String = "",
    val why: String = "",
    val project: String = "all",
    val kind: String = "learn",
    val next: String = "",
    val note: String = "",
    val guessed: Boolean = false,
    val author: String = "",
    val posted: String = "",
    val later: String? = null,
    @SerialName("has_picture") val hasPicture: Boolean = false,
)

/** The Inbox deck (the next cards) and the day's counts: 20 sorted a day is the goal, [cap] keeps a day. */
@Serializable
data class InboxResponse(
    val day: String = "",
    val goal: Int = 20,
    val sorted: Int = 0,
    @SerialName("kept_today") val keptToday: Int = 0,
    val cap: Int = 5,
    @SerialName("min_words") val minWords: Int = 6,
    val total: Int = 0,
    val items: List<InboxItemDto> = emptyList(),
)

/** `POST /api/v1/inbox`: one choice on one save. null fields are left out. */
@Serializable
data class SortBody(
    val action: String,
    val file: String,
    val sentence: String? = null,
    val project: String? = null,
    val someday: Boolean? = null,
)

/** pagel.uz took the choice; `already` = it had happened before (a retry, or sorted on the web), never a refusal. */
@Serializable
data class SortReply(
    val ok: Boolean = false,
    val already: Boolean = false,
    val sorted: Int? = null,
    @SerialName("kept_today") val keptToday: Int? = null,
)

/** One day a habit was ticked, by task id (dashboard src/lib/habits-api.ts). */
@Serializable
data class HabitTickDto(val day: String, val id: String)

/** `GET /api/v1/habits`: the phone needs the ticks; the habits themselves come with /sync. */
@Serializable
data class HabitsResponse(
    val day: String = "",
    @SerialName("done_today") val doneToday: List<String> = emptyList(),
    val ticks: List<HabitTickDto> = emptyList(),
)

/** `POST /api/v1/inbox {action: "triage", files}` answers 202: started, or not (one start a minute); the reads arrive with the next deck. */
@Serializable
data class TriageReply(val started: Boolean = false, @SerialName("wait_s") val waitS: Int? = null)

/** One finished task from `GET /api/v1/completed`, with the Tashkent day it was finished (dashboard src/lib/sync.ts). */
@Serializable
data class CompletedResponse(
    val tasks: List<SyncTaskDto> = emptyList(),
    val before: String? = null,
    /** Every finished task on pagel.uz, as the web counts them (dashboard 9815038); null from an older pagel.uz. */
    val total: Int? = null,
)
