package org.pagel.ai.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.pagel.ai.R
import org.pagel.ai.ui.motion.blackhole.BlackHoleBackdrop
import org.pagel.ai.ui.motion.blackhole.BlackHoleCanvas
import org.pagel.ai.ui.motion.blackhole.BlackHoleScene
import org.pagel.ai.ui.motion.blackhole.BlackHoleTiming
import org.pagel.ai.ui.motion.blackhole.PieceRect
import org.pagel.ai.ui.motion.blackhole.holeGeometry
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.upcoming.completedGroups
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.lab.Experiments
import org.pagel.ai.ui.lab.ExperimentEntry
import org.pagel.ai.ui.lab.LaboratoryShelf
import org.pagel.ai.ui.motion.blackhole.BlackHoleFrame
import org.pagel.ai.ui.motion.blackhole.BlackHoleLab
import org.pagel.ai.ui.motion.blackhole.Capsule
import org.pagel.ai.ui.motion.blackhole.LabStill
import org.pagel.ai.ui.upcoming.CompletedScreen
import org.pagel.ai.ui.upcoming.CompletedUi
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The black hole's frame series (black-hole v2 brief, "Prove"): six sample rows on a 360 x 800 dp screen whose camera is
 * a top-centre punch hole, at t = 0.1, 0.4, 0.8, 1.2 and 1.6 s of the 1.9 s "Clear all", with a fixed clock so a
 * regression shows as a picture diff; the same moments on the API 26-32 canvas ring; and Completed's "Clear done" button.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class BlackHoleScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val pad = PaddingValues(top = 16.dp, bottom = 32.dp)
    private val moments = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.8f, 1.2f, 1.6f)

    /** A 36 px punch hole, top centre, 40 px below the top edge, in the stage's px (xxhdpi: 1080 px wide). */
    private val camera = Capsule(540f, 58f, 36f, 36f)

    /** One composition (setContent runs once per test); the fixed moment changes between the captures. */
    private fun series(times: List<Float>, shaderRing: Boolean, prefix: String) {
        var still by mutableStateOf(LabStill(times.first(), shaderRing = shaderRing))
        compose.setContent { GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { BlackHoleFrame(still, camera) } } }
        for (t in times) {
            still = LabStill(t, shaderRing = shaderRing)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/$prefix-${"t%03d".format(Math.round(t * 100))}.png")
        }
    }

    /** A stand-in page: a mint grid with dark bars, so a bend shows as curved lines. */
    private fun gridPage(width: Int, height: Int): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bitmap)
        c.drawColor(android.graphics.Color.rgb(246, 250, 248))
        val line = android.graphics.Paint().apply { color = android.graphics.Color.rgb(40, 160, 120); strokeWidth = 6f }
        val bar = android.graphics.Paint().apply { color = android.graphics.Color.rgb(30, 40, 50) }
        for (x in 0 until width step 90) c.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), line)
        for (y in 0 until height step 90) { c.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), line); c.drawRect(0f, y + 30f, width * 0.6f, y + 52f, bar) }
        return bitmap
    }

    /** The lens at t = 0.8 s: the grid under the hole bends toward it and twists; far away it is straight. */
    @Test fun theLensBendsThePageAtPointEight() {
        val page = gridPage(1080, 2400)
        compose.setContent { GlassTheme { BlackHoleFrame(LabStill(0.8f), camera, page) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/black-hole-lens-t080.png")
    }

    private fun doneTask(id: String, text: String, at: String) = TaskEntity(
        id = id, project = "me", phase = "Now", sort = 1.0, text = text, title = null, status = "done", priority = 2, star = false,
        due = null, every = null, doneWhen = null, doneAt = at, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
    )

    /**
     * The lens over the REAL Completed page (checker 2026-11-01): the page is shown with its rows, copied (the rows then
     * painted out of the copy, as at the tap), the list is cleared as the app clears it, and the hole plays over it.
     * t = 0.2 and 0.3 s show the rows already stretching toward the hole; 0.8 s the bend at its widest.
     */
    @Test fun theLensAndThePullOverTheRealCompletedPage() {
        val today = LocalDate.parse("2026-10-28")
        val tasks = listOf(
            doneTask("c1", "Renew the domain", "2026-10-28T04:12:00Z"), doneTask("c2", "Write the IT Park report", "2026-10-28T03:40:00Z"),
            doneTask("c3", "Export the hover log", "2026-10-28T02:10:00Z"), doneTask("c4", "Record the fractions lesson", "2026-10-27T10:00:00Z"),
            doneTask("c5", "Call the bank about the report", "2026-10-27T09:00:00Z"), doneTask("c6", "Weigh in", "2026-10-26T07:30:00Z"),
        )
        var ui by mutableStateOf(CompletedUi(loading = false, today = today, groups = completedGroups(tasks, today), count = tasks.size))
        var scene by mutableStateOf<BlackHoleScene?>(null)
        val time = mutableFloatStateOf(0f)
        compose.setContent {
            GlassTheme {
                AuroraBackground(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        CompletedScreen(ui, pad)
                        scene?.let { BlackHoleCanvas(it, { time.floatValue }) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val half = android.graphics.Bitmap.createScaledBitmap(shot, shot.width / 2, shot.height / 2, true).copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val top = 330f
        val pieces = (0 until 8).map { PieceRect(0f, top + it * 216f, shot.width.toFloat(), top + (it + 1) * 216f) }
        compose.runOnIdle {
            ui = CompletedUi(loading = false, today = today, count = 0, allClear = true)
            scene = BlackHoleScene.prepare(shot, pieces, holeGeometry(camera, shot.width.toFloat()), 36f, BlackHoleTiming.ClearAll, half)
        }
        for (t in listOf(0.2f, 0.3f, 0.8f)) {
            compose.runOnIdle { time.floatValue = t }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/black-hole-page-${"t%03d".format(Math.round(t * 100))}.png")
        }
    }

    /** Painting the pieces out of the copied page: where a row was, the column runs from the pixel above to the one below. */
    @Test fun paintingOutThePiecesFillsFromAboveAndBelow() {
        val bitmap = android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(250, 250, 250))
        for (y in 10 until 20) for (x in 0 until 40) bitmap.setPixel(x, y, android.graphics.Color.BLACK)
        BlackHoleBackdrop.paintOutPieces(bitmap, listOf(PieceRect(0f, 20f, 80f, 40f)), 0f, 0f)
        for (y in 10 until 20) assertTrue("row $y is no longer black", android.graphics.Color.red(bitmap.getPixel(20, y)) > 200)
    }

    /** Every line of this version's What's new group fits ONE line at 360 dp (checker 2026-11-01). */
    @Test @Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi") fun theWhatsNewLinesOfThisVersionFitOneLineEach() {
        val items = ApplicationProvider.getApplicationContext<android.content.Context>().resources.getStringArray(R.array.whats_new_items).toList()
        val lines = items.drop(1).takeWhile { !it.startsWith("#") }.map { it.substringBefore('|') }
        assertEquals(5, lines.size)
        compose.setContent { GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { org.pagel.ai.ui.shell.WhatsNewContent({}) } } }
        for (line in lines) {
            val node = compose.onNodeWithText(line, useUnmergedTree = true).fetchSemanticsNode()
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            assertEquals("\"$line\" fits one line", 1, results.first().lineCount)
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/whats-new-black-hole-360x800.png")
    }

    @Test fun clearAllFrameSeriesOnTheAgslRing() = series(moments, shaderRing = true, prefix = "black-hole")

    @Test fun clearAllFrameSeriesOnTheCanvasFallback() = series(listOf(0.4f, 0.8f, 1.2f), shaderRing = false, prefix = "black-hole-fallback")

    private fun laboratory(name: String) {
        val entry = ExperimentEntry(Experiments.all.first(), on = true, note = "Janky frames: 3.1 % (4 of 128) · first frame 42 ms")
        compose.setContent { GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { LaboratoryShelf(listOf(entry), pad) } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        compose.onNodeWithText("Laboratory").assertIsDisplayed()
        compose.onNodeWithText("Black hole").assertIsDisplayed()
        compose.onNodeWithText("Try it").assertIsDisplayed()
        compose.onNodeWithText("Janky frames: 3.1 % (4 of 128) · first frame 42 ms").assertIsDisplayed()
    }

    @Test @Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi") fun laboratoryAtOneTimes() = laboratory("laboratory-1x")

    @Test @Config(sdk = [35], qualifiers = "w360dp-h800dp-hdpi") fun laboratoryAtOnePointFiveTimes() = laboratory("laboratory-1_5x")

    @Test fun theLabShowsBothLengthsAndTheJankLine() {
        compose.setContent { GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { BlackHoleLab(pad, jank = "Janky frames: 3.1 % (4 of 128)") } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/black-hole-lab.png")
        compose.onNodeWithText("Delete one · 1.3 s").assertIsDisplayed()
        compose.onNodeWithText("Clear all · 1.9 s").assertIsDisplayed()
        compose.onNodeWithText("Janky frames: 3.1 % (4 of 128)").assertIsDisplayed()
    }

    /** Checker 2026-10-30 #2: after a clear the list says All clear and the count is the list's, not "4 tasks done". */
    @Test fun afterAClearCompletedSaysAllClearWithTheListsCount() {
        compose.setContent {
            GlassTheme {
                AuroraBackground(Modifier.fillMaxSize()) {
                    CompletedScreen(CompletedUi(loading = false, today = LocalDate.parse("2026-10-28"), count = 0, allClear = true), pad)
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/completed-all-clear.png")
        compose.onNodeWithText("All clear").assertIsDisplayed()
        compose.onNodeWithText("0 tasks done").assertIsDisplayed()
        compose.onAllNodesWithText("Nothing finished yet").assertCountEquals(0)
    }

    @Test fun completedHasClearDoneAndItCallsBack() {
        val today = LocalDate.parse("2026-10-28")
        val done = listOf(
            TaskEntity(
                id = "c1", project = "me", phase = "Now", sort = 1.0, text = "Renew the domain", title = null, status = "done", priority = 2, star = false,
                due = null, every = null, doneWhen = null, doneAt = "2026-10-28T04:12:00Z", updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
            ),
        )
        var cleared = 0
        compose.setContent {
            GlassTheme {
                AuroraBackground(Modifier.fillMaxSize()) {
                    CompletedScreen(CompletedUi(loading = false, today = today, groups = completedGroups(done, today), count = 1), pad, onClearDone = { cleared++ })
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/completed-clear-done.png")
        compose.onNodeWithContentDescription("Clear done").performClick()
        compose.waitForIdle()
        assertEquals(1, cleared)
    }
}
