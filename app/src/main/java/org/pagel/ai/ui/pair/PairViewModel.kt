package org.pagel.ai.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.session.message
import javax.inject.Inject

data class PairUiState(
    val code: String = "",
    val server: String = Session.DEFAULT_SERVER,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canConnect: Boolean get() = code.count(Char::isDigit) == 6 && !busy
}

/** Pairing: Boss types the 6-digit code pagel.uz → Phones shows; the session keeps the token it gets back. */
@HiltViewModel
class PairViewModel @Inject constructor(private val session: Session) : ViewModel() {
    private val _ui = MutableStateFlow(PairUiState())
    val ui: StateFlow<PairUiState> = _ui.asStateFlow()

    fun onCode(text: String) = _ui.update { it.copy(code = text.filter(Char::isDigit).take(6), error = null) }

    fun onServer(text: String) = _ui.update { it.copy(server = text.trim(), error = null) }

    fun connect() {
        val s = _ui.value
        if (!s.canConnect) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = session.pair(s.server, s.code)) {
                // The account flow flips the app to the shell; nothing else to do here.
                is Outcome.Ok -> _ui.update { it.copy(busy = false) }
                is Outcome.Failed -> _ui.update { it.copy(busy = false, code = "", error = r.error.message()) }
            }
        }
    }
}
