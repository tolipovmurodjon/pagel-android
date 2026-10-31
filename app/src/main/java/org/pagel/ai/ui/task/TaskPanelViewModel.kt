package org.pagel.ai.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** When it was finished (UTC instant); the panel of a done task is read only and offers Reopen. */
    val doneAt: String? = null,
    val steps: List<PanelStep> = emptyList(),
    /** Some edit waits for pagel.uz to learn the new fields (an older server): a cloud-off mark says so. */
    val held: Boolean = false,
    /** Open tasks pinned to Today by a star, this one included: at 3 the pin switch is off and says why. */
    val pinned: Int = 0,
    /** A habit's taps a day (1 = once). */
    val times: Int = 1,
    /** Micro-steps "Make it tiny" wrote (core round 4): the panel row says "Tiny steps · 4" and opens Focus. */
    val microCount: Int = 0,
    /** Its project holds lessons (a course): the panel offers Lesson steps as a chip ([looksLikeLessons]). */
    val lessons: Boolean = false,
    /** A daily habit: the panel edits it with the habit rows (Time, Repeat, Reminder, After) instead of Date and Priority. */
    val habit: Boolean = false,
)

/** The web's rule: at most 3 tasks pinned to Today by hand (a date puts more there by itself). */
const val MAX_PINNED = 3

/** The task panel (NATIVE-PLAN Phase 1): reads Room only; every edit answers at once and saves behind. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskPanelViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val today: TodayRepository,
    private val toasts: ToastCenter,
    session: Session,
    private val projectRepo: org.pagel.ai.data.projects.ProjectRepository,
) : ViewModel() {
    private val id = MutableStateFlow<String?>(null)

    val ui: StateFlow<PanelUi> = id.flatMapLatest { taskId ->
        if (taskId == null) flowOf(PanelUi())
        else combine(tasks.task(taskId), tasks.steps(taskId), tasks.held(taskId), session.projects(), tasks.pinned()) { t, steps, held, projects, pinned ->
            if (t == null) PanelUi(loading = false, id = taskId)
            else {
                val p = projects.firstOrNull { it.name == t.project }
                PanelUi(
                    loading = false, id = t.id, project = t.project, projectLabel = shortName(t.project, p?.title ?: t.project), projectColor = p?.color,
                    title = t.title?.takeIf(String::isNotBlank) ?: headline(t.text), text = t.text, doneWhen = t.doneWhen.orEmpty(),
                    due = t.due, every = t.every, priority = t.priority, star = t.star, open = t.status == "open", doneAt = t.doneAt,
                    steps = steps.map { PanelStep(it.text, it.done) }, held = held > 0, pinned = pinned, times = t.times,
                    microCount = org.pagel.ai.data.sync.microOf(t.micro).size,
                    habit = org.pagel.ai.data.habits.HabitLook.isDailyHabit(t),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PanelUi())

    val projects: StateFlow<List<ProjectEntity>> = session.projects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether the task's project reads like a course; looked up again only when the task changes project. */
    val lessons: StateFlow<Boolean> = ui.map { it.project to it.projectLabel }.distinctUntilChanged().flatMapLatest { (project, label) ->
        if (project.isEmpty()) flowOf(false) else tasks.projectTasks(project).map { list -> looksLikeLessons(label, list.map { it.text }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun bind(taskId: String) { id.value = taskId }

    private val cur get() = ui.value
    private fun go(block: suspend (String) -> Unit) { val t = id.value ?: return; viewModelScope.launch { block(t) } }

    fun title(v: String) = go { tasks.title(it, v) }
    fun text(v: String) = go { tasks.text(it, v) }
    fun doneWhen(v: String) = go { tasks.doneWhen(it, v) }
    fun priority(p: Int) = go { tasks.priority(it, p) }
    fun star(on: Boolean) = go { tasks.star(it, on) }
    fun every(v: String?) = go { tasks.every(it, v) }
    fun times(n: Int) = go { tasks.times(it, n) }

    /** A date later than today takes the task off Today: say where it went, with Undo (the web's "Shows on its date"). */
    fun due(v: String?) {
        val before = cur.due
        val today = LocalDate.parse(tasks.tashkentDay())
        go { taskId ->
            tasks.due(taskId, v)
            val day = v?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            // A starred task stays on Today whatever its date (views.ts): only say "moved" when it really left.
            if (day != null && day.isAfter(today) && cur.every == null && !cur.star) {
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

    /**
     * The ring in the panel: the same finish as Today (2026-10-28, Boss: "no sound, no toast, not marking strike"). The
     * panel used to close in the same frame, which cleared this ViewModel and cancelled the tick before Room heard of it;
     * now the panel stays open on the done state (strike-through, Reopen) and the toast carries Undo.
     */
    fun complete() = go { taskId ->
        today.tick(taskId, done = true)
        val inRow = org.pagel.ai.ui.today.Combo.app.tick(System.currentTimeMillis())
        toasts.show(org.pagel.ai.ui.today.comboText(inRow), action = "Undo") {
            org.pagel.ai.ui.today.Combo.app.undo()
            viewModelScope.launch { today.tick(taskId, done = false) }
        }
    }

    /** A done task opened again from its panel (pagel.uz's undone), with Undo. */
    fun reopen() = go { taskId ->
        val op = tasks.reopen(taskId) ?: return@go
        toasts.show("Back on your list", action = "Undo") { viewModelScope.launch { if (!tasks.takeBack(taskId, op)) tasks.finish(taskId) } }
    }

    /** The project capsule's picker: the task moves to [project] at once, with Undo (taken back if it has not left yet). */
    fun moveTo(project: String) {
        val from = cur.project
        val label = projects.value.firstOrNull { it.name == project }?.let { shortName(it.name, it.title) } ?: project
        go { taskId ->
            val op = tasks.move(taskId, project) ?: return@go
            toasts.show("Moved to $label", action = "Undo", millis = 5_000) { viewModelScope.launch { if (!tasks.takeBack(taskId, op)) tasks.move(taskId, from) } }
        }
    }

    fun delete() = go { taskId ->
        val op = tasks.delete(taskId) ?: return@go
        toasts.show("Deleted", action = "Undo") { viewModelScope.launch { tasks.undoDelete(taskId, op) } }
    }

    /** "Turn into project": its open steps become the new project's tasks (the first is the next step). */
    fun turnIntoProject(then: (String) -> Unit) = go { taskId ->
        val name = projectRepo.turnIntoProject(taskId) { text, project -> today.add(text, project, 2, null, null, onToday = false) } ?: return@go
        toasts.show("Now a project")
        then(name)
    }

    fun dayToday(): LocalDate = LocalDate.parse(tasks.tashkentDay())
}
