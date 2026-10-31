package org.pagel.ai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.HabitFreezeEntity
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.BadgeKind
import org.pagel.ai.data.habits.Clear
import org.pagel.ai.data.habits.Mark
import org.pagel.ai.data.habits.StripContext
import org.pagel.ai.data.habits.habitBadges
import org.pagel.ai.data.habits.habitBoard
import org.pagel.ai.data.habits.heatOf
import org.pagel.ai.data.habits.Part
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.HabitIcons
import org.pagel.ai.ui.habits.HabitTimer
import org.pagel.ai.ui.habits.TimerLook
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * Habits 3.0 (Boss 2026-10-31): a day off is drawn as done, the card's warmth, the badges from a fixed log, the picture
 * set (every old emoji key has a line icon) and no emoji code point left in ui/habits.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HabitsV3LogicTest {
    private fun habit(id: String, due: String = "2026-10-01 07:00", every: String = "day", group: String? = null) = TaskEntity(
        id = id, project = "me", phase = "Now", sort = 1.0, text = id, title = null, status = "open", priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
        habitGroup = group,
    )

    private fun days(id: String, from: String, to: String): List<HabitTickEntity> =
        generateSequence(LocalDate.parse(from)) { it.plusDays(1) }.takeWhile { !it.isAfter(LocalDate.parse(to)) }.map { HabitTickEntity(it.toString(), id) }.toList()

    @Test fun aDayOffIsKeptAsFullyDoneAndDrawnLikeADoneDay() {
        val a = habit("a"); val b = habit("b")
        val ticks = days("a", "2026-10-26", "2026-10-27") + days("b", "2026-10-26", "2026-10-26")
        val freezes = listOf(HabitFreezeEntity("2026-10-27", "b"))
        val today = LocalDate.parse("2026-10-28")
        val week = StripContext(listOf(a, b), ticks, freezes, today).week(LocalDate.parse("2026-10-26"))
        // Tuesday 27: a done, b taken off: the whole day holds, exactly like Monday (no other mark).
        assertEquals(Clear.ALL, week.days[0].clear)
        assertEquals(Clear.ALL, week.days[1].clear)
        val card = habitBoard(listOf(a, b), ticks, emptyList(), freezes, today, LocalDate.parse("2026-10-27")).cards.first { it.task.id == "b" }
        assertEquals(Mark.FROZEN, card.mark)
        assertTrue("a day off is finished", card.finished)
        assertFalse("but nothing was ticked", card.done)
    }

    @Test fun aMissedDayBreaksTheChainAndLoggingItRejoinsIt() {
        val a = habit("a")
        val today = LocalDate.parse("2026-10-28")
        val before = days("a", "2026-10-24", "2026-10-25") + days("a", "2026-10-27", "2026-10-27")
        fun marks(t: List<HabitTickEntity>) = StripContext(listOf(a), t, emptyList(), today).week(LocalDate.parse("2026-10-26")).days.map { it.clear }
        assertEquals(Clear.NONE, marks(before)[0])
        // Logging Monday 26 for its own date (the habit log gets that day) makes it whole again.
        val after = before + HabitTickEntity("2026-10-26", "a")
        assertEquals(Clear.ALL, marks(after)[0])
        assertTrue(StripContext(listOf(a), after, emptyList(), today).week(LocalDate.parse("2026-10-26")).chainIn)
    }

    @Test fun theCardWarmsWithTheLastThirtyDays() {
        val a = habit("a", due = "2026-09-01 07:00")
        val today = LocalDate.parse("2026-10-28")
        fun heat(from: String, to: String) = days("a", from, to).map { it.day }.toSet().let { heatOf(a, it, emptySet(), today) }
        assertEquals(1f, heat("2026-09-29", "2026-10-28"), 0.001f)
        assertEquals(0.5f, heat("2026-10-14", "2026-10-28"), 0.001f)
        assertEquals(0f, heatOf(a, emptySet(), emptySet(), today), 0.001f)
        // A day off counts as held, a missed day is only lighter.
        assertEquals(1f, heatOf(a, days("a", "2026-09-29", "2026-10-27").map { it.day }.toSet(), setOf("2026-10-28"), today), 0.001f)
    }

    @Test fun badgesComeFromTheLogAndEachCarriesItsDay() {
        val a = habit("a")
        val prayer = habit("p", group = "namoz")
        val ticks = days("a", "2026-10-01", "2026-10-10") + days("p", "2026-10-01", "2026-10-30")
        val today = LocalDate.parse("2026-10-12")
        val got = habitBadges(listOf(a, prayer), ticks, emptyList(), today).associateBy { it.kind to it.n }
        fun on(k: BadgeKind, n: Int) = got.getValue(k to n).earnedOn?.toString()
        assertEquals("2026-10-01", on(BadgeKind.FINISHED, 1))
        assertEquals("2026-10-10", on(BadgeKind.FINISHED, 10))
        assertNull(on(BadgeKind.FINISHED, 20))
        assertEquals("2026-10-03", on(BadgeKind.STREAK, 3))
        assertEquals("2026-10-05", on(BadgeKind.STREAK, 5))
        assertEquals("2026-10-10", on(BadgeKind.STREAK, 10))
        assertNull(on(BadgeKind.STREAK, 15))
        assertEquals("2026-10-03", on(BadgeKind.PERFECT_DAYS, 3))
        assertEquals("2026-10-10", on(BadgeKind.PERFECT_DAYS, 10))
        assertNull(on(BadgeKind.PERFECT_DAYS, 20))
        assertEquals(18, got.size)
    }

    @Test fun aDayOffCountsAsAPerfectDay() {
        val a = habit("a")
        val ticks = days("a", "2026-10-01", "2026-10-09")
        val today = LocalDate.parse("2026-10-12")
        val without = habitBadges(listOf(a), ticks, emptyList(), today).first { it.kind == BadgeKind.PERFECT_DAYS && it.n == 10 }
        assertFalse(without.earned)
        val with = habitBadges(listOf(a), ticks, listOf(HabitFreezeEntity("2026-10-10", "a")), today).first { it.kind == BadgeKind.PERFECT_DAYS && it.n == 10 }
        assertEquals("2026-10-10", with.earnedOn.toString())
    }

    @Test fun everyOldEmojiHasALineIcon() {
        for (key in Emoji.all.keys) {
            val icon = HabitIcons.forEmoji(key)
            assertTrue("$key maps to $icon", icon in HabitIcons.names)
            assertNotNull(HabitIcons.vector(icon))
        }
        assertEquals("sprout", HabitIcons.forEmoji("nope"))
        assertEquals("sprout", HabitIcons.forEmoji(null))
        // A pick may carry its colour: "coffee/amber".
        assertEquals("coffee", HabitIcons.forEmoji("coffee/amber"))
        assertEquals("dumbbell", HabitIcons.keyFor("Drink water", "dumbbell/teal"))
        assertEquals("glass-water", HabitIcons.keyFor("Drink water"))
        // The prayers wear the sun's line icons (design audit 2026-10-31).
        assertEquals(listOf("sunrise", "sun", "sun-dim", "sunset", "moon"), org.pagel.ai.data.habits.Namoz.Prayer.entries.map { HabitIcons.forEmoji(it.emoji) })
    }

    @Test fun everyIconParsesAndIsRoundStroked() {
        assertTrue("about 60 icons or more, got ${HabitIcons.names.size}", HabitIcons.names.size >= 60)
        for (n in HabitIcons.names) assertTrue(n, HabitIcons.vector(n).root.size > 0)
    }

    /** Boss 2026-10-31 (brain rules/no-emoji-in-app-ui): the habits screens draw no emoji, only line icons. */
    @Test fun noEmojiCodePointIsLeftInTheHabitsScreens() {
        val dir = listOf("src/main/java/org/pagel/ai/ui/habits", "app/src/main/java/org/pagel/ai/ui/habits").map(::File).first { it.isDirectory }
        fun emoji(cp: Int) = cp >= 0x1F000 || cp in 0x2600..0x26FF || (cp in 0x2700..0x27BF && cp != 0x2713) || cp == 0xFE0F || cp in 0x2B00..0x2BFF
        val found = dir.listFiles { f -> f.extension == "kt" }!!.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (line.codePoints().anyMatch(::emoji)) "${f.name}:${i + 1}" else null }
        }
        assertEquals("emoji left in: $found", emptyList<String>(), found)
    }

    @Test fun theTimerLookRoundTrips() {
        val look = TimerLook(colour = 3, bell = false, sound = 2)
        assertEquals(look, TimerLook.decode(look.encode()))
        assertNull(TimerLook.decode("garbage"))
        assertEquals(TimerLook(-1, true, 0), TimerLook.decode(TimerLook().encode()))
    }

    @Test fun theTimerLookRemembersTheAmbientSoundAndItsVolume() {
        val look = TimerLook(colour = 4, bell = true, sound = 1, ambient = 5, volume = 0.25f)
        assertEquals(look, TimerLook.decode(look.encode()))
        // A save from before the ambient sounds (three fields) still opens, with no sound.
        assertEquals(TimerLook(2, false, 1, -1, 0.6f), TimerLook.decode("2|false|1"))
        // A volume outside 0..1 is pulled back into range.
        assertEquals(1f, TimerLook.decode("0|true|0|1|7.5")!!.volume, 0f)
        assertNull(TimerLook.decode("0|true"))
    }

    @Test fun prayedLaterIsLoggedApartAndRoundTrips() {
        val keys = setOf(org.pagel.ai.data.habits.NamozQaza.key("2026-11-02", "t_asr"), org.pagel.ai.data.habits.NamozQaza.key("2026-11-02", "t_shom"))
        assertEquals(keys, org.pagel.ai.data.habits.NamozQaza.decode(org.pagel.ai.data.habits.NamozQaza.encode(keys)))
        assertEquals(emptySet<String>(), org.pagel.ai.data.habits.NamozQaza.decode(""))
    }

    @Test fun theNamozCueWordsAreCalmPlainAndHaveNoEmojiOrExclamation() {
        for (p in org.pagel.ai.data.habits.Namoz.Prayer.entries) {
            for (text in listOf(org.pagel.ai.data.habits.NamozCue.start(p), org.pagel.ai.data.habits.NamozCue.ready(p))) {
                assertTrue(text, '!' !in text)
                assertTrue(text, text.codePoints().noneMatch { it > 0x2000 })
            }
        }
        assertEquals("Asr time. Wudu, then pray.", org.pagel.ai.data.habits.NamozCue.start(org.pagel.ai.data.habits.Namoz.Prayer.ASR))
    }

    @Test fun theFivePrayersSitInTheirTimeOfDayAndNeverInTheChain() {
        val day = LocalDate.parse("2026-11-02")
        val windows = org.pagel.ai.data.habits.Namoz.windows(day)
        val parts = windows.associate { it.prayer to org.pagel.ai.data.habits.partAt(it.from) }
        assertEquals(Part.MORNING, parts[org.pagel.ai.data.habits.Namoz.Prayer.BOMDOD])
        assertEquals(Part.AFTERNOON, parts[org.pagel.ai.data.habits.Namoz.Prayer.PESHIN])
        assertEquals(Part.AFTERNOON, parts[org.pagel.ai.data.habits.Namoz.Prayer.ASR])
        assertEquals(Part.EVENING, parts[org.pagel.ai.data.habits.Namoz.Prayer.SHOM])
        assertEquals(Part.EVENING, parts[org.pagel.ai.data.habits.Namoz.Prayer.XUFTON])
    }

    @Test fun theTimerStateMachineStartPauseResumeFinish() {
        val idle = HabitTimer(day = "2026-10-31", minutes = 10)
        assertEquals(false, idle.running)
        assertEquals(0L, idle.spent(5_000))
        // Start at t=1000: it runs and counts from there.
        val running = idle.copy(startedAt = 1_000)
        assertEquals(true, running.running)
        assertEquals(4_000L, running.spent(5_000))
        // Pause at t=5000: the time so far is kept and the clock stands still.
        val paused = running.copy(startedAt = null, spentMs = running.spent(5_000))
        assertEquals(false, paused.running)
        assertEquals(4_000L, paused.spent(60_000))
        // Resume at t=60000: the paused time carries on, nothing is lost or counted twice.
        val resumed = paused.copy(startedAt = 60_000)
        assertEquals(4_000L + 3_000L, resumed.spent(63_000))
        // Finish early (4 of 10 minutes) still counts: whatever was spent stays on the clock until it is cleared.
        val early = HabitTimer(startedAt = null, spentMs = 240_000, day = "2026-10-31", minutes = 10)
        assertEquals(240_000L, early.spent(0))
        assertEquals(true, early.spent(0) < early.minutes * 60_000L)
    }
}
