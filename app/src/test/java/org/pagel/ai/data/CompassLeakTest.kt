package org.pagel.ai.data

import android.view.inputmethod.EditorInfo
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.pagel.ai.crash.CrashLog
import org.pagel.ai.reminders.CompassAlarmReceiver
import org.pagel.ai.reminders.CompassReminderMath
import org.pagel.ai.data.compass.CompassRules
import org.pagel.ai.ui.compass.keyboardFor
import org.pagel.ai.ui.compass.noLearning
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** The compass checker's side paths (2026-10-29): crash reports, the keyboard, the reminders' words and dates. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CompassLeakTest {
    private fun compassCrash(message: String) = IllegalStateException(message, RuntimeException("cause: zebra goal words")).apply {
        stackTrace = arrayOf(StackTraceElement("org.pagel.ai.data.compass.CompassRepository", "saveGoal", "CompassRepository.kt", 10))
    }

    @Test fun aCrashThroughTheCompassKeepsItsFramesButNoMessage() {
        val r = CrashLog.report(Thread.currentThread(), compassCrash("zebra goal words in a message"), "0.3.30", 330, "test", 35, 1L)
        assertEquals(CrashLog.HIDDEN, r.message)
        assertFalse(r.stack.contains("zebra"))
        assertTrue(r.stack.contains("org.pagel.ai.data.compass.CompassRepository.saveGoal"))
        assertTrue(r.stack.contains("Caused by: java.lang.RuntimeException"))
    }

    @Test fun anyOtherCrashKeepsItsMessage() {
        val e = IllegalStateException("the sync broke").apply { stackTrace = arrayOf(StackTraceElement("org.pagel.ai.data.sync.SyncEngine", "run", "SyncEngine.kt", 5)) }
        val r = CrashLog.report(Thread.currentThread(), e, "0.3.30", 330, "test", 35, 1L)
        assertEquals("the sync broke", r.message)
        assertTrue(r.stack.contains("the sync broke"))
    }

    /** Checker #4: every Compass field asks the keyboard not to learn (IME_FLAG_NO_PERSONALIZED_LEARNING). */
    @Test fun theKeyboardIsToldNotToLearn() {
        val info = EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_NEXT }
        noLearning(info)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertEquals(EditorInfo.IME_ACTION_NEXT, info.imeOptions and EditorInfo.IME_MASK_ACTION)
    }

    /** Checker #3: the passphrase gets the password keyboard, no autocorrect, no auto-caps; goal fields keep sentences. */
    @Test fun thePassphraseKeyboardChangesNothingItTypes() {
        val secret = keyboardFor(secret = true, lines = 1)
        assertEquals(KeyboardType.Password, secret.keyboardType)
        assertEquals(false, secret.autoCorrectEnabled)
        assertEquals(KeyboardCapitalization.None, secret.capitalization)
        val goal = keyboardFor(secret = false, lines = 2)
        assertNotEquals(KeyboardType.Password, goal.keyboardType)
        assertEquals(KeyboardCapitalization.Sentences, goal.capitalization)
    }

    private fun at(day: String, h: Int, m: Int = 0) = ZonedDateTime.of(LocalDate.parse(day), LocalTime.of(h, m), CompassRules.TASHKENT)

    @Test fun theBirthdayNoteRingsOnTheFirstOfNovemberAtEight() {
        assertEquals(at("2026-11-01", 8), CompassReminderMath.nextBirthday(at("2026-10-29", 1)))
        assertEquals(at("2026-11-01", 8), CompassReminderMath.nextBirthday(at("2026-11-01", 7, 59)))
        assertEquals(at("2027-11-01", 8), CompassReminderMath.nextBirthday(at("2026-11-01", 8)))
    }

    @Test fun theSeasonReviewRingsOnTheFirstDayOfWeekThirteenOnlyForASeasonWithGoals() {
        val review = CompassReminderMath.nextReview(at("2026-11-03", 10))
        assertEquals(at("2027-01-24", 9), review)
        assertTrue(CompassReminderMath.reviewWanted(setOf("2026-11-01"), review))
        assertFalse(CompassReminderMath.reviewWanted(emptySet(), review))
        // After a review morning the next one is the next season's.
        assertEquals(at("2027-05-02", 9).minusWeeks(1), CompassReminderMath.nextReview(at("2027-01-24", 9, 1)))
    }

    @Test fun theRemindersSayNoGoalsWords() {
        val (title, line) = CompassAlarmReceiver.text(CompassAlarmReceiver.BIRTHDAY, LocalDate.parse("2026-11-01"))
        assertEquals("Year 19 starts today", title)
        assertTrue(line.isNotBlank())
        val (rt, rl) = CompassAlarmReceiver.text(CompassAlarmReceiver.REVIEW, LocalDate.parse("2027-01-24"))
        assertEquals("Season review week", rt)
        assertTrue(rl.isNotBlank())
    }
}
