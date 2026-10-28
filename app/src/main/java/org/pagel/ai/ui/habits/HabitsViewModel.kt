package org.pagel.ai.ui.habits

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.HabitsDay
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.data.habits.habitsDay
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.sync.LiveStatus
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.di.IoDispatcher
import org.pagel.ai.ui.glass.ToastCenter
import javax.inject.Inject

data class HabitsUi(
    val loading: Boolean = true,
    val today: String = "",
    val day: HabitsDay = HabitsDay(emptyList(), emptyList(), emptyList(), 0, 0),
    /** Habits whose ring is filling: they keep their row until the fold, then move to "Done today". */
    val finishing: Set<String> = emptySet(),
    val reminders: Set<String> = emptySet(),
    val projects: Map<String, ProjectEntity> = emptyMap(),
    val byId: Map<String, TaskEntity> = emptyMap(),
)

/** Habits (NATIVE-PLAN Phase 3, the web's /habits): reads Room only; a tick answers in one frame, the outbox sends it. */
@HiltViewModel
class HabitsViewModel @Inject constructor(
    private val repo: HabitsRepository,
    private val tasks: TaskRepository,
    private val accounts: AccountStore,
    private val toasts: ToastCenter,
    @ApplicationContext private val ctx: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    session: Session,
) : ViewModel() {
    private val finishing = MutableStateFlow<Set<String>>(emptySet())
    private val _celebrate = Channel<Unit>(Channel.BUFFERED)
    /** The day's last habit ticked: ding and confetti, once a day on this phone (the web's AllDone). */
    val celebrate: Flow<Unit> = _celebrate.receiveAsFlow()
    private var celebratedDay: String? = null
    private val prefs by lazy { ctx.getSharedPreferences("pagel-habits", Context.MODE_PRIVATE) }

    private val dayFlow = combine(repo.habits, repo.ticks(), finishing) { h, t, f -> Triple(h, t, f) }

    val ui: StateFlow<HabitsUi> = combine(dayFlow, session.projects(), repo.reminderRows, session.live) { (habits, ticks, folding), projects, reminders, live ->
        val today = repo.today()
        HabitsUi(
            loading = habits.isEmpty() && (live is LiveStatus.Idle || live is LiveStatus.Syncing),
            today = today,
            day = habitsDay(habits, ticks, today, folding),
            finishing = folding,
            reminders = reminders.filter { it.enabled }.map { it.taskId }.toSet(),
            projects = projects.associateBy(ProjectEntity::name),
            byId = habits.associateBy(TaskEntity::id),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsUi())

    init {
        refresh()
        repo.replan()
        viewModelScope.launch {
            ui.collect { u ->
                if (!u.day.allDone || u.finishing.isNotEmpty() || celebratedDay == u.today) return@collect
                celebratedDay = u.today
                val first = withContext(io) {
                    val key = "done-${u.today}"
                    if (prefs.getBoolean(key, false)) false else { prefs.edit().putBoolean(key, true).apply(); true }
                }
                if (first) _celebrate.send(Unit)
            }
        }
    }

    /** pagel.uz's ticks (streaks, done today); offline the saved ones stay and nothing is said. */
    fun refresh() {
        viewModelScope.launch {
            val account = (accounts.load() as? AccountState.Paired)?.account ?: return@launch
            repo.refresh(account)
        }
    }

    fun replan() = repo.replan()

    fun tick(id: String) {
        finishing.update { it + id }
        viewModelScope.launch {
            val t = repo.tick(id)
            if (t == null) { finishing.update { it - id }; return@launch }
            toasts.show("Done", action = "Undo") { undo(id, t) }
            delay(FOLD_MS)
            finishing.update { it - id }
        }
    }

    private fun undo(id: String, t: org.pagel.ai.data.habits.Ticked) {
        finishing.update { it - id }
        viewModelScope.launch { repo.undo(id, t) }
    }

    fun setReminder(id: String, on: Boolean) {
        viewModelScope.launch { repo.setReminder(id, on) }
    }

    /** A new time from the wheel: the habit's date keeps its day (today if it had none), the time changes. */
    fun setTime(id: String, time: String) {
        viewModelScope.launch {
            val t = ui.value.byId[id] ?: return@launch
            tasks.due(id, (t.due?.take(10) ?: repo.today()) + " " + time)
            repo.replan()
        }
    }

    /** The exact-alarm grant is asked once ever, at the first reminder Boss turns on, never in a loop. */
    fun askExactOnce(needed: Boolean, open: () -> Unit) {
        if (!needed) return
        viewModelScope.launch {
            val first = withContext(io) { if (prefs.getBoolean(ASKED_EXACT, false)) false else { prefs.edit().putBoolean(ASKED_EXACT, true).apply(); true } }
            if (first) open()
        }
    }

    companion object {
        /** Today's finish: ring pop + tick draw, a pause, then the row moves (the same beat as Today). */
        const val FOLD_MS = 1_250L
        private const val ASKED_EXACT = "asked-exact-alarm"
    }
}
