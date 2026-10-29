package org.pagel.ai.data

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTestDefaults
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.widget.CaloriesGlance
import org.pagel.ai.widget.CaloriesWidget
import org.pagel.ai.widget.HabitsWidget
import org.pagel.ai.widget.NamozWidget
import org.pagel.ai.widget.NextTaskWidget
import org.pagel.ai.widget.QuickAddWidget
import org.pagel.ai.widget.RoomWidgetHabits
import org.pagel.ai.widget.TapHabitFromWidget
import org.pagel.ai.widget.TickFromWidget
import org.pagel.ai.widget.TodayWidget
import org.pagel.ai.widget.WidgetCalories
import org.pagel.ai.widget.WidgetHabits
import org.pagel.ai.widget.WidgetParts
import org.pagel.ai.widget.partsOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Phase 8: a widget's tap is the app's own write. Today's tick leaves a DONE row in the outbox; a ring's tap leaves the
 * Habits tab's PATCH {done: true}; a second tap on a full ring adds nothing. And every real widget composes from Room
 * through its own provideGlance without a crash (the instrumented-free stand-in for "add the widget": Glance's compose()).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetActionsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PagelDatabase
    /** 2026-11-01 13:00 in Tashkent: namoz counts from today. */
    private val now = Instant.parse("2026-11-01T08:00:00Z").toEpochMilli()
    private var kicks = 0
    private val saved = partsOf
    private val calories = MutableStateFlow<CaloriesGlance?>(CaloriesGlance("k", 1420, 2000))

    private fun task(id: String, text: String, due: String? = null, every: String? = null) = TaskEntity(
        id = id, project = "pagel", phase = "Now", sort = 1.0, text = text, title = null, status = "open", priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "2026-11-01T05:00:00Z", serverVersion = 1, lastModified = 0,
    )

    @Before fun setUp() {
        db = inMemoryDb()
        val today = TodayRepository(db.dao(), { kicks++ }, clock = { now })
        val habits = HabitsRepository(db.dao(), PagelApi(OkHttpClient(), Dispatchers.IO), { kicks++ }, { }, clock = { now })
        val fake = object : WidgetParts {
            override fun dao(): PagelDao = db.dao()
            override fun today(): TodayRepository = today
            override fun habits(): WidgetHabits = RoomWidgetHabits(habits, clock = { now })
            override fun calories(): WidgetCalories = object : WidgetCalories {
                override fun today(): Flow<CaloriesGlance?> = calories
                override suspend fun refresh() = Unit
            }
        }
        partsOf = { fake }
    }

    @After fun tearDown() {
        partsOf = saved
        db.close()
    }

    @Test fun aTickOnTodaysWidgetIsTheAppsTickInTheOutbox() = runBlocking {
        db.dao().upsertTasks(listOf(task("t_a", "Call the bank")))
        db.dao().appendToToday("t_a")
        TickFromWidget().onAction(ctx, GlanceAppWidgetUnitTestDefaults.glanceId(), actionParametersOf(TickFromWidget.TaskId to "t_a"))
        assertEquals("done", db.dao().taskOnce("t_a")!!.status)
        val op = db.dao().waitingOutbox().single()
        assertEquals("t_a" to OutboxEntity.DONE, op.taskId to op.op)
        assertEquals(1, kicks)
    }

    @Test fun aRingTapIsTheHabitsTabsTickAndAFullRingAddsNothing() = runBlocking {
        db.dao().upsertTasks(listOf(task("t_g", "Gym", due = "2026-11-01 18:00", every = "day")))
        val tap = TapHabitFromWidget()
        tap.onAction(ctx, GlanceAppWidgetUnitTestDefaults.glanceId(), actionParametersOf(TapHabitFromWidget.HabitId to "t_g"))
        val op = db.dao().waitingOutbox().single()
        assertEquals(OutboxEntity.PATCH, op.op)
        assertTrue(op.body!!.startsWith("""{"set":{"done":true,"day":"2026-11-01""""))
        assertEquals("2026-11-02 18:00", db.dao().taskOnce("t_g")!!.due)
        tap.onAction(ctx, GlanceAppWidgetUnitTestDefaults.glanceId(), actionParametersOf(TapHabitFromWidget.HabitId to "t_g"))
        assertEquals(1, db.dao().waitingOutbox().size)
    }

    @Test fun aPrayerTickFromTheNamozWidgetCountsToday() = runBlocking {
        db.dao().upsertTasks(listOf(task("t_p", "Peshin", due = "2026-11-01", every = "day")))
        TapHabitFromWidget().onAction(ctx, GlanceAppWidgetUnitTestDefaults.glanceId(), actionParametersOf(TapHabitFromWidget.HabitId to "t_p"))
        assertEquals(1, db.dao().habitTicksOf("t_p").single { it.day == "2026-11-01" }.n)
        assertEquals(1, db.dao().waitingOutbox().size)
    }

    @Test fun everyWidgetComposesFromRoomAtTwoSizesWithoutACrash() = runBlocking {
        db.dao().upsertTasks(listOf(
            task("t_a", "Call the bank"), task("t_g", "Gym", due = "2026-11-01 18:00", every = "day"),
            task("t_p", "Peshin", due = "2026-11-01", every = "day"),
        ))
        db.dao().appendToToday("t_a")
        val widgets = listOf(TodayWidget(), HabitsWidget(), NamozWidget(), CaloriesWidget(), NextTaskWidget(), QuickAddWidget())
        for (w in widgets) for (size in listOf(DpSize(70.dp, 70.dp), DpSize(300.dp, 200.dp))) {
            assertNotNull("${w.javaClass.simpleName} at $size", w.compose(ctx, size = size))
        }
    }
}
