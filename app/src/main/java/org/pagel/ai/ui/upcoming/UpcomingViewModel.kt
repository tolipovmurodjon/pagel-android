package org.pagel.ai.ui.upcoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.sync.LiveStatus
import org.pagel.ai.data.upcoming.DoneGroup
import org.pagel.ai.data.upcoming.UpcomingRepository
import org.pagel.ai.data.upcoming.UpcomingView
import org.pagel.ai.data.upcoming.completedGroups
import org.pagel.ai.data.upcoming.doneCount
import org.pagel.ai.data.upcoming.upcomingView
import org.pagel.ai.ui.glass.ToastCenter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** Tashkent's today, again each minute, so a screen left open over midnight moves on to the new day. */
internal fun UpcomingRepository.days(): Flow<LocalDate> = flow { while (true) { emit(today()); delay(60_000) } }.distinctUntilChanged()

data class UpcomingUi(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.of(2026, 1, 1),
    val view: UpcomingView = UpcomingView(emptyList(), emptyList()),
    val projects: Map<String, ProjectEntity> = emptyMap(),
    /** Tasks whose ring is filling (done from here): they keep their row for the finish moment. */
    val finishing: Set<String> = emptySet(),
)

private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/** Upcoming + Overdue (Phase 4, the web's /upcoming): reads Room; a drop or a move answers at once, the outbox sends it. */
@HiltViewModel
class UpcomingViewModel @Inject constructor(
    private val repo: UpcomingRepository,
    private val toasts: ToastCenter,
    session: Session,
) : ViewModel() {
    /** Done here and still folding: Room already has them done, so their last open copy keeps the row for the fold. */
    private val finishing = MutableStateFlow<Map<String, TaskEntity>>(emptyMap())

    val ui: StateFlow<UpcomingUi> = combine(repo.dated, repo.days(), session.projects(), session.live, finishing) { tasks, today, projects, live, folding ->
        val ids = tasks.mapTo(HashSet()) { it.id }
        UpcomingUi(
            loading = tasks.isEmpty() && (live is LiveStatus.Idle || live is LiveStatus.Syncing),
            today = today,
            view = upcomingView(tasks + folding.values.filter { it.id !in ids }, today),
            projects = projects.associateBy(ProjectEntity::name),
            finishing = folding.keys,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpcomingUi())

    /** Dropped on a day of the strip: Room first, the PATCH waits in the outbox; Undo puts the old date back. */
    fun reschedule(id: String, day: LocalDate) {
        viewModelScope.launch {
            val m = repo.reschedule(id, day) ?: return@launch
            toasts.show("Moved to ${dayWord(day)}", action = "Undo") { viewModelScope.launch { repo.undoMoves(listOf(m)) } }
        }
    }

    fun moveAllToToday(ids: List<String>) {
        viewModelScope.launch {
            val moved = repo.moveAllToToday(ids)
            if (moved.isEmpty()) return@launch
            toasts.show(if (moved.size == 1) "Moved to today" else "${moved.size} moved to today", action = "Undo") {
                viewModelScope.launch { repo.undoMoves(moved) }
            }
        }
    }

    /** Done from Upcoming: the ring fills, the row folds, then it leaves; Undo opens it again. */
    fun finish(id: String) {
        val t = (ui.value.view.overdue + ui.value.view.days.flatMap { it.tasks }).firstOrNull { it.id == id } ?: return
        finishing.update { it + (id to t) }
        viewModelScope.launch {
            val op = repo.finish(id)
            if (op != null) toasts.show("Done", action = "Undo") { finishing.update { it - id }; viewModelScope.launch { repo.undoFinish(id, op) } }
            delay(FOLD_MS)
            finishing.update { it - id }
        }
    }

    private fun dayWord(day: LocalDate): String {
        val today = ui.value.today
        return when (day) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> day.format(SHORT_DAY)
        }
    }

    companion object {
        const val FOLD_MS = 1_250L
    }
}

data class CompletedUi(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.of(2026, 1, 1),
    val groups: List<DoneGroup> = emptyList(),
    val count: Int = 0,
    val projects: Map<String, ProjectEntity> = emptyMap(),
    /** Unticked here and still folding: Room has them open already, their done copy keeps the row for the fold. */
    val reopening: Set<String> = emptySet(),
)

/** pagel.uz's count of finished tasks and how many Room held when it came (see [doneCount]). */
internal data class ServerCount(val total: Int, val roomThen: Int)

/** Completed (Phase 4, the web's /completed): by Tashkent day from Room; untick opens a task again through the outbox. */
@HiltViewModel
class CompletedViewModel @Inject constructor(
    private val repo: UpcomingRepository,
    private val accounts: AccountStore,
    private val toasts: ToastCenter,
    session: Session,
) : ViewModel() {
    private val reopening = MutableStateFlow<Map<String, TaskEntity>>(emptyMap())
    private val server = MutableStateFlow<ServerCount?>(null)

    val ui: StateFlow<CompletedUi> = combine(
        combine(repo.completed, reopening, server) { a, b, c -> Triple(a, b, c) }, repo.days(), session.projects(), session.live,
    ) { room: Triple<List<TaskEntity>, Map<String, TaskEntity>, ServerCount?>, today, projects, live ->
        val (done, folding, counted) = room
        val ids = done.mapTo(HashSet()) { it.id }
        CompletedUi(
            loading = done.isEmpty() && folding.isEmpty() && (live is LiveStatus.Idle || live is LiveStatus.Syncing),
            today = today,
            groups = completedGroups(done + folding.values.filter { it.id !in ids }, today),
            count = doneCount(done.size, counted?.total, counted?.roomThen ?: 0),
            projects = projects.associateBy(ProjectEntity::name),
            reopening = folding.keys,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompletedUi())

    init {
        // The older ones (/sync keeps 14 days) and pagel.uz's count of all; offline, Room's copy stays and nothing is said.
        viewModelScope.launch {
            val account = (accounts.load() as? AccountState.Paired)?.account ?: return@launch
            val r = repo.refreshCompleted(account)
            val total = (r as? Outcome.Ok)?.value ?: return@launch
            server.value = ServerCount(total, repo.completed.first().size)
        }
    }

    /** The check empties, the row folds, then it leaves (checker P4 #5: it vanished in one frame); Undo puts it back. */
    fun reopen(id: String) {
        val t = ui.value.groups.firstNotNullOfOrNull { g -> g.tasks.firstOrNull { it.id == id } } ?: return
        reopening.update { it + (id to t) }
        viewModelScope.launch {
            val op = repo.reopen(id)
            if (op == null) { reopening.update { it - id }; return@launch }
            toasts.show("Back on your list", action = "Undo") { reopening.update { it - id }; viewModelScope.launch { repo.undoReopen(id, op) } }
            delay(REOPEN_MS)
            reopening.update { it - id }
        }
    }

    companion object {
        const val REOPEN_MS = 650L
    }
}
