package org.pagel.ai.reminders

import android.Manifest
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.pagel.ai.MainActivity
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.data.habits.streakOf
import org.pagel.ai.ui.today.headline

/**
 * A habit's reminder (Phase 3). FIRE (the alarm): posts "<habit> · 07:30" with a Done button, unless it was already
 * ticked today or its reminder is off, then plans the next one. DONE (the button): ticks it offline through the same
 * outbox as the app (Room first, sent when there is a network) and takes the notification away. Not exported: only
 * PAGEL's own PendingIntents (explicit, FLAG_IMMUTABLE) reach it. Finishes in under 10 s (goAsync + a 9 s cap).
 */
class HabitAlarmReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Parts {
        fun habits(): HabitsRepository
        fun dao(): PagelDao
        fun namoz(): org.pagel.ai.data.db.NamozDao
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val action = intent.action ?: return
        val pending = goAsync()
        val parts = EntryPointAccessors.fromApplication(ctx.applicationContext, Parts::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000) {
                    when (action) {
                        FIRE -> fire(ctx, parts, id)
                        DONE -> {
                            parts.habits().tick(id)
                            ctx.getSystemService(NotificationManager::class.java)?.cancel(id, NOTIF)
                        }
                        else -> {}
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(ctx: Context, parts: Parts, id: String) {
        val habits = parts.habits()
        val dao = parts.dao()
        val t = dao.taskOnce(id)
        val on = dao.remindersOnce().any { it.taskId == id && it.enabled }
        val today = habits.today()
        val due = t?.due
        val days = dao.habitTicksOf(id).map { it.day }.toSet()
        // Already ticked today (in the app, on the web, or by an earlier Done): no ring for a done habit (checker #3).
        if (t != null && on && t.status == "open" && !t.every.isNullOrBlank() && (due == null || due.take(10) <= today) && today !in days) {
            val streak = streakOf(t.every, days, today) ?: 0
            val title = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)
            // A prayer (round D): only "Peshin vaqti kirdi · 12:11", its own picture, no streak (Boss: plain and respectful).
            val prayer = org.pagel.ai.data.habits.Namoz.prayerOf(title)?.takeIf { org.pagel.ai.data.habits.isNamoz(t) }
            val window = prayer?.let { p -> org.pagel.ai.data.habits.Namoz.windows(java.time.LocalDate.parse(today), org.pagel.ai.data.habits.Namoz.setupOf(parts.namoz().settingsOnce())).first { it.prayer == p } }
            if (window != null) postPrayer(ctx, id, window) else post(ctx, id, title, due?.drop(11)?.trim().orEmpty(), streak)
        }
        habits.replan()
    }

    /** The prayer's notice: one plain line, its picture, Done; no streak, no joke, no red (Boss 2026-10-28). */
    private fun postPrayer(ctx: Context, id: String, w: org.pagel.ai.data.habits.Namoz.Window) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Habits", NotificationManager.IMPORTANCE_HIGH).apply { description = "A habit's reminder at its time" })
        val done = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, HabitAlarmReceiver::class.java).setAction(DONE).setData(Uri.parse("pagel://habit-done/$id")).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(org.pagel.ai.R.drawable.ic_stat_pagel)
            .setLargeIcon(org.pagel.ai.ui.glass.Emoji.largeIcon(ctx, w.prayer.emoji))
            .setColor(0xFF1C7E69.toInt())
            .setContentTitle(org.pagel.ai.data.habits.Namoz.notice(w))
            // On a locked screen only "Reminder" shows: the prayer's name stays private (namoz brief walls).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(org.pagel.ai.R.drawable.ic_stat_pagel).setColor(0xFF1C7E69.toInt())
                    .setContentTitle("Reminder").setCategory(NotificationCompat.CATEGORY_REMINDER).build(),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Done", done)
            .build()
        runCatching { nm.notify(id, NOTIF, n) }
    }

    private fun post(ctx: Context, id: String, habit: String, time: String, streak: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Habits", NotificationManager.IMPORTANCE_HIGH).apply { description = "A habit's reminder at its time" })
        val done = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, HabitAlarmReceiver::class.java).setAction(DONE).setData(Uri.parse("pagel://habit-done/$id")).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val emoji = org.pagel.ai.ui.glass.Emoji.keyFor(habit)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(org.pagel.ai.R.drawable.ic_stat_pagel)
            .setLargeIcon(org.pagel.ai.ui.glass.Emoji.largeIcon(ctx, emoji))
            .setColor(0xFF1C7E69.toInt())
            // Collapsed, a long habit as the title was cut ("Stretch for ten min…", walk 40, also with BigTextStyle):
            // a short title, the habit in the text line (it wraps when expanded), the time in the header.
            .setContentTitle(if (streak > 1) "Time for it · 🔥 $streak" else "Time for it")
            .setContentText("${org.pagel.ai.ui.glass.Emoji.glyph(emoji)} $habit")
            .apply { if (time.isNotEmpty()) setSubText(time) }
            .setStyle(NotificationCompat.BigTextStyle().bigText("${org.pagel.ai.ui.glass.Emoji.glyph(emoji)} $habit"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Done", done)
            .build()
        runCatching { nm.notify(id, NOTIF, n) }
    }

    companion object {
        const val FIRE = "org.pagel.ai.HABIT_FIRE"
        const val DONE = "org.pagel.ai.HABIT_DONE"
        const val EXTRA_ID = "habit"
        const val CHANNEL = "habits"
        private const val NOTIF = 3
    }
}
