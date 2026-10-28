package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pagel.ai.data.habits.Part
import org.pagel.ai.data.habits.streakOf
import org.pagel.ai.reminders.ReminderMath
import org.pagel.ai.ui.glass.Repeat
import java.time.ZoneId
import java.time.ZonedDateTime

/** Phase 3's pure rules: the web's repeat step (lib/repeat.ts), its streak (habit-log.ts) and the reminder's next time. */
class HabitsLogicTest {
    @Test fun nextDueFollowsTheWebsRepeatRule() {
        assertEquals("2026-10-29 07:30", Repeat.nextDue("2026-10-28 07:30", "day", "2026-10-28"))
        // Missed for days: it comes back once, today, not three times.
        assertEquals("2026-10-28", Repeat.nextDue("2026-10-25", "day", "2026-10-28"))
        // Friday → weekday → Monday.
        assertEquals("2026-11-02", Repeat.nextDue("2026-10-30", "weekday", "2026-10-30"))
        assertEquals("2026-11-04 09:00", Repeat.nextDue("2026-10-28 09:00", "week", "2026-10-28"))
        // Wednesday, "tue,thu,sun" → Thursday; Thursday → Sunday.
        assertEquals("2026-10-29", Repeat.nextDue("2026-10-28", "tue,thu,sun", "2026-10-28"))
        assertEquals("2026-11-01", Repeat.nextDue("2026-10-29", "tue,thu,sun", "2026-10-29"))
        assertEquals("2026-11-02", Repeat.nextDue("2026-10-28", "monday", "2026-10-28"))
        // A month keeps its day, or the month's last when it has none (Jan 31 → Feb 28).
        assertEquals("2027-02-28", Repeat.nextDue("2027-01-31", "month", "2027-01-31"))
        assertEquals("2026-11-15", Repeat.nextDue("2026-11-01", "month 1,15", "2026-11-01"))
        assertEquals("2026-11-30", Repeat.nextDue("2026-11-15", "month 15,last", "2026-11-15"))
        assertEquals("2026-11-11", Repeat.nextDue("2026-10-28", "2 weeks", "2026-10-28"))
        assertEquals("2026-10-31", Repeat.nextDue("2026-10-28", "3 days", "2026-10-28"))
    }

    /** Checker 2026-10-28 #1: a tick moves a habit past today, so a late one is not late forever (web nextAfter). */
    @Test fun aTickMovesAHabitToItsFirstDateAfterToday() {
        // "Drink water" (daily) missed on Tuesday, ticked on Wednesday: due Thursday, not Wednesday again.
        assertEquals("2026-10-29 08:00", Repeat.nextAfter("2026-10-27 08:00", "day", "2026-10-28"))
        // Missed for days: the next one after today, once.
        assertEquals("2026-10-29", Repeat.nextAfter("2026-10-25", "day", "2026-10-28"))
        assertEquals("2026-11-02", Repeat.nextAfter("2026-10-05", "week", "2026-10-26"))
        // Tuesday missed in "tue,thu,sun", ticked Thursday: Sunday.
        assertEquals("2026-11-01", Repeat.nextAfter("2026-10-27", "tue,thu,sun", "2026-10-29"))
        // On time or early: the same as nextDue.
        assertEquals("2026-10-29 07:30", Repeat.nextAfter("2026-10-28 07:30", "day", "2026-10-28"))
        assertEquals("2026-11-02", Repeat.nextAfter("2026-10-30", "weekday", "2026-10-30"))
        assertEquals("Mondays", Repeat.label("monday"))
    }

    @Test fun aRepeatFallsOnTheDaysTheWebShowsInUpcoming() {
        assertEquals(listOf("2026-10-29", "2026-11-01", "2026-11-03"), Repeat.datesBetween("2026-10-27 18:00", "tue,thu,sun", "2026-10-28", "2026-11-03"))
        assertEquals(listOf("2026-10-28", "2026-10-29", "2026-10-30"), Repeat.datesBetween("2026-10-28", "day", "2026-10-28", "2026-10-30"))
        assertEquals(emptyList<String>(), Repeat.datesBetween("2026-11-10", "week", "2026-10-28", "2026-11-03"))
    }

    @Test fun aStreakCountsOnlyTheDaysItRepeatsOn() {
        val days = setOf("2026-10-27", "2026-10-26", "2026-10-25")
        // Today (28) not done yet does not break it.
        assertEquals(3, streakOf("day", days, "2026-10-28"))
        assertEquals(4, streakOf("day", days + "2026-10-28", "2026-10-28"))
        // A gap breaks it.
        assertEquals(1, streakOf("day", setOf("2026-10-27", "2026-10-25"), "2026-10-28"))
        // "tue,thu,sun": Tue 27 and Sun 25 in a row; Wednesday and Monday never count.
        assertEquals(2, streakOf("tue,thu,sun", setOf("2026-10-27", "2026-10-25"), "2026-10-28"))
        assertNull(streakOf("week", days, "2026-10-28"))
    }

    @Test fun partsOfTheDayAreTheWebs() {
        assertEquals(Part.MORNING, Part.of("2026-10-28 07:30"))
        assertEquals(Part.AFTERNOON, Part.of("2026-10-28 12:00"))
        assertEquals(Part.EVENING, Part.of("2026-10-28 17:00"))
        assertEquals(Part.ANYTIME, Part.of("2026-10-28"))
        assertEquals(Part.ANYTIME, Part.of(null))
    }

    private val tashkent = ZoneId.of("Asia/Tashkent")
    private fun at(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), tashkent)

    @Test fun aReminderRingsAtTheNextTimeStillAhead() {
        // Before its time today: today.
        assertEquals(at("2026-10-28T07:30"), ReminderMath.nextFire("2026-10-28 07:30", "day", at("2026-10-28T06:00")))
        // Its time has passed and it is not done: the next day it repeats on, same time.
        assertEquals(at("2026-10-29T07:30"), ReminderMath.nextFire("2026-10-28 07:30", "day", at("2026-10-28T09:00")))
        // Ticked (moved to tomorrow): tomorrow.
        assertEquals(at("2026-10-29T07:30"), ReminderMath.nextFire("2026-10-29 07:30", "day", at("2026-10-28T09:00")))
        // A set of days skips the others: Wed 21:30 passed → Thu 21:30.
        assertEquals(at("2026-10-29T21:30"), ReminderMath.nextFire("2026-10-28 21:30", "tue,thu,sun", at("2026-10-28T22:00")))
        // Late for days: the next one ahead, never one in the past.
        assertEquals(at("2026-10-28T20:00"), ReminderMath.nextFire("2026-10-20 20:00", "day", at("2026-10-28T19:00")))
        // No time, no reminder.
        assertNull(ReminderMath.nextFire("2026-10-28", "day", at("2026-10-28T06:00")))
        assertNull(ReminderMath.nextFire("2026-10-28 07:30", null, at("2026-10-28T06:00")))
    }
}
