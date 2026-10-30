package org.pagel.ai.ui.guide

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.shell.ProjectListRoute
import org.pagel.ai.ui.shell.ReviewRoute
import org.pagel.ai.ui.shell.ShutdownRoute
import org.pagel.ai.ui.shell.Tab
import org.pagel.ai.ui.shell.UpcomingListRoute

/** More → How it works: the in-app GTD guide. [card] is the card it opens on (the "?" on a screen opens its own). */
@Serializable data class GuideRoute(val card: Int = 0) : NavKey

/** Where a card's "Try it" goes: the Add sheet, a bottom tab, or a screen pushed on the current tab. */
sealed interface GuideDestination {
    data object AddSheet : GuideDestination
    data class OnTab(val tab: Tab) : GuideDestination
    data class Push(val route: NavKey) : GuideDestination
}

/** One card of the guide: one idea, Boss's own example, one don't, and a button to the real screen. */
data class GuideCard(
    val id: String,
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val line: String,
    val example: String,
    val dont: String,
    val tryLabel: String,
    val goes: GuideDestination,
)

object GuideCards {
    const val CAPTURE = 0
    const val CLARIFY = 1
    const val PROJECTS = 3
    const val CLOSE_THE_DAY = 8

    /** The ten cards, in the order GTD runs: capture, clarify, plan, do, review, unstick. */
    val all: List<GuideCard> = listOf(
        GuideCard("capture", TabIcons.Plus, Color(0xFF1C7E69), "Capture",
            "Everything out of your head, into + or Inbox.",
            "“call Aziz”, “Unity lesson 4”", "Sorting while you capture.",
            "Add a task", GuideDestination.AddSheet),
        GuideCard("clarify", TabIcons.ListTodo, Color(0xFF0E7490), "Clarify",
            "Swipe the Inbox. Not doable: drop it, someday, or a note. Doable in 2 minutes: do it now. Longer: make a task.",
            "“gym” becomes “pack gym bag tonight”", "Vague words like “gym” or “Unity”.",
            "Open Inbox", GuideDestination.OnTab(Tab.Inbox)),
        GuideCard("next-step", TabIcons.ArrowRight, Color(0xFFD9620B), "Next step",
            "The very next thing you can see yourself doing.",
            "“Open the lesson 4 video”", "“Learn Unity”.",
            "Open Today", GuideDestination.OnTab(Tab.Today)),
        GuideCard("projects", TabIcons.Folder, Color(0xFF1C7E69), "Projects",
            "More than one step is a project, with a finish line.",
            "“Finish the Unity course”", "A project per lesson.",
            "Open Projects", GuideDestination.Push(ProjectListRoute)),
        GuideCard("areas", TabIcons.Briefcase, Color(0xFF692FC2), "Areas",
            "Areas are never done. They hold projects.",
            "Games holds the Unity course.", "Nesting deeper than 2 levels.",
            "Open Projects", GuideDestination.Push(ProjectListRoute)),
        GuideCard("calendar", TabIcons.Calendar, Color(0xFFD9620B), "Calendar",
            "Only things tied to a time.",
            "Lesson 10:00–11:00 on Saturday", "Filling the week with hopes.",
            "Open Upcoming", GuideDestination.Push(UpcomingListRoute)),
        GuideCard("waiting", TabIcons.Hourglass, Color(0xFF246FE0), "Waiting and Someday",
            "Things that are not yours right now.",
            "“Waiting: Aziz’s file”", "Letting them crowd Today.",
            "Open weekly review", GuideDestination.Push(ReviewRoute)),
        GuideCard("todays-3", TabIcons.Star, Color(0xFFEB8909), "Today’s 3",
            "Pick 3 and finish them. The rest is a bonus.",
            "Call Aziz, Unity lesson 4, pack the gym bag", "Planning 15 and finishing 4.",
            "Open Today", GuideDestination.OnTab(Tab.Today)),
        GuideCard("close-the-day", TabIcons.Moon, Color(0xFF5B4BC4), "Close the day",
            "Empty the Inbox. Give every project a next step. Choose the week.",
            "Inbox at zero, then lights out.", "Skipping it for 2 weeks.",
            "Open Close the day", GuideDestination.Push(ShutdownRoute)),
        GuideCard("stuck", TabIcons.Zap, Color(0xFFBE123C), "Stuck",
            "Make it tiny.",
            "“Read 2 pages” becomes “get the book in hand”", "Waiting for motivation.",
            "Open Today", GuideDestination.OnTab(Tab.Today)),
    )
}
