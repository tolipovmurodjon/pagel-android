package org.pagel.ai.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.data.habits.ReminderPlanner
import org.pagel.ai.ui.glass.Repeat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** When a habit's reminder rings next: pure, so the scheduler's math is tested on the JVM. */
object ReminderMath {
    /**
     * The habit's next time still ahead of [now] (Tashkent): its due date and time if that is still to come, else the
     * next day it repeats on. A habit without a time has no reminder (null).
     */
    fun nextFire(due: String?, every: String?, now: ZonedDateTime): ZonedDateTime? {
        if (due == null || every.isNullOrBlank() || due.length < 16) return null
        val time = runCatching { LocalTime.parse(due.substring(11, 16)) }.getOrNull() ?: return null
        var day = runCatching { LocalDate.parse(due.take(10)) }.getOrNull() ?: return null
        var at = ZonedDateTime.of(day, time, HabitsRepository.TASHKENT)
        var guard = 0
        while (!at.isAfter(now) && guard++ < 400) {
            day = LocalDate.parse(Repeat.nextDue(day.toString(), every, day.toString()).take(10))
            at = ZonedDateTime.of(day, time, HabitsRepository.TASHKENT)
        }
        return at.takeIf { it.isAfter(now) }
    }

    /** Without the exact-alarm grant the reminder may come up to this late (AlarmManager.setWindow). */
    const val WINDOW_MS = 10 * 60_000L
}

/**
 * The habits' reminders on AlarmManager. Exact alarms (Android 14+ denies SCHEDULE_EXACT_ALARM by default; Boss grants
 * it once, asked at the first reminder he turns on): setExactAndAllowWhileIdle, so it rings on time in Doze. Without
 * the grant: a 10-minute window, never nothing. USE_EXACT_ALARM is not used: Play keeps it for alarm-clock and
 * calendar apps. Each alarm fires [HabitAlarmReceiver] (explicit, FLAG_IMMUTABLE), which posts the notification.
 */
class HabitAlarms(
    private val ctx: Context,
    private val dao: PagelDao,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The evening "Close the day" reminder (Phase 5) rides on the same planner, so boot and updates plan it too. */
    private val rituals: org.pagel.ai.data.db.RitualsDao? = null,
) : ReminderPlanner {
    private var job: Job? = null

    override fun replan() {
        job?.cancel()
        job = scope.launch(io) { runCatching { plan() } }
    }

    private suspend fun plan() {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val habits = dao.habitsOnce().associateBy { it.id }
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock()), HabitsRepository.TASHKENT)
        rituals?.let { runCatching { planEvening(am, it, now) } }
        // Round C: the tasks' reminders, the morning plan and the evening nudge ride on the same planner.
        runCatching { TaskAlarms.plan(ctx, dao, rituals, am, now) }
        // Phase 6a: the Compass's birthday morning and season review (kinds and dates only, never words).
        runCatching { CompassAlarms.plan(ctx, am, now) }
        for (r in dao.remindersOnce()) {
            val pi = fireIntent(ctx, r.taskId)
            val h = habits[r.taskId]
            // A prayer rings at that day's own time (round D), never at the date's fixed one.
            val prayer = h?.takeIf { org.pagel.ai.data.habits.isNamoz(it) }?.let { org.pagel.ai.data.habits.Namoz.prayerOf(it.title ?: it.text) }
            val at = when {
                !r.enabled || h == null -> null
                prayer != null -> org.pagel.ai.data.habits.Namoz.nextStart(prayer, now)
                else -> ReminderMath.nextFire(h.due, h.every, now)
            }
            if (at == null) { am.cancel(pi); continue }
            val ms = at.toInstant().toEpochMilli()
            // Each alarm on its own: a revoked exact grant throws SecurityException, which used to drop every habit after
            // this one (the single runCatching around plan(), checker 2026-10-28 #7). It falls back to the window.
            try {
                if (canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
                else am.setWindow(AlarmManager.RTC_WAKEUP, ms, ReminderMath.WINDOW_MS, pi)
            } catch (_: SecurityException) {
                runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, ms, ReminderMath.WINDOW_MS, pi) }
            }
        }
    }

    /** Every evening at Boss's time (20:45, his day close, unless he set one): "Close the day". Off = no alarm at all. */
    private suspend fun planEvening(am: AlarmManager, rituals: org.pagel.ai.data.db.RitualsDao, now: ZonedDateTime) {
        val s = rituals.stateOnce() ?: org.pagel.ai.data.db.RitualStateEntity()
        val pi = ShutdownAlarmReceiver.fireIntent(ctx)
        if (!s.eveningOn) { am.cancel(pi); return }
        val ms = org.pagel.ai.data.rituals.Rituals.nextEvening(s.eveningAt, now).toInstant().toEpochMilli()
        try {
            if (canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am.setWindow(AlarmManager.RTC_WAKEUP, ms, ReminderMath.WINDOW_MS, pi)
        } catch (_: SecurityException) {
            runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, ms, ReminderMath.WINDOW_MS, pi) }
        }
    }

    companion object {
        fun canExact(am: AlarmManager): Boolean = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

        /** One alarm per habit: the data URI makes each PendingIntent its own. */
        fun fireIntent(ctx: Context, id: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, HabitAlarmReceiver::class.java).setAction(HabitAlarmReceiver.FIRE).setData(Uri.parse("pagel://habit/$id")).putExtra(HabitAlarmReceiver.EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
