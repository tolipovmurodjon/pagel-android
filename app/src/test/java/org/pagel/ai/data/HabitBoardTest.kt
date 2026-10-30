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
        // Monday 26: only Stretch had started (the other three were first ticked on the 27th), and it was done.
        assertEquals(Clear.ALL, b.week[0].clear)
        assertEquals(1 to 1, b.week[0].held to b.week[0].due)
        assertEquals(Clear.NONE, b.week[2].clear)  // today, none yet
        assertEquals(Clear.FUTURE, b.week[3].clear)
        assertEquals(10, b.cards.first().minutes)
        assertEquals(4 to 4, b.week[1].held to b.week[1].due)
    }

    /** Checker 2026-10-29 #1, built from real ticks through habitBoard: a habit added today never breaks past full days. */
    @Test fun addingAHabitTodayKeepsLastWeeksFullDaysFull() {
        val old = listOf(t("a", text = "Stretch", due = "2026-10-20 07:30"), t("b", text = "Water", due = "2026-10-20"))
        val ticks = listOf("2026-10-26", "2026-10-27").flatMap { d -> listOf(HabitTickEntity(d, "a"), HabitTickEntity(d, "b")) }
        val before = habitBoard(old, ticks, emptyList(), emptyList(), wed)
        assertEquals(listOf(Clear.ALL, Clear.ALL), before.week.take(2).map { it.clear })
        // Boss adds "Read" today (dated today, never ticked): Monday and Tuesday stay full.
        val after = habitBoard(old + t("c", text = "Read", due = "2026-10-28 21:00"), ticks, emptyList(), emptyList(), wed)
        assertEquals(listOf(Clear.ALL, Clear.ALL), after.week.take(2).map { it.clear })
        assertEquals(2 to 2, after.week[1].held to after.week[1].due)
        // Today it counts: 0 of 3.
        assertEquals(0 to 3, after.week[2].held to after.week[2].due)
        // A habit with no date and no tick starts today too.
        val undated = habitBoard(old + t("d", text = "Walk", due = null), ticks, emptyList(), emptyList(), wed)
        assertEquals(Clear.ALL, undated.week[1].clear)
    }

    /** Checker #2: a partial day where one habit is frozen is not held; only a whole day holds (ice when frozen). */
    @Test fun aFreezeOnAPartialDayDoesNotLinkTheChain() {
        val h = listOf(t("a", text = "Stretch", due = "2026-10-20 07:30"), t("b", text = "Water", due = "2026-10-20"))
        val ticks = listOf(HabitTickEntity("2026-10-26", "a"), HabitTickEntity("2026-10-26", "b"), HabitTickEntity("2026-10-27", "a"))
        val b = habitBoard(h, ticks, emptyList(), listOf(HabitFreezeEntity("2026-10-27", "a")), wed)
        assertEquals(Clear.SOME, b.week[1].clear)  // Tuesday: a done (and frozen), b not
        assertTrue(b.week[1].frozen)
        // Water frozen on Tuesday too: now the whole day holds.
        val held = habitBoard(h, ticks, emptyList(), listOf(HabitFreezeEntity("2026-10-27", "b")), wed)
        assertEquals(Clear.ALL, held.week[1].clear)
    }

    /** Checker data safety: a future day's freeze never counts as held. */
    @Test fun aFutureDayIsNeverFrozen() {
        assertEquals(Mark.FUTURE, markOf(t(), LocalDate.parse("2026-10-30"), wed, 0, frozen = true))
    }

    /** Checker #9: the bar runs off the strip's left edge when last Sunday was full and this Monday is full. */
    @Test fun theChainRunsInFromLastWeek() {
        val h = listOf(t("a", text = "Stretch", due = "2026-10-20 07:30"))
        val ticks = listOf("2026-10-25", "2026-10-26").map { HabitTickEntity(it, "a") }
        assertTrue(habitBoard(h, ticks, emptyList(), emptyList(), wed).chainIn)
        assertFalse(habitBoard(h, ticks.drop(1), emptyList(), emptyList(), wed).chainIn)
    }

    /** Checker #3: a running clock is saved on the phone with the day it was started for, and comes back the same. */
    @Test fun aRunningClockSurvivesTheAppBeingKilled() {
        val m = mapOf(
            "h1" to org.pagel.ai.ui.habits.HabitTimer(startedAt = 1_000L, spentMs = 5_000L, day = "2026-10-28", minutes = 10),
            "h2" to org.pagel.ai.ui.habits.HabitTimer(startedAt = null, spentMs = 60_000L, day = "2026-10-27", minutes = 5),
        )
        val back = org.pagel.ai.ui.habits.HabitClocks.decode(org.pagel.ai.ui.habits.HabitClocks.encode(m))
        assertEquals(m, back)
        assertEquals(emptyMap<String, org.pagel.ai.ui.habits.HabitTimer>(), org.pagel.ai.ui.habits.HabitClocks.decode("broken|line"))
    }

    /** Boss 2026-10-29: stacking offers only real habits, never itself, never a loop; a moment is kept as words. */
    @Test fun theAnchorPickerOffersOnlyHabitsAndNoLoops() {
        val L = org.pagel.ai.data.habits.HabitLook
        val a = t("a", text = "Workout"); val b = t("b", text = "Floor routine"); val w = t("w", text = "Weekly review", every = "week")
        val prayer = t("p", text = "Bomdod", group = "namoz")
        val prefs = mapOf("b" to HabitPrefEntity("b", after = "a"))
        assertEquals(listOf("b"), L.anchorChoices("a", listOf(a, b, w, prayer), emptyMap()).map { it.id })
        // b already follows a: a after b would be a loop.
        assertEquals(emptyList<String>(), L.anchorChoices("a", listOf(a, b, w), prefs).map { it.id })
        assertEquals(org.pagel.ai.data.habits.HabitLook.Anchor.Moment("☕ Coffee"), L.anchorOf(L.store(org.pagel.ai.data.habits.HabitLook.Anchor.Moment("☕ Coffee"))))
        assertEquals(org.pagel.ai.data.habits.HabitLook.Anchor.Habit("a"), L.anchorOf("a"))
        assertEquals("After ☕ coffee → 🧘 Floor routine", L.recipe(org.pagel.ai.data.habits.HabitLook.Anchor.Moment("☕ Coffee"), null, "🧘", "Floor routine"))
    }

    @Test fun stackedHabitsSitUnderTheirAnchor() {
        val L = org.pagel.ai.data.habits.HabitLook
        // c follows a, d follows c; e follows a habit on another page, so it stays a plain row.
        val prefs = mapOf("c" to HabitPrefEntity("c", after = "a"), "d" to HabitPrefEntity("d", after = "c"), "e" to HabitPrefEntity("e", after = "zz"))
        assertEquals(listOf("a" to 0, "c" to 1, "d" to 2, "b" to 0, "e" to 0), L.stackOrder(listOf("a", "b", "c", "d", "e"), { it }, prefs))
        // A loop that slipped in (a after b, b after a) still shows each row once.
        val loop = mapOf("a" to HabitPrefEntity("a", after = "b"), "b" to HabitPrefEntity("b", after = "a"))
        assertEquals(setOf("a", "b"), L.stackOrder(listOf("a", "b"), { it }, loop).map { it.first }.toSet())
    }

    @Test fun aHabitsLookKeepsEmojiAndColourTogether() {
        val L = org.pagel.ai.data.habits.HabitLook
        assertEquals("coffee/amber", L.look("coffee", "amber"))
        assertEquals("coffee", L.emojiOf("coffee/amber")); assertEquals("amber", L.colourOf("coffee/amber"))
        assertEquals("/teal", L.look(null, "teal")); assertNull(L.emojiOf("/teal"))
        assertEquals("coffee", L.look("coffee", "purple"))  // not one of the ten: dropped
    }

    /** Boss 2026-10-29: never a week before the habit started; a 3-day-old habit has one bar and is "too new". */
    @Test fun weeksStartWithTheHabit() {
        val h = t("n", text = "Read", due = "2026-10-26 21:00")
        val w = org.pagel.ai.data.habits.habitWeeks(h, listOf(HabitTickEntity("2026-10-27", "n")), emptyList(), wed)
        assertEquals(1, w.bars.size)
        assertTrue(w.tooNew)
        assertEquals(3, w.daysTracked)
        assertEquals(1 to 7, w.bars[0].done to w.bars[0].target)
    }
}
