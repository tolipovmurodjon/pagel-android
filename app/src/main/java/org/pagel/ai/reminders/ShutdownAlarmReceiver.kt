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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.pagel.ai.MainActivity
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.rituals.Rituals
import org.pagel.ai.data.rituals.RitualsRepository
import org.pagel.ai.ui.shell.OpenRequests

/**
 * The evening "Close the day" reminder (Phase 5). FIRE (the alarm at Boss's time, 20:45 unless he set one): asks
 * pagel.uz first whether the day was closed on the web (5 s at most), and if it is still open posts "Close the day"
 * with a button that opens the ritual; then plans tomorrow's. Not exported: only PAGEL's own explicit, immutable
 * PendingIntent reaches it. Finishes in under 10 s (goAsync + a 9 s cap).
 */
class ShutdownAlarmReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Parts {
        fun rituals(): RitualsRepository
        fun accounts(): AccountStore
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != FIRE) return
        val pending = goAsync()
        val parts = EntryPointAccessors.fromApplication(ctx.applicationContext, Parts::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000) { fire(ctx, parts) }
            } finally {
                runCatching { parts.rituals().setEvening(parts.rituals().state.first().eveningOn) }
                pending.finish()
            }
        }
    }

    private suspend fun fire(ctx: Context, parts: Parts) {
        val repo = parts.rituals()
        val account = (parts.accounts().load() as? AccountState.Paired)?.account
        if (account != null) withTimeoutOrNull(5_000) { repo.refresh(account) }
        val s = repo.state.first()
        if (!s.eveningOn || s.shutDay == repo.ritualDay()) return
        val open = Rituals.stars(repo.openTasks.first()).size
        post(ctx, open)
    }

    private fun post(ctx: Context, stars: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Close the day", NotificationManager.IMPORTANCE_HIGH).apply { description = "The evening shutdown, at your time" })
        val open = PendingIntent.getActivity(
            ctx, 4,
            Intent(ctx, MainActivity::class.java).putExtra(OpenRequests.EXTRA, OpenRequests.SHUTDOWN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val line = when (stars) {
            0 -> "Pick tomorrow's 3. Five minutes, then sleep."
            1 -> "1 star still open. Five minutes, then sleep."
            else -> "$stars stars still open. Five minutes, then sleep."
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(org.pagel.ai.R.drawable.ic_stat_bell)
            .setColor(0xFF1C7E69.toInt())
            .setContentTitle("Close the day")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            // Its own group, high like the habits' channel: never bundled with the anchor notification, whose intent a tap
            // there opened instead (checker P5 #5).
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Close the day", open)
            .build()
        runCatching { nm.notify(NOTIF, n) }
    }

    companion object {
        const val FIRE = "org.pagel.ai.EVENING_FIRE"
        /** "evening", not "rituals": a channel's importance cannot be raised once made, and 0.9 builds made it DEFAULT. */
        const val CHANNEL = "evening"
        private const val GROUP = "org.pagel.ai.rituals"
        private const val NOTIF = 5

        fun fireIntent(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, ShutdownAlarmReceiver::class.java).setAction(FIRE).setData(Uri.parse("pagel://evening")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
