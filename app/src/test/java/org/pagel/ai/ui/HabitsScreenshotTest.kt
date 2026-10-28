package org.pagel.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.habitsDay
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.habits.HabitsScreen
import org.pagel.ai.ui.habits.HabitsUi
import org.pagel.ai.ui.habits.ReminderContent
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 3 (Habits) at Boss's sizes: the day by part with streaks and the bar, the same at 1.5× font, the all-done card,
 * and the reminder sheet (switch + time wheel) at 360 dp and 1.5×.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class HabitsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val today = "2026-10-28"

    private fun habit(id: String, text: String, due: String?, every: String = "day", priority: Int = 2) = TaskEntity(
        id = id, project = "me", phase = "Now", sort = 1.0, text = text, title = null, status = "open", priority = priority, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
    )

    private val habits = listOf(
        habit("h1", "Stretch for ten minutes", "2026-10-28 07:30"),
        habit("h2", "Weigh in and write it down", "2026-10-27 08:00", every = "monday", priority = 1),
        habit("h3", "Read 10 pages of the book", "2026-10-28 21:30"),
        habit("h4", "Wind down: phone away", "2026-10-28 21:45", every = "tue,thu,sun"),
        habit("h5", "Drink water", "2026-10-28"),
        habit("h6", "Gym", "2026-10-29 18:00", every = "tue,thu,sun"),
    )
    private val ticks = listOf("2026-10-27", "2026-10-26", "2026-10-25").map { HabitTickEntity(it, "h1") } +
        listOf(HabitTickEntity("2026-10-28", "h5"), HabitTickEntity("2026-10-27", "h5"), HabitTickEntity("2026-10-27", "h4"))

    private fun ui(h: List<TaskEntity> = habits, t: List<HabitTickEntity> = ticks, reminders: Set<String> = setOf("h3")) = HabitsUi(
        loading = false, today = today, day = habitsDay(h, t, today), reminders = reminders, byId = h.associateBy { it.id },
    )

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    @Test fun theDayByPartAt360() {
        show { HabitsScreen(ui(), PaddingValues(top = 16.dp, bottom = 96.dp)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-360.png")
        compose.onNodeWithText("🌅 Morning").assertIsDisplayed()
        compose.onNodeWithText("1 of 5 done today").assertIsDisplayed()
        compose.onNodeWithContentDescription("1 of 5 habits done today").assertIsDisplayed()
        // Three days in a row, today not done yet: the flame says 3.
        compose.onNodeWithContentDescription("3 in a row").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reminder: Stretch for ten minutes").assertIsDisplayed()
    }

    @Test fun theDayByPartAtLargeFont() {
        show(1.5f) { HabitsScreen(ui(), PaddingValues(top = 16.dp, bottom = 96.dp)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-360-font-1.5.png")
        compose.onNodeWithText("Habits").assertIsDisplayed()
        compose.onNodeWithContentDescription("Done: Stretch for ten minutes").assertIsDisplayed()
    }

    @Test fun allDoneForTheDay() {
        val all = habits.take(1).map { it.copy(due = "2026-10-29 07:30") } + habits[4].copy(due = "2026-10-29")
        val done = ticks + HabitTickEntity(today, "h1")
        show { HabitsScreen(ui(all, done), PaddingValues(top = 16.dp, bottom = 96.dp)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-all-done.png")
        compose.onNodeWithText("All done today").assertIsDisplayed()
        compose.onNodeWithText("2 habits ticked. Longest streak: 4 in a row.").assertIsDisplayed()
        compose.onNodeWithText("Done today ✓").assertIsDisplayed()
    }

    @Test fun tickingAHabitCallsBackWithItsId() {
        var ticked = ""
        show { HabitsScreen(ui(), PaddingValues(top = 16.dp, bottom = 96.dp), onTick = { ticked = it }) }
        compose.onNodeWithContentDescription("Done: Stretch for ten minutes").performClick()
        assertEquals("h1", ticked)
    }

    @Test fun reminderSheetAt360() {
        show { Column(Modifier.padding(top = 16.dp)) { ReminderContent(habits[0], on = true, exact = true, onToggle = {}, onTime = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-reminder.png")
        compose.onNodeWithContentDescription("Remind me").assertIsDisplayed()
        compose.onNodeWithText("At 07:30 on the days it repeats, with a Done button.").assertIsDisplayed()
    }

    @Test fun reminderSheetLargeFontWithoutExactAlarms() {
        show(1.5f) { Column(Modifier.padding(top = 16.dp)) { ReminderContent(habits[2], on = true, exact = false, onToggle = {}, onTime = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habits-reminder-font-1.5.png")
        // A missing grant is one tappable 48 dp row, not a 3-line red paragraph (checker 2026-10-28 #6).
        compose.onNodeWithContentDescription("Exact time · Allow").assertIsDisplayed()
    }
}
