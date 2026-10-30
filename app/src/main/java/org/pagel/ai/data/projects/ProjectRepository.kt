package org.pagel.ai.data.projects

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.OutboxScheduler
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Serializable
data class Milestone(val id: String, val title: String, val due: String? = null, val done: Boolean = false)

/** A project's 1-year goal (normal synced data, visible to PAGEL; not the sealed Compass). */
@Serializable
data class ProjectGoal(val id: String, val title: String, val milestones: List<Milestone> = emptyList())

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")
private const val INBOX = "inbox"

/** "Your week" counted on this phone, so it works offline: tasks finished, projects they moved forward, habit-days kept. */
data class WeekCounts(val done: Int, val projectsMoved: Int, val habitsKept: Int)

private fun dayOf(iso: String): LocalDate? =
    runCatching { Instant.parse(iso).atZone(TASHKENT).toLocalDate() }.getOrNull() ?: runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()

private fun firstDayOfWeek(now: Long): LocalDate = Instant.ofEpochMilli(now).atZone(TASHKENT).toLocalDate().minusDays(6)

/** The last 7 Tashkent days, today included. Inbox is not a project; a habit-day counts once its taps reach its times. */
fun weekCounts(done: List<TaskEntity>, habits: List<TaskEntity>, ticks: List<HabitTickEntity>, now: Long): WeekCounts {
    val first = firstDayOfWeek(now)
    val inWeek = done.filter { t -> t.status == "done" && t.every.isNullOrEmpty() && t.doneAt?.let(::dayOf)?.let { !it.isBefore(first) } == true }
    val times = habits.associate { it.id to it.times.coerceAtLeast(1) }
    val kept = ticks.count { k -> times[k.taskId]?.let { k.n >= it } == true && dayOf(k.day)?.let { !it.isBefore(first) } == true }
    return WeekCounts(inWeek.size, inWeek.map { it.project }.filter { it != INBOX }.distinct().size, kept)
}

/** Days a project took, for its finish moment: its first finished task's day to today (Tashkent), at least 1; null if none. */
fun daysTakenOf(tasks: List<TaskEntity>, now: Long): Int? {
    val start = tasks.filter { it.every.isNullOrEmpty() }.mapNotNull { it.doneAt?.let(::dayOf) }.minOrNull() ?: return null
    val today = Instant.ofEpochMilli(now).atZone(TASHKENT).toLocalDate()
    return (ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceAtLeast(1)
}

fun goalsOf(raw: String): List<ProjectGoal> = runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ProjectGoal.serializer()), raw) }.getOrDefault(emptyList())

/** What a create gives back: the project's name (its id) and the outbox op that sends it. */
data class Created(val name: String, val op: Long)

/** Room between two neighbours' sorts, so a drag has a place to land without renumbering the rest. */
const val SORT_GAP = 1024

/** A whole-number sort strictly between [before] and [after] (either may be absent); null when they touch. */
fun sortBetween(before: Int?, after: Int?): Int? = when {
    before == null && after == null -> 0
    before == null -> after!! - SORT_GAP
    after == null -> before + SORT_GAP
    after - before > 1 -> before + (after - before) / 2
    else -> null
}

/** The ten tile swatches in the order a new project takes them (rules/gradient-project-icons). */
val SWATCHES = listOf("mint", "blue", "violet", "coral", "amber", "pink", "teal", "sky", "red", "slate")

/** pagel.uz's project names: lower-case letters, digits, dashes; the phone picks one so a retry never makes a twin. */
fun slugOf(title: String, taken: Collection<String>): String {
    val s = java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFKD)
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(28)
    val base = if (s.length >= 2 && s[0].isLetter()) s else "p-${s.ifEmpty { "new" }}".take(28)
    if (base !in taken) return base
    var i = 2
    while ("${base.take(25)}-$i" in taken) i++
    return "${base.take(25)}-$i"
}

/**
 * Projects you shape (core round, 2026-10-29): every change writes Room first (the screen answers in one frame), then
 * waits in the outbox as one `project` op sent to pagel.uz (projects-api.ts). Conflict rule: a project with a change
 * still waiting keeps the phone's copy (PagelDao.applySync); once pagel.uz has it, the server's copy wins again. A
 * delete and a merge hold for [org.pagel.ai.data.tasks.UNDO_WINDOW_MS]: Undo inside it costs pagel.uz nothing.
 */
