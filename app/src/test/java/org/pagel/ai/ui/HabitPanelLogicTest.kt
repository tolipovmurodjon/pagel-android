package org.pagel.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.habits.HabitIcons
import org.pagel.ai.ui.habits.plainMoment

/**
 * The habit rows of the task panel (Boss 2026-10-31: one editor, no emoji): the Repeat row's words, the moments shown
 * without their glyph, every old emoji key still resolving to an icon, and the old ⋮ sheet gone for good.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HabitPanelLogicTest {
    @Test fun theRepeatRowReadsInWords() {
        assertEquals("Every day", Repeat.summary("day"))
        assertEquals("Every day", Repeat.summary("sun,mon,tue,wed,thu,fri,sat"))
        assertEquals("Weekdays", Repeat.summary("weekday"))
        assertEquals("Weekdays", Repeat.summary("mon,tue,wed,thu,fri"))
        assertEquals("Mon · Wed · Fri", Repeat.summary("mon,wed,fri"))
        assertEquals("Tue · Thu · Sun", Repeat.summary("tue,thu,sun"))
        assertEquals("Every day except Sun", Repeat.summary("mon,tue,wed,thu,fri,sat"))
        assertEquals("Never", Repeat.summary(null))
        assertEquals("Weekly", Repeat.summary("week"))
        // "month 1,15" starts with "mon": it must not read as Monday.
        assertEquals("Monthly · 1, 15", Repeat.summary("month 1,15"))
    }

    @Test fun aMomentShowsItsWordsNotItsGlyph() {
        assertEquals("Coffee", plainMoment("☕ Coffee"))
        assertEquals("Fajr", plainMoment("🕌 Fajr"))
        assertEquals("Brushing teeth", plainMoment("🪥 Brushing teeth"))
        assertEquals("Getting into bed", plainMoment("🛏 Getting into bed"))
        assertEquals("Lunch", plainMoment("Lunch"))
        assertEquals("After the call", plainMoment("After the call"))
    }

    @Test fun everyOldEmojiKeyStillHasAnIcon() {
        for (key in HabitIcons.legacyKeys()) {
            val icon = HabitIcons.keyFor("anything", "$key/amber")
            assertNotNull("emoji key $key", HabitIcons.all[icon])
        }
        // Every bundled emoji key is covered, so no saved look turns blank.
        assertEquals(Emoji.all.keys, HabitIcons.legacyKeys())
    }

    @Test fun anIconPickedInThePanelKeepsItsEmojiTwinForTheListsThatStillDrawEmoji() {
        for (key in HabitIcons.all.keys) {
            val twin = HabitIcons.emojiOf(key)
            assertTrue("icon $key has an emoji twin", twin != null && twin in Emoji.all)
            assertEquals(twin, Emoji.keyFor("anything", "$key/mint"))
        }
    }

    @Test fun aNewHabitTakesItsIconFromItsWords() {
        assertEquals("water", HabitIcons.keyFor("Drink water", null))
        assertEquals("leaf", HabitIcons.keyFor("Zzzz qqq", null))
        assertEquals("flame", HabitIcons.keyFor("Drink water", "flame/teal"))
    }

    @Test fun noCodePathOpensTheOldSettingsSheet() {
        val page = Class.forName("org.pagel.ai.ui.habits.HabitPageKt")
        assertFalse(page.declaredMethods.any { it.name.contains("HabitSettings") })
        assertTrue(runCatching { Class.forName("org.pagel.ai.ui.habits.SheetAt") }.isFailure)
    }
}
