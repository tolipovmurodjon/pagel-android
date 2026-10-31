package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.data.api.StuckDto
import org.pagel.ai.data.api.SweepRowDto
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.rituals.Move
import org.pagel.ai.data.rituals.Rituals
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** The rituals' rules on the JVM: ritual days at +05:00, the star cap, Close the day's lists, the review's, the rows. */
class RitualLogicTest {
    private fun t(
        id: String, project: String = "me", priority: Int = 2, star: Boolean = false, status: String = "open", now: Boolean = true,
        every: String? = null, sort: Double = 1.0, phase: String = "Now",
    ) = TaskEntity(
        id = id, project = project, phase = phase, sort = sort, text = "Task $id", title = null, status = status, priority = priority, star = star,
        due = null, every = every, doneWhen = null, doneAt = null, updatedAt = "", serverVersion = 1, lastModified = 0, phaseNow = now,
    )
    private fun ms(s: String) = OffsetDateTime.parse(s).toInstant().toEpochMilli()

    @Test fun theReviewPillShowsOnlyOnASundayWhenDueAndOtherDaysItLivesInMore() {
        // 2026-11-01 is a Sunday, 2026-10-31 a Saturday.
        assertTrue(Rituals.reviewPill(null, "2026-11-01"))
        assertFalse(Rituals.reviewPill(null, "2026-10-31"))
        assertFalse(Rituals.reviewPill("2026-10-28", "2026-11-01"))
    }

    @Test fun theRitualDayIsLastNightUntilFiveInTashkent() {
        assertEquals("2026-10-28", Rituals.ritualDay(ms("2026-10-28T23:59:00+05:00")))
        assertEquals("2026-10-28", Rituals.ritualDay(ms("2026-10-29T04:59:00+05:00")))
        assertEquals("2026-10-29", Rituals.ritualDay(ms("2026-10-29T05:00:00+05:00")))
        // 19:30 UTC is 00:30 Tashkent: still the 28th's evening, though UTC says the 28th too and Tashkent says the 29th.
        assertEquals("2026-10-28", Rituals.ritualDay(ms("2026-10-28T19:30:00Z")))
    }

    @Test fun aReviewIsDueAfterSevenDaysOrNever() {
        assertTrue(Rituals.reviewDue(null, "2026-10-28"))
        assertFalse(Rituals.reviewDue("2026-10-22", "2026-10-28"))
        assertTrue(Rituals.reviewDue("2026-10-21", "2026-10-28"))
        assertEquals("2026-10-28", Rituals.later("2026-10-21", "2026-10-28"))
        assertEquals("2026-10-28", Rituals.later("2026-10-28", null))
    }

    @Test fun closeTheDayShowsTodaysOpenStarsMostImportantFirst() {
        val open = listOf(t("a", priority = 2, star = true), t("b", priority = 0, star = true), t("c"), t("d", star = true, status = "done"))
        assertEquals(listOf("b", "a"), Rituals.stars(open).map { it.id })
    }

    @Test fun tomorrowsCandidatesPutEachProjectsNextActionFirstAndSkipStarsSomedayHabitsAndLaterPhases() {
        val open = listOf(
            t("h1", "humopedia", priority = 0, sort = 5.0), t("h2", "humopedia", priority = 0, sort = 1.0),
            t("m1", "me", priority = 2), t("m0", "me", priority = 1, star = true),
            t("s", "me", priority = 3), t("g", "me", priority = 0, every = "day"), t("l", "ababil", priority = 0, now = false),
            t("a1", "ababil", priority = 2), t("a2", "ababil", priority = 1, sort = 9.0),
        )
        // Next actions: humopedia h2 (P0, first in plan), ababil a2 (P1); "me"'s is the habit g, never a candidate.
        assertEquals(listOf("h2", "a2", "h1", "m1", "a1"), Rituals.candidates(open).map { it.id })
    }

    /** No cap, as the web (Boss 2026-10-27: "Pick not working… keep my current ones"; checker P5 #3). */
    @Test fun picksAreNotCappedAndUnpickAlwaysWorks() {
        var picks = emptyList<String>()
        for (id in listOf("a", "b", "c", "d")) picks = Rituals.togglePick(picks, id)
        assertEquals(listOf("a", "b", "c", "d"), picks)
        picks = Rituals.togglePick(picks, "b")
        assertEquals(listOf("a", "c", "d"), picks)
    }

