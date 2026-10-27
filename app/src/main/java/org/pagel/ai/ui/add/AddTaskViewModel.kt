package org.pagel.ai.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.ProjectEntity
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.ui.glass.DatePick
import org.pagel.ai.ui.glass.Repeat
import org.pagel.ai.ui.glass.ToastCenter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * What Boss picked with the chips. A picked date or repeat beats the typed one (the web's `picked`/`rep`); a typed
 * #project beats the picked project, as on the web. `dueSet` tells "picked No date" from "picked nothing".
 */
data class Picks(
    val dueSet: Boolean = false,
    val due: String? = null,
    val everySet: Boolean = false,
    val every: String? = null,
    val priority: Int? = null,
)

/** What a typed line will become, before Enter: the web's add-task chips. */
data class AddPlan(val text: String, val project: String, val priority: Int, val due: String?, val every: String?, val tooLong: Boolean)

private val TASHKENT = ZoneId.of("Asia/Tashkent")

/**
 * The web's Add: on Today a task with no date is dated today (so it always shows); from a project it only joins the
 * plan. P3 unless p1-p4 is typed or a flag picked; the picked project unless #project is typed.
 */
fun planOf(typed: String, pick: String, projects: List<String>, now: LocalDateTime, picks: Picks = Picks(), toToday: Boolean = true): AddPlan {
    val q = parseQuick(typed, projects, now)
    val every = if (picks.everySet) picks.every else q.every
    val today = now.toLocalDate().toString()
    val due = if (picks.dueSet) picks.due else q.due ?: if (toToday || every != null) today else null
    return AddPlan(q.text, q.project ?: pick, picks.priority ?: q.priority ?: 2, due ?: if (every != null) today else null, every,
        tooLong = q.text.split(Regex("""\s+""")).size > MAX_WORDS)
}

/** pagel.uz refuses longer lines (task-api.ts): say it before Enter, not after a refusal. */
const val MAX_WORDS = 20

/** Where a new task went, said plainly (the web's ghost rows): "Today", "Upcoming, Fri 30 Oct", "Habits, Weekly". */
fun whereOf(plan: AddPlan, today: LocalDate): String {
    val day = plan.due?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return when {
        plan.every != null -> "Habits, ${Repeat.label(plan.every)}"
        day == null -> "in the plan"
        !day.isAfter(today) -> "Today"
        else -> "Upcoming, ${DatePick.weekday(day)} ${DatePick.dueText(day.toString(), LocalDate.MIN)}"
    }
}

@HiltViewModel
class AddTaskViewModel @Inject constructor(private val repo: TodayRepository, private val toasts: ToastCenter, session: Session) : ViewModel() {
    val projects: StateFlow<List<ProjectEntity>> = session.projects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun now(): LocalDateTime = LocalDateTime.now(TASHKENT)

    /** Saves at once (Room + outbox) and says where it went; false when there is nothing to save or it is too long. */
    fun save(typed: String, pick: String, picks: Picks, toToday: Boolean): Boolean {
        val plan = planOf(typed, pick, projects.value.map { it.name }, now(), picks, toToday)
        if (plan.text.isBlank() || plan.tooLong) return false
        val today = now().toLocalDate()
        val day = plan.due?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val onToday = toToday && plan.every == null && day != null && !day.isAfter(today)
        viewModelScope.launch { repo.add(plan.text, plan.project, plan.priority, plan.due, plan.every, onToday) }
        toasts.show("Added · ${whereOf(plan, today)}")
        return true
    }
}
