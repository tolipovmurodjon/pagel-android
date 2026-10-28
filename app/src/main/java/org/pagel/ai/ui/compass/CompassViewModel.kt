package org.pagel.ai.ui.compass

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
import kotlinx.serialization.Serializable
import androidx.navigation3.runtime.NavKey
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.compass.CompassRepository
import org.pagel.ai.data.compass.CompassRules
import org.pagel.ai.data.compass.CompassVaultEntity
import org.pagel.ai.data.compass.Goal
import org.pagel.ai.data.compass.GoalBody
import org.pagel.ai.data.compass.GoalProgress
import org.pagel.ai.data.compass.KeyGone
import org.pagel.ai.data.compass.Level
import org.pagel.ai.data.compass.NeedsUnlock
import org.pagel.ai.data.compass.NoteBody
import org.pagel.ai.data.compass.NoteKind
import org.pagel.ai.data.compass.Opened
import org.pagel.ai.data.compass.VaultState
import org.pagel.ai.data.compass.YearNumbers
import java.time.LocalDate
import javax.inject.Inject

/** More → Compass (Phase 6a): Boss's sacred goals, locked. */
@Serializable data object CompassRoute : NavKey

/** Everything the Compass screen draws. Words live here only while the key is open. */
data class CompassUi(
    val state: VaultState = VaultState.READING,
    val today: LocalDate = LocalDate.now(CompassRepository.TASHKENT),
    val opened: Opened = Opened(),
    val progress: Map<String, GoalProgress> = emptyMap(),
    val vault: CompassVaultEntity = CompassVaultEntity(),
    val report: YearNumbers = YearNumbers(),
    /** One calm line under the lock or a button when something did not work (never goal words). */
    val message: String? = null,
) {
    val yearStart: LocalDate get() = CompassRules.yearStart(today)
    val season get() = CompassRules.seasonOf(today)
    val week: Int get() = CompassRules.weekOf(season, today)
    fun at(level: Level) = opened.goals.filter { it.level == level }
    /** This season's goals: season-level goals dated in this season. */
    val seasonGoals: List<Goal> get() = opened.goals.filter { it.level == Level.SEASON && it.starts == season.starts.toString() }
    /** The age report's year: the year just ended for the first 60 days after the birthday, else the one ending next. */
    val reportEnd: LocalDate get() = if (today.isBefore(yearStart.plusDays(60))) yearStart.minusDays(1) else CompassRules.yearEnd(yearStart)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CompassViewModel @Inject constructor(private val repo: CompassRepository, private val accounts: AccountStore) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private val today = repo.today()
    private val reportEnd = CompassUi(today = today).reportEnd

    val ui: StateFlow<CompassUi> = combine(
        combine(repo.state, repo.opened, repo.vault) { s, o, v -> Triple(s, o, v) },
        repo.state.flatMapLatest { if (it == VaultState.OPEN) repo.progress() else flowOf(emptyMap<String, GoalProgress>()) },
        repo.state.flatMapLatest { if (it == VaultState.OPEN) repo.yearNumbers(CompassRules.yearStart(reportEnd), reportEnd) else flowOf(YearNumbers()) },
        message,
    ) { (state, opened, vault), progress, report, msg ->
        CompassUi(state, today, opened, progress, vault, report, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompassUi(today = today))

    init {
        viewModelScope.launch {
            runCatching { repo.prepare() }
            (accounts.load() as? AccountState.Paired)?.account?.let { runCatching { repo.pullIfNew(it) } }
            repo.nudge()
        }
    }

    /** Before the system unlock sheet: the Keystore guard must exist so its 60 s window covers the first seal. */
    fun beforeUnlock() { viewModelScope.launch { runCatching { repo.prepare() } } }

    /** The system sheet said yes: open (or make) the key. */
    fun unlocked(passphrase: CharArray? = null, startOver: Boolean = false) {
        viewModelScope.launch {
            message.value = null
            try {
                when {
                    startOver -> repo.startOver()
                    passphrase != null -> if (!repo.recover(passphrase)) message.value = "That passphrase does not open them. Try again."
                    ui.value.state == VaultState.NEW -> repo.create()
                    else -> repo.unlock()
                }
            } catch (_: NeedsUnlock) {
                message.value = "The unlock ran out. Try again."
            } catch (_: KeyGone) {
                message.value = "This phone's key is gone."
            } finally {
                passphrase?.fill(' ')
            }
        }
    }

    fun said(text: String?) { message.value = text }
    fun lock() = repo.lock()
    fun leave() = repo.leave()
    fun stay() = repo.stay()

    /**
     * Saves a goal. A season goal added or taken out after the season's first week carries one line why, sealed as
     * its own note ([why]).
     */
    fun save(goal: Goal, why: String? = null) = guarded {
        val id = repo.saveGoal(goal)
        if (!why.isNullOrBlank()) repo.saveNote(NoteKind.CHANGE_WHY, today.toString(), NoteBody(listOf(why.trim()), id))
    }

    fun delete(goal: Goal, why: String? = null) = guarded {
        repo.deleteGoal(goal.id)
        if (!why.isNullOrBlank()) repo.saveNote(NoteKind.CHANGE_WHY, today.toString(), NoteBody(listOf(why.trim()), goal.id))
    }

    fun tickKeyResult(goal: Goal, i: Int) = guarded {
        val krs = goal.body.keyResults.mapIndexed { n, k -> if (n == i) k.copy(done = !k.done) else k }
        repo.saveGoal(goal.copy(body = goal.body.copy(keyResults = krs)))
    }

    /** An outcome that is not in his hands closes as "a different door", never as failed. */
    fun closeAsDoor(goal: Goal) = guarded { repo.saveGoal(goal.copy(body = goal.body.copy(state = GoalBody.DOOR))) }

    fun reset(goal: Goal) = guarded {
        val hits = ui.value.progress[goal.id]?.hitDays.orEmpty()
        repo.saveGoal(goal.copy(body = CompassRules.reset(ui.value.season, today, goal.body, hits)))
    }

    fun saveNote(kind: String, day: String, answers: List<String>, id: String = "", goalId: String? = null) = guarded {
        repo.saveNote(kind, day, NoteBody(answers.map(String::trim), goalId), id)
    }

    fun setLine(on: Boolean, goal: Goal?) = guarded { repo.setLine(on, goal) }

    fun setRecovery(passphrase: CharArray?) = guarded {
        try { repo.setRecovery(passphrase) } finally { passphrase?.fill(' ') }
    }

    fun link(project: String, goal: String?) = guarded { repo.link(project, goal) }

    private fun guarded(work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work()
            } catch (_: NeedsUnlock) {
                message.value = "Locked. Unlock to save."
            }
        }
    }
}
