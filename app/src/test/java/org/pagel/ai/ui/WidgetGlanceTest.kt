package org.pagel.ai.ui

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.MainActivity
import org.pagel.ai.widget.CaloriesContent
import org.pagel.ai.widget.CaloriesGlance
import org.pagel.ai.widget.HabitsContent
import org.pagel.ai.widget.NamozContent
import org.pagel.ai.widget.NextTaskContent
import org.pagel.ai.widget.QuickAddContent
import org.pagel.ai.widget.TapHabitFromWidget
import org.pagel.ai.widget.TickFromWidget
import org.pagel.ai.widget.TodayContent
import org.pagel.ai.widget.WidgetOpen
import org.robolectric.annotation.Config

/**
 * Phase 8 Glance layout tests (androidx.glance:glance-appwidget-testing, the way the Glance skill tests a layout): each
 * widget's words, and that each tap does what it says (a ring ticks, + opens Add, 🎤 opens voice, a task opens itself).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetGlanceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val big = DpSize(300.dp, 220.dp)

    @Test fun todaySaysTheDayAndTicksARow() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(big)
        provideComposable { TodayContent(WidgetFixtures.today) }
        onNode(hasText("12 done · 1 left")).assertExists()
        onNode(hasText("Call the bank")).assertExists()
        onNode(hasRunCallbackClickAction<TickFromWidget>(actionParametersOf(TickFromWidget.TaskId to "t_a"))).assertExists()
        onNode(hasStartActivityClickAction<MainActivity>(actionParametersOf(WidgetOpen.Key to "task:t_a"))).assertExists()
    }

    @Test fun habitsShowTheCountAndARingTicks() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(big)
        provideComposable { HabitsContent(WidgetFixtures.habits) }
        onNode(hasText("1/3")).assertExists()
        onNode(hasRunCallbackClickAction<TapHabitFromWidget>(actionParametersOf(TapHabitFromWidget.HabitId to "w"))).assertExists()
        // A full ring does not tick again: it opens Habits.
        onAllNodes(hasRunCallbackClickAction<TapHabitFromWidget>(actionParametersOf(TapHabitFromWidget.HabitId to "g"))).assertCountEquals(0)
    }

    @Test fun habitsAtOneByOneShowOnlyTheDay() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(DpSize(70.dp, 70.dp))
        provideComposable { HabitsContent(WidgetFixtures.habits) }
        onNode(hasText("1/3")).assertExists()
        onNode(hasStartActivityClickAction<MainActivity>(actionParametersOf(WidgetOpen.Key to "habits"))).assertExists()
    }

    @Test fun namozMarksTheNextAndTicksFromNovember() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(DpSize(250.dp, 220.dp))
        provideComposable { NamozContent(WidgetFixtures.namozNov1) }
        onNode(hasText("Peshin")).assertExists()
        onNode(hasText("1/5")).assertExists()
        onNode(hasRunCallbackClickAction<TapHabitFromWidget>(actionParametersOf(TapHabitFromWidget.HabitId to "n_PESHIN"))).assertExists()
    }

    @Test fun namozBeforeNovemberOnlyShowsTimes() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(DpSize(250.dp, 220.dp))
        provideComposable { NamozContent(WidgetFixtures.namozOct31) }
        onNode(hasText("From 1 Nov")).assertExists()
        onAllNodes(hasRunCallbackClickAction<TapHabitFromWidget>(actionParametersOf(TapHabitFromWidget.HabitId to "n_PESHIN"))).assertCountEquals(0)
    }

    @Test fun caloriesReadOneLineAndTheCameraLogsFood() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(DpSize(180.dp, 180.dp))
        provideComposable { CaloriesContent(CaloriesGlance("k", 1420, 2000)) }
        onNode(hasText("1,420 / 2,000 kcal")).assertExists()
        onNode(hasText("580 left")).assertExists()
        onNode(hasStartActivityClickAction<MainActivity>(actionParametersOf(WidgetOpen.Key to "food"))).assertExists()
    }

    @Test fun caloriesWithoutAKcalHabitInviteOne() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(DpSize(180.dp, 180.dp))
        provideComposable { CaloriesContent(null) }
        onNode(hasText("No kcal habit yet")).assertExists()
    }

    @Test fun nextTaskOpensItselfAndShowsItsStep() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(DpSize(300.dp, 110.dp))
        provideComposable { NextTaskContent(WidgetFixtures.next) }
        onNode(hasText("Next: Call before 17:00")).assertExists()
        onNode(hasStartActivityClickAction<MainActivity>(actionParametersOf(WidgetOpen.Key to "task:t_a"))).assertExists()
    }

    @Test fun quickAddOpensAddAndVoice() = runGlanceAppWidgetUnitTest {
        setContext(ctx)
        setAppWidgetSize(DpSize(250.dp, 70.dp))
        provideComposable { QuickAddContent() }
        onNode(hasStartActivityClickAction<MainActivity>(actionParametersOf(WidgetOpen.Key to "add"))).assertExists()
        onNode(hasStartActivityClickAction<MainActivity>(actionParametersOf(WidgetOpen.Key to "voice"))).assertExists()
    }
}
