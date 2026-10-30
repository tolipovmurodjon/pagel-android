package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.NamozMarkEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.NamozStats
import java.time.LocalDate
import java.time.YearMonth

/** The Namoz page's counts on fake data: full days, per-prayer bars, the month grid and the mosque count. */
class NamozStatsTest {
    private fun t(id: String, text: String) = TaskEntity(
        id = id, project = "me", phase = "Now", sort = 1.0, text = text, title = null, status = "open", priority = 2, star = false,
        due = "2026-11-01 05:35", every = "day", doneWhen = null, doneAt = null, updatedAt = "", serverVersion = 1, lastModified = 0, times = 1, habitGroup = Namoz.GROUP,
    )
    private val prayers = Namoz.Prayer.entries.map { t("p_${it.name}", it.uz) } + t("other", "Stretch")
    private fun all(day: String) = Namoz.Prayer.entries.map { HabitTickEntity(day, "p_${it.name}") }

    // 1 Nov (Sunday) all five; 2 Nov all but Bomdod; 3 Nov all five; an October tick and a non-prayer tick never count.
    private val ticks = all("2026-11-01") + all("2026-11-02").drop(1) + all("2026-11-03") +
        HabitTickEntity("2026-10-31", "p_ASR") + HabitTickEntity("2026-11-02", "other")

    @Test fun doneHasOnlyPrayersFromTheStart() {
        val d = NamozStats.done(prayers, ticks)
        assertEquals(setOf("2026-11-01", "2026-11-02", "2026-11-03"), d.keys)
        assertEquals(4, d["2026-11-02"]!!.size)
    }

    @Test fun weekCountsFullDaysFromMondayButNotBeforeTheStart() {
        val d = NamozStats.done(prayers, ticks)
        // Sunday 1 Nov: the week began Mon 26 Oct, only 1 Nov counts.
        assertEquals(1 to 1, NamozStats.week(d, LocalDate.of(2026, 11, 1)))
        // Wednesday 4 Nov: Mon 2 (4/5), Tue 3 (5/5), Wed 4 (none yet).
        assertEquals(1 to 3, NamozStats.week(d, LocalDate.of(2026, 11, 4)))
    }

    @Test fun perPrayerBarsOverThirtyDays() {
        val bars = NamozStats.perPrayer(NamozStats.done(prayers, ticks), LocalDate.of(2026, 11, 3))
        assertEquals(Namoz.Prayer.entries.toList(), bars.map { it.first })
        assertEquals(Triple(Namoz.Prayer.BOMDOD, 2, 3), bars[0])
        assertTrue(bars.drop(1).all { it.second == 3 && it.third == 3 })
    }

    @Test fun monthGridAndMosque() {
        val grid = NamozStats.month(NamozStats.done(prayers, ticks), YearMonth.of(2026, 11))
        assertEquals(30, grid.size)
        assertEquals(5, grid[0].second.size)
        assertEquals(4, grid[1].second.size)
        assertEquals(0, grid[10].second.size)
        val marks = listOf(
            NamozMarkEntity("2026-11-01", "ASR", NamozMarkEntity.MOSQUE), NamozMarkEntity("2026-11-02", "SHOM", NamozMarkEntity.MOSQUE),
            NamozMarkEntity("2026-11-02", "PESHIN", NamozMarkEntity.LATER), NamozMarkEntity("2026-12-01", "ASR", NamozMarkEntity.MOSQUE),
        )
        assertEquals(2, NamozStats.mosque(marks, YearMonth.of(2026, 11)))
        assertEquals(0, NamozStats.mosque(marks, YearMonth.of(2026, 10)))
    }

    /** The grid swipes from November 2026 to this month; before the start it still has November (never empty). */
    @Test fun monthsToSwipe() {
        assertEquals(listOf(YearMonth.of(2026, 11)), NamozStats.months(LocalDate.of(2026, 10, 30)))
        assertEquals(listOf(YearMonth.of(2026, 11)), NamozStats.months(LocalDate.of(2026, 11, 30)))
        assertEquals((11..12).map { YearMonth.of(2026, it) } + YearMonth.of(2027, 1), NamozStats.months(LocalDate.of(2027, 1, 3)))
    }
}
