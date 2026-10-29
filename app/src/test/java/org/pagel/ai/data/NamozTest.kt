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
        assertEquals("Peshin · ${w[1].label().take(5)}", Namoz.notice(w[1]))
    }

    @Test fun threeTashkentDatesMatchAFixedTable() {
        // The published Tashkent table (Uzbekistan's method, Hanafi Asr) on 3 dates, pinned in code.
        val table = mapOf(
            LocalDate.of(2026, 11, 1) to listOf("05:35", "06:54", "12:11", "15:37", "17:20", "18:35"),
            LocalDate.of(2026, 11, 15) to listOf("05:49", "07:11", "12:12", "15:23", "17:05", "18:22"),
            LocalDate.of(2026, 11, 30) to listOf("06:04", "07:28", "12:16", "15:14", "16:57", "18:16"),
        )
        for ((d, want) in table) {
            val ours = Namoz.day(d)
            want.forEachIndexed { i, s -> assertTrue("$d #$i ${ours[i]} vs $s", abs(min(ours[i]) - min(LocalTime.parse(s))) <= 1) }
        }
    }

    @Test fun settingsBecomeASetupAndNoRowIsTashkent() {
        assertEquals(Namoz.Setup(), Namoz.setupOf(null))
        val s = org.pagel.ai.data.db.NamozSettingsEntity(method = "mwl", hanafi = false, offsets = "1,2,x", autoLocation = true)
        val setup = Namoz.setupOf(s)
        assertEquals(listOf(1, 2, 0, 0, 0), setup.offsets)
        assertEquals(41.2995, setup.lat, 0.0) // auto, but no place known yet: Tashkent
        assertFalse(setup.hanafi)
        val d = org.pagel.ai.data.db.NamozSettingsEntity()
        assertFalse(d.qazaOn); assertEquals(0, d.qaza); assertTrue(d.hanafi); assertFalse(d.autoLocation)
    }

    @Test fun nextCurrentCountdownAndHijri() {
        val d = LocalDate.of(2026, 11, 1)
        val w = Namoz.windows(d)
        val (next, at) = Namoz.next(d.atTime(w[2].from.minusMinutes(63)))
        assertEquals(Namoz.Prayer.ASR, next.prayer)
        assertEquals("in 1 h 03 m", Namoz.countdown(d.atTime(w[2].from.minusMinutes(63)), at))
        assertEquals("in 5 m", Namoz.countdown(at.minusMinutes(5), at))
        assertEquals(Namoz.Prayer.BOMDOD, Namoz.next(d.atTime(23, 0)).first.prayer)
        assertEquals(Namoz.Prayer.PESHIN, Namoz.current(d.atTime(w[1].from.plusMinutes(1))))
        assertEquals(Namoz.Prayer.XUFTON, Namoz.current(d.atTime(2, 0)))
        assertNull(Namoz.current(d.atTime(9, 0)))
        assertTrue(Namoz.hijri(d), Namoz.hijri(d).endsWith("1448"))
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
