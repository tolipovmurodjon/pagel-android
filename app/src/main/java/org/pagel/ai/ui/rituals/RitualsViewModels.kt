package org.pagel.ai.ui.rituals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.api.StuckDto
import org.pagel.ai.data.api.SweepRowDto
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.db.SweepEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.inbox.InboxRepository
import org.pagel.ai.data.rituals.Added
import org.pagel.ai.data.rituals.Closed
import org.pagel.ai.data.rituals.Kind
import org.pagel.ai.data.rituals.Move
import org.pagel.ai.data.rituals.Rituals
import org.pagel.ai.data.rituals.RitualsRepository
import org.pagel.ai.data.rituals.rowList
import org.pagel.ai.data.rituals.stuckList
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.sync.LiveStatus
import org.pagel.ai.ui.glass.ToastCenter
import org.pagel.ai.ui.today.headline
import javax.inject.Inject

/** A task as the rituals draw it: its words, its project's tile, its flag. */
data class RitualTask(val id: String, val title: String, val project: String, val projectTitle: String, val projectColor: String?, val priority: Int)

internal fun TaskEntity.ritualTask(projects: Map<String, ProjectEntity>) = RitualTask(
    id, title?.takeIf(String::isNotBlank) ?: headline(text), project, projects[project]?.title ?: project, projects[project]?.color, priority,
)

/** pagel.uz's view of the rituals, shared by Today's pills, Close the day and the review: refreshed when one opens. */
private suspend fun refreshFrom(accounts: AccountStore, repo: RitualsRepository) {
    val account = (accounts.load() as? AccountState.Paired)?.account ?: return
    repo.refresh(account)
}

// ── Today's entry points ─────────────────────────────────────────────────────────────────────────────────────────

/** Today's ritual pills: closed tonight (✓), the weekly review due (the web's nudge, only once pagel.uz has been asked). */
data class RitualPills(val shutTonight: Boolean = false, val reviewDue: Boolean = false)

@HiltViewModel
class RitualPillsViewModel @Inject constructor(private val repo: RitualsRepository, accounts: AccountStore) : ViewModel() {
    val pills: StateFlow<RitualPills> = repo.state.map { s ->
        val day = repo.ritualDay()
        RitualPills(shutTonight = s.shutDay == day, reviewDue = (s.fetchedAt > 0 || s.lastReview != null) && Rituals.reviewDue(s.lastReview, day))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RitualPills())

    init {
        viewModelScope.launch { refreshFrom(accounts, repo) }
    }
}

// ── Empty your head ──────────────────────────────────────────────────────────────────────────────────────────────

data class SweepUi(
    val loading: Boolean = true,
    val id: String? = null,
    val dump: String = "",
    val state: String = SweepEntity.DRAFT,
    val rows: List<SweepRowDto> = emptyList(),
    val message: String? = null,
    val online: Boolean = true,
    val projects: List<ProjectEntity> = emptyList(),
    val added: Added? = null,
) {
    val keep: Int get() = Rituals.kept(rows).size
    val tooLong: Boolean get() = rows.any(Rituals::tooLong)
}

@HiltViewModel
class SweepViewModel @Inject constructor(private val repo: RitualsRepository, private val toasts: ToastCenter, session: Session) : ViewModel() {
    private val added = MutableStateFlow<Added?>(null)
    private val drafts = Channel<String>(Channel.CONFLATED)
    private val lock = Mutex()
    private var draftId: String? = null

