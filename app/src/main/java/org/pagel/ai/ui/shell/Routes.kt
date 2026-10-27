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
