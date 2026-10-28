package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.data.compass.CompassRules
import org.pagel.ai.data.compass.Goal
import org.pagel.ai.data.compass.GoalBody
import org.pagel.ai.data.compass.KeyResult
import org.pagel.ai.data.compass.Level
import java.time.LocalDate

/** The Compass's calendar and scoring (Phase 6a): birthday years, seasons, the cap, the chain, 70 %, reset. */
class CompassRulesTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun theYearRunsBirthdayToBirthday() {
        assertEquals(d("2025-11-01"), CompassRules.yearStart(d("2026-10-31")))
        assertEquals(d("2026-11-01"), CompassRules.yearStart(d("2026-11-01")))
        assertEquals(d("2026-11-01"), CompassRules.yearStart(d("2027-10-31")))
        assertEquals(d("2025-11-01"), CompassRules.yearStart(d("2026-01-01")))
        assertEquals(d("2027-10-31"), CompassRules.yearEnd(d("2026-11-01")))
    }

    @Test fun hisAgeTurnsOnTheFirstOfNovember() {
        assertEquals(18, CompassRules.ageOn(d("2026-10-31")))
        assertEquals(19, CompassRules.ageOn(d("2026-11-01")))
        assertEquals("Year 19", CompassRules.yearTitle(d("2026-11-01")))
        assertEquals("Year 18", CompassRules.yearTitle(CompassRules.yearStart(d("2026-10-29"))))
        assertEquals("Year 18 → 19", CompassRules.reportTitle(d("2026-10-31")))
    }

    @Test fun fourSeasonsOfThirteenWeeksCoverTheYearWithNoGap() {
        val s = CompassRules.seasons(d("2026-11-01"))
        assertEquals(4, s.size)
        assertEquals(d("2026-11-01"), s[0].starts)
        assertEquals(d("2027-01-23"), s[0].workEnds)
        assertEquals(d("2027-01-30"), s[0].ends)
        for (i in 1 until 4) assertEquals(s[i - 1].ends.plusDays(1), s[i].starts)
        assertEquals(d("2027-10-31"), s[3].ends)
        assertEquals(1, CompassRules.seasonOf(d("2026-11-01")).n)
        assertEquals(4, CompassRules.seasonOf(d("2026-10-31")).n)
        assertEquals(2, CompassRules.seasonOf(d("2027-01-31")).n)
    }

    @Test fun weeksCountFromTheSeasonStartAndTheThirteenthIsTheReview() {
        val s = CompassRules.seasonOf(d("2026-11-01"))
        assertEquals(1, CompassRules.weekOf(s, d("2026-11-01")))
        assertEquals(1, CompassRules.weekOf(s, d("2026-11-07")))
        assertEquals(2, CompassRules.weekOf(s, d("2026-11-08")))
        assertEquals(13, CompassRules.weekOf(s, d("2027-01-30")))
        assertEquals(d("2026-11-08"), CompassRules.weekStart(s, 2))
    }

    @Test fun aSeasonHoldsThreeAndAChangeAfterItsFirstWeekAsksWhy() {
        assertTrue(CompassRules.canAdd(2))
        assertFalse(CompassRules.canAdd(3))
        val s = CompassRules.seasonOf(d("2026-11-01"))
        assertFalse(CompassRules.changeNeedsWhy(s, d("2026-11-07")))
        assertTrue(CompassRules.changeNeedsWhy(s, d("2026-11-08")))
    }

    private fun g(id: String, level: Level, parent: String?) = Goal(id, level, parent, null, null, GoalBody(title = id))

    @Test fun everyChainEndsAtWhyILive() {
        val goals = listOf(g("p", Level.PURPOSE, null), g("t", Level.TEN_YEAR, "p"), g("y", Level.YEAR, "t"), g("s", Level.SEASON, "y"), g("x", Level.SOMEDAY, null))
            .associateBy { it.id }
        assertEquals(listOf("s", "y", "t", "p"), CompassRules.chain(goals, "s")!!.map { it.id })
        assertNotNull(CompassRules.chain(goals, "x"))
        // A season goal linked straight to the purpose skips a level: refused.
        assertNull(CompassRules.chain(goals + ("bad" to g("bad", Level.SEASON, "p")), "bad"))
        // A missing parent breaks the chain; a loop never hangs.
        assertNull(CompassRules.chain(goals + ("lost" to g("lost", Level.YEAR, "nope")), "lost"))
        assertNull(CompassRules.chain(mapOf("a" to g("a", Level.YEAR, "b"), "b" to g("b", Level.TEN_YEAR, "a")), "a"))
        assertTrue(CompassRules.parentOk(Level.PURPOSE, null))
        assertFalse(CompassRules.parentOk(Level.YEAR, null))
    }

    /** Week 5 of season 1 (2026-11-29..12-05): weeks 1-4 are finished, week 5 counts once it is hit. */
    private val season = CompassRules.seasonOf(d("2026-11-01"))
    private val today = d("2026-12-01")
    private fun weekDay(w: Int) = CompassRules.weekStart(season, w).plusDays(2).toString()

    @Test fun seventyPercentIsAWinAndOnlyMyPartCounts() {
        val body = GoalBody(title = "s", leadAction = "Write 1 page", outcome = "Get the job")
        // 3 of the 4 finished weeks hit: 75 % → a win.
        val hit3 = CompassRules.score(season, today, body, setOf(weekDay(1), weekDay(2), weekDay(4)))
        assertEquals(4, hit3.weeksCounted)
        assertEquals(0.75f, hit3.value!!, 0.001f)
        assertTrue(hit3.win)
        // 2 of 4 → 50 %, not yet a win; this week counts once it is hit (3 of 5 = 60 %).
        assertFalse(CompassRules.score(season, today, body, setOf(weekDay(1), weekDay(2))).win)
        assertEquals(0.6f, CompassRules.score(season, today, body, setOf(weekDay(1), weekDay(2), weekDay(5))).value!!, 0.001f)
        // Exactly 70 % is a win: 7 of 10 finished weeks.
        val late = d("2027-01-11")
        val seven = (1..7).map { CompassRules.weekStart(season, it).toString() }.toSet()
        assertTrue(CompassRules.score(season, late, body, seven).win)
        // Key results average in; nothing measurable yet = no score, never a zero.
        val krs = body.copy(keyResults = listOf(KeyResult("a", true), KeyResult("b", false)))
        assertEquals((0.75f + 0.5f) / 2, CompassRules.score(season, today, krs, setOf(weekDay(1), weekDay(2), weekDay(4))).value!!, 0.001f)
        assertNull(CompassRules.score(season, today, GoalBody(title = "only an outcome", outcome = "x"), emptySet()).value)
    }

    @Test fun neverMissTwiceAndResetRollsTheWeekForward() {
        val body = GoalBody(title = "s", leadAction = "Run")
        val once = CompassRules.score(season, today, body, setOf(weekDay(1), weekDay(2), weekDay(3)))
        assertTrue(once.missedLastWeek)
        assertFalse(once.missedTwice)
        val twice = CompassRules.score(season, today, body, setOf(weekDay(1), weekDay(2)))
        assertTrue(twice.missedTwice)
        // Reset: the quiet weeks are set aside (no red, no restart); 2 of 2 counted weeks = a win again.
        val reset = CompassRules.reset(season, today, body, setOf(weekDay(1), weekDay(2)))
        assertEquals(listOf(CompassRules.weekStart(season, 3).toString(), CompassRules.weekStart(season, 4).toString()), reset.excused)
        val after = CompassRules.score(season, today, reset, setOf(weekDay(1), weekDay(2)))
        assertEquals(2, after.weeksCounted)
        assertTrue(after.win)
        assertFalse(after.missedLastWeek)
    }

    @Test fun todaysLineIsTheFirstWords() {
        assertEquals("Build the school that finds every…", CompassRules.firstWords("Build the school that finds every child's gift in Uzbekistan"))
        assertEquals("Be kind", CompassRules.firstWords("  Be   kind "))
    }
}
