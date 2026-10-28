package org.pagel.ai.ui.today

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.sync.LiveStatus
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.di.IoDispatcher
import org.pagel.ai.ui.projects.line
import javax.inject.Inject

/** One row as Today draws it: the task, its project's tile, and whether it is ticked (folding away). */
data class TodayRow(
    val id: String,
    val title: String,
    val priority: Int,
    val due: String?,
    val every: String?,
    val project: String,
    val projectTitle: String,
    val projectColor: String?,
    val done: Boolean,
)

data class TodayUiState(
    val loading: Boolean = true,
    val rows: List<TodayRow> = emptyList(),
    /** Open tasks on today's list, ticked ones not counted. */
    val open: Int = 0,
    val waiting: Int = 0,
    val allDone: Boolean = false,
    val status: String = "",
    /** Tasks finished today (Tashkent), on Today or anywhere: the progress bar's "2 of 5 ✓". */
    val doneToday: Int = 0,
    /** Days in a row Boss cleared his whole Today (the day-cleared card's 🔥). */
    val clearedStreak: Int = 0,
    /** Changes waiting to reach pagel.uz: the "↑ 2 waiting" chip, tap = send now. */
    val outbox: Int = 0,
    /** Tomorrow's stars (the closed day's folded "Tomorrow · 3"). */
    val tomorrow: List<TodayRow> = emptyList(),
) {
    val progress: DayProgress get() = DayProgress(doneToday, open)
}

/** Today (NATIVE-PLAN Phase 2): reads Room only; a tick answers in one frame and reaches pagel.uz through the outbox. */
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repo: TodayRepository,
    session: Session,
    @ApplicationContext private val ctx: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val finishing = MutableStateFlow<Set<String>>(emptySet())
    private val streak = DayClear.streak
    private val counts = combine(repo.doneToday(), streak, repo.waiting, repo.dated) { done, s, w, dated -> Counts(done, s, w, dated) }
    private data class Counts(val done: Int, val streak: Int, val outbox: Int, val dated: List<org.pagel.ai.data.db.TaskEntity>)

    // A refused tick is said by the shell (ShellViewModel), for every screen at once.
    val ui: StateFlow<TodayUiState> = combine(repo.today, finishing, session.projects(), session.live, counts) { tasks, folding, projects, live, (done, cleared, outbox, dated) ->
        val byName = projects.associateBy(ProjectEntity::name)
        val dealt = dealToday(tasks, folding)
        TodayUiState(
            loading = tasks.isEmpty() && (live is LiveStatus.Idle || live is LiveStatus.Syncing),
            rows = dealt.visible.map { t ->
                val p = byName[t.project]
                TodayRow(t.id, t.title?.takeIf(String::isNotBlank) ?: headline(t.text), t.priority, t.due, t.every, t.project,
                    p?.title ?: t.project, p?.color, done = t.status != "open")
            },
            open = tasks.count { it.status == "open" },
            waiting = dealt.waiting,
            allDone = dealt.allDone,
            status = live.line(),
            doneToday = done,
            clearedStreak = cleared,
            outbox = outbox,
            // The day Close the day wrote (its ritual day + 1), so a close after midnight still peeks (checker #8).
            tomorrow = java.time.LocalDate.parse(org.pagel.ai.data.rituals.Rituals.ritualDay(System.currentTimeMillis())).plusDays(1).toString().let { tm ->
                dated.filter { it.star && it.status == "open" && it.due?.take(10) == tm }.map { t ->
                    val p = byName[t.project]
                    TodayRow(t.id, t.title?.takeIf(String::isNotBlank) ?: headline(t.text), t.priority, t.due, t.every, t.project, p?.title ?: t.project, p?.color, done = false)
                }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    /** Tick: Room changes at once (the row fills and pops), the row keeps its slot for the fold, then the next is dealt. */
    fun tick(id: String) {
        finishing.update { it + id }
        viewModelScope.launch {
            repo.tick(id, done = true)
            delay(FOLD_MS)
            finishing.update { it - id }
        }
    }

    /** The chip's tap: send what waits now, not after the backoff (WorkManager's call blocks: off Main). */
    fun sendNow() {
        viewModelScope.launch(io) { org.pagel.ai.data.today.OutboxWorker.nudge(ctx) }
    }

    /** Undo from the snackbar: if the tick has not left the phone yet, pagel.uz never sees it. */
    fun undo(id: String) {
        finishing.update { it - id }
        viewModelScope.launch { repo.tick(id, done = false) }
    }

    companion object {
        /** The web's finish: ring pop + tick draw, a 0.85 s pause, a 0.38 s fold (globals.css "The finish moment"). */
        const val FOLD_MS = 1_250L
    }
}
