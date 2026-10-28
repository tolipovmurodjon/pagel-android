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
/** One habit's page (round D): chain calendar, stats, emoji, stacking. */
@Serializable data class HabitDetailRoute(val id: String) : NavKey
/** Habits' History (round D): Calendar · All habits · Achievements. */
@Serializable data object HabitHistoryRoute : NavKey
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
    private val names = setOf(SHUTDOWN)
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun open(name: String) {
        if (name in names) pending.value = name
    }

    fun route(name: String): NavKey? = when (name) {
        SHUTDOWN -> ShutdownRoute
        else -> null
    }
}
