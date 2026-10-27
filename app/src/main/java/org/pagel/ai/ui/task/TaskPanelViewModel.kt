package org.pagel.ai.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.tasks.NotDoing
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.ui.glass.DatePick
import org.pagel.ai.ui.glass.ToastCenter
import org.pagel.ai.ui.projects.shortName
import org.pagel.ai.ui.today.headline
import java.time.LocalDate
import javax.inject.Inject

data class PanelStep(val text: String, val done: Boolean)

/** One task as the panel shows it (the web's Item): everything editable in place. */
data class PanelUi(
    val loading: Boolean = true,
    val id: String = "",
    val project: String = "",
    val projectLabel: String = "",
    val projectColor: String? = null,
    val title: String = "",
    val text: String = "",
    val doneWhen: String = "",
    val due: String? = null,
    val every: String? = null,
    val priority: Int = 2,
    val star: Boolean = false,
    val open: Boolean = true,
    val steps: List<PanelStep> = emptyList(),
    /** Some edit waits for pagel.uz to learn the new fields (an older server): a cloud-off mark says so. */
    val held: Boolean = false,
)

/** The task panel (NATIVE-PLAN Phase 1): reads Room only; every edit answers at once and saves behind. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskPanelViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val today: TodayRepository,
    private val toasts: ToastCenter,
    session: Session,
) : ViewModel() {
    private val id = MutableStateFlow<String?>(null)

    val ui: StateFlow<PanelUi> = id.flatMapLatest { taskId ->
        if (taskId == null) flowOf(PanelUi())
        else combine(tasks.task(taskId), tasks.steps(taskId), tasks.held(taskId), session.projects()) { t, steps, held, projects ->
            if (t == null) PanelUi(loading = false, id = taskId)
            else {
                val p = projects.firstOrNull { it.name == t.project }
                PanelUi(
                    loading = false, id = t.id, project = t.project, projectLabel = shortName(t.project, p?.title ?: t.project), projectColor = p?.color,
                    title = t.title?.takeIf(String::isNotBlank) ?: headline(t.text), text = t.text, doneWhen = t.doneWhen.orEmpty(),
                    due = t.due, every = t.every, priority = t.priority, star = t.star, open = t.status == "open",
                    steps = steps.map { PanelStep(it.text, it.done) }, held = held > 0,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PanelUi())

    val projects: StateFlow<List<ProjectEntity>> = session.projects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun bind(taskId: String) { id.value = taskId }

    private val cur get() = ui.value
    private fun go(block: suspend (String) -> Unit) { val t = id.value ?: return; viewModelScope.launch { block(t) } }

    fun title(v: String) = go { tasks.title(it, v) }
    fun text(v: String) = go { tasks.text(it, v) }
    fun doneWhen(v: String) = go { tasks.doneWhen(it, v) }
    fun priority(p: Int) = go { tasks.priority(it, p) }
    fun star(on: Boolean) = go { tasks.star(it, on) }
    fun every(v: String?) = go { tasks.every(it, v) }

    /** A date later than today takes the task off Today: say where it went, with Undo (the web's "Shows on its date"). */
    fun due(v: String?) {
        val before = cur.due
        val today = LocalDate.parse(tasks.tashkentDay())
        go { taskId ->
            tasks.due(taskId, v)
            val day = v?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (day != null && day.isAfter(today) && cur.every == null) {
                val where = if (day == today.plusDays(1)) "tomorrow" else "${DatePick.weekday(day)} ${DatePick.dueText(v, today)}"
                toasts.show("Moved to $where", action = "Undo") { viewModelScope.launch { tasks.due(taskId, before) } }
            }
        }
    }

    fun steps(list: List<String>, ticked: List<Int>) = go { tasks.steps(it, list, ticked) }

    /** The web's "Make these steps tasks": each step becomes a P2 task in the same project (not on Today). */
    fun makeStepsTasks() {
        val u = cur
        go {
            for (s in u.steps) today.add(s.text, u.project, 1, null, null, onToday = false)
            toasts.show("${u.steps.size} tasks added · ${u.projectLabel}")
        }
    }

    /** Won't do: null when saved, else why it was refused (said under the field, as the web does). */
    fun notDoing(kind: String, reason: String): String? {
        NotDoing.check(reason)?.let { return it }
        go { tasks.notDoing(it, kind, reason) }
        toasts.show(when (kind) { "drop" -> "Dropped, reason saved"; "someday" -> "Someday, reason saved"; else -> "Blocked, reason saved" })
        return null
    }

    fun complete() = go { taskId ->
        today.tick(taskId, done = true)
        toasts.show("Done", action = "Undo") { viewModelScope.launch { today.tick(taskId, done = false) } }
    }

    fun delete() = go { taskId ->
        val op = tasks.delete(taskId) ?: return@go
        toasts.show("Deleted", action = "Undo") { viewModelScope.launch { tasks.undoDelete(taskId, op) } }
    }

    fun dayToday(): LocalDate = LocalDate.parse(tasks.tashkentDay())
}
