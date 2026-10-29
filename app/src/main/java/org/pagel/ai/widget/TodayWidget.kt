package org.pagel.ai.widget

import android.content.Context
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.material3.ColorProviders
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pagel.ai.R
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.di.AppScope
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.projects.shortName
import org.pagel.ai.ui.today.dealToday
import org.pagel.ai.ui.today.dueLabel
import org.pagel.ai.ui.today.headline
import org.pagel.ai.widget.layout.CheckListItem
import org.pagel.ai.widget.layout.CheckListLayout
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What the widget needs from the app: Room to read, the Today repository to tick (same outbox as the app). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetParts {
    fun dao(): PagelDao
    fun today(): TodayRepository
    /** Phase 8: the habit and namoz widgets' door (WidgetSources.kt), and the Calories widget's numbers. */
    fun habits(): WidgetHabits
    fun calories(): WidgetCalories
}

internal fun parts(ctx: Context): WidgetParts = partsOf(ctx)

/** Hilt's entry point in the app; a test puts fakes here to run a widget's ActionCallback on the JVM. */
internal var partsOf: (Context) -> WidgetParts = { EntryPointAccessors.fromApplication(it, WidgetParts::class.java) }

/** Today's 3 and the day's count ("12 done · 1 left"), from Room only. */
internal fun todayGlanceFlow(dao: PagelDao, today: TodayRepository): Flow<TodayGlance> =
    combine(dao.todayTasks(), dao.projects(), today.doneToday()) { t, p, done -> todayGlance(t, p, done, LocalDate.now(TASHKENT)) }

/** The next task on Today and its next open step; the steps are read for that one task only. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun nextTaskFlow(dao: PagelDao): Flow<NextTaskGlance?> =
    combine(dao.todayTasks(), dao.projects()) { t, p -> t to p }.flatMapLatest { (tasks, projects) ->
        val first = dealToday(tasks, finishing = emptySet()).visible.firstOrNull()
        if (first == null) flowOf(null) else dao.steps(first.id).map { steps -> nextTaskGlance(tasks, steps, projects) }
    }

/**
 * White by day, deep ink at night; mint only for the ticks and the icon. The container greens read as a dull
 * green slab on the home screen (Boss 2026-10-27), so every surface Glance may paint is set to the page colour.
 */
private val WidgetColors = ColorProviders(
    light = lightColorScheme(
        primary = Glass.Primary, secondary = Glass.Primary, onSurface = Glass.Ink, onSurfaceVariant = Glass.Muted,
        surface = Color.White, background = Color.White, primaryContainer = Color.White, secondaryContainer = Color.White,
        surfaceVariant = Color.White, onPrimaryContainer = Glass.Primary, onSecondaryContainer = Glass.Primary,
    ),
    dark = darkColorScheme(
        primary = Glass.Aurora1, secondary = Glass.Aurora1, onSurface = Color.White, onSurfaceVariant = Color(0xFFB8C0D6),
        surface = Glass.Ink, background = Glass.Ink, primaryContainer = Glass.Ink, secondaryContainer = Glass.Ink,
        surfaceVariant = Glass.Ink, onPrimaryContainer = Glass.Aurora1, onSecondaryContainer = Glass.Aurora1,
    ),
)

/**
 * "Today's 3" on the home screen (NATIVE-PLAN Phase 2): the same 3 tasks the app shows, in pagel.uz's order, from
 * Room. Tick one and it goes through the app's outbox; the next task is dealt. Google's check_list canonical layout.
 */
class TodayWidget : PagelWidget() {
    // Exact (PagelWidget): one layout for the real size, without holding a view per breakpoint in the launcher's memory.
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val p = parts(context)
        val flow = todayGlanceFlow(p.dao(), p.today())
        val first = withContext(Dispatchers.IO) { flow.first() }
        provideContent {
            val g by remember { flow }.collectAsState(first)
            TodayContent(g)
        }
    }
}

/**
 * Today's 3 in the white-mint glass (Phase 8): the title bar, "12 done · 1 left" under it (Today's own bar words), the
 * 3 rows with their tick, a row's words open the task. Drawn from the model only, so tests render it without the app.
 */
