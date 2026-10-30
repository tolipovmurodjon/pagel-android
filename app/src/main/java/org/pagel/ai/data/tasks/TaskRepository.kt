package org.pagel.ai.data.tasks

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.StepEntity
import org.pagel.ai.data.db.SyncStatus
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.today.OutboxScheduler
import java.time.Instant
import java.time.ZoneId

/** PATCH fields only the 2026-10-27 pagel.uz knows; it names what it understood in `applied` (task-api.ts). */
val NEW_FIELDS = setOf("title", "every", "remove", "restore", "not_doing", "times", "project", "sort", "milestone")

/** How long a delete waits on the phone before it leaves, so Undo costs pagel.uz nothing (Gmail's "Undo send"). */
const val UNDO_WINDOW_MS = 5_000L

private val json = Json { ignoreUnknownKeys = true }

/**
 * Everything the task panel changes (NATIVE-PLAN Phase 1, the web's today/task-panel.tsx). Each edit writes Room at
 * once (the panel answers in one frame, rules/respond-first-save-after), then waits in the outbox as one PATCH:
 * `set` = the wire fields, `was` = the phone's values before, so a refusal rolls back exactly what it changed.
 * Fields an older server does not know go in their own op, so an old server never half-applies an edit.
 * Conflict rule: a task with a waiting edit keeps the phone's copy (PagelDao.applySync skips it); once pagel.uz has
 * it, the server's copy wins again.
 */
