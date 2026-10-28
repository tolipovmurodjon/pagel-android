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
        if (t != null && on && t.status == "open" && !t.every.isNullOrBlank() && (due == null || due.take(10) <= today)) {
            val streak = streakOf(t.every, dao.habitTicksOf(id).map { it.day }.toSet(), today) ?: 0
            post(ctx, id, t.title?.takeIf(String::isNotBlank) ?: headline(t.text), listOfNotNull(due?.drop(11)?.takeIf(String::isNotBlank), if (streak > 1) "🔥 $streak" else null).joinToString(" · "))
        }
        habits.replan()
    }

    private fun post(ctx: Context, id: String, title: String, line: String) {
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
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(line.ifEmpty { "Time for it" })
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
