package org.pagel.ai.widget

import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.StepEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.habitsDay
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.projects.shortName
import org.pagel.ai.ui.today.DayProgress
import org.pagel.ai.ui.today.dealToday
import org.pagel.ai.ui.today.headline
import org.pagel.ai.widget.layout.CheckListItem
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

// What each home-screen widget shows (Phase 8), as plain data built from Room's rows. The widgets draw only these:
// a task's or habit's own words, counts, times and kcal. Nothing here ever reads the Compass or the journal
// (WidgetPrivacyTest scans this package and every model built from fixtures that hold sealed words).

/** "Today's 3" with the day's count under it: "12 done · 1 left", the same words as Today's bar. */
data class TodayGlance(val rows: List<CheckListItem>, val done: Int, val open: Int) {
    val line: String get() = DayProgress(done, open).text
}

fun todayGlance(tasks: List<TaskEntity>, projects: List<ProjectEntity>, doneToday: Int, today: LocalDate): TodayGlance =
    TodayGlance(rows(tasks, projects, today), doneToday, tasks.count { it.status == "open" })

/** The next task on Today and its next open step ("Call the bank" → "Find the card number"); tap opens the task. */
data class NextTaskGlance(val id: String, val title: String, val step: String?, val project: String)

fun nextTaskGlance(tasks: List<TaskEntity>, steps: List<StepEntity>, projects: List<ProjectEntity>): NextTaskGlance? {
    val t = dealToday(tasks, finishing = emptySet()).visible.firstOrNull() ?: return null
    val titles = projects.associate { it.name to it.title }
    val step = steps.filter { it.taskId == t.id && !it.done }.minByOrNull { it.position }
    return NextTaskGlance(
        id = t.id,
        title = t.title?.takeIf(String::isNotBlank) ?: headline(t.text),
        step = step?.let { it.title?.takeIf(String::isNotBlank) ?: headline(it.text) },
        project = shortName(t.project, titles[t.project] ?: t.project),
    )
}

/** One habit as a ring: its Fluent emoji in the middle, the ring filled by taps of times ("2/3"). */
data class HabitRing(val id: String, val title: String, val emoji: String, val taps: Int, val times: Int) {
    val done: Boolean get() = taps >= times
    /** The ring's fill in twelfths (1/2, 1/3 and 1/4 land exactly), for the bundled arc drawables. */
    val twelfths: Int get() = twelfthsOf(taps, times)
}

/** Today's habits as rings, prayers apart (they have their own widget); "3/5" = done of today's habits. */
data class HabitsGlance(val rings: List<HabitRing>, val done: Int, val total: Int) {
    val count: String get() = "$done/$total"
}

fun twelfthsOf(part: Int, whole: Int): Int =
    if (whole <= 0) 0 else ((part.coerceIn(0, whole) * 12 + whole / 2) / whole).coerceIn(0, 12)

/** A prayer's habit on the base before round D's `habitGroup`: its Uzbek name as the title, every day (Namoz.prayerOf). */
fun isPrayer(t: TaskEntity): Boolean = t.every == "day" && Namoz.prayerOf(titleOf(t)) != null

internal fun titleOf(t: TaskEntity): String = t.title?.takeIf(String::isNotBlank) ?: headline(t.text)

/**
 * The Habits widget from the same day the Habits tab deals ([habitsDay]): every habit due today, the done ones
 * included (their ring is full), in time order. [emojiOf] is Boss's pick once round D's prefs are merged.
 */
fun habitsGlance(
    habits: List<TaskEntity>,
    ticks: List<HabitTickEntity>,
    today: String,
    emojiOf: (TaskEntity) -> String = { Emoji.keyFor(titleOf(it)) },
): HabitsGlance {
    val mine = habits.filterNot(::isPrayer)
    val day = habitsDay(mine, ticks, today)
    val items = (day.parts.flatMap { it.second } + day.done).sortedWith(compareBy({ it.task.due?.drop(11).orEmpty().ifEmpty { "99" } }, { it.task.priority }))
    val rings = items.map { HabitRing(it.task.id, titleOf(it.task), emojiOf(it.task), it.taps.coerceAtMost(it.times), it.times) }
    return HabitsGlance(rings, done = rings.count { it.done }, total = rings.size)
}

/** One prayer's row: its Uzbek name, today's window, the habit to tick (null until Boss starts tracking). */
data class PrayerRow(val prayer: Namoz.Prayer, val from: String, val to: String, val habitId: String?, val done: Boolean) {
    val name: String get() = prayer.uz
    val emoji: String get() = prayer.emoji
    val time: String get() = "$from–$to"
}

/**
 * Namoz today: the five with their times, [next] = the first not yet prayed whose time is now or still coming (it is
 * the highlighted row), ticks only from [Namoz.START] (Boss: "from 1 November"). No chain, no "missed": a prayer not
 * ticked is only an empty circle.
 */
data class NamozGlance(val rows: List<PrayerRow>, val next: Int?, val counting: Boolean) {
    val done: Int get() = rows.count { it.done }
    val count: String get() = "$done/5"
    val tracked: Boolean get() = rows.any { it.habitId != null }
}

fun namozGlance(
    habits: List<TaskEntity>,
    ticks: List<HabitTickEntity>,
    today: LocalDate,
    now: LocalTime,
    windows: List<Namoz.Window> = Namoz.windows(today),
): NamozGlance {
    val byPrayer = habits.filter(::isPrayer).associateBy { Namoz.prayerOf(titleOf(it)) }
    val ymd = today.toString()
    val counting = Namoz.counts(ymd)
    val rows = windows.map { w ->
        val h = byPrayer[w.prayer]
        val done = counting && h != null && ticks.any { it.taskId == h.id && it.day == ymd && it.n >= 1 }
        PrayerRow(w.prayer, hhmm(w.from), hhmm(w.to), h?.id, done)
    }
    // Xufton's window runs past midnight: today it lasts until the day ends.
    val next = windows.indices.firstOrNull { i ->
        val w = windows[i]
        val ends = if (w.to.isBefore(w.from)) LocalTime.MAX else w.to
        !rows[i].done && now.isBefore(ends)
    }
    return NamozGlance(rows, next, counting)
}

private fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

/** Calories today against the goal: "1,420 / 2,000 kcal" and the ring; "at_most" turns "over" once past it. */
data class CaloriesGlance(val habitId: String?, val eaten: Int, val goal: Int?, val atMost: Boolean = true) {
    val text: String get() = if (goal != null) "${grouped(eaten)} / ${grouped(goal)} kcal" else "${grouped(eaten)} kcal"
    /** The small line under it: what is left, or how far over (never a judgement, only the number). */
    val left: String? get() = goal?.let { g -> if (eaten <= g) "${grouped(g - eaten)} left" else "${grouped(eaten - g)} over" }
    val twelfths: Int get() = if (goal == null || goal <= 0) 0 else twelfthsOf(eaten.coerceAtMost(goal), goal)
    val over: Boolean get() = atMost && goal != null && eaten > goal

    companion object {
        fun grouped(n: Int): String = String.format(Locale.US, "%,d", n)
    }
}
