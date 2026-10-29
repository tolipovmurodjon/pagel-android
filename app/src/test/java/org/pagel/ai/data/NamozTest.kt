package org.pagel.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.ui.glass.Emoji
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

/** Round D: the phone's Tashkent prayer times against the published November 2026 table, and the emoji keywords. */
class NamozTest {
    private fun min(t: LocalTime) = t.hour * 60 + t.minute

    @Test fun everyNovemberTimeIsWithinAMinuteOfThePublishedTable() {
        val rows = javaClass.classLoader!!.getResource("namoz-tashkent-2026-11.txt")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(30, rows.size)
        var worst = 0
        for (row in rows) {
            val parts = row.split(" ")
            val ours = Namoz.day(LocalDate.of(2026, 11, parts[0].toInt()))
            parts.drop(1).forEachIndexed { i, s -> worst = maxOf(worst, abs(min(ours[i]) - min(LocalTime.parse(s)))) }
        }
        assertTrue("worst $worst min", worst <= 1)
    }

    @Test fun theFirstOfNovemberWindowsAndTheNotice() {
        val w = Namoz.windows(LocalDate.of(2026, 11, 1))
        assertEquals(listOf("Bomdod", "Peshin", "Asr", "Shom", "Xufton"), w.map { it.prayer.uz })
        // Published: Bomdod 05:35, Quyosh 06:54, Peshin 12:11, Asr 15:37, Shom 17:20, Xufton 18:35 (±1 min).
        assertTrue(abs(min(w[1].from) - min(LocalTime.of(12, 11))) <= 1)
        assertEquals(w[2].from, w[1].to)
        assertEquals("Peshin vaqti kirdi · ${w[1].label().take(5)}", Namoz.notice(w[1]))
    }

    @Test fun aPrayerReminderRingsAtThatDaysTimeNeverBeforeTheFirst() {
        val z = java.time.ZoneId.of("Asia/Tashkent")
        // Asked on 28 October: the first Peshin is 1 November.
        val first = Namoz.nextStart(Namoz.Prayer.PESHIN, java.time.ZonedDateTime.of(2026, 10, 28, 9, 0, 0, 0, z))
        assertEquals(LocalDate.of(2026, 11, 1), first.toLocalDate())
        assertTrue(abs(min(first.toLocalTime()) - min(LocalTime.of(12, 11))) <= 1)
        // After that day's Peshin: the next day's.
        val next = Namoz.nextStart(Namoz.Prayer.PESHIN, java.time.ZonedDateTime.of(2026, 11, 2, 13, 0, 0, 0, z))
        assertEquals(LocalDate.of(2026, 11, 3), next.toLocalDate())
    }

    @Test fun offsetsMoveOnlyTheirPrayerAndShafiiAsrIsEarlier() {
        val d = LocalDate.of(2026, 11, 1)
        val base = Namoz.day(d)
        val moved = Namoz.day(d, Namoz.Setup(offsets = listOf(2, 0, 0, 0, -3)))
        assertEquals(base[0].plusMinutes(2), moved[0])
        assertEquals(base[1], moved[1]) // sunrise never moves
        assertEquals(base[2], moved[2])
        assertEquals(base[5].minusMinutes(3), moved[5])
        val shafii = Namoz.day(d, Namoz.Setup(hanafi = false))
        assertTrue("Shafi'i Asr ${shafii[3]} before Hanafi ${base[3]}", shafii[3] < base[3])
        // An unknown method key falls back to Uzbekistan's, so a bad saved row never changes the times.
        assertEquals(base, Namoz.day(d, Namoz.Setup(method = "nope")))
    }

    @Test fun onlyFromTheFirstOfNovember() {
        assertFalse(Namoz.counts("2026-10-31"))
        assertTrue(Namoz.counts("2026-11-01"))
        assertEquals(Namoz.Prayer.SHOM, Namoz.prayerOf("Shom"))
        assertNull(Namoz.prayerOf("Shower"))
    }

    @Test fun habitsGetTheirEmojiByTheirWords() {
        assertEquals("food", Emoji.keyFor("Log today's food and sugar, then shut down"))
        assertEquals("lotus", Emoji.keyFor("Do the five-minute floor routine"))
        assertEquals("inbox", Emoji.keyFor("Go through five saves in your Inbox"))
        assertEquals("moon", Emoji.keyFor("Start winding down, in bed by 22:00"))
        assertEquals("gym", Emoji.keyFor("Do the full-body F workout at openGym"))
        assertEquals("scale", Emoji.keyFor("Weigh yourself and measure your waist"))
        assertEquals("food", Emoji.keyFor("Ovqatni rasmga olib kaloriyasini o'lchash, botga yuborish"))
        assertEquals("water", Emoji.keyFor("Drink water"))
        assertEquals("seedling", Emoji.keyFor("Something new"))
        // Boss's own pick wins; an unknown pick falls back to the words.
        assertEquals("coffee", Emoji.keyFor("Drink water", chosen = "coffee"))
        assertEquals("water", Emoji.keyFor("Drink water", chosen = "nope"))
        assertTrue(Emoji.all.size in 40..60)
        assertTrue(Emoji.Notice.entries.all { it.key in Emoji.all })
    }
}
