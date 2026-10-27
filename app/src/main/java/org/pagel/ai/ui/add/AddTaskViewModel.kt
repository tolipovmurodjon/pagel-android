package org.pagel.ai.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.ui.projects.shortName
import org.pagel.ai.ui.today.dueLabel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/** A task just saved, shown under the field for a moment like the web's "ghost" rows: what, and where it went. */
data class Saved(val id: Long, val text: String, val where: String)

/** What a typed line will become, before Enter: the web's add-task chips. */
data class AddPlan(val text: String, val project: String, val priority: Int, val due: String, val every: String?, val tooLong: Boolean)

private val TASHKENT = ZoneId.of("Asia/Tashkent")

/** The web's Add on Today: no date means today, P3 unless p1-p4 is typed, the picked project unless #project is. */
fun planOf(typed: String, pick: String, projects: List<String>, now: LocalDateTime): AddPlan {
    val q = parseQuick(typed, projects, now)
    return AddPlan(q.text, q.project ?: pick, q.priority ?: 2, q.due ?: now.toLocalDate().toString(), q.every,
        tooLong = q.text.split(Regex("""\s+""")).size > MAX_WORDS)
}

/** pagel.uz refuses longer lines (task-api.ts): say it before Enter, not after a refusal. */
const val MAX_WORDS = 20

@HiltViewModel
class AddTaskViewModel @Inject constructor(private val repo: TodayRepository, session: Session) : ViewModel() {
    val projects: StateFlow<List<ProjectEntity>> = session.projects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _saved = MutableStateFlow<List<Saved>>(emptyList())
    val saved: StateFlow<List<Saved>> = _saved.asStateFlow()
    private var nextId = 0L

    fun now(): LocalDateTime = LocalDateTime.now(TASHKENT)

    /** Saves at once (Room + outbox); returns false when there is nothing to save or the line is too long. */
    fun save(typed: String, pick: String): Boolean {
        val all = projects.value
        val plan = planOf(typed, pick, all.map { it.name }, now())
        if (plan.text.isBlank() || plan.tooLong) return false
        val today = now().toLocalDate()
        val day = LocalDate.parse(plan.due.take(10))
        val onToday = !day.isAfter(today)
        viewModelScope.launch { repo.add(plan.text, plan.project, plan.priority, plan.due, plan.every, onToday) }
        val label = shortName(plan.project, all.firstOrNull { it.name == plan.project }?.title ?: plan.project)
        val where = if (onToday) "Today, in line" else "Upcoming, ${dueLabel(plan.due, today)?.text ?: plan.due}"
        _saved.update { (listOf(Saved(nextId++, plan.text, "$label · $where")) + it).take(3) }
        return true
    }
}
