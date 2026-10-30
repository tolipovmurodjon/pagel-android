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
    /** Taps a habit needs a day (round B); an older pagel.uz sends none = 1. */
    val times: Int = 1,
    /** A habit's group (round D): "namoz" for the five prayers; an older pagel.uz sends none. */
    val group: String? = null,
    /** The project goal's milestone (core round); an older pagel.uz sends none. */
    val milestone: String? = null,
    /** Micro-steps (core round 4): "Make it tiny"'s funny small steps; an older pagel.uz sends none. */
    val micro: List<String> = emptyList(),
    /** Today's own order (core round 4): the task's sort on [todayDay]'s Today, set by a drag; null = pagel.uz's place. */
    @SerialName("today_sort") val todaySort: Double? = null,
    @SerialName("today_day") val todayDay: String? = null,
)

@Serializable
data class SyncProjectDto(
    val name: String, val title: String, val color: String? = null, val sort: Int = 0, val open: Int = 0,
    // Core round (2026-10-29); an older pagel.uz sends none of these.
    val icon: String? = null, val area: String? = null, val parent: String? = null, val archived: Boolean = false,
    @SerialName("finished_at") val finishedAt: String? = null, val shipped: String? = null,
    val goals: kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(emptyList()),
)

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
data class NewTaskBody(
    val project: String, val text: String, val priority: Int, val due: String? = null, val every: String? = null,
    /** The phone's own id for this add (round B): a retry after a lost reply gets the same task back, never a second. */
    @SerialName("client_id") val clientId: String? = null,
    /** A habit's group (round D, "namoz"); left out when null, so an older pagel.uz never sees it. */
    val group: String? = null,
)

/** The created task; only its id is needed here, the next sync brings the rest. */
@Serializable
data class CreatedTaskDto(val id: String)

/** `POST /api/v1/tiny`: a proposal of micro-steps for one task (nothing saved); spice 1 = few … 3 = tiny tiny steps. */
@Serializable
data class TinyBody(val id: String, val spice: Int)

@Serializable
data class TinyReply(val steps: List<String> = emptyList())

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
    /** The whole deck by where each save came from (Instagram, X, typed…); an older pagel.uz sends none. */
    @SerialName("by_source") val bySource: Map<String, Int> = emptyMap(),
    /** How many saves in the whole deck are 60+ days old (the tidy-up's real count; a page holds at most 60). */
    @SerialName("older_than_60") val olderThan60: Int? = null,
)

/** `POST /api/v1/inbox`: one choice on one save. null fields are left out. */
@Serializable
data class SortBody(
    val action: String,
    val file: String,
    val sentence: String? = null,
    val project: String? = null,
    val someday: Boolean? = null,
    /** An Act's date (round D item 2: When? Today · Tomorrow); an older pagel.uz ignores it. */
    val due: String? = null,
    /** A bulk tidy-up part (Inbox clarify fix 2): pagel.uz commits it as "(tidy)" and it never counts toward the day's 20. */
    val quiet: Boolean? = null,
)

/** `GET /api/v1/notes?project=` (round D item 2): one project's quick notes, newest first. */
@Serializable
data class QuickNoteDto(val text: String, val link: String? = null, val picture: String? = null, val day: String? = null)

@Serializable
data class QuickNotesReply(val project: String = "", val notes: List<QuickNoteDto> = emptyList())

@Serializable
data class NoteProjectDto(val project: String, val count: Int = 0, val last: String? = null)

@Serializable
data class NoteProjectsReply(val projects: List<NoteProjectDto> = emptyList())

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
data class HabitTickDto(val day: String, val id: String, val n: Int = 1)

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

/** One row of a split dump (dashboard src/lib/sweep.ts): do it soon, maybe later, or nothing to do. */
@Serializable
data class SweepRowDto(val kind: String = "action", val text: String = "", val project: String = "me")

/** `POST /api/v1/sweep {id, dump}` (dashboard src/lib/ritual-api.ts). */
@Serializable
data class SweepBody(val id: String, val dump: String)

@Serializable
data class SweepReply(val rows: List<SweepRowDto> = emptyList())

/** `GET /api/v1/rituals`: the ritual day, closed tonight, the last weekly review, the projects with no next step. */
@Serializable
data class StuckDto(val project: String, val title: String = "")

@Serializable
data class RitualsResponse(
    val day: String = "",
    @SerialName("shut_today") val shutToday: Boolean = false,
    @SerialName("last_review") val lastReview: String? = null,
    @SerialName("review_due") val reviewDue: Boolean = false,
    val stuck: List<StuckDto> = emptyList(),
)

/** `POST /api/v1/rituals`: a ritual closed on the phone; the task changes went as their own PATCH/POST. */
@Serializable
data class RitualBody(
    val action: String,
    val id: String,
    val day: String,
    val kept: Int = 0,
    val pushed: Int = 0,
    val dropped: Int = 0,
    val picked: Int = 0,
    val added: Int = 0,
)

@Serializable
data class RitualReply(val ok: Boolean = false, val already: Boolean = false)

/** One day of real use (`GET /api/v1/kpi/week`): ticks by where they came from, app opens, open → first tick. */
@Serializable
data class KpiDayDto(
    val day: String,
    val done: Map<String, Int> = emptyMap(),
    val opens: Int = 0,
    @SerialName("first_tick_ms") val firstTickMs: Long? = null,
)

@Serializable
data class KpiWeekReply(
    val days: List<KpiDayDto> = emptyList(),
    @SerialName("projects_finished") val projectsFinished: Int = 0,
    @SerialName("days_opened") val daysOpened: Int = 0,
    val done: Int = 0,
)
