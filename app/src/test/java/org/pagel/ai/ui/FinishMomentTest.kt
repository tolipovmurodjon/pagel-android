package org.pagel.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pagel.ai.ui.today.Combo
import org.pagel.ai.ui.today.DayProgress
import org.pagel.ai.ui.today.TodayCounts
import org.pagel.ai.ui.today.clearChange
import org.pagel.ai.ui.today.clearedStreak
import org.pagel.ai.ui.today.comboText
import org.pagel.ai.ui.today.nudgeText
import java.time.LocalDate

/** Round B (H): the finish moment's numbers: ticks in a row, the day's progress, the streak of cleared days. */
class FinishMomentTest {
    private val min = 60_000L

    @Test fun ticksWithinTenMinutesOfEachOtherAreInARow() {
        val c = Combo()
        assertEquals(1, c.tick(0))
        assertEquals(2, c.tick(4 * min))
        assertEquals(3, c.tick(13 * min)) // 9 min after the one before: still in a row
        assertEquals(1, c.tick(24 * min)) // 11 min later: a new run
    }

    @Test fun undoTakesTheLastTickOutOfTheRun() {
        val c = Combo()
        c.tick(0); c.tick(min)
        c.undo()
        assertEquals(2, c.tick(2 * min))
    }

    @Test fun theToastSaysDoneThenTheCombo() {
        assertEquals("Done", comboText(1))
        assertEquals("2 in a row 🔥", comboText(2))
        assertEquals("3 in a row — on fire 🔥", comboText(3))
    }

    @Test fun progressCountsTodaysFinishedAgainstTheWholeDay() {
        val p = DayProgress(done = 2, open = 3)
        assertEquals(5, p.total)
        assertEquals("2 of 5 ✓", p.text)
        assertEquals(0.4f, p.fraction, 0.0001f)
        assertEquals(0f, DayProgress(0, 0).fraction, 0f)
    }

    @Test fun theNudgeNamesTheNextTask() {
        assertEquals("Next: Call the bank · 10 min?", nudgeText("Call the bank"))
        assertNull(nudgeText(null))
    }

    @Test fun clearedDaysCountBackFromTodayOrYesterday() {
        val today = LocalDate.of(2026, 10, 28)
        assertEquals(3, clearedStreak(setOf("2026-10-28", "2026-10-27", "2026-10-26", "2026-10-24"), today))
        // Today not cleared yet: yesterday's run still stands.
        assertEquals(2, clearedStreak(setOf("2026-10-27", "2026-10-26"), today))
        assertEquals(0, clearedStreak(setOf("2026-10-25"), today))
    }

    // Round B checker #9: the day is cleared by a tick from any screen, never by a sync moving tasks away; Undo takes it back.
    @Test fun theDayIsClearedByATickNotBySyncAndUndoTakesItBack() {
        assertEquals(0, clearChange(null, TodayCounts(0, 3, 0)))
        assertEquals(1, clearChange(TodayCounts(1, 2, 0), TodayCounts(0, 3, 1)))
        // a sync took the last open task off Today: nothing finished, nothing waits
        assertEquals(0, clearChange(TodayCounts(1, 2, 0), TodayCounts(0, 2, 0)))
        // a habit's tick (moves the date, waits in the outbox) clears it too
        assertEquals(1, clearChange(TodayCounts(1, 2, 0), TodayCounts(0, 2, 1)))
        // Undo brings the task back
        assertEquals(-1, clearChange(TodayCounts(0, 3, 1), TodayCounts(1, 2, 0)))
        assertEquals(0, clearChange(TodayCounts(0, 3, 0), TodayCounts(0, 3, 0)))
    }

    @Test fun nextSaysWhatATickWouldMakeWithoutCountingIt() {
        val c = Combo()
        assertEquals(1, c.next(0)); c.tick(0); c.tick(min)
        assertEquals(3, c.next(2 * min))
        assertEquals(3, c.next(2 * min))
        assertEquals(1, c.next(30 * min))
    }
}
