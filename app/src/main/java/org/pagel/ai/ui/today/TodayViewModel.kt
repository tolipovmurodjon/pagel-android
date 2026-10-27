package org.pagel.ai.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.sync.LiveStatus
import org.pagel.ai.data.today.TodayRepository
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
    /** A tick pagel.uz refused: already rolled back in Room; the screen says why once, then [TodayViewModel.acknowledge]. */
    val refused: OutboxEntity? = null,
)

/** Today (NATIVE-PLAN Phase 2): reads Room only; a tick answers in one frame and reaches pagel.uz through the outbox. */
@HiltViewModel
class TodayViewModel @Inject constructor(private val repo: TodayRepository, session: Session) : ViewModel() {
    private val finishing = MutableStateFlow<Set<String>>(emptySet())

    val ui: StateFlow<TodayUiState> = combine(repo.today, repo.refused, finishing, session.projects(), session.live) { tasks, refused, folding, projects, live ->
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
            refused = refused.firstOrNull(),
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

    /** Undo from the snackbar: if the tick has not left the phone yet, pagel.uz never sees it. */
    fun undo(id: String) {
        finishing.update { it - id }
        viewModelScope.launch { repo.tick(id, done = false) }
    }

    fun acknowledge(op: OutboxEntity) {
        viewModelScope.launch { repo.acknowledge(op) }
    }

    companion object {
        /** The web's finish: ring pop + tick draw, a 0.85 s pause, a 0.38 s fold (globals.css "The finish moment"). */
        const val FOLD_MS = 1_250L
    }
}