@androidx.compose.runtime.Composable
fun TodayContent(g: TodayGlance) {
    GlanceTheme(colors = WidgetColors) {
        val ctx = LocalContext.current
        CheckListLayout(
            title = ctx.getString(R.string.widget_today_title),
            subtitle = g.line.takeIf { g.done + g.open > 0 },
            titleIconRes = R.drawable.widget_today_icon,
            titleBarActionIconRes = R.drawable.sample_add_icon,
            titleBarActionIconContentDescription = ctx.getString(R.string.widget_today_open),
            titleBarAction = WidgetOpen.add,
            items = g.rows,
            checkedItems = emptyList(),
            checkedIconRes = R.drawable.sample_checked_circle_icon,
            unCheckedIconRes = R.drawable.sample_circle_icon,
            checkButtonContentDescription = ctx.getString(R.string.widget_today_mark_done),
            onCheck = { key -> actionRunCallback<TickFromWidget>(actionParametersOf(TickFromWidget.TaskId to key)) },
            onOpen = { key -> WidgetOpen.task(key) },
            emptyAction = WidgetOpen.today,
        )
    }
}

/** Today's rows for the widget: the app's own dealing (3 at a time), title and "when · project" as on the phone. */
internal fun rows(tasks: List<TaskEntity>, projects: List<ProjectEntity>, today: LocalDate = LocalDate.now(ZoneId.of("Asia/Tashkent"))): List<CheckListItem> {
    val titles = projects.associate { it.name to it.title }
    return dealToday(tasks, finishing = emptySet()).visible.map { t ->
        val project = shortName(t.project, titles[t.project] ?: t.project)
        val due = dueLabel(t.due, today)?.text
        CheckListItem(key = t.id, title = t.title?.takeIf(String::isNotBlank) ?: headline(t.text), supportingText = listOfNotNull(due, project).joinToString(" · "))
    }
}

/**
 * The widget's tick. Zero-argument constructor: Glance creates it by reflection (and R8 keeps `<init>`, see
 * proguard-rules.pro); the task id comes as an action parameter.
 */
class TickFromWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskId] ?: return
        parts(context).today().tick(id, done = true)
        WidgetHaptics.finish(context)
        TodayWidget().updateAll(context)
        NextTaskWidget().updateAll(context)
    }

    companion object {
        val TaskId = ActionParameters.Key<String>("taskId")
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

/**
 * Keeps every widget true: whenever what a widget shows changes (a tick in the app, a sync from pagel.uz), that widget
 * is redrawn. Glance only redraws on its own while its session is open (~45 s), so this push is what keeps them live
 * all day. Each push compares the widget's model with the last one pushed ([WidgetRevisions]): a sync that changed
 * nothing a widget shows wakes no launcher.
 */
@Singleton
class TodayWidgetUpdater @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: PagelDao,
    private val today: TodayRepository,
    private val habits: WidgetHabits,
    @AppScope private val scope: CoroutineScope,
) {
    private val revisions = WidgetRevisions()

    fun start() {
        watch("today", todayGlanceFlow(dao, today)) { TodayWidget().updateAll(ctx) }
        watch("next", nextTaskFlow(dao)) { NextTaskWidget().updateAll(ctx) }
        watch("habits", habits.habits()) { HabitsWidget().updateAll(ctx) }
        watch("namoz", habits.namoz()) { NamozWidget().updateAll(ctx) }
    }

    @OptIn(FlowPreview::class)
    private fun <T> watch(name: String, models: Flow<T>, push: suspend () -> Unit) {
        scope.launch {
            models.distinctUntilChanged().debounce(300).collect { m ->
                if (revisions.changed(name, m)) runCatching { push() }
            }
        }
    }
}

/**
 * The last model each widget was drawn from. A new model equal to the last one is not pushed again (skill
 * glance-appwidget-updates-and-memory: a revision decides a redraw), so a sync that touched other rows costs nothing.
 */
class WidgetRevisions {
    private val last = HashMap<String, Any?>()

    @Synchronized
    fun changed(widget: String, model: Any?): Boolean {
        if (last.containsKey(widget) && last[widget] == model) return false
        last[widget] = model
        return true
    }
}
