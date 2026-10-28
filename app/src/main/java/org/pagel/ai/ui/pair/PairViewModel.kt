package org.pagel.ai.ui.pair

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.pagel.ai.BuildConfig
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.session.Session
import org.pagel.ai.data.session.message
import javax.inject.Inject

/** Where pairing is: typing the code, asking pagel.uz (slow after 4 s), or done (the check draws, then Today). */
enum class PairStep { TYPING, CONNECTING, SLOW, DONE }

data class PairUiState(
    val code: String = "",
    val server: String = Session.DEFAULT_SERVER,
    val step: PairStep = PairStep.TYPING,
    val error: String? = null,
    /** Counts wrong codes: each one shakes the boxes once. */
    val shake: Int = 0,
    /** pagel.uz did not answer in [PairViewModel.TIMEOUT_MS]: the button says "Try again" with the code kept. */
    val timedOut: Boolean = false,
) {
    val busy: Boolean get() = step != PairStep.TYPING
    val canConnect: Boolean get() = code.count(Char::isDigit) == 6 && !busy
}

/**
 * Pairing: Boss types (or pastes) the 6-digit code pagel.uz → Phones shows; the sixth digit sends it. The session keeps
 * the token it gets back. No spinner (Boss 2026-10-28: "the progress bar is trash"): the button fills while it asks.
 */
@HiltViewModel
class PairViewModel @Inject constructor(private val session: Session, saved: SavedStateHandle) : ViewModel() {
    // Debug builds only: the CI emulator points the app at a fake pagel.uz with `am start --es pagel.server <url>`.
    private val _ui = MutableStateFlow(PairUiState(server = saved.get<String>(EXTRA_SERVER)?.takeIf { BuildConfig.DEBUG } ?: Session.DEFAULT_SERVER))
    val ui: StateFlow<PairUiState> = _ui.asStateFlow()
    private var job: Job? = null

    fun onCode(text: String) {
        if (_ui.value.busy) return
        val code = text.filter(Char::isDigit).take(6)
        _ui.update { it.copy(code = code, error = null, timedOut = false) }
        if (code.length == 6) connect()
    }

    fun onServer(text: String) = _ui.update { it.copy(server = text.trim(), error = null) }

    fun connect() {
        val s = _ui.value
        if (!s.canConnect) return
        _ui.update { it.copy(step = PairStep.CONNECTING, error = null, timedOut = false) }
        job?.cancel()
        job = viewModelScope.launch {
            val slow = launch { delay(SLOW_MS); _ui.update { if (it.step == PairStep.CONNECTING) it.copy(step = PairStep.SLOW) else it } }
            // pagel.uz took the code: the check draws itself first, then the account flips the app to Today.
            val r = withTimeoutOrNull(TIMEOUT_MS) { session.pair(s.server, s.code) { _ui.update { it.copy(step = PairStep.DONE) }; delay(CHECK_MS) } }
            slow.cancel()
            when (r) {
                null -> _ui.update { it.copy(step = PairStep.TYPING, timedOut = true, error = "pagel.uz did not answer in 30 s.") }
                is Outcome.Ok -> {}
                is Outcome.Failed -> _ui.update { it.copy(step = PairStep.TYPING, code = "", error = r.error.message(), shake = it.shake + 1) }
            }
        }
    }

    companion object {
        const val SLOW_MS = 4_000L
        const val TIMEOUT_MS = 30_000L
        const val CHECK_MS = 700L
    }
}

const val EXTRA_SERVER = "pagel.server"
