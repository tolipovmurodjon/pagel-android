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
import kotlinx.coroutines.flow.first
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

/** What now's one pick: the task, one WHY line (its project) and its tiny first step. */
data class NowPick(val id: String, val title: String, val why: String, val first: String, val project: String, val projectColor: String?, val priority: Int)

/** What now's sheet, one stage at a time: the energy tap (once a day), the pick, nothing fits, the running start. */
data class WhatNowUi(
    val stage: Stage = Stage.CLOSED,
    val pick: NowPick? = null,
    /** Picks shown so far (at most [MAX_PICKS]). */
    val shown: Int = 0,
    /** When "Start · 10 min" was tapped (ms); the clock runs from it. */
    val startedAt: Long = 0,
    val minutes: Int = START_MINUTES,
) {
    enum class Stage { CLOSED, ENERGY, PICK, NONE, TIMER }
}

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
    /** Every open task of today in Boss's order (the "All today · N" list), with each one's order key. */
    val all: List<TodayRow> = emptyList(),
    val allKeys: List<Double> = emptyList(),
    /** The full list is open (remembered for today only). */
    val expanded: Boolean = false,
) {
    val progress: DayProgress get() = DayProgress(doneToday, open)
}

/** Today (NATIVE-PLAN Phase 2): reads Room only; a tick answers in one frame and reaches pagel.uz through the outbox. */
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repo: TodayRepository,
    private val tasks: org.pagel.ai.data.tasks.TaskRepository,
    private val toasts: org.pagel.ai.ui.glass.ToastCenter,
    session: Session,
    @ApplicationContext private val ctx: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val finishing = MutableStateFlow<Set<String>>(emptySet())
    private val streak = DayClear.streak
    private val counts = combine(repo.doneToday(), streak, repo.waiting, repo.dated) { done, s, w, dated -> Counts(done, s, w, dated) }
    private data class Counts(val done: Int, val streak: Int, val outbox: Int, val dated: List<org.pagel.ai.data.db.TaskEntity>)

    private val day = repo.day()
    /** Today in Boss's order (his drags over pagel.uz's places), the order keys, and today's screen state. */
    private val ordered = combine(repo.today, repo.todaySorts(day), repo.state(day)) { list, sorts, state ->
        Ordered(orderToday(list, sorts), todayKeys(list, sorts), state.expanded)
    }
    private data class Ordered(val tasks: List<org.pagel.ai.data.db.TaskEntity>, val keys: Map<String, Double>, val expanded: Boolean)

    // A refused tick is said by the shell (ShellViewModel), for every screen at once.
    val ui: StateFlow<TodayUiState> = combine(ordered, finishing, session.projects(), session.live, counts) { (tasks, keys, expanded), folding, projects, live, (done, cleared, outbox, dated) ->
        val byName = projects.associateBy(ProjectEntity::name)
        val dealt = dealToday(tasks, folding)
        val queue = tasks.filter { it.status == "open" || it.id in folding }
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
            all = queue.map { t ->
                val p = byName[t.project]
                TodayRow(t.id, t.title?.takeIf(String::isNotBlank) ?: headline(t.text), t.priority, t.due, t.every, t.project,
                    p?.title ?: t.project, p?.color, done = t.status != "open")
            },
            allKeys = queue.map { keys[it.id] ?: 0.0 },
            expanded = expanded,
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

    /** "All today · N" opens the full list in place; the chevron folds it back to 3 (remembered for today only). */
    fun expand(on: Boolean) {
        viewModelScope.launch { repo.setExpanded(on) }
    }

    /**
     * A drag on the full list: the row from [from] lands at [to]. One sort between its new neighbours, one PATCH of that
     * row; Undo sends its old place back (a real inverse write).
     */
    fun reorder(from: Int, to: Int) {
        val u = ui.value
        val row = u.all.getOrNull(from) ?: return
        if (from == to || to !in u.all.indices || u.allKeys.size != u.all.size) return
        val sort = todaySortAfterMove(u.allKeys, from, to)
        viewModelScope.launch {
            val old = repo.todaySortOf(row.id, day)
            val op = tasks.todaySort(row.id, day, sort) ?: return@launch
            repo.event("today_reorder")
            toasts.show("Moved to #${to + 1}", action = "Undo") { viewModelScope.launch { tasks.undoTodaySort(row.id, op, day, old) } }
        }
    }

    // What now? ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private val _now = MutableStateFlow(WhatNowUi())
    val whatNow: StateFlow<WhatNowUi> = _now
    private val skipped = mutableSetOf<String>()

    /** The calm button: the energy tap first (once a day), then one pick. */
    fun openWhatNow() {
        skipped.clear()
        viewModelScope.launch {
            val energy = repo.state(day).first().energy
            if (Energy.of(energy) == null) _now.value = WhatNowUi(WhatNowUi.Stage.ENERGY) else pick(0)
        }
    }

    fun energy(e: Energy) {
        viewModelScope.launch {
            repo.setEnergy(e.word)
            pick(0)
        }
    }

    /** "Not this": the next pick; after [MAX_PICKS] it says nothing else fits, never a list. */
    fun notThis() {
        val p = _now.value.pick ?: return
        skipped += p.id
        viewModelScope.launch {
            repo.event("what_now_skip")
            if (_now.value.shown >= MAX_PICKS) _now.value = _now.value.copy(stage = WhatNowUi.Stage.NONE, pick = null) else pick(_now.value.shown)
        }
    }

    private suspend fun pick(shownBefore: Int) {
        val u = ui.value
        val energy = Energy.of(repo.state(day).first().energy) ?: Energy.OK
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Tashkent"))
        val today = now.toLocalDate()
        val free = freeMinutes(u.all.map { it.due } + repo.dated.first().map { it.due }, today, now.toLocalTime())
        val r = whatNow(u.all, energy, today, now.toLocalTime(), free, skipped)
        if (r == null) { _now.value = WhatNowUi(WhatNowUi.Stage.NONE, shown = shownBefore); return }
        val t = tasks.task(r.id).first()
        val steps = tasks.steps(r.id).first().filter { !it.done }.map { it.text }
        val first = firstStepOf(org.pagel.ai.data.sync.microOf(t?.micro), steps)
        _now.value = WhatNowUi(WhatNowUi.Stage.PICK, NowPick(r.id, r.title, r.projectTitle, first, r.project, r.projectColor, r.priority), shown = shownBefore + 1)
        repo.event("what_now_pick")
    }

    /** "Start · 10 min": the clock runs; the tick ends it in the finish moment. */
    fun startNow() {
        _now.value = _now.value.copy(stage = WhatNowUi.Stage.TIMER, startedAt = System.currentTimeMillis(), minutes = START_MINUTES)
    }

    /** The running start's tick: the task is done (the same tick as Today's ring) and the sheet closes. */
    fun finishNow(): String? {
        val id = _now.value.pick?.id ?: return null
        _now.value = WhatNowUi()
        tick(id)
        return id
    }

    fun closeWhatNow() { _now.value = WhatNowUi() }

    companion object {
        /** The web's finish: ring pop + tick draw, a 0.85 s pause, a 0.38 s fold (globals.css "The finish moment"). */
        const val FOLD_MS = 1_250L
    }
}
