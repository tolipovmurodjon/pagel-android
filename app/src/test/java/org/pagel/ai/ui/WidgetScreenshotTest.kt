package org.pagel.ai.ui

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.glance.state.GlanceStateDefinition
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.widget.CaloriesContent
import org.pagel.ai.widget.HabitsContent
import org.pagel.ai.widget.NamozContent
import org.pagel.ai.widget.NextTaskContent
import org.pagel.ai.widget.QuickAddContent
import org.pagel.ai.widget.TodayContent
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 8 pictures: each widget as the launcher would draw it. Glance turns the content into RemoteViews (compose()),
 * RemoteViews.apply() inflates them into real Views, and the Views sit on a wallpaper-like gradient at the widget's
 * size. Two sizes each (a small one and the full one), plus dark mode for the Today, Habits and Namoz widgets.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** A widget built only from [body], with no saved state, for compose(). */
    private class Probe(private val body: @Composable () -> Unit) : GlanceAppWidget() {
        override val sizeMode: SizeMode = SizeMode.Exact
        override val stateDefinition: GlanceStateDefinition<*>? = null
        override suspend fun provideGlance(context: Context, id: GlanceId) {
            provideContent { body() }
        }
    }

    private val wallpaper = Brush.linearGradient(listOf(Color(0xFF5D7FA8), Color(0xFF9DB7C9), Color(0xFFD9C7A8)))
    private val nightWallpaper = Brush.linearGradient(listOf(Color(0xFF0B1022), Color(0xFF1E2A44)))

    private fun shot(name: String, w: Dp, h: Dp, night: Boolean = false, body: @Composable () -> Unit) {
        val views = runBlocking { Probe(body).compose(ctx, size = DpSize(w, h)) }
        compose.setContent {
            Box(Modifier.background(if (night) nightWallpaper else wallpaper).padding(16.dp), contentAlignment = Alignment.Center) {
                AndroidView(factory = { c -> views.apply(c, FrameLayout(c)) }, modifier = Modifier.size(w, h))
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/widget-$name.png")
    }

    // One picture per test: a compose rule takes one setContent per test.
    @Test fun today4x2() = shot("today-4x2", 300.dp, 200.dp) { TodayContent(WidgetFixtures.today) }
    @Test fun today2x2() = shot("today-2x2", 160.dp, 180.dp) { TodayContent(WidgetFixtures.today) }
    @Test @Config(qualifiers = "+night") fun today4x2Dark() = shot("today-4x2-dark", 300.dp, 200.dp, night = true) { TodayContent(WidgetFixtures.today) }

    @Test fun habits4x2() = shot("habits-4x2", 320.dp, 200.dp) { HabitsContent(WidgetFixtures.habitsMany) }
    @Test fun habits2x1() = shot("habits-2x1", 200.dp, 80.dp) { HabitsContent(WidgetFixtures.habits) }
    @Test fun habits1x1() = shot("habits-1x1", 80.dp, 80.dp) { HabitsContent(WidgetFixtures.habits) }
    @Test @Config(qualifiers = "+night") fun habits4x2Dark() = shot("habits-4x2-dark", 320.dp, 200.dp, night = true) { HabitsContent(WidgetFixtures.habitsMany) }

    @Test fun namoz3x3() = shot("namoz-3x3", 250.dp, 250.dp) { NamozContent(WidgetFixtures.namozNov1) }
    @Test fun namoz2x1() = shot("namoz-2x1", 180.dp, 80.dp) { NamozContent(WidgetFixtures.namozNov1) }
    @Test fun namozBeforeNovember() = shot("namoz-before-november", 250.dp, 250.dp) { NamozContent(WidgetFixtures.namozOct31) }
    @Test @Config(qualifiers = "+night") fun namoz3x3Dark() = shot("namoz-3x3-dark", 250.dp, 250.dp, night = true) { NamozContent(WidgetFixtures.namozNov1) }

    @Test fun calories2x2() = shot("calories-2x2", 180.dp, 180.dp) { CaloriesContent(WidgetFixtures.calories) }
    @Test fun calories4x1() = shot("calories-4x1", 300.dp, 80.dp) { CaloriesContent(WidgetFixtures.calories) }
    @Test fun calories1x1() = shot("calories-1x1", 80.dp, 80.dp) { CaloriesContent(WidgetFixtures.calories) }
    @Test fun caloriesNone() = shot("calories-none", 180.dp, 180.dp) { CaloriesContent(null) }

    @Test fun next4x1() = shot("next-4x1", 300.dp, 90.dp) { NextTaskContent(WidgetFixtures.next) }
    @Test fun next2x2() = shot("next-2x2", 180.dp, 180.dp) { NextTaskContent(WidgetFixtures.next) }

    @Test fun add3x1() = shot("add-3x1", 250.dp, 80.dp) { QuickAddContent() }
    @Test fun add1x1() = shot("add-1x1", 80.dp, 80.dp) { QuickAddContent() }
}
