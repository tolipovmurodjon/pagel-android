package org.pagel.ai.reminders

import android.content.Context
import java.time.LocalTime

/**
 * Round C notifications: the settings (More → Notifications) and the funny lines' rotation. Kept on the phone in one
 * SharedPreferences file, read and written off Main.
 */
object NotifyPrefs {
    private const val FILE = "pagel-notify"
    const val CHILL = "chill"
    const val NORMAL = "normal"
    const val NO_MERCY = "nomercy"
    val TONES = listOf(CHILL, NORMAL, NO_MERCY)

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun tone(ctx: Context): String = p(ctx).getString("tone", NORMAL) ?: NORMAL
    fun setTone(ctx: Context, t: String) = p(ctx).edit().putString("tone", t).apply()
    /** The morning plan's time (default 08:00); "" = off. */
    fun morning(ctx: Context): String = p(ctx).getString("morning", "08:00") ?: "08:00"
    fun setMorning(ctx: Context, t: String) = p(ctx).edit().putString("morning", t).apply()
    fun evening(ctx: Context): Boolean = p(ctx).getBoolean("evening", true)
    fun setEvening(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("evening", on).apply()
    /** Tasks that also ring 10 minutes before (set per task in its panel). */
    fun early(ctx: Context): Set<String> = p(ctx).getStringSet("early", emptySet()).orEmpty().toSet()
    fun setEarly(ctx: Context, id: String, on: Boolean) = p(ctx).edit().putStringSet("early", if (on) early(ctx) + id else early(ctx) - id).apply()
    /** The task alarms set last time, so a task that lost its time (or was done) has its alarm cancelled. */
    fun planned(ctx: Context): Set<String> = p(ctx).getStringSet("planned", emptySet()).orEmpty().toSet()
    fun setPlanned(ctx: Context, ids: Set<String>) = p(ctx).edit().putStringSet("planned", ids).apply()
    /** The overdue nudge's last day: at most once a day. */
    fun overdueDay(ctx: Context): String = p(ctx).getString("overdue-day", "") ?: ""
    fun setOverdueDay(ctx: Context, day: String) = p(ctx).edit().putString("overdue-day", day).apply()

    /** "kind:index@epochMs" entries of the lines shown (the last 200). */
    fun history(ctx: Context, kind: String): List<Pair<Int, Long>> =
        p(ctx).getString("history-$kind", "").orEmpty().split(',').mapNotNull { e ->
            val (i, t) = e.split('@').takeIf { it.size == 2 } ?: return@mapNotNull null
            (i.toIntOrNull() ?: return@mapNotNull null) to (t.toLongOrNull() ?: return@mapNotNull null)
        }
    fun remember(ctx: Context, kind: String, index: Int, at: Long) {
        val h = (history(ctx, kind) + (index to at)).takeLast(200)
        p(ctx).edit().putString("history-$kind", h.joinToString(",") { "${it.first}@${it.second}" }).apply()
    }
}

/** Which funny line comes next, and quiet hours. Pure: tested on the JVM (NotifyCopyTest). */
object NotifyCopy {
    const val WEEK_MS = 7 * 24 * 3_600_000L

    /**
     * The next of [size] lines: never one shown in the last 7 days while another is free; when all were, the one shown
     * longest ago. Among the free ones, [seed] picks (so the order is not the file's order).
     */
    fun pick(size: Int, history: List<Pair<Int, Long>>, now: Long, seed: Int = (now / 60_000L).toInt()): Int {
        require(size > 0)
        val last = HashMap<Int, Long>()
        for ((i, t) in history) if (i in 0 until size) last[i] = maxOf(last[i] ?: Long.MIN_VALUE, t)
        val free = (0 until size).filter { (last[it] ?: Long.MIN_VALUE) < now - WEEK_MS }
        if (free.isNotEmpty()) return free[Math.floorMod(seed, free.size)]
        return (0 until size).minBy { last[it] ?: Long.MIN_VALUE }
    }

    /**
     * A line that fits the task's own words, when they say what it is (round C checker #7: "the gym won't lift itself"
     * rang for "Pay the internet bill"). English keywords only; any other title gets the tone's rotation.
     */
    fun keywordLine(title: String): String? {
        val t = title.lowercase()
        return KEYWORDS.firstOrNull { (words, _) -> words.any { Regex("\\b" + Regex.escape(it.trim())).containsMatchIn(t) } }?.second
    }
    private val KEYWORDS = listOf(
        listOf("gym", "workout", "lift", "run ", "stretch", "push-up", "pushup") to "the gym won't lift itself 💪",
        listOf("pay", "bill", "bank", "invoice", "tax") to "money moves only 💸",
        listOf("call", "phone") to "one call, then freedom 📞",
        listOf("read", "book", "pages") to "pages won't flip themselves 📖",
        listOf("email", "mail", "reply", "message") to "inbox zero arc ✉️",
        listOf("write", "draft", "report") to "first sentence, then flow ✍️",
        listOf("clean", "tidy", "wash") to "clean space, clean mind 🧽",
        listOf("study", "learn", "lesson", "course") to "brain gains loading 🧠",
    )

    /** 23:00-07:00: a reminder comes without sound or buzz; the morning and evening nudges do not come at all. */
    fun quiet(t: LocalTime): Boolean = t.hour >= 23 || t.hour < 7
}
