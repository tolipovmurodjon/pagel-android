package org.pagel.ai.data.habits

import org.pagel.ai.data.db.HabitFreezeEntity
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import java.time.LocalDate

/** The three ladders of the Achievements page (Boss's reference: perfect days, streaks, habits finished). */
enum class BadgeKind(val steps: List<Int>) {
    PERFECT_DAYS(listOf(3, 10, 20, 30, 50, 100)),
    STREAK(listOf(3, 5, 10, 15, 30, 90)),
    FINISHED(listOf(1, 10, 20, 50, 100, 300)),
}

/** One badge: its ladder, its number, and the day it was earned (null = still locked). */
data class HabitBadge(val kind: BadgeKind, val n: Int, val earnedOn: LocalDate?) {
    val earned: Boolean get() = earnedOn != null
    val title: String get() = when (kind) {
        BadgeKind.PERFECT_DAYS -> "$n Perfect Days"
        BadgeKind.STREAK -> "$n Day Streak"
        BadgeKind.FINISHED -> if (n == 1) "First Habit Finished" else "Finish $n Times"
    }
    val how: String get() = when (kind) {
        BadgeKind.PERFECT_DAYS -> "Do every habit of the day, $n days (a day off counts)."
        BadgeKind.STREAK -> "Keep one habit going $n scheduled days in a row."
        BadgeKind.FINISHED -> if (n == 1) "Finish a habit once." else "Finish habits $n times in all."
    }
}

/**
 * The badges from the logs alone (Boss's real history, 400 days back): a day off counts as done, the five prayers are
 * not in it (Namoz has no badges). Each earned badge carries the day its number was reached.
 */
fun habitBadges(habits: List<TaskEntity>, ticks: List<HabitTickEntity>, freezes: List<HabitFreezeEntity>, today: LocalDate): List<HabitBadge> {
    val others = habits.filterNot(::isNamoz)
    val byId = others.associateBy { it.id }
    val taps = ticks.associate { "${it.day} ${it.taskId}" to it.n }
    val frozenOf = freezes.groupBy({ it.taskId }, { it.day }).mapValues { it.value.toSet() }
    val starts = startsOf(others, ticks, today)
    val first = ticks.filter { it.taskId in byId }.minOfOrNull { it.day }?.let(LocalDate::parse) ?: today

    val perfect = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
        .filter { clearOf(others, taps, frozenOf, it, today, starts) == Clear.ALL }.toList()

    val streakAt = mutableMapOf<Int, LocalDate>()
    for (t in others) {
        val own = starts[t.id] ?: today
        val frozen = frozenOf[t.id].orEmpty()
        var run = 0
        var d = maxOf(own, first)
        while (!d.isAfter(today)) {
            val ymd = d.toString()
            if (repeatsOn(t, d)) {
                when {
                    (taps["$ymd ${t.id}"] ?: 0) >= t.times.coerceAtLeast(1) -> { run++; streakAt.merge(run, d) { a, b -> if (a.isBefore(b)) a else b } }
                    ymd in frozen || d == today -> Unit
                    else -> run = 0
                }
            }
            d = d.plusDays(1)
        }
    }

    val finished = ticks.filter { byId[it.taskId]?.let { t -> it.n >= t.times.coerceAtLeast(1) } == true }.map { it.day }.sorted()

    return BadgeKind.entries.flatMap { kind ->
        kind.steps.map { n ->
            val on = when (kind) {
                BadgeKind.PERFECT_DAYS -> perfect.getOrNull(n - 1)
                // A run of n is also a run of every smaller number, so the earliest day any habit held n is the day.
                BadgeKind.STREAK -> streakAt[n]
                BadgeKind.FINISHED -> finished.getOrNull(n - 1)?.let(LocalDate::parse)
            }
            HabitBadge(kind, n, on)
        }
    }
}
