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
    /** Every open dated task (the calendar's views reach past the next 7 days). */
    val tasks: List<TaskEntity> = emptyList(),
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
            tasks = tasks + folding.values.filter { it.id !in ids },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpcomingUi())

    /** Dropped on a day of the strip: Room first, the PATCH waits in the outbox; Undo puts the old date back. */
    fun reschedule(id: String, day: LocalDate) {
        viewModelScope.launch {
            val m = repo.reschedule(id, day) ?: return@launch
            toasts.show("Moved to ${dayWord(day)}", action = "Undo") { viewModelScope.launch { repo.undoMoves(listOf(m)) } }
        }
    }

    /** A calendar block dropped on a new time or day: Room first, the PATCH waits in the outbox; Undo puts it back. */
    fun moveTo(id: String, due: String) {
        viewModelScope.launch {
            val m = repo.moveTo(id, due) ?: return@launch
            val day = LocalDate.parse(due.take(10))
            toasts.show("Moved to ${dayWord(day)} ${due.drop(11).replace('-', '–')}".trim(), action = "Undo") { viewModelScope.launch { repo.undoMoves(listOf(m)) } }
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
    /** Counts the Undos of "Clear done": the screen fades the black hole out when it changes. */
    val clearUndone: Int = 0,
    /** "Clear done" emptied the list here: it says All clear, and the count is the list's (older ones stay on pagel.uz). */
    val allClear: Boolean = false,
)

/** Completed's Room side, for one combine: the done tasks, the folding ones, pagel.uz's count, the clear Undos. */
private data class CompletedRoom(val done: List<TaskEntity>, val folding: Map<String, TaskEntity>, val counted: ServerCount?, val undone: Int, val cleared: Boolean)

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
    private val clearUndone = MutableStateFlow(0)
    /** True from a "Clear done" until its Undo: the count follows the list, not pagel.uz's total. */
    private val clearedHere = MutableStateFlow(false)

    val ui: StateFlow<CompletedUi> = combine(
        combine(repo.completed, reopening, server, clearUndone, clearedHere) { a, b, c, d, e -> CompletedRoom(a, b, c, d, e) },
        repo.days(), session.projects(), session.live,
    ) { room, today, projects, live ->
        val (done, folding, counted, undone, cleared) = room
        val ids = done.mapTo(HashSet()) { it.id }
        val rows = done + folding.values.filter { it.id !in ids }
        CompletedUi(
            loading = !cleared && done.isEmpty() && folding.isEmpty() && (live is LiveStatus.Idle || live is LiveStatus.Syncing),
            today = today,
            groups = completedGroups(rows, today),
            count = if (cleared) rows.size else doneCount(done.size, counted?.total, counted?.roomThen ?: 0),
            projects = projects.associateBy(ProjectEntity::name),
            reopening = folding.keys,
            clearUndone = undone,
            allClear = cleared && rows.isEmpty(),
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

    /**
     * "Clear done": every finished task Completed shows leaves Room at once (the black hole only draws over a snapshot
     * taken before), each remove waits 5 s on the phone; Undo brings them all back done. Returns how many left.
     */
    fun clearDone(): Int {
        val ids = ui.value.groups.flatMap { g -> g.tasks.map { it.id } }.filter { it !in ui.value.reopening }
        if (ids.isEmpty()) return 0
        clearedHere.value = true
        viewModelScope.launch {
            val cleared = repo.clearDone(ids)
            if (cleared.isEmpty()) return@launch
            val words = if (cleared.size == 1) "Cleared 1 done task" else "Cleared ${cleared.size} done tasks"
            toasts.show(words, action = "Undo", millis = CLEAR_UNDO_MS) {
                clearUndone.update { it + 1 }
                clearedHere.value = false
                viewModelScope.launch { repo.undoClear(cleared) }
            }
        }
        return ids.size
    }

    companion object {
        const val REOPEN_MS = 650L
        /** A bulk clear keeps its Undo longer than one tick's 4 s: it is the bigger mistake to make by accident. */
        const val CLEAR_UNDO_MS = 30_000L
    }
}
