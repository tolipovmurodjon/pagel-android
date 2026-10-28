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
data class Ticked(val op: Long, val was: String?)

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
        if (dao.habitTicksOf(id).any { it.day == today }) return null
        val now = clock()
        val next = Repeat.nextAfter(t.due?.takeIf { it.length >= 10 } ?: today, every, today)
        val body = buildJsonObject {
            putJsonObject("set") { put("done", true); put("day", today); put("if_due", t.due) }
            putJsonObject("was") { put("due", t.due); put("untick", today) }
        }
        touched[id] = now
        val op = dao.tickHabit(
            t.copy(due = next, syncStatus = SyncStatus.PENDING, lastModified = now),
            HabitTickEntity(today, id),
            OutboxEntity(taskId = id, op = OutboxEntity.PATCH, createdAt = now, body = body.toString()),
        )
        scheduler.kick()
        reminders.replan()
        return Ticked(op, t.due)
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
        val today = today()
        val now = clock()
        touched[id] = now
        dao.upsertTasks(listOf(t.copy(due = ticked.was, syncStatus = SyncStatus.PENDING, lastModified = now)))
        dao.deleteHabitTick(today, id)
        val body = buildJsonObject {
            // undo: true = pagel.uz takes that day's tick out (habit_undone). A plain due change on a habit (a new time
            // from the reminder wheel) keeps the tick (2026-10-28: "Wind down" set to 04:55 erased Tuesday's tick).
            putJsonObject("set") { put("due", ticked.was); put("undo", true) }
            putJsonObject("was") { put("due", t.due); put("retick", today) }
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
                dao.replaceHabitTicks(r.value.ticks.map { HabitTickEntity(it.day, it.id) }, keep)
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
    }
}
