package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.data.db.HabitFreezeEntity
import org.pagel.ai.data.db.HabitPrefEntity
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.Clear
import org.pagel.ai.data.habits.Mark
import org.pagel.ai.data.habits.Part
import org.pagel.ai.data.habits.chainBest
import org.pagel.ai.data.habits.chainStreak
import org.pagel.ai.data.habits.completion
import org.pagel.ai.data.habits.freezesLeft
import org.pagel.ai.data.habits.habitBoard
import org.pagel.ai.data.habits.links
import org.pagel.ai.data.habits.markOf
import org.pagel.ai.data.habits.minutesOf
import org.pagel.ai.data.habits.neverMissTwice
import java.time.LocalDate
import java.time.YearMonth

/** Round D's pure rules: the chain's links, streaks with freezes, never miss twice, completion, minutes, namoz's start. */
class HabitBoardTest {
    private fun t(id: String = "h", every: String = "day", times: Int = 1, text: String = "Stretch", due: String? = "2026-10-28 07:30", group: String? = null) = TaskEntity(
        id = id, project = "me", phase = "Now", sort = 1.0, text = text, title = null, status = "open", priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "", serverVersion = 1, lastModified = 0, times = times, habitGroup = group,
    )
    private val wed = LocalDate.parse("2026-10-28")

    @Test fun heldDaysLinkAndTheChainRunsOverOffDays() {
        assertEquals(listOf(true, false, false, false), links(listOf(Mark.DONE, Mark.DONE, Mark.MISSED, Mark.DONE)))
        // Tue done, Wed not its day, Thu done: one chain over Wednesday.
        assertEquals(listOf(true, true, false), links(listOf(Mark.DONE, Mark.OFF, Mark.DONE)))
        // A frozen day holds the chain.
        assertEquals(listOf(true, true, false), links(listOf(Mark.DONE, Mark.FROZEN, Mark.DONE)))
        assertEquals(listOf(false, false), links(listOf(Mark.DONE, Mark.TODAY, Mark.FUTURE)).take(2))
    }

    @Test fun aFrozenDayHoldsTheStreakButDoesNotCount() {
        val done = setOf("2026-10-27", "2026-10-25", "2026-10-24")
        assertEquals(1, chainStreak(t(), done, emptySet(), wed))
        assertEquals(3, chainStreak(t(), done, setOf("2026-10-26"), wed))
        assertEquals(3, chainBest(t(), done, setOf("2026-10-26"), wed))
        assertEquals(2, freezesLeft(emptyList(), YearMonth.of(2026, 10)))
        assertEquals(0, freezesLeft(listOf(HabitFreezeEntity("2026-10-02", "a"), HabitFreezeEntity("2026-10-26", "h")), YearMonth.of(2026, 10)))
        assertEquals(2, freezesLeft(listOf(HabitFreezeEntity("2026-09-26", "h")), YearMonth.of(2026, 10)))
    }

    @Test fun neverMissTwiceAfterOneMiss() {
        // Done Monday, missed Tuesday, Wednesday not done yet: the amber chip.
        assertTrue(neverMissTwice(t(), setOf("2026-10-26"), emptySet(), wed))
        assertFalse(neverMissTwice(t(), setOf("2026-10-26", "2026-10-27"), emptySet(), wed))
        // Frozen Tuesday is not a miss; a brand-new habit is not a miss either.
        assertFalse(neverMissTwice(t(), setOf("2026-10-26"), setOf("2026-10-27"), wed))
        assertFalse(neverMissTwice(t(), emptySet(), emptySet(), wed))
        // Tue/Thu/Sun on Thursday: its last day was Tuesday.
        assertTrue(neverMissTwice(t(every = "tue,thu,sun"), setOf("2026-10-25"), emptySet(), LocalDate.parse("2026-10-29")))
        // Never for a prayer.
        assertFalse(neverMissTwice(t(text = "Peshin", group = "namoz"), setOf("2026-11-02"), emptySet(), LocalDate.parse("2026-11-04")))
    }

    @Test fun completionCountsDueDaysFromTheFirstDone() {
        val done = setOf("2026-10-20", "2026-10-21", "2026-10-23")
        assertEquals(3 to 9, completion(t(), done, emptySet(), LocalDate.parse("2026-10-01"), wed))
        assertEquals(3 to 8, completion(t(), done, setOf("2026-10-22"), LocalDate.parse("2026-10-01"), wed))
    }

    @Test fun minutesComeFromTheTitle() {
        assertEquals(10, minutesOf("Stretch for ten minutes"))
        assertEquals(5, minutesOf("Do the five-minute floor routine"))
        assertEquals(20, minutesOf("Read 20 min"))
        assertNull(minutesOf("Drink water"))
        assertEquals(15, minutesOf("Drink water", chosen = 15))
    }

    @Test fun namozCountsOnlyFromTheFirstOfNovember() {
        val p = t(text = "Peshin", group = "namoz", due = "2026-11-01 12:11")
        assertEquals(Mark.BEFORE, markOf(p, LocalDate.parse("2026-10-31"), wed, 0, false))
        assertEquals(Mark.MISSED, markOf(p, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02"), 0, false))
        // No chain before it: a streak stops at 1 November instead of reaching back.
        assertEquals(2, chainStreak(p, setOf("2026-11-01", "2026-11-02"), emptySet(), LocalDate.parse("2026-11-03")))
        // A prayer by title (an older pagel.uz without `group`) is still namoz.
        val b = habitBoard(listOf(t(id = "x", text = "Bomdod", due = "2026-11-01 05:35"), t()), emptyList(), emptyList(), emptyList(), wed)
        assertEquals(listOf("Bomdod"), b.namoz.map { it.title })
        assertEquals(listOf("Stretch"), b.cards.map { it.title })
    }

    @Test fun theBoardSortsPartsStacksAndClearsTheWeek() {
        val habits = listOf(t("a", text = "Stretch for ten minutes"), t("b", text = "Inbox saves", due = "2026-10-28 09:00"), t("c", text = "Read", due = "2026-10-28 21:30"), t("d", text = "Drink water", due = "2026-10-28"))
        val ticks = listOf("a", "b", "c", "d").map { HabitTickEntity("2026-10-27", it) } + HabitTickEntity("2026-10-26", "a")
        val b = habitBoard(habits, ticks, listOf(HabitPrefEntity("c", after = "a")), emptyList(), wed)
        // Read follows Stretch, so it sits right under it although it is an evening habit.
        assertEquals(listOf("Stretch for ten minutes", "Read", "Inbox saves", "Drink water"), b.cards.map { it.title })
        assertEquals(Part.ANYTIME, b.cards.last().part)
        assertEquals(listOf("Stretch for ten minutes", "Inbox saves", "Drink water"), b.inPart(Part.MORNING).map { it.title })
        assertEquals(Clear.ALL, b.week[1].clear)   // Tuesday 27: all four held
        assertEquals(Clear.SOME, b.week[0].clear)  // Monday 26: one of four
        assertEquals(Clear.NONE, b.week[2].clear)  // today, none yet
        assertEquals(Clear.FUTURE, b.week[3].clear)
        assertEquals(10, b.cards.first().minutes)
        // The all-habits chain (Boss 2026-10-29): a day fills only at 4 of 4; Monday's thin ring is 1 of 4.
        assertEquals(4 to 4, b.week[1].held to b.week[1].due)
        assertEquals(1 to 4, b.week[0].held to b.week[0].due)
        assertEquals(0.25f, b.week[0].progress, 0.001f)
    }
}
