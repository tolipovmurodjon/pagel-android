package org.pagel.ai.data.habits

import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.NamozMarkEntity
import org.pagel.ai.data.db.TaskEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Namoz page's own numbers, from Boss's ticks only. Plain counts: no score, no judgement, nothing before
 * [Namoz.START]. A day without a tick is just an empty dot.
 */
object NamozStats {
    /** Which prayers were ticked on each day: day → set of prayers. */
    fun done(prayers: List<TaskEntity>, ticks: List<HabitTickEntity>): Map<String, Set<Namoz.Prayer>> {
        val byTask = prayers.mapNotNull { t -> Namoz.prayerOf(t.title ?: t.text)?.let { t.id to it } }.toMap()
        return ticks.filter { it.n >= 1 && Namoz.counts(it.day) }.mapNotNull { k -> byTask[k.taskId]?.let { k.day to it } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    /** Days counted in a range: from the later of [from] and the start day, to [to]. */
    private fun counted(from: LocalDate, to: LocalDate): List<LocalDate> {
        val start = maxOf(from, LocalDate.parse(Namoz.START))
        if (start > to) return emptyList()
        return generateSequence(start) { it.plusDays(1) }.takeWhile { it <= to }.toList()
    }

    /** "5/5 days this week": full days (all five) from Monday to [today], and how many days counted. */
    fun week(done: Map<String, Set<Namoz.Prayer>>, today: LocalDate): Pair<Int, Int> {
        val days = counted(today.with(DayOfWeek.MONDAY), today)
        return days.count { done[it.toString()].orEmpty().size == 5 } to days.size
    }

    /** Each prayer's ticked days over the last [span] days (from the start day on): prayer → (ticked, counted). */
    fun perPrayer(done: Map<String, Set<Namoz.Prayer>>, today: LocalDate, span: Int = 30): List<Triple<Namoz.Prayer, Int, Int>> {
        val days = counted(today.minusDays(span - 1L), today)
        return Namoz.Prayer.entries.map { p -> Triple(p, days.count { p in done[it.toString()].orEmpty() }, days.size) }
    }

    /** The month grid: each day of [month] with its ticked prayers (empty before the start day). */
    fun month(done: Map<String, Set<Namoz.Prayer>>, month: YearMonth): List<Pair<LocalDate, Set<Namoz.Prayer>>> =
        (1..month.lengthOfMonth()).map { d -> month.atDay(d).let { it to done[it.toString()].orEmpty() } }

    /** The months the grid can show: the start month up to [today]'s month, oldest first (never empty). */
    fun months(today: LocalDate): List<YearMonth> {
        val first = YearMonth.from(LocalDate.parse(Namoz.START))
        val last = maxOf(first, YearMonth.from(today))
        return generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= last }.toList()
    }

    /** Prayers marked "In the mosque" in [month]. */
    fun mosque(marks: List<NamozMarkEntity>, month: YearMonth): Int {
        val p = month.toString()
        return marks.count { it.how == NamozMarkEntity.MOSQUE && it.day.startsWith(p) && Namoz.counts(it.day) }
    }
}
