package org.pagel.ai.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.pagel.ai.MainActivity
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.data.habits.ReminderPlanner
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.ui.today.headline
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Round C (Boss: "notifications, a full new one, so I do tasks 100%"): every task with a time rings at its time (a range
 * at its start), and 10 minutes before when its panel says so; a morning plan at 08:00 (Boss's time) with the first
 * task's next step; an evening nudge 30 minutes before Close the day while stars are left; an overdue nudge at most
 * once a day (with the morning plan). Exact alarms with the habits' inexact fallback; planned with the habits on every
 * HabitAlarms.replan() (boot, update, time change, sync, any edit that calls it).
 */
object TaskAlarms {
    const val FIRE = "org.pagel.ai.TASK_FIRE"
    const val DONE = "org.pagel.ai.TASK_DONE"
    const val SNOOZE = "org.pagel.ai.TASK_SNOOZE"
    const val TOMORROW = "org.pagel.ai.TASK_TOMORROW"
    const val MORNING = "org.pagel.ai.MORNING_FIRE"
    const val EVENING = "org.pagel.ai.EVENING_NUDGE"
    const val EXTRA_ID = "task"
    const val EXTRA_EARLY = "early"
    const val CH_TASKS = "tasks"
    const val CH_RITUALS = "rituals"
    /** Task alarms reach a week ahead; the plan runs again on every change, so later ones come in time. */
    private const val HORIZON_DAYS = 7L

    /** A task's start in Tashkent: "2026-10-30 10:00-12:00" → 10:00 that day; null without a time. */
    fun startOf(due: String?): ZonedDateTime? {
        if (due == null || due.length < 16) return null
        val day = runCatching { LocalDate.parse(due.take(10)) }.getOrNull() ?: return null
        val time = runCatching { LocalTime.parse(due.substring(11, 16)) }.getOrNull() ?: return null
        return ZonedDateTime.of(day, time, HabitsRepository.TASHKENT)
    }

    /** The next [hhmm] after [now] (today if still ahead, else tomorrow). */
    fun nextAt(hhmm: String, now: ZonedDateTime): ZonedDateTime? {
        val t = runCatching { LocalTime.parse(hhmm) }.getOrNull() ?: return null
        val today = now.toLocalDate().atTime(t).atZone(HabitsRepository.TASHKENT)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    suspend fun plan(ctx: Context, dao: PagelDao, rituals: org.pagel.ai.data.db.RitualsDao?, am: AlarmManager, now: ZonedDateTime) {
        val early = NotifyPrefs.early(ctx)
        val leads = NotifyPrefs.leads(ctx)
        val horizon = now.plusDays(HORIZON_DAYS)
        val planned = HashSet<String>()
        // A repeating timed task rings too (round C checker), unless its own habit reminder (the bell) already does.
        val belled = dao.remindersOnce().filter { it.enabled }.mapTo(HashSet()) { it.taskId }
        for (t in dao.datedOpen().first()) {
            if (t.status != "open" || t.id in belled) continue
            val at = (if (t.every.isNullOrBlank()) startOf(t.due) else ReminderMath.nextFire(t.due, t.every, now)) ?: continue
            // "Remind me" (core round 4): at the time or N minutes early; Off = none (RemindChoice.rings).
            for (lead in RemindChoice.rings(leads[t.id], t.id in early)) {
                val ring = at.minusMinutes(lead.toLong())
                if (ring.isAfter(now) && ring.isBefore(horizon)) {
                    set(am, ring, fireIntent(ctx, t.id, lead > 0))
                    planned += if (lead > 0) "${t.id}!" else t.id
                }
            }
        }
        for (old in NotifyPrefs.planned(ctx) - planned) am.cancel(fireIntent(ctx, old.removeSuffix("!"), old.endsWith("!")))
        NotifyPrefs.setPlanned(ctx, planned)
        // The morning plan: every day at Boss's time; "" = off.
        val m = nextAt(NotifyPrefs.morning(ctx), now)
        if (m == null) am.cancel(ritualIntent(ctx, MORNING)) else set(am, m, ritualIntent(ctx, MORNING))
        // The evening nudge: 30 minutes before Close the day.
        val eveningAt = rituals?.stateOnce()?.takeIf { it.eveningOn }?.eveningAt
        val e = if (NotifyPrefs.evening(ctx) && eveningAt != null) nextAt(eveningAt, now.plusMinutes(30))?.minusMinutes(30) else null
        if (e == null) am.cancel(ritualIntent(ctx, EVENING)) else set(am, e, ritualIntent(ctx, EVENING))
    }

    private fun set(am: AlarmManager, at: ZonedDateTime, pi: PendingIntent) {
        val ms = at.toInstant().toEpochMilli()
        try {
            if (HabitAlarms.canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am.setWindow(AlarmManager.RTC_WAKEUP, ms, ReminderMath.WINDOW_MS, pi)
        } catch (_: SecurityException) {
            runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, ms, ReminderMath.WINDOW_MS, pi) }
        }
    }

    /** One alarm per task (and one for its 10-minutes-before): the data URI makes each PendingIntent its own. */
    fun fireIntent(ctx: Context, id: String, early: Boolean): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, TaskAlarmReceiver::class.java).setAction(FIRE).setData(Uri.parse("pagel://task/$id" + if (early) "/early" else ""))
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_EARLY, early),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun ritualIntent(ctx: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, TaskAlarmReceiver::class.java).setAction(action).setData(Uri.parse("pagel://" + action.substringAfterLast('.').lowercase())),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun actionIntent(ctx: Context, action: String, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, TaskAlarmReceiver::class.java).setAction(action).setData(Uri.parse("pagel://task-${action.substringAfterLast('_').lowercase()}/$id")).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_TASKS, "Tasks", NotificationManager.IMPORTANCE_HIGH).apply { description = "A task at its time, with Done, +15 min and Tomorrow" })
        nm.createNotificationChannel(NotificationChannel(CH_RITUALS, "Rituals", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "The morning plan, the evening nudge, the overdue nudge" })
    }

    /** A funny line of [arrayRes], never the same within 7 days (NotifyCopy). */
    fun line(ctx: Context, kind: String, arrayRes: Int, now: Long): String {
        val lines = ctx.resources.getStringArray(arrayRes)
        val i = NotifyCopy.pick(lines.size, NotifyPrefs.history(ctx, kind), now)
        NotifyPrefs.remember(ctx, kind, i, now)
        return lines[i]
    }

    fun taskLines(ctx: Context): Int = when (NotifyPrefs.tone(ctx)) {
        NotifyPrefs.CHILL -> org.pagel.ai.R.array.notify_task_chill
        NotifyPrefs.NO_MERCY -> org.pagel.ai.R.array.notify_task_nomercy
        else -> org.pagel.ai.R.array.notify_task_normal
    }
}

