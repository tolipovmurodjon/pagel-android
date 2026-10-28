package org.pagel.ai.ui.today

import android.content.Context
import java.time.LocalDate

/**
 * The finish moment as a game would (Boss 2026-10-28: "when I clear a task it should give me dopamine to continue to
 * the next"): ticks in a row, the day's progress, and the streak of cleared days. Plain logic, tested by itself.
 */

/** Ticks in a row: each within [windowMs] (10 min) of the one before, as a combo in a game. */
class Combo(private val windowMs: Long = 10 * 60_000L) {
    private var last = Long.MIN_VALUE
    private var count = 0

    /** A tick at [now] (ms): how many in a row it makes. */
    @Synchronized
    fun tick(now: Long): Int {
        count = if (last != Long.MIN_VALUE && now - last in 0..windowMs) count + 1 else 1
        last = now
        return count
    }

    /** What a tick at [now] would make, without counting it (the burst is drawn before the tick is saved). */
    @Synchronized
    fun next(now: Long): Int = if (last != Long.MIN_VALUE && now - last in 0..windowMs) count + 1 else 1

    /** Undo takes the last tick out of the run. */
    @Synchronized
    fun undo() { if (count > 0) count-- }

    companion object {
        /** One run for the whole app: a tick on Today and the next on Upcoming are still in a row. */
        val app = Combo()
    }
}

/** What the toast says after a tick: "Done", then "2 in a row 🔥", "3 in a row — on fire". */
fun comboText(inRow: Int): String = when {
    inRow < 2 -> "Done"
    inRow == 2 -> "2 in a row 🔥"
    else -> "$inRow in a row — on fire 🔥"
}

/** Today's progress: tasks finished today against today's whole list (finished + still open). */
data class DayProgress(val done: Int, val open: Int) {
    val total: Int get() = done + open
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
    val text: String get() = "$done of $total ✓"
}

/** The nudge after a tick: the next task and a small first slice of time ("next: Call the bank · 10 min?"). */
fun nudgeText(nextTitle: String?): String? = nextTitle?.let { "Next: ${it.take(48)} · 10 min?" }

/** Days in a row, ending today (or yesterday, when today is not cleared yet), that Boss cleared his whole Today. */
fun clearedStreak(days: Set<String>, today: LocalDate): Int {
    var d = if (today.toString() in days) today else today.minusDays(1)
    var n = 0
    while (d.toString() in days) { n++; d = d.minusDays(1) }
    return n
}

/** The days Boss cleared his whole Today, kept on the phone (the newest 400). Read and written off Main. */
object ClearedDays {
    private const val PREFS = "pagel-finish"
    private const val KEY = "cleared-days"

    fun all(ctx: Context): Set<String> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty().toSet()

    /** Marks [day] cleared; returns the streak it makes. */
    fun mark(ctx: Context, day: LocalDate): Int {
        val days = (all(ctx) + day.toString()).sortedDescending().take(400).toSet()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, days).apply()
        return clearedStreak(days, day)
    }

    /** Undo of the tick that cleared [day]: the day is not cleared after all; returns the streak left. */
    fun unmark(ctx: Context, day: LocalDate): Int {
        val days = all(ctx) - day.toString()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, days).apply()
        return clearedStreak(days, day)
    }
}

/**
 * Today going from open tasks to none, from ANY screen (round B checker #9: only a Today row cleared the day; the panel,
 * Upcoming and Habits did not). +1 = cleared now: open went to 0 because of a tick here (done grew, or a change waits
 * to be sent), never a sync moving tasks away. -1 = an Undo brought a task back on a cleared day. 0 = nothing.
 */
fun clearChange(was: TodayCounts?, now: TodayCounts): Int = when {
    was == null -> 0
    was.open > 0 && now.open == 0 && now.done > 0 && (now.done > was.done || now.waiting > was.waiting) -> 1
    was.open == 0 && now.open > 0 && (now.done < was.done || now.waiting != was.waiting) -> -1
    else -> 0
}

data class TodayCounts(val open: Int, val done: Int, val waiting: Int)

/** The cleared day, for the whole app: the shell watches Room and says it; Today reads the streak. */
object DayClear {
    val streak = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** One event per clear: the streak it makes. The shell throws the big burst and plays the chord. */
    val events = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 4)
}