class ProjectRepository(
    private val dao: PagelDao,
    private val tasks: TaskRepository,
    private val scheduler: OutboxScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun projects(): Flow<List<ProjectEntity>> = dao.projects()

    /** The projects touched last, newest first (at most 3). */
    fun recent(): Flow<List<String>> = dao.recentProjects()

    /** "Your week" from Room alone: the numbers are right offline and right after a tick, before any sync. */
    fun week(): Flow<WeekCounts> =
        combine(dao.completed(), dao.habits(), dao.habitTicks(firstDayOfWeek(clock()).toString())) { done, habits, ticks -> weekCounts(done, habits, ticks, clock()) }

    /** The finish moment's "over N days". */
    fun daysTaken(project: List<TaskEntity>): Int? = daysTakenOf(project, clock())

    /** A new project from its name alone (2 taps: + then Enter); the next swatch colours it. Returns its name. */
    suspend fun create(title: String, color: String? = null, area: String? = null, parent: String? = null): String =
        make(title, color, area, parent).name

    /** [create] for a caller that can offer Undo (the picker): also gives the op that sends it, for [undoCreate]. */
    suspend fun createUndoable(title: String): Created = make(title, null, null, null)

    /** The project already called [title] (case and spacing ignored), so the picker can pick it instead of making a twin. */
    suspend fun existing(title: String): ProjectEntity? {
        val want = title.trim().lowercase().replace(Regex("""\s+"""), " ")
        return dao.projectsOnce().firstOrNull { !it.hidden && (it.title.trim().lowercase().replace(Regex("""\s+"""), " ") == want || it.name == slugOf(title, emptyList())) }
    }

    private suspend fun make(title: String, color: String?, area: String?, parent: String?): Created {
        val names = dao.projectNames()
        val name = slugOf(title, names)
        val all = dao.projectsOnce()
        // First colour no project uses yet, so new projects don't repeat colours.
        val used = all.mapNotNull { it.color }.toSet()
        val pick = color ?: SWATCHES.firstOrNull { it !in used } ?: SWATCHES[all.size % SWATCHES.size]
        val sort = (all.maxOfOrNull { it.sort } ?: -1) + 1
        dao.upsertProjects(listOf(ProjectEntity(name, title.trim(), pick, sort, 0, area = area, parent = parent)))
        // The phone's slug is the project's id: pagel.uz keeps it, and a retry of a POST that already landed answers 200
        // (projects-api.ts), so a network drop never makes a twin. The tasks linked to it queue after this op, in order.
        val op = send(name, "POST", "/api/v1/projects", buildJsonObject { put("name", name); put("title", title.trim()); put("color", pick); if (area != null) put("area", area) })
        if (parent != null) send(name, "PATCH", path(name), buildJsonObject { put("parent", parent) })
        return Created(name, op)
    }

    /**
     * Undo of a create: a real inverse write. Still waiting in the outbox → both vanish and pagel.uz never hears of it;
     * already sent → a delete reaches pagel.uz. Tasks that were linked to it go back to Inbox, on the phone and there.
     */
    suspend fun undoCreate(created: Created) {
        val name = created.name
        val p = dao.projectOnce(name) ?: return
        val linked = dao.taskIdsOf(name)
        if (linked.isNotEmpty()) dao.moveTasksLocal(linked, INBOX, clock())
        if (dao.takeBackOp(created.op) > 0) { dao.deleteProjectsExcept(dao.projectNames() - name); return }
        dao.upsertProjects(listOf(p.copy(hidden = true)))
        send(name, "PATCH", path(name), buildJsonObject { put("deleted", true); put("tasks", "inbox") })
    }

    suspend fun rename(name: String, title: String) = patch(name, buildJsonObject { put("title", title.trim()) }) { it.copy(title = title.trim()) }
    suspend fun color(name: String, color: String) = patch(name, buildJsonObject { put("color", color) }) { it.copy(color = color) }
    suspend fun icon(name: String, icon: String?) = patch(name, buildJsonObject { put("icon", icon) }) { it.copy(icon = icon) }
    suspend fun area(name: String, area: String?) = patch(name, buildJsonObject { put("area", area) }) { it.copy(area = area) }
    suspend fun parent(name: String, parent: String?) = patch(name, buildJsonObject { put("parent", parent) }) { it.copy(parent = parent) }
    suspend fun archive(name: String, on: Boolean) = patch(name, buildJsonObject { put("archived", on) }) { it.copy(archived = on) }

    /** The finish line: done, with a "what I shipped" line saved to it; it moves to Done projects. */
    suspend fun finish(name: String, shipped: String) {
        val at = Instant.ofEpochMilli(clock()).toString()
        patch(name, buildJsonObject { put("finished", true); put("shipped", shipped.trim().ifEmpty { null }) }) { it.copy(finishedAt = at, shipped = shipped.trim().ifEmpty { null }) }
    }

    suspend fun reopen(name: String) = patch(name, buildJsonObject { put("finished", false) }) { it.copy(finishedAt = null) }

    suspend fun goals(name: String, goals: List<ProjectGoal>) {
        val raw = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProjectGoal.serializer()), goals)
        patch(name, buildJsonObject { put("goals", json.parseToJsonElement(raw)) }) { it.copy(goals = raw) }
    }

    /** A new order of the project list (long-press drag): every project whose place changed gets its new sort. */
    suspend fun order(names: List<String>) {
        val now = dao.projectsOnce().associateBy { it.name }
        names.forEachIndexed { i, n ->
            val p = now[n] ?: return@forEachIndexed
            if (p.sort != i) patch(n, buildJsonObject { put("sort", i) }) { it.copy(sort = i) }
        }
    }

    /**
     * One drag = one PATCH of one row: [name] lands right after [after] (or right before [before] when it leads). Its sort
     * goes between its new neighbours ([sortBetween]); only when they touch is the list spaced out once ([SORT_GAP]) and
     * the rows whose sort changed are sent. The order is the same in the picker, the Projects tab and Move to.
     */
    suspend fun place(name: String, after: String?, before: String? = null) {
        val rest = dao.projectsOnce().filter { !it.hidden }.sortedBy { it.sort }.filter { it.name != name }
        val at = when {
            after != null -> rest.indexOfFirst { it.name == after } + 1
            before != null -> rest.indexOfFirst { it.name == before }.coerceAtLeast(0)
            else -> rest.size
        }
        val sort = sortBetween(rest.getOrNull(at - 1)?.sort, rest.getOrNull(at)?.sort)
        if (sort != null) { patch(name, buildJsonObject { put("sort", sort) }) { it.copy(sort = sort) }; return }
        val spaced = rest.map { it.name }.toMutableList().apply { add(at, name) }
        val now = dao.projectsOnce().associateBy { it.name }
        spaced.forEachIndexed { i, n -> val s = (i + 1) * SORT_GAP; if (now[n]?.sort != s) patch(n, buildJsonObject { put("sort", s) }) { it.copy(sort = s) } }
    }

    /** Delete: hidden at once; it leaves after the Undo window. Its open tasks go to Inbox, or drop with it ([dropTasks]). */
    suspend fun delete(name: String, dropTasks: Boolean): Long? {
        val p = dao.projectOnce(name) ?: return null
        dao.upsertProjects(listOf(p.copy(hidden = true)))
        return send(name, "PATCH", path(name), buildJsonObject { put("deleted", true); put("tasks", if (dropTasks) "drop" else "inbox") }, hold = true)
    }

    suspend fun undoDelete(name: String, op: Long?) {
        val p = dao.projectOnce(name) ?: return
        dao.upsertProjects(listOf(p.copy(hidden = false)))
        if (op != null && dao.takeBackOp(op) > 0) return
        send(name, "PATCH", path(name), buildJsonObject { put("deleted", false) })
    }

    /** Merge [from] into [into]: every task moves, [from] is archived (kept for Undo). Returns the op, for Undo. */
    suspend fun merge(from: String, into: String): Long? {
        val p = dao.projectOnce(from) ?: return null
        val ids = dao.taskIdsOf(from)
        dao.moveTasksLocal(ids, into, clock())
        dao.upsertProjects(listOf(p.copy(hidden = true, archived = true)))
        val body = buildJsonObject { put("method", "POST"); put("path", path(from) + "/merge"); put("body", buildJsonObject { put("into", into) }); put("hold", true)
            putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } } }
        return op(from, body)
    }

    suspend fun undoMerge(from: String, into: String, op: Long?) {
        val row = op?.let { dao.outboxRow(it) }
        val ids = row?.body?.let { (json.parseToJsonElement(it) as JsonObject)["ids"] as? JsonArray }?.map { it.jsonPrimitive.content } ?: dao.taskIdsOf(into)
        val p = dao.projectOnce(from) ?: return
        dao.upsertProjects(listOf(p.copy(hidden = false, archived = false)))
        if (op != null && dao.takeBackOp(op) > 0) { dao.moveTasksLocal(ids, from, clock()); return }
        // It already left: the tasks move back one by one and the project opens again.
        for (id in ids) tasks.move(id, from)
        send(from, "PATCH", path(from), buildJsonObject { put("archived", false) })
    }

    /**
     * "Turn into project": the task's name names a new project in the same area; its open steps become the project's
     * tasks in order (the first is the tiny next step), and the task leaves (restorable). A task with no steps moves in.
     */
    suspend fun turnIntoProject(taskId: String, add: suspend (text: String, project: String) -> String): String? {
        val t = dao.taskOnce(taskId)?.takeIf { it.every.isNullOrEmpty() } ?: return null
        val area = dao.projectOnce(t.project)?.area
        val name = create(t.title?.takeIf(String::isNotBlank) ?: t.text, area = area)
        val steps = dao.stepsOnce(taskId).filter { !it.done }
        if (steps.isEmpty()) tasks.move(taskId, name)
        else {
            for (s in steps) add(s.title?.takeIf(String::isNotBlank) ?: s.text, name)
            tasks.delete(taskId)
        }
        return name
    }

    /** One app open today (KPI: days opened). The first tick's time is counted by [Kpi] from Today's tick. */
    suspend fun recordOpen() {
        Kpi.opened(clock())
        kpi(buildJsonObject { put("day", day()); put("open", true) })
    }

    suspend fun kpi(body: JsonObject) {
        dao.addToOutbox(OutboxEntity(taskId = "kpi", op = OutboxEntity.KPI, createdAt = clock(),
            body = buildJsonObject { put("method", "POST"); put("path", "/api/v1/kpi"); put("body", body) }.toString()))
        scheduler.kick()
    }

    private fun day() = Instant.ofEpochMilli(clock()).atZone(ZoneId.of("Asia/Tashkent")).toLocalDate().toString()

    private suspend fun patch(name: String, set: JsonObject, change: (ProjectEntity) -> ProjectEntity): Long? {
        val p = dao.projectOnce(name) ?: return null
        dao.upsertProjects(listOf(change(p)))
        return send(name, "PATCH", path(name), set)
    }

    private suspend fun send(name: String, method: String, path: String, body: JsonElement, hold: Boolean = false): Long =
        op(name, buildJsonObject { put("method", method); put("path", path); put("body", body); if (hold) put("hold", true) })

    private suspend fun op(name: String, body: JsonObject): Long {
        val id = dao.addToOutbox(OutboxEntity(taskId = "project:$name", op = OutboxEntity.PROJECT, createdAt = clock(), body = body.toString()))
        scheduler.kick()
        return id
    }

    private fun path(name: String) = "/api/v1/projects/" + URLEncoder.encode(name, "UTF-8")

}

/**
 * The time from opening the app to the first tick of the day (KPI), counted once a day: the open sets the clock, the
 * first tick after it gives the number. In memory only; a number lost to a process death is simply not counted.
 */
object Kpi {
    @Volatile private var openedAt = 0L
    @Volatile private var countedDay: String? = null

    fun opened(at: Long) { openedAt = at }

    /** Tests only: forget the open and the counted day. */
    fun reset() { openedAt = 0L; countedDay = null }

    /** The ms from the open to this tick, or null when today's first tick was already counted (or no open was seen). */
    fun firstTick(at: Long, day: String): Long? {
        if (openedAt == 0L || countedDay == day || at < openedAt) return null
        countedDay = day
        return (at - openedAt).coerceAtMost(86_399_999L)
    }
}
