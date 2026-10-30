package org.pagel.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.reminders.NotifyPrefs
import org.pagel.ai.reminders.RemindChoice
import org.pagel.ai.ui.today.Energy
import org.pagel.ai.ui.today.TodayRow
import org.pagel.ai.ui.today.clockText
import org.pagel.ai.ui.today.firstStepOf
import org.pagel.ai.ui.today.freeMinutes
import org.pagel.ai.ui.today.looksBig
import org.pagel.ai.ui.today.todaySortAfterMove
import org.pagel.ai.ui.today.whatNow
import org.pagel.ai.ui.task.checkDays
import java.time.LocalDate
import java.time.LocalTime

/** What now's one pick (energy × free minutes × Boss's order), the order key math, the clocks and Remind me's words. */
class StartingMachineTest {
    private val day = LocalDate.of(2026, 10, 30)
    private fun row(id: String, title: String, due: String? = null, every: String? = null, done: Boolean = false) =
        TodayRow(id, title, 2, due, every, "me", "Me", null, done)

    private val order = listOf(
        row("big", "Build the landing page for the grant"),
        row("small", "Call Aziz"),
        row("tiny", "Water the plant"),
    )

    @Test fun okEnergyFollowsBossOrder() {
        assertEquals("big", whatNow(order, Energy.OK, day, LocalTime.of(10, 0), free = null, skipped = emptySet())?.id)
    }

    @Test fun lowEnergyPushesTheBigTaskDown() {
        assertEquals("small", whatNow(order, Energy.LOW, day, LocalTime.of(10, 0), free = null, skipped = emptySet())?.id)
    }

    @Test fun littleFreeTimeBeforeTheNextMeetingPicksASmallOne() {
        assertEquals("small", whatNow(order, Energy.HIGH, day, LocalTime.of(10, 0), free = 20, skipped = emptySet())?.id)
        assertEquals("with time, high energy eats the frog", "big", whatNow(order, Energy.HIGH, day, LocalTime.of(10, 0), free = 90, skipped = emptySet())?.id)
    }

    @Test fun aTimedTaskDueNowGoesFirstAndOneLaterTodayWaits() {
        val list = order + row("meet", "Call the bank", due = "2026-10-30 10:10") + row("later", "Gym", due = "2026-10-30 18:00")
        assertEquals("meet", whatNow(list, Energy.OK, day, LocalTime.of(10, 0), free = 10, skipped = emptySet())?.id)
        val onlyLater = listOf(row("later", "Gym", due = "2026-10-30 18:00"), row("tiny", "Water the plant"))
        assertEquals("tiny", whatNow(onlyLater, Energy.OK, day, LocalTime.of(10, 0), free = null, skipped = emptySet())?.id)
    }

    @Test fun notThisSkipsAndDoneAndHabitsNeverComeBack() {
        val list = listOf(row("a", "Call Aziz", done = true), row("h", "Stretch", every = "day"), row("b", "Water the plant"), row("c", "Pay rent"))
        assertEquals("b", whatNow(list, Energy.OK, day, LocalTime.NOON, null, emptySet())?.id)
        assertEquals("c", whatNow(list, Energy.OK, day, LocalTime.NOON, null, setOf("b"))?.id)
        assertNull(whatNow(list, Energy.OK, day, LocalTime.NOON, null, setOf("b", "c")))
    }

    @Test fun freeMinutesReadOnlyTimesStillAheadToday() {
        val dues = listOf("2026-10-30 09:00", "2026-10-30 10:45", "2026-10-30 14:00-15:00", "2026-10-31 10:15", null, "2026-10-30")
        assertEquals(45, freeMinutes(dues, day, LocalTime.of(10, 0)))
        assertNull(freeMinutes(listOf("2026-10-30 09:00"), day, LocalTime.of(10, 0)))
    }

    @Test fun bigMeansAVagueVerbOrMoreThanTenWords() {
        assertTrue(looksBig("Build the landing page"))
        assertTrue(looksBig("Learn: Unity basics"))
        assertTrue(looksBig("Call the bank and then ask about the report and the new card"))
        assertFalse(looksBig("Call the bank"))
    }

    @Test fun aSortBetweenNeighboursMovesOneRowOnly() {
        val keys = listOf(0.0, 1.0, 2.0, 3.0, 4.0)
        assertEquals(-1.0, todaySortAfterMove(keys, 4, 0), 0.0)
        assertEquals(5.0, todaySortAfterMove(keys, 0, 4), 0.0)
        assertEquals(1.5, todaySortAfterMove(keys, 4, 2), 0.0)
        assertEquals(2.5, todaySortAfterMove(keys, 0, 2), 0.0)
        assertEquals(3.0, todaySortAfterMove(keys, 3, 3), 0.0)
        // Many drags into the same gap stay strictly between their neighbours.
        var a = 0.0; val b = 1.0
        repeat(30) { val m = todaySortAfterMove(listOf(a, b, 9.0), 2, 1); assertTrue(m > a && m < b); a = m }
    }

    @Test fun theFirstStepIsTheFirstMicroStepElseAnOpenStep() {
        assertEquals("Pick up the book", firstStepOf(listOf("Pick up the book"), listOf("Read chapter 2")))
        assertEquals("Read chapter 2", firstStepOf(emptyList(), listOf("Read chapter 2")))
        assertEquals("Open it and look for 2 minutes", firstStepOf(emptyList(), emptyList()))
    }

    @Test fun clocksNeverGoNegative() {
        assertEquals("10:00", clockText(600))
        assertEquals("0:09", clockText(9))
        assertEquals("0:00", clockText(-4))
    }

    @Test fun remindMeSaysWhenInPlainWords() {
        assertEquals(listOf("At the time", "10 min early", "30 min early", "1 h early", "Off"), RemindChoice.ALL.map(RemindChoice::label))
        assertEquals("10 min early, 17:50", RemindChoice.value(10, "2026-10-30 18:00"))
        assertEquals("1 h early, 09:00", RemindChoice.value(60, "2026-10-30 10:00-12:00"))
        assertEquals("At the time, 18:00", RemindChoice.value(0, "2026-10-30 18:00"))
        assertEquals("Off", RemindChoice.value(NotifyPrefs.OFF, "2026-10-30 18:00"))
        assertEquals("1 h", RemindChoice.words(60))
    }

    @Test fun waitingForOffersTomorrowThreeDaysAndNextMonday() {
        val days = checkDays(day) // a Friday
        assertEquals(listOf(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2)), days.map { it.second })
        assertEquals("Mon 2 Nov", days[2].first)
    }

    @Test fun energyReadsItsOwnWordsOnly() {
        assertEquals(Energy.LOW, Energy.of("low"))
        assertNull(Energy.of("tired"))
    }
}
