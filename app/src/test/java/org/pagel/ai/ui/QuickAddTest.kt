package org.pagel.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pagel.ai.ui.add.Quick
import org.pagel.ai.ui.add.parseQuick
import java.time.LocalDateTime

/** The web's lib/quickadd.test.ts, case for case, so "tomorrow 3pm #me p1" means the same on the phone and pagel.uz. */
class QuickAddTest {
    private val now = LocalDateTime.of(2026, 10, 26, 10, 0) // a Monday
    private val p = listOf("me", "humopedia")

    @Test fun readsProjectPriorityDayAndTimeAndCutsThemFromTheText() {
        assertEquals(Quick("Call the bank", "me", 0, "2026-10-27 15:00", null), parseQuick("Call the bank tomorrow 3pm #me p1", p, now))
    }

    @Test fun findsTheNextWeekdayTodayAndNextWeek() {
        assertEquals("2026-10-30", parseQuick("Gym fri", p, now).due)
        assertEquals("2026-10-26", parseQuick("Pay rent today", p, now).due)
        assertEquals("2026-11-02", parseQuick("Plan sprint next week", p, now).due)
        assertEquals("2026-11-02", parseQuick("Standup mon", p, now).due)
    }

    @Test fun leavesUnknownProjectsAndPlainWordsAlone() {
        assertEquals(Quick("Read #mars notes", null, null, null, null), parseQuick("Read #mars notes", p, now))
        assertNull(parseQuick("Today's plan review", p, now).due)
    }

    @Test fun aTimeAloneMeansToday() {
        assertEquals("2026-10-26 18:30", parseQuick("Call Aziz 18:30", p, now).due)
    }

    @Test fun repeatsStartOnTheNearestDay() {
        val q = parseQuick("Weigh in every monday 7:30", p, now)
        assertEquals("monday", q.every)
        assertEquals("2026-10-26 07:30", q.due)
        assertEquals("day", parseQuick("Read daily", p, now).every)
    }
}