/**
 * The task reminders' receiver. FIRE posts "<funny line>" / "<task title>" with ✓ Done, ⏰ +15 min and 📅 Tomorrow,
 * grouped per day on the Tasks channel; DONE ticks offline through the outbox (Room first); SNOOZE rings again in 15
 * minutes; TOMORROW moves the task a day on. MORNING / EVENING post the rituals' nudges. Not exported; goAsync, < 10 s.
 */
class TaskAlarmReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Parts {
        fun tasks(): TaskRepository
        fun dao(): PagelDao
        fun reminders(): ReminderPlanner
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        val id = intent.getStringExtra(TaskAlarms.EXTRA_ID)
        val pending = goAsync()
        val parts = EntryPointAccessors.fromApplication(ctx.applicationContext, Parts::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000) {
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    when (action) {
                        TaskAlarms.FIRE -> if (id != null) fire(ctx, parts, id, intent.getBooleanExtra(TaskAlarms.EXTRA_EARLY, false), intent.getBooleanExtra(SNOOZED, false))
                        TaskAlarms.DONE -> if (id != null) { parts.tasks().finish(id); nm?.cancel(id, NOTIF) }
                        TaskAlarms.SNOOZE -> if (id != null) {
                            nm?.cancel(id, NOTIF)
                            val am = ctx.getSystemService(AlarmManager::class.java)
                            val pi = PendingIntent.getBroadcast(ctx, 0,
                                Intent(ctx, TaskAlarmReceiver::class.java).setAction(TaskAlarms.FIRE).setData(Uri.parse("pagel://task/$id/snooze"))
                                    .putExtra(TaskAlarms.EXTRA_ID, id).putExtra(SNOOZED, true),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            val at = System.currentTimeMillis() + 15 * 60_000L
                            runCatching { if (am != null && HabitAlarms.canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am?.setWindow(AlarmManager.RTC_WAKEUP, at, ReminderMath.WINDOW_MS, pi) }
                        }
                        TaskAlarms.TOMORROW -> if (id != null) {
                            nm?.cancel(id, NOTIF)
                            val t = parts.dao().taskOnce(id)
                            val due = t?.due
                            if (due != null && due.length >= 10) {
                                val next = LocalDate.parse(due.take(10)).plusDays(1).toString() + due.drop(10)
                                parts.tasks().due(id, next)
                            }
                            parts.reminders().replan()
                        }
                        TaskAlarms.MORNING -> { morning(ctx, parts); parts.reminders().replan() }
                        TaskAlarms.EVENING -> { evening(ctx, parts); parts.reminders().replan() }
                        else -> {}
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(ctx: Context, parts: Parts, id: String, early: Boolean, snoozed: Boolean) {
        val t = parts.dao().taskOnce(id) ?: return
        // Still open and still at that time (a moved or finished task does not ring); a snooze rings whatever the time.
        val now = ZonedDateTime.now(HabitsRepository.TASHKENT)
        // A repeating task is on time by its hour (its date moves when it is ticked: a ticked one is not due today).
        val at = TaskAlarms.startOf(t.due)?.let { if (t.every.isNullOrBlank()) it else now.with(it.toLocalTime()).takeIf { _ -> t.due!!.take(10) <= now.toLocalDate().toString() } }
        val lead = NotifyPrefs.leadOf(ctx, id).coerceAtLeast(1).toLong()
        val onTime = at != null && kotlin.math.abs(java.time.Duration.between(if (early) at.minusMinutes(lead) else at, now).toMinutes()) <= 15
        if (t.status == "open" && (snoozed || onTime)) {
            val title = NotifyCopy.keywordLine(titleOf(t)) ?: TaskAlarms.line(ctx, "task", TaskAlarms.taskLines(ctx), System.currentTimeMillis())
            val time = t.due?.drop(11)?.trim()?.replace('-', '–').orEmpty()
            post(ctx, id, if (early) "in ${RemindChoice.words(lead.toInt())} · $title" else title, titleOf(t), time, now.toLocalDate().toString(), quiet = NotifyCopy.quiet(now.toLocalTime()), actions = true)
        }
        parts.reminders().replan()
    }

    private suspend fun morning(ctx: Context, parts: Parts) {
        val now = ZonedDateTime.now(HabitsRepository.TASHKENT)
        if (NotifyCopy.quiet(now.toLocalTime())) return
        val today = parts.dao().todayTasks().first().filter { it.status == "open" }
        val ms = System.currentTimeMillis()
        if (today.isNotEmpty()) {
            val first = today.sortedWith(compareBy({ !it.star }, { it.priority }, { it.due.orEmpty() })).first()
            val step = parts.dao().stepsOnce(first.id).firstOrNull { !it.done }?.text
            val head = TaskAlarms.line(ctx, "morning", org.pagel.ai.R.array.notify_morning, ms).format(today.size)
            post(ctx, "morning", head, titleOf(first) + (step?.let { " → $it" } ?: ""), "", now.toLocalDate().toString(), quiet = false, actions = false, channel = TaskAlarms.CH_RITUALS)
        }
        // The overdue nudge, at most once a day.
        val day = now.toLocalDate().toString()
        val overdue = parts.dao().datedOpen().first().count { it.status == "open" && it.every.isNullOrBlank() && (it.due?.take(10) ?: day) < day }
        if (overdue > 0 && NotifyPrefs.overdueDay(ctx) != day) {
            NotifyPrefs.setOverdueDay(ctx, day)
            post(ctx, "overdue", TaskAlarms.line(ctx, "overdue", org.pagel.ai.R.array.notify_overdue, ms).format(overdue), "Open Upcoming to move or do them", "", day, quiet = false, actions = false, channel = TaskAlarms.CH_RITUALS)
        }
    }

    private suspend fun evening(ctx: Context, parts: Parts) {
        val now = ZonedDateTime.now(HabitsRepository.TASHKENT)
        if (NotifyCopy.quiet(now.toLocalTime())) return
        val stars = parts.dao().todayTasks().first().filter { it.status == "open" && it.star }
        if (stars.isEmpty()) return
        val head = TaskAlarms.line(ctx, "evening", org.pagel.ai.R.array.notify_evening, System.currentTimeMillis()).format(stars.size)
        post(ctx, "evening-nudge", head, stars.joinToString(" · ") { titleOf(it) }, "", now.toLocalDate().toString(), quiet = false, actions = false, channel = TaskAlarms.CH_RITUALS)
    }

    private fun titleOf(t: TaskEntity) = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)

    private fun post(ctx: Context, tag: String, title: String, text: String, sub: String, day: String, quiet: Boolean, actions: Boolean, channel: String = TaskAlarms.CH_TASKS) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        TaskAlarms.channels(ctx)
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val group = "tasks-$day"
        // Round D 0c: the human-made emoji as the large icon, and the title starts with it.
        val notice = when (tag) {
            "morning" -> org.pagel.ai.ui.glass.Emoji.Notice.MORNING_PLAN; "overdue" -> org.pagel.ai.ui.glass.Emoji.Notice.OVERDUE
            "evening-nudge" -> org.pagel.ai.ui.glass.Emoji.Notice.CLOSE_THE_DAY; else -> null
        }
        val emoji = notice?.key ?: org.pagel.ai.ui.glass.Emoji.keyFor(text)
        val glyph = notice?.glyph ?: org.pagel.ai.ui.glass.Emoji.glyph(emoji)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(org.pagel.ai.R.drawable.ic_stat_pagel)
            .setLargeIcon(org.pagel.ai.ui.glass.Emoji.largeIcon(ctx, emoji))
            .setColor(0xFF1C7E69.toInt())
            .setContentTitle(if (title.startsWith(glyph)) title else "$glyph $title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .apply { if (sub.isNotEmpty()) setSubText(sub) }
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(quiet)
            .setGroup(group)
            .apply {
                if (actions) {
                    addAction(0, "✓ Done", TaskAlarms.actionIntent(ctx, TaskAlarms.DONE, tag))
                    addAction(0, "⏰ +15 min", TaskAlarms.actionIntent(ctx, TaskAlarms.SNOOZE, tag))
                    addAction(0, "📅 Tomorrow", TaskAlarms.actionIntent(ctx, TaskAlarms.TOMORROW, tag))
                }
            }
            .build()
        runCatching {
            nm.notify(tag, NOTIF, n)
            // The day's group summary: Android folds the day's reminders under it.
            nm.notify(group, SUMMARY, NotificationCompat.Builder(ctx, channel).setSmallIcon(org.pagel.ai.R.drawable.ic_stat_pagel)
                .setColor(0xFF1C7E69.toInt()).setGroup(group).setGroupSummary(true).setSilent(true).setAutoCancel(true).build())
        }
    }

    companion object {
        private const val NOTIF = 5
        private const val SUMMARY = 6
        const val SNOOZED = "snoozed"
    }
}
