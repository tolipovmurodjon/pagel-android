package org.pagel.ai.data.habits

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.HabitReminderEntity
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.SyncStatus
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.tasks.applyWas
import org.pagel.ai.data.tasks.wasOf
import org.pagel.ai.data.today.OutboxScheduler
import org.pagel.ai.ui.glass.Repeat
import java.time.Instant
import java.time.ZoneId

/** Plans the habits' reminder alarms again (after a tick, a reminder switched, a new time); a no-op in tests. */
fun interface ReminderPlanner {
    fun replan()
}

/** A tick on the phone: its outbox row (Undo takes it back while it waits) and the date it moved from. */
/** A tick's outbox row, the date it moved from, and the day it counted for (an Undo after midnight sends that day). */
data class Ticked(val op: Long, val was: String?, val day: String = "")

/**
 * Habits (Phase 3, the web's /habits): repeating tasks from /sync, plus the days each was ticked. A tick answers in
 * one frame: Room moves the habit to its next date by the web's repeat rule and counts today, then the tick waits in
 * the outbox as `PATCH {done: true}` (pagel.uz moves the date the same way and logs habit_done). Undo before it left
 * takes it back; after, it sends the old date back (`PATCH {due, undo: true}`, logged as habit_undone), as the web.
 * Conflict rule: a habit with a change waiting keeps the phone's date and ticks; once sent, the server's copy wins.
 */
