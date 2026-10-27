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
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pagel.ai.MainActivity
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
}

private fun parts(ctx: Context) = EntryPointAccessors.fromApplication(ctx, WidgetParts::class.java)

/** The web's mint, solid: a widget cannot draw glass (RULES.md, widget). Ink on light mint, light mint on ink at night. */
private val WidgetColors = ColorProviders(
    light = lightColorScheme(
        primary = Glass.Primary, secondary = Glass.Primary, onSurface = Glass.Ink, onSurfaceVariant = Glass.Muted,
        surface = Glass.Page, background = Glass.Page, primaryContainer = Glass.PrimarySoft, secondaryContainer = Glass.PrimarySoft,
    ),
    dark = darkColorScheme(
        primary = Glass.Aurora1, secondary = Glass.Aurora1, onSurface = Color.White, onSurfaceVariant = Color(0xFFB8C0D6),
        surface = Glass.Ink, background = Glass.Ink, primaryContainer = Color(0xFF173A35), secondaryContainer = Color(0xFF173A35),
    ),
)

/**
 * "Today's 3" on the home screen (NATIVE-PLAN Phase 2): the same 3 tasks the app shows, in pagel.uz's order, from
 * Room. Tick one and it goes through the app's outbox; the next task is dealt. Google's check_list canonical layout.
 */
class TodayWidget : GlanceAppWidget() {
    // Exact: one layout for the real size, without holding a view per breakpoint in the launcher's memory.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = parts(context).dao()
        val firstTasks = withContext(Dispatchers.IO) { dao.todayTasks().first() }
        val firstProjects = withContext(Dispatchers.IO) { dao.projects().first() }
        provideContent {
            val tasks by remember { dao.todayTasks() }.collectAsState(firstTasks)
            val projects by remember { dao.projects() }.collectAsState(firstProjects)
            GlanceTheme(colors = WidgetColors) { Content(rows(tasks, projects)) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Content(items: List<CheckListItem>) {
        val ctx = LocalContext.current
        CheckListLayout(
            title = ctx.getString(R.string.widget_today_title),
            titleIconRes = R.drawable.widget_today_icon,
            titleBarActionIconRes = R.drawable.sample_add_icon,
            titleBarActionIconContentDescription = ctx.getString(R.string.widget_today_open),
            titleBarAction = actionStartActivity<MainActivity>(),
            items = items,
            checkedItems = emptyList(),
            checkedIconRes = R.drawable.sample_checked_circle_icon,
            unCheckedIconRes = R.drawable.sample_circle_icon,
            checkButtonContentDescription = ctx.getString(R.string.widget_today_mark_done),
            onCheck = { key -> actionRunCallback<TickFromWidget>(actionParametersOf(TickFromWidget.TaskId to key)) },
            emptyAction = actionStartActivity<MainActivity>(),
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
        TodayWidget().updateAll(context)
    }

    companion object {
        val TaskId = ActionParameters.Key<String>("taskId")
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

/**
 * Keeps the widget true: whenever Room's Today changes (a tick in the app, a sync from pagel.uz), the widget is redrawn.
 * Glance only redraws on its own while its session is open (~45 s), so this push is what keeps it live all day.
 */
@Singleton
class TodayWidgetUpdater @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: PagelDao,
    @AppScope private val scope: CoroutineScope,
) {
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(dao.todayTasks(), dao.projects()) { t, p -> t to p }
                .distinctUntilChanged()
                .debounce(300)
                .collect { runCatching { TodayWidget().updateAll(ctx) } }
        }
    }
}
