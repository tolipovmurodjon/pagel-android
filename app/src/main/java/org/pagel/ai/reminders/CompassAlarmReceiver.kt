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
import org.pagel.ai.MainActivity
import org.pagel.ai.data.compass.CompassDao
import org.pagel.ai.data.compass.CompassRules
import org.pagel.ai.data.compass.Level
import org.pagel.ai.ui.shell.OpenRequests
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** When the Compass's two reminders ring: pure, so the dates are tested on the JVM. */
object CompassReminderMath {
    val BIRTHDAY_AT: LocalTime = LocalTime.of(8, 0)
    val REVIEW_AT: LocalTime = LocalTime.of(9, 0)

    /** The next 1 November 08:00 in Tashkent after [now] (Boss's birthday: his new goal year starts). */
    fun nextBirthday(now: ZonedDateTime): ZonedDateTime {
        val born = CompassRules.BORN
        var at = ZonedDateTime.of(born.withYear(now.year), BIRTHDAY_AT, CompassRules.TASHKENT)
        if (!at.isAfter(now)) at = ZonedDateTime.of(born.withYear(now.year + 1), BIRTHDAY_AT, CompassRules.TASHKENT)
        return at
    }

    /** The next season review: the first day of a season's 13th week, 09:00 Tashkent, after [now]. */
    fun nextReview(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate()
        val seasons = CompassRules.seasons(CompassRules.yearStart(today)) + CompassRules.seasons(CompassRules.yearStart(today).plusYears(1))
        return seasons.map { ZonedDateTime.of(it.workEnds.plusDays(1), REVIEW_AT, CompassRules.TASHKENT) }.first { it.isAfter(now) }
    }

    /** The review rings for a season only when Boss wrote goals for it (only ids and dates are read, never words). */
    fun reviewWanted(seasonStarts: Set<String>, review: ZonedDateTime): Boolean {
        val season = CompassRules.seasonOf(review.toLocalDate().minusDays(1))
        return season.starts.toString() in seasonStarts
    }
}

/**
 * The Compass's reminders (Phase 6a, Boss's opt-in): 1 Nov 08:00 "your new year starts" and a season's review week.
 * Plain words only: no goal's words are ever in a notification (they live sealed; the planner reads only kinds and
 * dates). Planned with the habits' reminders (HabitAlarms.replan: boot, update, time change, a switch).
 */
object CompassAlarms {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Parts {
        fun compassDao(): CompassDao
    }

    suspend fun plan(ctx: Context, am: AlarmManager, now: ZonedDateTime) {
        val dao = EntryPointAccessors.fromApplication(ctx.applicationContext, Parts::class.java).compassDao()
        val v = dao.vaultOnce()
        val birthday = CompassAlarmReceiver.fireIntent(ctx, CompassAlarmReceiver.BIRTHDAY)
        if (v?.birthdayOn == true) set(am, CompassReminderMath.nextBirthday(now).toInstant().toEpochMilli(), birthday) else am.cancel(birthday)
        val review = CompassAlarmReceiver.fireIntent(ctx, CompassAlarmReceiver.REVIEW)
        val at = CompassReminderMath.nextReview(now)
        val seasons = dao.seasonStarts(Level.SEASON.wire).toSet()
        if (v?.reviewOn != false && CompassReminderMath.reviewWanted(seasons, at)) set(am, at.toInstant().toEpochMilli(), review) else am.cancel(review)
    }

    private fun set(am: AlarmManager, ms: Long, pi: PendingIntent) {
        try {
            if (HabitAlarms.canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am.setWindow(AlarmManager.RTC_WAKEUP, ms, ReminderMath.WINDOW_MS, pi)
        } catch (_: SecurityException) {
            runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, ms, ReminderMath.WINDOW_MS, pi) }
        }
    }
}

/** Posts the Compass reminder, then plans the next. Not exported: only PAGEL's own explicit, immutable intent reaches it. */
class CompassAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val what = intent.action ?: return
        if (what != BIRTHDAY && what != REVIEW) return
        post(ctx, what)
        runCatching { EntryPointAccessors.fromApplication(ctx.applicationContext, org.pagel.ai.service.BootReceiver.Parts::class.java).reminders().replan() }
    }

    private fun post(ctx: Context, what: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Compass", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Your birthday morning and the season review" })
        val open = PendingIntent.getActivity(
            ctx, 7,
            Intent(ctx, MainActivity::class.java).putExtra(OpenRequests.EXTRA, OpenRequests.COMPASS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, line) = text(what, LocalDate.now(CompassRules.TASHKENT))
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(org.pagel.ai.R.drawable.ic_stat_bell)
            .setColor(0xFF1C7E69.toInt())
            .setContentTitle(title)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(if (what == BIRTHDAY) NOTIF_BIRTHDAY else NOTIF_REVIEW, n) }
    }

    companion object {
        const val BIRTHDAY = "org.pagel.ai.COMPASS_BIRTHDAY"
        const val REVIEW = "org.pagel.ai.COMPASS_REVIEW"
        const val CHANNEL = "compass"
        private const val NOTIF_BIRTHDAY = 61
        private const val NOTIF_REVIEW = 62

        /** The notification's words: fixed, plain, never a goal's (CompassAlarmsTest checks them). */
        fun text(what: String, today: LocalDate): Pair<String, String> =
            if (what == BIRTHDAY) "${CompassRules.yearTitle(CompassRules.yearStart(today))} starts today" to "Happy birthday. Open the Compass: your age report, then this year's goals."
            else "Season review week" to "Two questions, five minutes: what this season taught you, and what the next one keeps."

        fun fireIntent(ctx: Context, what: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, CompassAlarmReceiver::class.java).setAction(what).setData(Uri.parse("pagel://compass/$what")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