class TaskRepository(
    private val dao: PagelDao,
    private val scheduler: OutboxScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun task(id: String): Flow<TaskEntity?> = dao.task(id)
    fun steps(id: String): Flow<List<org.pagel.ai.data.db.StepEntity>> = dao.steps(id)
    /** How many of this task's edits wait for pagel.uz to learn the new fields. */
    fun held(id: String): Flow<Int> = dao.heldCount(id)
    /** Open tasks pinned to Today by a star (the panel's "Pin to Today" stops at 3). */
    fun pinned(): Flow<Int> = dao.starredOpenCount()
    fun openTasks(project: String): Flow<List<TaskEntity>> = dao.openTasks(project)

    fun projectTasks(project: String): Flow<List<TaskEntity>> = dao.projectTasks(project)

    suspend fun title(id: String, title: String?) {
        val t = title?.replace(Regex("\\s+"), " ")?.trim()?.take(120)?.ifEmpty { null }
        edit(id, buildJsonObject { put("title", t) }) { it.copy(title = t) }
    }

    /** The full note (the plan line the AI reads), cut at 240 like the server. Blank is ignored, as on the web. */
    suspend fun text(id: String, text: String) {
        val t = text.replace(Regex("\\s+"), " ").trim().take(240)
        if (t.isEmpty()) return
        edit(id, buildJsonObject { put("text", t) }) { it.copy(text = t) }
    }

    /** A date (YYYY-MM-DD or YYYY-MM-DD HH:MM, Tashkent) or none. Today's list follows the web: see [placeOnToday]. */
    suspend fun due(id: String, due: String?) =
        edit(id, buildJsonObject { put("due", due) }, place = true) { it.copy(due = due) }

    suspend fun priority(id: String, priority: Int) =
        edit(id, buildJsonObject { put("priority", priority.coerceIn(0, 3)) }) { it.copy(priority = priority.coerceIn(0, 3)) }

    suspend fun star(id: String, on: Boolean) =
        edit(id, buildJsonObject { put("star", on) }, place = true) { it.copy(star = on) }

    /** A repeat makes the task a habit: it gets a date (today) if it had none, and leaves Today for Habits. */
    suspend fun every(id: String, every: String?) {
        val today = tashkentDay()
        edit(id, buildJsonObject { put("every", every) }, place = true) { it.copy(every = every, due = if (every != null && it.due == null) today else it.due) }
    }

    /** How many taps a habit needs a day (the Repeat sheet's "3 times a day"); 1 = once. */
    suspend fun times(id: String, times: Int) {
        val n = times.coerceIn(1, 12)
        edit(id, buildJsonObject { put("times", n) }) { it.copy(times = n) }
    }

    suspend fun doneWhen(id: String, doneWhen: String) {
        val d = doneWhen.replace(Regex("\\s+"), " ").trim().replace(Regex("^done when:?\\s*", RegexOption.IGNORE_CASE), "").take(160)
        edit(id, buildJsonObject { put("done_when", d) }) { it.copy(doneWhen = d.ifEmpty { null }) }
    }

    /** The whole step list with its ticks: the server matches ticks by index, so both always go together. */
    suspend fun steps(id: String, steps: List<String>, ticked: List<Int>) {
        val old = dao.stepsOnce(id).associateBy { it.text }
        val clean = steps.map { s -> if (s in old) s else s.replace(Regex("\\s+"), " ").trim().take(120) }.filter(String::isNotEmpty)
        val ticks = ticked.filter { it in clean.indices }.sorted()
        val set = buildJsonObject {
            putJsonArray("steps") { clean.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("ticked") { ticks.forEach { add(JsonPrimitive(it)) } }
        }
        edit(id, set, local = { dao.replaceSteps(id, clean.mapIndexed { i, s -> StepEntity(id, i, s, old[s]?.title, i in ticks) }) }) { it }
    }

    /**
     * Won't do, with the real reason (the web's tp-stop form). The excuse check runs here first, as on the web, so an
     * excuse never leaves the phone. Returns why it was refused, or null when saved. The star and P4 go in one op an
     * older server already understands; the reason (written to the brain's not-doing list) in its own.
     */
    suspend fun notDoing(id: String, kind: String, reason: String): String? {
        if (kind !in NotDoing.KINDS) return "Pick why: blocked, someday or not worth it."
        NotDoing.check(reason)?.let { return it }
        val t = dao.taskOnce(id) ?: return "That task is gone."
        val someday = kind == "someday"
        val first = buildJsonObject {
            if (t.star) put("star", false)
            if (someday && t.priority != 3) put("priority", 3)
        }
        if (first.isNotEmpty()) edit(id, first, place = true) { it.copy(star = false, priority = if (someday) 3 else it.priority) }
        val r = reason.replace(Regex("\\s+"), " ").trim().take(300)
        edit(id, buildJsonObject { putJsonObject("not_doing") { put("kind", kind); put("reason", r) } }, place = true) {
            if (kind == "drop") it.copy(status = "dropped") else it
        }
        return null
    }

    /** Moves a task to another project (drag onto a project, core round): it lands at the end of that project. */
    suspend fun move(id: String, project: String): Long? =
        edit(id, buildJsonObject { put("project", project) }) { it.copy(project = project, phase = "", phaseNow = true, sort = 1e9) }

    /** A new place in its project's list (long-press drag): a sort between its new neighbours. */
    suspend fun sort(id: String, sort: Double): Long? = edit(id, buildJsonObject { put("sort", sort) }) { it.copy(sort = sort) }

    /** Links a task to one of its project's milestones (null unlinks). */
    suspend fun milestone(id: String, milestone: String?): Long? = edit(id, buildJsonObject { put("milestone", milestone) }) { it.copy(milestone = milestone) }

    /** Delete: gone from the phone at once; the remove leaves after [UNDO_WINDOW_MS]. Returns the op, for Undo. */
    suspend fun delete(id: String): Long? =
        edit(id, buildJsonObject { put("remove", true) }, place = true) { it.copy(status = "dropped") }

    /** Undo of a delete: inside its 5 s the op is taken back (pagel.uz never hears of it); after, a restore is sent. */
    suspend fun undoDelete(id: String, op: Long) {
        val row = dao.outboxRow(op)
        if (row != null && dao.takeBackOp(op) > 0) {
            wasOf(row)?.let { dao.applyWas(id, it) }
            dao.markSyncedIfIdle(id)
            return
        }
        edit(id, buildJsonObject { put("restore", true) }, place = true) { it.copy(status = "open") }
    }

    /**
     * Undo of a delete made from Completed ("Clear done"): inside its 5 s the op is taken back; after, pagel.uz gets
     * restore + done in one PATCH, because its restore alone opens the task (task-store.ts `restore`).
     */
    suspend fun undoDeleteDone(id: String, op: Long) {
        if (takeBack(id, op)) return
        edit(id, buildJsonObject { put("restore", true); put("done", true) }) { it.copy(status = "done") }
    }

    /**
     * Done from a list that is not Today (Upcoming): `PATCH {done: true}` through the same outbox as the panel's edits,
     * so its Undo ([takeBack], else [reopen]) never puts the task on Today the way Today's own tick Undo does.
     */
    suspend fun finish(id: String): Long? {
        val at = Instant.ofEpochMilli(clock()).toString()
        return edit(id, buildJsonObject { put("done", true) }, place = true) { it.copy(status = "done", doneAt = at) }
    }

    /** Opens a finished task again (Completed's untick, the server's undone path): `PATCH {done: false}`. */
    suspend fun reopen(id: String): Long? =
        edit(id, buildJsonObject { put("done", false) }, place = true) { it.copy(status = "open", doneAt = null) }

    /**
     * Takes an edit back while it still waits on the phone (an Undo inside the outbox): the phone goes back to `was` and
     * pagel.uz never hears of it. False when it already left; the caller then sends the opposite edit.
     */
    suspend fun takeBack(id: String, op: Long): Boolean {
        val row = dao.outboxRow(op) ?: return false
        if (dao.takeBackOp(op) == 0) return false
        wasOf(row)?.let { dao.applyWas(id, it, clock()) }
        dao.markSyncedIfIdle(id)
        return true
    }

    /** The day in Tashkent, where pagel.uz counts its days. */
    fun tashkentDay(): String = Instant.ofEpochMilli(clock()).atZone(TASHKENT).toLocalDate().toString()

    private suspend fun edit(id: String, set: JsonObject, place: Boolean = false, local: (suspend () -> Unit)? = null, change: (TaskEntity) -> TaskEntity): Long? {
        val before = dao.taskOnce(id) ?: return null
        val now = clock()
        // Read before building: the JSON builder is plain code, the reads are suspend calls.
        val oldSteps = if ("steps" in set || "ticked" in set) dao.stepsOnce(id) else emptyList()
        val wasToday = place && dao.onTodayCount(id) > 0
        val was = buildJsonObject {
            var stepsKept = false
            for (k in set.keys) when (k) {
                "title" -> put("title", before.title)
                "text" -> put("text", before.text)
                "due" -> put("due", before.due)
                "every" -> { put("every", before.every); put("due", before.due) }
                "times" -> put("times", before.times)
                "priority" -> put("priority", before.priority)
                "star" -> put("star", before.star)
                "done_when" -> put("done_when", before.doneWhen)
                "steps", "ticked" -> if (!stepsKept) { putSteps(oldSteps); stepsKept = true }
                "remove", "restore", "not_doing" -> put("status", before.status)
                "done" -> { put("status", before.status); put("done_at", before.doneAt) }
                "project" -> put("project", before.project)
                "sort" -> put("sort", before.sort)
                "milestone" -> put("milestone", before.milestone)
            }
            if (place) put("today", wasToday)
        }
        val after = change(before).copy(syncStatus = SyncStatus.PENDING, lastModified = now)
        dao.upsertTasks(listOf(after))
        local?.invoke()
        if (place) placeOnToday(after)
        val body = buildJsonObject { put("set", set); put("was", was) }
        val op = dao.addToOutbox(OutboxEntity(taskId = id, op = OutboxEntity.PATCH, createdAt = now, body = body.toString()))
        scheduler.kick()
        return op
    }

    /**
     * Today, as the web deals it (views.ts todayView): an open task that is starred, or dated today or earlier, and is
     * not a habit. A date later takes it off Today until its day; a habit lives on Habits.
     */
    private suspend fun placeOnToday(t: TaskEntity) {
        val day = tashkentDay()
        // A star with a later date waits for its day, as the web (views.ts todayView; checker P4 #6: a drag of a starred
        // task onto Friday left it on Today).
        val later = t.due != null && t.due.take(10) > day
        val on = t.status == "open" && t.every == null && ((t.star && !later) || (t.due != null && t.due.take(10) <= day))
        if (on) dao.ensureOnToday(t.id) else dao.removeFromToday(t.id)
    }

    companion object {
        val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putSteps(steps: List<StepEntity>) {
    putJsonArray("steps") { steps.forEach { add(JsonPrimitive(it.text)) } }
    putJsonArray("ticked") { steps.forEachIndexed { i, s -> if (s.done) add(JsonPrimitive(i)) } }
}

/** The `was` part of a panel op, or null for other ops. */
fun wasOf(op: OutboxEntity): JsonObject? =
    op.body?.let { runCatching { json.parseToJsonElement(it).jsonObject["was"]?.jsonObject }.getOrNull() }

/** The `set` part of a panel op (what is sent). */
fun sentOf(op: OutboxEntity): JsonObject? =
    op.body?.let { runCatching { json.parseToJsonElement(it).jsonObject["set"]?.jsonObject }.getOrNull() }

private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull

/**
 * Puts a task back the way `was` remembers it: a refused edit (the sender) or a delete taken back in time (Undo).
 * Only the fields the op changed are in `was`, so a later edit of another field stays.
 */
suspend fun PagelDao.applyWas(taskId: String, was: JsonObject, at: Long = System.currentTimeMillis()) {
    var t = taskOnce(taskId) ?: return
    for ((k, v) in was) t = when (k) {
        "title" -> t.copy(title = v.str())
        "text" -> t.copy(text = v.str() ?: t.text)
        "due" -> t.copy(due = v.str())
        "every" -> t.copy(every = v.str())
        "times" -> t.copy(times = v.jsonPrimitive.intOrNull ?: t.times)
        "priority" -> t.copy(priority = v.jsonPrimitive.intOrNull ?: t.priority)
        "star" -> t.copy(star = v.jsonPrimitive.booleanOrNull ?: t.star)
        "done_when" -> t.copy(doneWhen = v.str())
        "status" -> t.copy(status = v.str() ?: t.status)
        "done_at" -> t.copy(doneAt = v.str())
        "project" -> t.copy(project = v.str() ?: t.project)
        "sort" -> t.copy(sort = v.jsonPrimitive.doubleOrNull ?: t.sort)
        "milestone" -> t.copy(milestone = v.str())
        else -> t
    }
    upsertTasks(listOf(t.copy(lastModified = at)))
    (was["steps"] as? JsonArray)?.let { steps ->
        val ticked = (was["ticked"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()
        val titles = stepsOnce(taskId).associate { it.text to it.title }
        replaceSteps(taskId, steps.mapIndexed { i, s -> val text = s.str().orEmpty(); StepEntity(taskId, i, text, titles[text], i in ticked) })
    }
    // A habit's tick taken back (a refused tick) or given back (a refused Undo of one): its day in the habit log follows.
    (was["untick"] as? JsonPrimitive)?.contentOrNull?.let { deleteHabitTick(it, taskId) }
    (was["retick"] as? JsonPrimitive)?.contentOrNull?.let { addHabitTick(org.pagel.ai.data.db.HabitTickEntity(it, taskId)) }
    // A count habit's tap (or a dot) refused: that day goes back to the taps it had ({"day": n}, 0 = none).
    (was["taps"] as? JsonObject)?.forEach { (day, n) ->
        val c = n.jsonPrimitive.intOrNull ?: 0
        if (c > 0) setHabitTick(org.pagel.ai.data.db.HabitTickEntity(day, taskId, c)) else deleteHabitTick(day, taskId)
    }
    when ((was["today"] as? JsonPrimitive)?.booleanOrNull) {
        true -> ensureOnToday(taskId)
        false -> removeFromToday(taskId)
        null -> {}
    }
}