    val ui: StateFlow<SweepUi> = combine(repo.sweep, session.projects(), session.live, added) { s, projects, live, done ->
        if (s?.state == SweepEntity.DRAFT && draftId == null) draftId = s.id
        SweepUi(
            loading = false, id = s?.id, dump = s?.dump.orEmpty(), state = s?.state ?: SweepEntity.DRAFT, rows = s?.rowList().orEmpty(),
            message = s?.message, online = live !is LiveStatus.Offline, projects = projects, added = done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SweepUi())

    init {
        // Typing is saved 0.4 s after the last key, in order, so a closed app keeps every word.
        viewModelScope.launch {
            for (text in drafts) { delay(400); save(text) }
        }
    }

    private suspend fun save(text: String): String = lock.withLock { repo.saveDraft(draftId, text).also { draftId = it } }

    fun type(text: String) { drafts.trySend(text) }

    fun split(text: String) {
        viewModelScope.launch { repo.split(save(text)) }
    }

    fun retry() { ui.value.id?.let { id -> viewModelScope.launch { repo.split(id) } } }

    fun back() { ui.value.id?.let { id -> viewModelScope.launch { repo.backToText(id) } } }

    private fun edit(change: (MutableList<SweepRowDto>) -> Unit) {
        val id = ui.value.id ?: return
        val rows = ui.value.rows.toMutableList().also(change)
        viewModelScope.launch { repo.setRows(id, rows) }
    }

    fun setKind(i: Int, kind: Kind) = edit { if (i in it.indices) it[i] = it[i].copy(kind = kind.wire) }
    fun setText(i: Int, text: String) = edit { if (i in it.indices) it[i] = it[i].copy(text = text) }
    fun setProject(i: Int, project: String) = edit { if (i in it.indices) it[i] = it[i].copy(project = project) }
    fun remove(i: Int) = edit { if (i in it.indices) it.removeAt(i) }

    fun add() {
        val id = ui.value.id ?: return
        viewModelScope.launch {
            val done = repo.add(id) ?: return@launch
            added.value = done
            draftId = null
            val n = done.actions + done.someday
            toasts.show(if (n == 1) "1 added" else "$n added", action = "Undo") { undoAdd(done) }
        }
    }

    private fun undoAdd(done: Added) {
        viewModelScope.launch {
            repo.undoAdd(done.sweep)
            draftId = null
            added.value = null
        }
    }

    /** "Empty it again": a fresh box. */
    fun again() {
        draftId = null
        added.value = null
    }
}

// ── Close the day ────────────────────────────────────────────────────────────────────────────────────────────────

data class ShutdownUi(
    val loading: Boolean = true,
    val stars: List<RitualTask> = emptyList(),
    val candidates: List<RitualTask> = emptyList(),
    val moves: Map<String, Move> = emptyMap(),
    /** Which star is being decided; [stars].size = the picks. */
    val step: Int = 0,
    val picks: List<String> = emptyList(),
    val done: Boolean = false,
    val shutTonight: Boolean = false,
    val eveningOn: Boolean = true,
    val eveningAt: String = Rituals.EVENING,
) {
    val deciding: Boolean get() = step < stars.size
    val tomorrow: Int get() = stars.count { (moves[it.id] ?: Move.KEEP) == Move.KEEP } + picks.size
}

@HiltViewModel
class ShutdownViewModel @Inject constructor(
    private val repo: RitualsRepository,
    private val toasts: ToastCenter,
    accounts: AccountStore,
    session: Session,
) : ViewModel() {
    /** The lists are taken once when the ritual opens: a sync while Boss decides never moves a card under his finger. */
    private val snapshot = MutableStateFlow<Pair<List<TaskEntity>, List<TaskEntity>>?>(null)
    private val local = MutableStateFlow(ShutdownUi())
    private var closed: Closed? = null

    val ui: StateFlow<ShutdownUi> = combine(snapshot, session.projects(), local, repo.state) { snap, projects, l, s ->
        val byName = projects.associateBy(ProjectEntity::name)
        l.copy(
            loading = snap == null,
            stars = snap?.first.orEmpty().map { it.ritualTask(byName) },
            candidates = snap?.second.orEmpty().map { it.ritualTask(byName) },
            shutTonight = s.shutDay == repo.ritualDay(),
            eveningOn = s.eveningOn,
            eveningAt = s.eveningAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShutdownUi())

    init {
        viewModelScope.launch {
            val open = repo.openTasks.first()
            snapshot.value = Rituals.stars(open) to Rituals.candidates(open)
        }
        viewModelScope.launch { refreshFrom(accounts, repo) }
    }

    fun choose(id: String, move: Move) = local.update { it.copy(moves = it.moves + (id to move), step = it.step + 1) }

    /** Back one card: its choice is open again. */
    fun back() = local.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun togglePick(id: String) = local.update { it.copy(picks = Rituals.togglePick(it.picks, id)) }

    fun close() {
        val snap = snapshot.value ?: return
        val l = local.value
        viewModelScope.launch {
            val c = repo.closeDay(snap.first, l.moves, l.picks)
            closed = c
            local.update { it.copy(done = true) }
            toasts.show("Day closed", action = "Undo") { undo() }
        }
    }

    private fun undo() {
        val c = closed ?: return
        closed = null
        viewModelScope.launch {
            repo.undo(c)
            local.update { it.copy(done = false, step = ui.value.stars.size) }
        }
    }

    fun setEvening(on: Boolean) { viewModelScope.launch { repo.setEvening(on) } }
    fun setEveningAt(at: String) { viewModelScope.launch { repo.setEvening(true, at) } }
}

// ── The weekly review ────────────────────────────────────────────────────────────────────────────────────────────

data class ReviewUi(
    val loading: Boolean = true,
    /** 0 inbox · 1 head · 2 next steps · 3 someday. */
    val step: Int = 0,
    val inbox: Int = 0,
    val stuck: List<StuckDto> = emptyList(),
    val someday: List<RitualTask> = emptyList(),
    val steps: Map<String, String> = emptyMap(),
    val drops: Set<String> = emptySet(),
    val last: String? = null,
    val done: Boolean = false,
) {
    companion object {
        const val STEPS = 4
    }
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repo: RitualsRepository,
    private val toasts: ToastCenter,
    inbox: InboxRepository,
    accounts: AccountStore,
    session: Session,
) : ViewModel() {
    private val local = MutableStateFlow(ReviewUi())
    private var closed: Closed? = null
    /** Someday is taken once when the review opens, so a dropped row stays to be undone. */
    private val somedaySnap = MutableStateFlow<List<TaskEntity>?>(null)

    private val inboxCount = combine(inbox.state, inbox.deck) { s, deck -> s?.total ?: deck.size }

    val ui: StateFlow<ReviewUi> = combine(local, repo.state, repo.openTasks, combine(somedaySnap, session.projects()) { a, b -> a to b }, inboxCount) { l, s, open, (snap, projects), n ->
        val byName = projects.associateBy(ProjectEntity::name)
        l.copy(
            loading = snap == null,
            inbox = n,
            // A project that got its next step during the review keeps its row (Boss is typing in it).
            stuck = Rituals.stuck(s.stuckList(), open).let { now -> s.stuckList().filter { st -> st in now || st.project in l.steps } },
            someday = snap.orEmpty().map { it.ritualTask(byName) },
            last = s.lastReview,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUi())

    init {
        viewModelScope.launch { somedaySnap.value = Rituals.someday(repo.openTasks.first()) }
        viewModelScope.launch { refreshFrom(accounts, repo) }
    }

    fun go(step: Int) = local.update { it.copy(step = step.coerceIn(0, ReviewUi.STEPS - 1)) }
    fun setStep(project: String, text: String) = local.update { it.copy(steps = it.steps + (project to text)) }
    fun toggleDrop(id: String) = local.update { it.copy(drops = if (id in it.drops) it.drops - id else it.drops + id) }

    fun finish() {
        val l = local.value
        viewModelScope.launch {
            val c = repo.finishReview(l.steps, l.drops.toList())
            closed = c
            local.update { it.copy(done = true) }
            toasts.show("Review done", action = "Undo") { undo() }
        }
    }

    private fun undo() {
        val c = closed ?: return
        closed = null
        viewModelScope.launch {
            repo.undo(c)
            local.update { it.copy(done = false) }
        }
    }
}
