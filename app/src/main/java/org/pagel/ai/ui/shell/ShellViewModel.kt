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
import javax.inject.Inject

/** Which root the app shows: nothing yet (reading the account off Main), pairing, or the tabs. */
@HiltViewModel
class ShellViewModel @Inject constructor(private val session: Session, private val today: TodayRepository, val toasts: ToastCenter) : ViewModel() {
    val account: StateFlow<AccountState?> = session.account

    init {
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
