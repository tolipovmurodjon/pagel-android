package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.reminders.NotifyCopy
import java.time.LocalTime

/** Round C: a funny line never comes back within 7 days, and quiet hours are 23:00-07:00. */
class NotifyCopyTest {
    private val hour = 3_600_000L

    @Test fun aLineNeverRepeatsWithinSevenDays() {
        val size = 14
        val shown = ArrayList<Pair<Int, Long>>()
        var now = 1_800_000_000_000L
        // Two lines a day for a week = 14 picks from 14 lines: all different.
        repeat(14) { k ->
            val i = NotifyCopy.pick(size, shown, now, seed = k * 7 + 3)
            assertFalse("line $i came back within a week", shown.any { it.first == i && it.second > now - NotifyCopy.WEEK_MS })
            shown += i to now
            now += 12 * hour
        }
        assertEquals(14, shown.map { it.first }.toSet().size)
    }

    @Test fun whenAllWereShownTheOldestComesBack() {
        val now = 1_800_000_000_000L
        val h = (0 until 3).map { it to now - (3 - it) * hour }
        assertEquals(0, NotifyCopy.pick(3, h, now))
        // A line shown 8 days ago is free again.
        assertEquals(1, NotifyCopy.pick(2, listOf(0 to now - hour, 1 to now - 8 * 24 * hour), now))
    }

    @Test fun quietHours() {
        assertTrue(NotifyCopy.quiet(LocalTime.of(23, 0)))
        assertTrue(NotifyCopy.quiet(LocalTime.of(6, 59)))
        assertFalse(NotifyCopy.quiet(LocalTime.of(7, 0)))
        assertFalse(NotifyCopy.quiet(LocalTime.of(22, 59)))
    }

    /** Round C checker #7: a week of mornings (10 lines) and 21 task fires (32 lines) never repeat a line. */
    @Test fun aWeekOfMorningsAndTwentyOneTaskFiresNeverRepeat() {
        for ((size, fires) in listOf(10 to 7, 32 to 21)) {
            val shown = ArrayList<Pair<Int, Long>>()
            var now = 1_800_000_000_000L
            repeat(fires) { k ->
                val i = NotifyCopy.pick(size, shown, now, seed = k * 13 + 5)
                assertFalse("$size lines: line $i came back within a week", shown.any { it.first == i })
                shown += i to now
                now += 7 * 24 * hour / fires - 1
            }
        }
    }

    @Test fun aLineMatchesTheTasksOwnWordsOrNone() {
        assertEquals("money moves only 💸", NotifyCopy.keywordLine("Pay the internet bill"))
        assertEquals("the gym won't lift itself 💪", NotifyCopy.keywordLine("Gym: legs"))
        assertEquals(null, NotifyCopy.keywordLine("Onamga qo'ng'iroq"))
    }
}