class HabitsRepository(
    private val dao: PagelDao,
    private val api: PagelApi,
    private val scheduler: OutboxScheduler,
    private val reminders: ReminderPlanner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val habits: Flow<List<TaskEntity>> = dao.habits()
    /** When each habit was last ticked or unticked on this phone (see [refresh]). */
    private val touched = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val reminderRows: Flow<List<HabitReminderEntity>> = dao.reminders()

    fun today(): String = Instant.ofEpochMilli(clock()).atZone(TASHKENT).toLocalDate().toString()

    /** The ticks a streak can reach back to (the server sends [DAYS] days of them). */
    fun ticks(): Flow<List<HabitTickEntity>> = dao.habitTicks(java.time.LocalDate.parse(today()).minusDays(DAYS + 10L).toString())

    /**
     * Today's tick. Null when it is not due, or already ticked today (from the app or the notification's Done): a second
     * tick would move the habit twice on pagel.uz (checker 2026-10-28 #3). Date, day and outbox row are one transaction,
     * so the screen never sees the habit moved but not yet counted (#5). The PATCH carries the day it was made and the
     * date it moved from: sent after midnight it still counts for this day, and never moves a habit the web ticked (#2).
     */
    suspend fun tick(id: String): Ticked? {
        val t = dao.taskOnce(id) ?: return null
        val every = t.every?.takeIf(String::isNotBlank) ?: return null
        val today = today()
        if (t.status != "open" || (t.due != null && t.due.take(10) > today)) return null
        val times = t.times.coerceAtLeast(1)
        val taps = dao.habitTicksOf(id).firstOrNull { it.day == today }?.n ?: 0
        if (taps >= times) return null
        val now = clock()
        // A habit done N times a day (round B): taps before the Nth count and keep it on today; the Nth moves it on.
        val last = taps + 1 >= times
        val next = if (last) Repeat.nextAfter(t.due?.takeIf { it.length >= 10 } ?: today, every, today) else t.due
        val body = buildJsonObject {
            // n: this tap's number for the day, so a retry after a lost reply is counted once (round B checker #2).
            putJsonObject("set") { put("done", true); put("day", today); put("if_due", t.due); put("n", taps + 1) }
            putJsonObject("was") {
                put("due", t.due)
                if (times == 1) put("untick", today) else putJsonObject("taps") { put(today, taps) }
            }
        }
        touched[id] = now
        val op = dao.tickHabit(
            t.copy(due = next, syncStatus = SyncStatus.PENDING, lastModified = now),
            HabitTickEntity(today, id, taps + 1),
            OutboxEntity(taskId = id, op = OutboxEntity.PATCH, createdAt = now, body = body.toString()),
        )
        scheduler.kick()
        reminders.replan()
        return Ticked(op, t.due, today)
    }

    /** Today's taps of a habit (0 = none yet). */
    suspend fun tapsToday(id: String): Int = dao.habitTicksOf(id).firstOrNull { it.day == today() }?.n ?: 0

    /**
     * One day of the habit's last week, ticked or unticked from its dots (round B: "a habit tracker fully"), or one tap
     * taken off today (a long press on a count ring). Counted for that day only: `PATCH {done, day}`, which pagel.uz
     * logs for that day without moving the date. Unticking today's last tap of a habit that had moved on brings it back
     * to today (pagel.uz does the same). Days older than 7 are not touched.
     */
    suspend fun setDay(id: String, day: String, done: Boolean): Boolean {
        val t = dao.taskOnce(id) ?: return false
        if (t.every.isNullOrBlank() || t.status != "open") return false
        val today = today()
        val back = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(day), java.time.LocalDate.parse(today))
        // The 7 dots: today and the 6 days before, as pagel.uz takes them (it used to be 8 on both sides).
        if (back !in 0L until DOT_DAYS) return false
        // Today's tick with the habit still on today is the ring's own tap: it moves the habit as the ring does.
        if (done && day == today && (t.due == null || t.due.take(10) <= today)) return tick(id) != null
        val times = t.times.coerceAtLeast(1)
        val taps = dao.habitTicksOf(id).firstOrNull { it.day == day }?.n ?: 0
        val n = if (done) taps + 1 else taps - 1
        if (n < 0 || n > times) return false
        val now = clock()
        val due = t.due.orEmpty()
        val bringBack = !done && day == today && taps == times && due.take(10) > today
        val body = buildJsonObject {
            putJsonObject("set") { put("done", done); put("day", day); if (done) put("n", n) }
            putJsonObject("was") { put("due", t.due); putJsonObject("taps") { put(day, taps) } }
        }
        touched[id] = now
        dao.tickHabit(
            t.copy(due = if (bringBack) today + due.drop(10) else t.due, syncStatus = SyncStatus.PENDING, lastModified = now),
            HabitTickEntity(day, id, n),
            OutboxEntity(taskId = id, op = OutboxEntity.PATCH, createdAt = now, body = body.toString()),
        )
        scheduler.kick()
        reminders.replan()
        return true
    }

    /** Undo of [tick]: taken back if it is still on the phone, otherwise the old date goes back to pagel.uz. */
    suspend fun undo(id: String, ticked: Ticked) {
        val row = dao.outboxRow(ticked.op)
        if (row != null && dao.takeBackOp(ticked.op) > 0) {
            wasOf(row)?.let { dao.applyWas(id, it, clock()) }
            dao.markSyncedIfIdle(id)
            reminders.replan()
            return
        }
        val t = dao.taskOnce(id) ?: return
        // The tick's own day, not the clock's: an Undo sent after midnight takes the tap off the day it was made.
        val today = ticked.day.ifEmpty { today() }
        val now = clock()
        touched[id] = now
        dao.upsertTasks(listOf(t.copy(due = ticked.was, syncStatus = SyncStatus.PENDING, lastModified = now)))
        val taps = dao.habitTicksOf(id).firstOrNull { it.day == today }?.n ?: 0
        if (taps > 1) dao.setHabitTick(HabitTickEntity(today, id, taps - 1)) else dao.deleteHabitTick(today, id)
        val body = buildJsonObject {
            // undo: true = pagel.uz takes that day's tick out (habit_undone). A plain due change on a habit (a new time
            // from the reminder wheel) keeps the tick (2026-10-28: "Wind down" set to 04:55 erased Tuesday's tick).
            putJsonObject("set") { put("due", ticked.was); put("undo", true); put("day", today) }
            putJsonObject("was") { put("due", t.due); if (taps <= 1) put("retick", today) else putJsonObject("taps") { put(today, taps) } }
        }
        dao.addToOutbox(OutboxEntity(taskId = id, op = OutboxEntity.PATCH, createdAt = now, body = body.toString()))
        scheduler.kick()
        reminders.replan()
    }

    /**
     * pagel.uz's ticks of the last [DAYS] days; offline, the saved ones stay. A habit ticked or unticked here after the
     * GET left keeps the phone's days: the reply was read before that change landed, and its outbox row may already be
     * gone (checker 2026-10-28 #8, the same race as the Inbox's).
     */
    suspend fun refresh(account: Account): Outcome<Unit> {
        val started = clock()
        return when (val r = api.habits(account.serverUrl, account.token, DAYS)) {
            is Outcome.Failed -> r
            is Outcome.Ok -> {
                val keep = touched.filterValues { it > started }.keys
                dao.replaceHabitTicks(r.value.ticks.map { HabitTickEntity(it.day, it.id, it.n) }, keep)
                Outcome.Ok(Unit)
            }
        }
    }

    suspend fun setReminder(id: String, on: Boolean) {
        dao.setReminder(HabitReminderEntity(id, on))
        reminders.replan()
    }

    fun replan() = reminders.replan()

    companion object {
        val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")
        /** As far back as the web counts a streak (habit-log.ts, 400 days); 60 capped a 75-day streak at ~60 (#8). */
        const val DAYS = 400
        /** The week dots: today and the 6 days before (pagel.uz takes a dot for these 7 days only). */
        const val DOT_DAYS = 7L
    }
}
