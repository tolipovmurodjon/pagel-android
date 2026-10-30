package org.pagel.ai.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.NamozLearnEntity
import org.pagel.ai.data.db.NamozMarkEntity
import org.pagel.ai.data.db.NamozSettingsEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.namoz.NamozScreen
import org.pagel.ai.ui.namoz.NamozUi
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

/**
 * The Namoz page at Boss's sizes: before 1 November (times + "Tracking starts 1 November", no stats), a normal day,
 * a full month, 1.5× font and 360 dp. A tall window so the whole page (stats, month grid, learning) is in one shot.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1700dp-xxhdpi")
class NamozScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun prayer(p: Namoz.Prayer) = TaskEntity(
        id = "n_${p.name}", project = "me", phase = "Now", sort = 1.0, text = p.uz, title = null, status = "open", priority = 2, star = false,
        due = "2026-11-01 05:35", every = "day", doneWhen = null, doneAt = null, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
        times = 1, habitGroup = Namoz.GROUP,
    )

    private val prayers = Namoz.Prayer.entries.map(::prayer)

    /** Days 1..[upTo] of November: all five ticked, except Bomdod on every 4th day (plain fake data). */
    private fun ticks(upTo: Int, today: Set<Namoz.Prayer> = emptySet(), todayDay: Int = upTo) = buildList {
        for (d in 1 until todayDay.coerceAtMost(upTo + 1)) for (p in Namoz.Prayer.entries) {
            if (p == Namoz.Prayer.BOMDOD && d % 4 == 0) continue
            add(HabitTickEntity("2026-11-%02d".format(d), "n_${p.name}"))
        }
        for (p in today) add(HabitTickEntity("2026-11-%02d".format(todayDay), "n_${p.name}"))
    }

    private val learning = listOf(
        NamozLearnEntity(1, "Al-Fatiha", learned = true, createdAt = 1),
        NamozLearnEntity(2, "Al-Ikhlas", createdAt = 2),
    )

    private fun ui(now: String, ticks: List<HabitTickEntity> = emptyList(), marks: List<NamozMarkEntity> = emptyList()) =
        NamozUi.of(LocalDateTime.parse(now), prayers, ticks, marks, learning, NamozSettingsEntity(), emptySet())

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { body() } }
        }
    }

    private val pad = PaddingValues(top = 16.dp, bottom = 96.dp)

    private val mosque = listOf(
        NamozMarkEntity("2026-11-06", Namoz.Prayer.PESHIN.name, NamozMarkEntity.MOSQUE),
        NamozMarkEntity("2026-11-10", Namoz.Prayer.SHOM.name, NamozMarkEntity.MOSQUE),
        NamozMarkEntity("2026-11-12", Namoz.Prayer.PESHIN.name, NamozMarkEntity.MOSQUE),
        NamozMarkEntity("2026-11-12", Namoz.Prayer.BOMDOD.name, NamozMarkEntity.ON_TIME),
    )

    /** 12 November, 16:10: Asr's time, Bomdod and Peshin ticked today. */
    private val day = "2026-11-12T16:10"
    private fun dayUi() = ui(day, ticks(12, setOf(Namoz.Prayer.BOMDOD, Namoz.Prayer.PESHIN)), mosque)

    @Test fun beforeTheFirstOfNovemberOnlyTimes() {
        show { NamozScreen(ui("2026-10-30T14:00"), pad) }
        compose.onNodeWithText("Tracking starts 1 November").assertExists()
        compose.onAllNodesWithText("full days this week").assertCountEquals(0)
        compose.onAllNodesWithText("Asr")[0].assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/namoz-before.png")
    }

    @Test fun aNormalDay() {
        show { NamozScreen(dayUi(), pad) }
        compose.onNodeWithText("full days this week").assertExists()
        compose.onNodeWithText("3 in the mosque this month").assertExists()
        compose.onAllNodesWithText("Tracking starts 1 November").assertCountEquals(0)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/namoz-day.png")
    }

    @Test fun aFullMonth() {
        val all = ticks(30, Namoz.Prayer.entries.toSet())
        show { NamozScreen(ui("2026-11-30T21:00", all, mosque), pad) }
        compose.onNodeWithContentDescription("30: 5 of 5").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/namoz-month.png")
    }

    @Test fun aNormalDayAtOneAndAHalfFont() {
        show(fontScale = 1.5f) { NamozScreen(dayUi(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/namoz-day-font-1.5.png")
    }

    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    @Test fun theFirstScreenAt360dp() {
        show { NamozScreen(dayUi(), pad) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/namoz-360x800.png")
    }

    @Test fun tapTicksAndLongPressAsksHow() {
        val taps = mutableListOf<Namoz.Prayer>()
        val marks = mutableListOf<Pair<Namoz.Prayer, String?>>()
        show { NamozScreen(dayUi(), pad, onTap = { taps += it }, onMark = { p, h -> marks += p to h }) }
        compose.onAllNodesWithText("Asr")[0].performClick()
        assertEquals(listOf(Namoz.Prayer.ASR), taps)
        compose.onAllNodesWithText("Shom")[0].performTouchInput { longClick() }
        // Peshin's row already reads "In the mosque" (a Checkbox): pick the sheet's choice, the Button.
        compose.onNode(androidx.compose.ui.test.hasText("In the mosque") and androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button)).performClick()
        assertEquals(listOf(Namoz.Prayer.SHOM to NamozMarkEntity.MOSQUE), marks)
    }

    /** The ⋮ sheet open: every reminder off by default, Show on Today, Qaza off. */
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    @Test fun theSettingsSheetOpen() {
        show { org.pagel.ai.ui.namoz.NamozSettingsSheet(dayUi(), onSave = {}, onRemind = { _, _ -> }, onPlace = {}) {} }
        compose.mainClock.advanceTimeBy(1_000)
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/outputs/roborazzi/namoz-settings.png")
    }

    /** The whole sheet on a tall screen: minutes before (at the time by default), the methods as a checked list. */
    @Test fun theWholeSettingsSheet() {
        show { org.pagel.ai.ui.namoz.NamozSettingsSheet(dayUi(), onSave = {}, onRemind = { _, _ -> }, onPlace = {}) {} }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("At the time").assertIsSelected()
        compose.onNodeWithText("15 minutes before").assertIsNotSelected()
        compose.onNodeWithText("Muslim World League").assertExists()
        compose.onNodeWithText("Hanafi").assertIsSelected()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/outputs/roborazzi/namoz-settings-full.png")
    }

    /** The calm-words wall: none of these words is ever on the page. */
    @Test fun noShamingWords() {
        show { NamozScreen(dayUi(), pad) }
        for (w in listOf("missed", "Missed", "late!", "streak", "lost", "failed")) compose.onAllNodesWithText(w, substring = true).assertCountEquals(0)
    }
}
