package org.pagel.ai.ui.shell

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.pagel.ai.ui.glass.TabIcons

/** Type-safe Navigation 3 routes (RULES.md, navigation). Each tab has its own back stack; More opens the rest. */
@Serializable data object TodayRoute : NavKey
@Serializable data object InboxRoute : NavKey
@Serializable data object HabitsRoute : NavKey
@Serializable data object NotebookRoute : NavKey
@Serializable data object MoreRoute : NavKey
/** Every project from pagel.uz, opened from More (it was the Today tab until the native Today landed). */
@Serializable data object ProjectListRoute : NavKey
/** The next 7 days with Overdue on top (the web's /upcoming), opened from More as on the web's phone bar. */
@Serializable data object UpcomingListRoute : NavKey
/** Finished tasks by day (the web's /completed), opened from More. */
@Serializable data object CompletedListRoute : NavKey
/** One project's open tasks by phase, opened from Projects or from a task panel's project capsule. */
@Serializable data class ProjectPageRoute(val name: String) : NavKey
/** pagel.uz itself in the kept-alive WebView, until each native feature replaces its page. */
@Serializable data class WebRoute(val path: String) : NavKey

/** The phone bar, same five as the web's phone bar (dashboard src/app/nav.tsx PHONE + More). */
enum class Tab(val label: String, val root: NavKey, val icon: ImageVector) {
    Today("Today", TodayRoute, TabIcons.Today),
    Inbox("Inbox", InboxRoute, TabIcons.Inbox),
    Habits("Habits", HabitsRoute, TabIcons.Habits),
    Notebook("Notebook", NotebookRoute, TabIcons.Notebook),
    More("More", MoreRoute, TabIcons.More),
}

/** Empty your head (the web's /sweep), Close the day (/shutdown), the weekly review (/review): Phase 5's rituals. */
@Serializable data object SweepRoute : NavKey
@Serializable data object ShutdownRoute : NavKey
@Serializable data object ReviewRoute : NavKey
/** More → Notifications (round C): tone, morning plan, evening nudge, channels. */
@Serializable data object NotificationsRoute : NavKey

/**
 * A screen asked for from outside the shell (the evening notification's "Close the day"): MainActivity puts the name
 * here, the shell opens it on Today's stack and clears it. Only the names below are opened.
 */
object OpenRequests {
    const val EXTRA = "pagel.open"
    const val SHUTDOWN = "shutdown"
    /** The Compass's birthday and season-review notifications (Phase 6a). */
    const val COMPASS = "compass"
    /** The home-screen widgets (Phase 8): the Add sheet, voice add, the food log, the Habits tab, Today, one task. */
    const val ADD = "add"
    const val VOICE = "voice"
    const val FOOD = "food"
    const val HABITS = "habits"
    const val TODAY = "today"
    const val TASK_PREFIX = "task:"
    private val names = setOf(SHUTDOWN, COMPASS, ADD, VOICE, FOOD, HABITS, TODAY)
    /** A task id as pagel.uz and the phone make them (t_…, local_…); anything else is not opened. */
    private val TASK_ID = Regex("[A-Za-z0-9_-]{1,64}")
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun open(name: String) {
        if (name in names || taskOf(name) != null) pending.value = name
    }

    /** The task a "task:<id>" request opens, or null. */
    fun taskOf(name: String): String? = name.takeIf { it.startsWith(TASK_PREFIX) }?.removePrefix(TASK_PREFIX)?.takeIf(TASK_ID::matches)

    fun route(name: String): NavKey? = when (name) {
        SHUTDOWN -> ShutdownRoute
        COMPASS -> org.pagel.ai.ui.compass.CompassRoute
        else -> null
    }
}
