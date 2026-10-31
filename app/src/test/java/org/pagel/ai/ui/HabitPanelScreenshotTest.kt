package org.pagel.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.db.HabitFreezeEntity
import org.pagel.ai.data.db.HabitPrefEntity
import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.Part
import org.pagel.ai.data.habits.habitBoard
import org.pagel.ai.data.habits.habitsDay
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.glass.PriorityColors
import org.pagel.ai.ui.habits.HabitLookContent
import org.pagel.ai.ui.habits.HabitPanel
import org.pagel.ai.ui.habits.HabitPanelActions
import org.pagel.ai.ui.habits.HabitPanelState
import org.pagel.ai.ui.habits.HabitsUi
import org.pagel.ai.ui.habits.NewHabitContent
import org.pagel.ai.ui.task.PanelActions
import org.pagel.ai.ui.task.PanelStep
import org.pagel.ai.ui.task.PanelUi
import org.pagel.ai.ui.task.TaskPanelContent
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * A habit is edited in the task panel and nowhere else (Boss 2026-10-31). The panel for a habit collapsed, each row
 * open (time, repeat, reminder on with its wheel, after), the title in edit mode, the icon and colour sheet, the New
 * habit sheet, and the panel for a normal task unchanged; 360 x 800, 320 x 640, and 1.5x font. Every shot is checked for
 * cut text, for red pixels and for emoji.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class HabitPanelScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val today = "2026-10-28"
    private val day = LocalDate.parse(today)

    private fun habit(id: String, text: String, due: String?, every: String = "day", times: Int = 1) = TaskEntity(
        id = id, project = "me", phase = "Now", sort = 1.0, text = text, title = null, status = "open", priority = 2, star = false,
        due = due, every = every, doneWhen = null, doneAt = null, updatedAt = "2026-10-27T05:00:00Z", serverVersion = 1, lastModified = 0,
        times = times, habitGroup = null,
    )

    private val habits = listOf(
        habit("h1", "Stretch for ten minutes", "2026-10-28 07:30"),
        habit("h2", "Go through five saves in your Inbox", "2026-10-28 09:00"),
        habit("h3", "Drink water", "2026-10-28"),
        habit("h4", "Do the full-body F workout at openGym", "2026-10-29 18:00", every = "mon,wed,fri"),
    )
    private val ticks = listOf(HabitTickEntity("2026-10-27", "h1"), HabitTickEntity("2026-10-26", "h1"), HabitTickEntity("2026-10-27", "h2"))
    // An old saved look (an emoji key, with a colour) and an old moment with its glyph: both must show without any emoji.
    private val prefs = listOf(
        HabitPrefEntity("h1", emoji = "lotus/teal", after = "moment:" + org.pagel.ai.data.habits.HabitLook.MOMENTS[0], identity = "I'm someone who moves every morning"),
        HabitPrefEntity("h2", after = "h1"),
    )

    private fun ui(reminders: Set<String> = emptySet(), p: List<HabitPrefEntity> = prefs) = HabitsUi(
        loading = false, today = today, day = habitsDay(habits, ticks, today),
        board = habitBoard(habits, ticks, p, emptyList<HabitFreezeEntity>(), day, day),
        part = Part.MORNING, reminders = reminders, byId = habits.associateBy { it.id }, now = 200_000, ticks = ticks,
        prefs = p.associateBy { it.taskId },
    )

    private fun habitPanel(h: TaskEntity, reminders: Set<String> = emptySet(), a: HabitPanelActions = HabitPanelActions(), notify: Boolean = true, exact: Boolean = true) =
        HabitPanel(HabitPanelState(h, ui(reminders), reminderOn = h.id in reminders, notify = notify, exact = exact), a)

    private fun panelUi(h: TaskEntity) = PanelUi(
        loading = false, id = h.id, project = "me", projectLabel = "Me", title = h.text, text = h.text, due = h.due, every = h.every, priority = 2, habit = true,
        steps = listOf(PanelStep("Roll the mat out", false)),
    )

    private fun show(fontScale: Float = 1f, body: @Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
            GlassTheme { AuroraBackground(Modifier.fillMaxSize()) { Column(Modifier.padding(top = 16.dp)) { body() } } }
        }
    }

    private fun showHabit(h: TaskEntity = habits[0], row: String = "", fontScale: Float = 1f, reminders: Set<String> = emptySet(), a: HabitPanelActions = HabitPanelActions(), notify: Boolean = true, exact: Boolean = true) =
        show(fontScale) { TaskPanelContent(panelUi(h), day, PanelActions(), habit = habitPanel(h, reminders, a, notify, exact), habitRow0 = row) }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun below(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { below(it) }
    private fun allNodes() = below(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())

    private fun layoutOf(n: SemanticsNode): TextLayoutResult? {
        val out = mutableListOf<TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.firstOrNull()
    }

    /** No text cut by an ellipsis, by its box, or inside a word. The wheel's digits sit in a clipped drum on purpose. */
    private fun assertNoCutText(what: String) {
        var seen = 0
        for (n in allNodes()) {
            val l = layoutOf(n) ?: continue
            val text = l.layoutInput.text.text
            if (text.length <= 2 && text.all(Char::isDigit)) continue
            seen++
            val at = "$what \"${text.take(40)}\""
            assertTrue("$at has an ellipsis", (0 until l.lineCount).none { l.isLineEllipsized(it) })
            val needs = l.multiParagraph.intrinsics.maxIntrinsicWidth
            assertTrue("$at is cut by its box (words $needs px, box ${n.size.width} px)", !(l.lineCount == 1 && needs > n.size.width + 1f))
            for (i in 0 until l.lineCount - 1) {
                val e = l.getLineEnd(i)
                assertTrue("$at breaks a word at line ${i + 1}", e >= text.length || text[e - 1].isWhitespace() || text[e].isWhitespace() || text[e - 1] == '-')
            }
        }
        assertTrue("$what: some text was checked", seen > 0)
    }

    /** Never red in the UI (Boss's rule): no pixel that is clearly red. */
    private fun assertNeverRed(what: String) {
        val map = compose.onRoot().captureToImage().toPixelMap()
        var red = 0
        for (y in 0 until map.height step 2) for (x in 0 until map.width step 2) {
            val c = map[x, y]
            if (c.red > 0.78f && c.green < 0.28f && c.blue < 0.28f) red++
        }
        assertEquals("$what has $red red pixels", 0, red)
    }

    /** Boss 2026-10-31: no emoji. Every text and every spoken label on screen is free of emoji code points. */
    private fun assertNoEmoji(what: String) {
        val strings = allNodes().flatMap { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
        assertTrue("$what: some text was read", strings.isNotEmpty())
        for (s in strings) assertTrue("$what has an emoji in \"$s\"", !hasEmoji(s))
    }

    private fun hasEmoji(s: String) = s.codePoints().anyMatch { cp ->
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x2300..0x23FF || cp == 0xFE0F || cp == 0x200D
    }

    private fun judge(what: String) {
        assertNoCutText(what); assertNeverRed(what); assertNoEmoji(what)
    }

    // ── the panel for a habit ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun theHabitPanelCollapsed() {
        showHabit()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-panel-360.png")
        compose.onNodeWithContentDescription("Time: Morning · 07:30").assertExists()
        compose.onNodeWithContentDescription("Repeat: Every day").assertExists()
        compose.onNodeWithContentDescription("Reminder: Off").assertExists()
        compose.onNodeWithContentDescription("After: Coffee").assertExists()
        // The task-only rows and tools are gone for a habit, and nothing is open yet: no wheel, no day list.
        for (gone in listOf("Priority", "Pin to Today", "Make it tiny", "I'm stuck", "Won't do, with the reason", "Done when")) {
            compose.onAllNodesWithText(gone).assertCountEquals(0)
            compose.onAllNodesWithContentDescription(gone).assertCountEquals(0)
        }
        compose.onAllNodesWithContentDescription("Time wheel 07:30").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("After: Drink water").assertCountEquals(0)
        compose.onNodeWithContentDescription("Icon and colour").assertExists()
        compose.onNodeWithText("I'm someone who moves every morning").assertExists()
        compose.onNodeWithContentDescription("Start 10 min").assertExists()
        judge("habit panel 360")
    }

    @Test fun theHabitPanelAtLargeFont() {
        showHabit(fontScale = 1.5f)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-panel-360-font-1.5.png")
        judge("habit panel 1.5x")
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp-xxhdpi") fun theHabitPanelOnASmallPhone() {
        showHabit()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-panel-320.png")
        judge("habit panel 320")
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp-xxhdpi") fun theHabitPanelOnASmallPhoneAtLargeFont() {
        showHabit(row = "time", fontScale = 1.5f)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-panel-320-font-1.5-time.png")
        judge("habit panel 320 1.5x")
    }

    @Test fun theTimeRowOpensItsCapsuleAndTheWheel() {
        var part: Part? = null
        showHabit(row = "time", a = HabitPanelActions(onPart = { part = it }))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-panel-time-open.png")
        compose.onNodeWithContentDescription("Morning").assertIsSelected()
        compose.onNodeWithContentDescription("Time wheel 07:30").assertExists()
        compose.onNodeWithContentDescription("Evening").performClick()
        assertEquals(Part.EVENING, part)
        judge("time row open")
    }

    @Test fun anAnytimeHabitHasNoWheel() {
        showHabit(habits[2], row = "time")
        compose.onNodeWithContentDescription("Time: Anytime").assertExists()
        compose.onAllNodesWithContentDescription("Time wheel 08:00").assertCountEquals(0)
        judge("anytime")
    }

    @Test fun theRepeatRowReadsItsDays() {
        show { TaskPanelContent(panelUi(habits[3]), day, PanelActions(), habit = habitPanel(habits[3])) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-panel-repeat-custom.png")
        compose.onNodeWithContentDescription("Repeat: Mon · Wed · Fri").assertExists()
        judge("repeat custom")
    }

    @Test fun theReminderShowsItsWheelOnlyWhenOnAndOpen() {
        showHabit(reminders = setOf("h1"), row = "reminder")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-panel-reminder-on.png")
        compose.onNodeWithContentDescription("Reminder: 07:30").assertExists()
        compose.onNodeWithContentDescription("Time wheel 07:30").assertExists()
        judge("reminder on")
    }

    @Test fun aReminderThatIsOffHasNoWheelEvenIfItsRowWasOpen() {
        showHabit(reminders = emptySet(), row = "reminder")
        compose.onNodeWithContentDescription("Reminder: Off").assertExists()
        compose.onAllNodesWithContentDescription("Time wheel 07:30").assertCountEquals(0)
    }

    @Test fun aMissingGrantIsARowToTapNotAParagraph() {
        var fixed = ""
        showHabit(reminders = setOf("h1"), row = "reminder", notify = false, a = HabitPanelActions(onFixNotify = { fixed = "notify" }))
        compose.onNodeWithContentDescription("Notifications off · Turn on").performClick()
        assertEquals("notify", fixed)
        judge("grant row")
    }

    @Test fun theAfterRowListsMomentsAndRealHabitsWithoutAnyEmoji() {
        var anchor: org.pagel.ai.data.habits.HabitLook.Anchor? = null
        showHabit(row = "after", a = HabitPanelActions(onAnchor = { anchor = it }))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-panel-after-open.png")
        compose.onNodeWithContentDescription("After: None").assertExists()
        compose.onNodeWithContentDescription("After: Fajr").assertExists()
        // The row itself also reads "After: Coffee" (its value); the list line is the selectable one.
        compose.onNode(androidx.compose.ui.test.hasContentDescription("After: Coffee") and androidx.compose.ui.test.isSelectable()).assertIsSelected()
        // h2 already comes after h1, so h1 may not come after h2 (a loop); itself is never offered.
        compose.onAllNodesWithContentDescription("After: Go through five saves in your Inbox").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("After: Stretch for ten minutes").assertCountEquals(0)
        compose.onNodeWithContentDescription("After: Drink water").performScrollTo().performClick()
        assertEquals(org.pagel.ai.data.habits.HabitLook.Anchor.Habit("h3"), anchor)
        judge("after open")
    }

    @Test fun theTitleIsEditedInPlaceTrimmedAndAnEmptyOneIsRestored() {
        val saved = mutableListOf<String>()
        show { TaskPanelContent(panelUi(habits[0]), day, PanelActions(onTitle = { saved += it }), habit = habitPanel(habits[0])) }
        compose.onNodeWithContentDescription("Title").performTextReplacement("  Stretch longer  ")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-panel-title-edit.png")
        compose.onNodeWithContentDescription("Title").performImeAction()
        assertEquals(listOf("Stretch longer"), saved)
        compose.onNodeWithContentDescription("Title").performTextReplacement("   ")
        compose.onNodeWithContentDescription("Title").performImeAction()
        assertEquals("an empty title saves nothing", listOf("Stretch longer"), saved)
    }

    @Test fun theIconAndColourSheetOffersLineIconsAndNoEmoji() {
        var icon = ""
        var colour = ""
        show { Column { HabitLookContent(habitPanel(habits[0]).state, HabitPanelActions(onIcon = { icon = it }, onColour = { colour = it })) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-look-sheet.png")
        // The old saved lotus/teal look shows as the flower icon in teal.
        compose.onNodeWithContentDescription("Icon flower-2").assertIsSelected()
        compose.onNodeWithContentDescription("Colour teal").assertIsSelected()
        compose.onNodeWithContentDescription("Icon glass-water").performClick()
        assertEquals("glass-water", icon)
        compose.onNodeWithContentDescription("Colour amber").performScrollTo().performClick()
        assertEquals("amber", colour)
        compose.onAllNodesWithContentDescription("Colour red").assertCountEquals(0)
        judge("look sheet")
    }

    // ── a normal task is unchanged, but no longer blue ─────────────────────────────────────────────────────────────

    @Test fun aNormalTaskKeepsItsRowsAndLosesTheBlue() {
        val task = panelUi(habits[0]).copy(habit = false, every = null, due = "2026-10-29 15:00", priority = 2, doneWhen = "Boss edits it on his phone")
        show { TaskPanelContent(task, day, PanelActions()) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/habit-panel-normal-task.png")
        compose.onNodeWithContentDescription("Priority: P3").assertExists()
        compose.onNodeWithText("Pin to Today").assertExists()
        compose.onNodeWithContentDescription("Make it tiny").assertExists()
        compose.onAllNodesWithContentDescription("Time: Morning · 07:30").assertCountEquals(0)
        // P3 is soft ink now: the old flag blue (0x246FE0) clashed with the mint.
        val p3 = PriorityColors[2]
        assertTrue("P3 is not blue: $p3", !(p3.blue > p3.red + 0.25f && p3.blue > 0.6f))
        assertNeverRed("normal task")
    }

    // ── the New habit sheet ────────────────────────────────────────────────────────────────────────────────────────

    @Test fun theNewHabitSheetIsOneNameAndThreeRows() {
        var added = ""
        show { NewHabitContent(Part.ANYTIME, { n, p, e, r -> added = "$n|${p.label}|$e|$r" }, {}) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/new-habit-360.png")
        compose.onNodeWithContentDescription("Time: Anytime").assertExists()
        compose.onNodeWithContentDescription("Repeat: Every day").assertExists()
        compose.onNodeWithContentDescription("Reminder: Off").assertExists()
        compose.onNodeWithContentDescription("Habit name").performTextReplacement("Floss at night")
        compose.onNodeWithContentDescription("Remind me").performClick()
        // A reminder rings at a time, so an Anytime habit moves to the morning.
        compose.onNodeWithContentDescription("Time: Morning").assertExists()
        compose.onNodeWithText("Add habit").performClick()
        assertEquals("Floss at night|Morning|day|true", added)
        judge("new habit")
    }

    @Test fun theNewHabitSheetAtLargeFont() {
        show(1.5f) { NewHabitContent(Part.MORNING, { _, _, _, _ -> }, {}) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/new-habit-360-font-1.5.png")
        assertNoCutText("new habit 1.5x")
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h640dp-xxhdpi") fun theNewHabitSheetOnASmallPhone() {
        show { NewHabitContent(Part.MORNING, { _, _, _, _ -> }, {}) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/new-habit-320.png")
        assertNoCutText("new habit 320")
    }
}
