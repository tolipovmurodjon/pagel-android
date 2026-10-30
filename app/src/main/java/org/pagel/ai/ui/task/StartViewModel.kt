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
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.api.TinyBody
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.sync.microOf
import org.pagel.ai.data.tasks.NotDoing
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.ui.glass.ToastCenter
import org.pagel.ai.ui.projects.shortName
import org.pagel.ai.ui.today.headline
import javax.inject.Inject

/** "Make it tiny"'s proposal: nothing yet, the AI writing, steps ready, no internet, or pagel.uz's own words why not. */
sealed interface Tiny {
    data object Idle : Tiny
    data object Writing : Tiny
    data class Ready(val steps: List<String>) : Tiny
    data object Offline : Tiny
    data class Failed(val message: String) : Tiny
}

/** One task as the starting tools see it: I'm stuck, Make it tiny, Focus mode. */
data class StartUi(
    val loading: Boolean = true,
    val id: String = "",
    val title: String = "",
    val project: String = "",
    val projectLabel: String = "",
    val projectColor: String? = null,
    val open: Boolean = true,
    val micro: List<String> = emptyList(),
    val doneWhen: String = "",
    val tiny: Tiny = Tiny.Idle,
)

/** The four plain reasons a task is stuck (core round 4), each a fix in one tap; the event carries the word only. */
enum class StuckReason(val word: String, val label: String, val fix: String) {
    TOO_BIG("too_big", "Too big", "Make it tiny or a project"),
    NOT_CLEAR("not_clear", "Not clear", "Write what done looks like"),
    DONT_WANT("dont_want", "Don't want to", "Just 2 minutes, then stop"),
    WAITING("waiting", "Waiting on someone", "Who, and when to check"),
}

/**
 * The starting machine on one task (core round 4): micro-steps through pagel.uz's AI (`POST /api/v1/tiny`, a proposal;
 * saved as the task's `micro` through the outbox), I'm stuck's four fixes, and Focus mode's ticks. Reads Room; the only
 * network call is the proposal, and without internet it says so in one quiet line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StartViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val today: TodayRepository,
    private val api: PagelApi,
    private val accounts: AccountStore,
    private val toasts: ToastCenter,
    private val projectRepo: org.pagel.ai.data.projects.ProjectRepository,
    session: Session,
) : ViewModel() {
    private val id = MutableStateFlow<String?>(null)
    private val tiny = MutableStateFlow<Tiny>(Tiny.Idle)

    val ui: StateFlow<StartUi> = id.flatMapLatest { taskId ->
        if (taskId == null) flowOf(StartUi())
        else combine(tasks.task(taskId), session.projects(), tiny) { t, projects, proposal ->
            if (t == null) StartUi(loading = false, id = taskId)
            else {
                val p = projects.firstOrNull { it.name == t.project }
                StartUi(
                    loading = false, id = t.id, title = t.title?.takeIf(String::isNotBlank) ?: headline(t.text), project = t.project,
                    projectLabel = shortName(t.project, p?.title ?: t.project), projectColor = p?.color, open = t.status == "open",
                    micro = microOf(t.micro), doneWhen = t.doneWhen.orEmpty(), tiny = proposal,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUi())

    fun bind(taskId: String) { if (id.value != taskId) { id.value = taskId; tiny.value = Tiny.Idle } }

    /** Asks pagel.uz's AI for 3-6 tiny steps at this spice (1 few … 3 tiny tiny). Never a spinner: skeleton lines. */
    fun propose(spice: Int) {
        val taskId = id.value ?: return
        tiny.value = Tiny.Writing
        viewModelScope.launch {
            val account = (accounts.load() as? AccountState.Paired)?.account ?: run { tiny.value = Tiny.Offline; return@launch }
            tiny.value = when (val out = api.tiny(account.serverUrl, account.token, TinyBody(taskId, spice.coerceIn(1, 3)))) {
                is Outcome.Ok -> out.value.steps.map(String::trim).filter(String::isNotEmpty).take(8).let { if (it.isEmpty()) Tiny.Failed("No steps came back · try again") else Tiny.Ready(it) }
                is Outcome.Failed -> when (val e = out.error) {
                    DataError.Network -> Tiny.Offline
                    is DataError.Refused -> Tiny.Failed(e.message)
                    else -> Tiny.Failed("pagel.uz could not write them · try again")
                }
            }
        }
    }

    /** Keeps the proposal as the task's micro-steps (one PATCH {micro}); then Focus mode walks them. */
    fun keepTiny(steps: List<String>, then: () -> Unit) {
        val taskId = id.value ?: return
        viewModelScope.launch {
            tasks.micro(taskId, steps)
            tiny.value = Tiny.Idle
            then()
        }
    }

    fun clearProposal() { tiny.value = Tiny.Idle }

    /** One micro-step ticked in Focus mode: a plain event for the weekly review. */
    fun microDone() = viewModelScope.launch { today.event("micro_done") }

    /** The last micro-step (or a running start) ticked: the task is done, with Undo. */
    fun finishTask() {
        val taskId = id.value ?: return
        viewModelScope.launch {
            today.tick(taskId, done = true)
            val inRow = org.pagel.ai.ui.today.Combo.app.tick(System.currentTimeMillis())
            toasts.show(org.pagel.ai.ui.today.comboText(inRow), action = "Undo") {
                org.pagel.ai.ui.today.Combo.app.undo()
                viewModelScope.launch { today.tick(taskId, done = false) }
            }
        }
    }

    /** I'm stuck: the reason, as one word, for the weekly review. */
    fun stuck(reason: StuckReason) = viewModelScope.launch { today.event("stuck", reason.word) }

    /** Not clear → the finish line ("Done when …"). */
    fun doneWhen(text: String) {
        val taskId = id.value ?: return
        viewModelScope.launch { tasks.doneWhen(taskId, text); toasts.show("Finish line saved") }
    }

    /**
     * Waiting on someone → Waiting for: Blocked with the person as its real reason, off Today, back on the date to check.
     * Null when saved, else why not (a name is needed).
     */
    fun waitingFor(person: String, day: String, dayWords: String): String? {
        val taskId = id.value ?: return "That task is gone."
        val who = person.replace(Regex("\\s+"), " ").trim()
        if (who.isEmpty()) return "Who is it? A name is enough."
        val reason = "Waiting for $who, check $dayWords"
        NotDoing.check(reason)?.let { return it }
        viewModelScope.launch {
            tasks.notDoing(taskId, "waiting", reason)
            tasks.due(taskId, day)
            toasts.show("Waiting for $who · back $dayWords")
        }
        return null
    }

    /** Too big → its own project (the same as the panel's Turn into project). */
    fun turnIntoProject(then: (String) -> Unit) {
        val taskId = id.value ?: return
        viewModelScope.launch {
            val name = projectRepo.turnIntoProject(taskId) { text, project -> today.add(text, project, 2, null, null, onToday = false) } ?: return@launch
            toasts.show("Now a project")
            then(name)
        }
    }
}