    @Test fun closeTheDayIsAnEveningPillFromSixUntilFiveInTashkent() {
        assertFalse(Rituals.evening(ms("2026-10-28T09:20:00+05:00")))
        assertFalse(Rituals.evening(ms("2026-10-28T17:59:00+05:00")))
        assertTrue(Rituals.evening(ms("2026-10-28T18:00:00+05:00")))
        assertTrue(Rituals.evening(ms("2026-10-29T04:59:00+05:00")))
        assertFalse(Rituals.evening(ms("2026-10-29T05:00:00+05:00")))
        // 01:30 closes the 28th: the ritual's head says Wednesday, not Thursday (checker P5 #10).
        assertEquals(java.time.LocalDate.parse("2026-10-28"), Rituals.ritualDate(ms("2026-10-29T01:30:00+05:00")))
    }

    @Test fun tomorrowHoldsTheKeptStarsPlusThePicks() {
        val stars = listOf(t("a", star = true), t("b", star = true), t("c", star = true))
        assertEquals(3, Rituals.starsTomorrow(stars, emptyMap(), emptyList()))
        assertEquals(2, Rituals.starsTomorrow(stars, mapOf("a" to Move.LATER, "b" to Move.DROP), listOf("x")))
    }

    @Test fun theReviewListsSomedayAndTheProjectsStillStuck() {
        val open = listOf(t("p3", "me", priority = 3), t("p3late", "me", priority = 3, now = false), t("x", "humopedia"), t("new", "ababil", phase = "", now = false))
        assertEquals(listOf("p3"), Rituals.someday(open).map { it.id })
        val server = listOf(StuckDto("humopedia", "Humopedia"), StuckDto("ababil", "ABABIL"), StuckDto("labs", "Labs"))
        // humopedia has an open NOW task now; ababil got one added on the phone (not yet synced); labs is still stuck.
        assertEquals(listOf("labs"), Rituals.stuck(server, open).map { it.project })
    }

    @Test fun addKeepsDoAndSomedayRowsAndRefusesOneOverTwentyWords() {
        val rows = listOf(SweepRowDto("action", "Email Aziz", "me"), SweepRowDto("drop", "Worry about rain", "me"), SweepRowDto("someday", "Learn guitar", "me"), SweepRowDto("action", "  ", "me"))
        assertEquals(listOf("Email Aziz", "Learn guitar"), Rituals.kept(rows).map { it.text })
        val long = SweepRowDto("action", (1..21).joinToString(" ") { "w$it" }, "me")
        assertTrue(Rituals.tooLong(long))
        assertFalse(Rituals.tooLong(long.copy(kind = "drop")))
        assertFalse(Rituals.tooLong(SweepRowDto("action", (1..20).joinToString(" ") { "w$it" }, "me")))
        assertEquals(3, Rituals.lines("call mom\n\nfix login\nlearn guitar\n"))
    }

    @Test fun theEveningReminderRingsTonightOrTomorrowInTashkent() {
        val before = ZonedDateTime.parse("2026-10-28T21:00:00+05:00[Asia/Tashkent]")
        assertEquals(ZonedDateTime.parse("2026-10-28T21:30:00+05:00[Asia/Tashkent]"), Rituals.nextEvening("21:30", before))
        val after = ZonedDateTime.parse("2026-10-28T21:31:00+05:00[Asia/Tashkent]")
        assertEquals(ZonedDateTime.parse("2026-10-29T21:30:00+05:00[Asia/Tashkent]"), Rituals.nextEvening("21:30", after))
        // A phone on UTC: 17:00 UTC is 22:00 Tashkent, past 21:30, so tomorrow.
        val utc = ZonedDateTime.parse("2026-10-28T17:00:00Z")
        assertEquals(ZonedDateTime.parse("2026-10-29T21:30:00+05:00[Asia/Tashkent]").toInstant(), Rituals.nextEvening("21:30", utc).toInstant())
        // A bad time falls back to 20:45 (Boss's day close), already past at 21:00: tomorrow.
        assertEquals(ZonedDateTime.parse("2026-10-29T20:45:00+05:00[Asia/Tashkent]"), Rituals.nextEvening("bad", before))
    }
}
