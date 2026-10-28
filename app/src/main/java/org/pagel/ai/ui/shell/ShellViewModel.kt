package org.pagel.ai.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.pagel.ai.data.db.OutboxEntity
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.ui.glass.ToastCenter
import org.pagel.ai.ui.today.ClearedDays
import org.pagel.ai.ui.today.DayClear
import org.pagel.ai.ui.today.TodayCounts
import org.pagel.ai.ui.today.clearChange
import org.pagel.ai.ui.today.clearedStreak
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** Which root the app shows: nothing yet (reading the account off Main), pairing, or the tabs. */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val session: Session,
    private val today: TodayRepository,
    val toasts: ToastCenter,
    @dagger.hilt.android.qualifiers.ApplicationContext private val ctx: android.content.Context,
) : ViewModel() {
    val account: StateFlow<AccountState?> = session.account

    init {
        // The day cleared, from any screen (round B checker #9): the last open task of Today ticked here marks the day
        // and says it once; an Undo that brings a task back takes the day back, so the streak never inflates.
        viewModelScope.launch {
            val zone = java.time.ZoneId.of("Asia/Tashkent")
            DayClear.streak.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { clearedStreak(ClearedDays.all(ctx), java.time.LocalDate.now(zone)) }
            var was: TodayCounts? = null
            combine(today.today, today.doneToday(), today.waiting) { tasks, done, waiting -> TodayCounts(tasks.count { it.status == "open" }, done, waiting) }
                .collect { now ->
                    val change = clearChange(was, now)
                    was = now
                    val day = java.time.LocalDate.now(zone)
                    if (change == 1) {
                        val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ClearedDays.mark(ctx, day) }
                        DayClear.streak.value = n
                        DayClear.events.tryEmit(n)
                    } else if (change == -1 && day.toString() in ClearedDays.all(ctx)) {
                        DayClear.streak.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ClearedDays.unmark(ctx, day) }
                    }
                }
        }
        // pagel.uz refused a change from any screen: Room is already rolled back; say why once (red), then let it go.
        // A refused add stays a moment first, so an open Add sheet can put its words back ("Not saved").
        viewModelScope.launch {
            val shown = HashSet<Long>()
            today.refused.collect { list ->
                val op = list.firstOrNull { it.id !in shown } ?: return@collect
                shown += op.id
                toasts.show("Not saved on pagel.uz: ${op.refused}", good = false, millis = 6_000)
                if (op.op == OutboxEntity.ADD) delay(1_500)
                today.acknowledge(op)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { session.disconnect() }
    }
}
