package org.pagel.ai.ui.namoz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.NamozDao
import org.pagel.ai.data.db.NamozLearnEntity
import org.pagel.ai.data.db.NamozMarkEntity
import org.pagel.ai.data.db.NamozSettingsEntity
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.isNamoz
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * The Namoz page: Boss's own ticks (the habit's tick, synced to pagel.uz through the outbox like any habit), how each
 * was prayed (on this phone only), his learning list and the page's settings. Nothing here is ever sent to an AI.
 */
@HiltViewModel
class NamozViewModel @Inject constructor(
    private val repo: HabitsRepository,
    private val dao: NamozDao,
) : ViewModel() {
    /** The clock, once a minute on the minute: the countdown never recomposes per second. */
    private val minute = flow {
        while (true) {
            val now = LocalDateTime.now(ZONE).withSecond(0).withNano(0)
            emit(now)
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    private val from = LocalDate.parse(repo.today()).minusDays(400).toString()

    val ui: StateFlow<NamozUi> = combine(
        combine(repo.habits, repo.ticks(), repo.reminderRows) { h, t, r -> Triple(h.filter(::isNamoz), t, r) },
        dao.marks(from), dao.learning(), dao.settings(), minute,
    ) { (prayers, ticks, rem), marks, learning, settings, now ->
        NamozUi.of(now, prayers, ticks, marks, learning, settings ?: NamozSettingsEntity(), rem.filter { it.enabled }.map { it.taskId }.toSet())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NamozUi.empty())

    private fun idOf(p: Namoz.Prayer) = ui.value.prayers[p]?.id
    private val today get() = repo.today()

    /** Tap: prayed, or taken back. Before 1 November nothing is ticked. */
    fun tap(p: Namoz.Prayer) {
        val id = idOf(p) ?: return
        if (!Namoz.counts(today)) return
        val done = p in ui.value.doneToday
        viewModelScope.launch {
            repo.setDay(id, today, !done)
            if (done) dao.clearMark(today, p.name)
        }
    }

    /** Long press: On time · Later · In the mosque (ticks it too), or Clear (takes the tick and the mark back). */
    fun mark(p: Namoz.Prayer, how: String?) {
        val id = idOf(p) ?: return
        if (!Namoz.counts(today)) return
        val done = p in ui.value.doneToday
        viewModelScope.launch {
            if (how == null) { if (done) repo.setDay(id, today, false); dao.clearMark(today, p.name); return@launch }
            if (!done) repo.setDay(id, today, true)
            dao.setMark(NamozMarkEntity(today, p.name, how))
        }
    }

    fun save(s: NamozSettingsEntity) = viewModelScope.launch { dao.setSettings(s); repo.replan() }
    fun qaza(delta: Int) = ui.value.settings.let { s -> save(s.copy(qaza = (s.qaza + delta).coerceAtLeast(0))) }
    fun addLearn(text: String) = text.trim().takeIf { it.isNotEmpty() }?.let { t -> viewModelScope.launch { dao.addLearn(NamozLearnEntity(text = t, createdAt = System.currentTimeMillis())) } }
    fun setLearned(id: Long, on: Boolean) = viewModelScope.launch { dao.setLearned(id, on) }
    fun deleteLearn(id: Long) = viewModelScope.launch { dao.deleteLearn(id) }

    companion object { val ZONE: ZoneId = ZoneId.of("Asia/Tashkent") }
}
