package org.pagel.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.glass.LiquidBottomTab
import org.pagel.ai.ui.glass.LiquidBottomTabs
import org.pagel.ai.ui.pair.PairScreen
import org.pagel.ai.ui.pair.PairUiState
import org.pagel.ai.ui.projects.ProjectsScreen
import org.pagel.ai.ui.projects.ProjectsUiState
import org.pagel.ai.ui.shell.Tab
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of each glass piece at the A05s's size (390×844 dp class) and with Boss's font made 1.5× larger.
 * Saved by Roborazzi under build/outputs/roborazzi and kept as a CI artifact; a human (and the next session) looks.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class GlassScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val projects = listOf(
        ProjectEntity("humopedia", "Humopedia", null, 0, 41), ProjectEntity("ababil", "ABABIL", null, 1, 27),
        ProjectEntity("pagel", "PAGEL", null, 2, 58), ProjectEntity("uzcosmos", "UzCosmos", null, 3, 4),
        ProjectEntity("me", "Me", null, 4, 12), ProjectEntity("labs", "Labs", null, 5, 6),
    )

    @Test fun glassBar() {
        compose.setContent {
            GlassTheme {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(390.dp, 220.dp)) {
                    AuroraBackground(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        Text("Content scrolls under the glass", Modifier.align(Alignment.Center), fontSize = 22.sp)
                    }
                    LiquidBottomTabs({ 0 }, {}, backdrop, Tab.entries.size, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                        Tab.entries.forEach { t ->
                            LiquidBottomTab({}) { Icon(t.icon, null, Modifier.size(22.dp)); Text(t.label, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Notebook").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/glass-bar.png")
    }

    @Test fun pairing() {
        compose.setContent { GlassTheme { PairScreen(PairUiState(code = "48"), {}, {}, {}) } }
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/pair-empty.png")
    }

    @Test fun pairingReadyAndWrongCode() {
        compose.setContent { GlassTheme { PairScreen(PairUiState(code = "482913", error = "Wrong code. 4 tries left."), {}, {}, {}) } }
        compose.onNodeWithText("Connect").assertIsEnabled()
        compose.onNodeWithText("Wrong code. 4 tries left.", substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/pair-error.png")
    }

    @Test fun projects() {
        compose.setContent { GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { ProjectsScreen(ProjectsUiState(false, projects, "Live"), PaddingValues(top = 24.dp)) } } }
        compose.onNodeWithText("Humopedia").assertIsDisplayed()
        compose.onNodeWithText("148 open tasks · Live").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/projects.png")
    }

    @Test fun projectsLargeFont() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.5f)) {
                GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { ProjectsScreen(ProjectsUiState(false, projects, "Live"), PaddingValues(top = 24.dp)) } }
            }
        }
        compose.onNodeWithText("Humopedia").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/projects-font-1.5.png")
    }
}
