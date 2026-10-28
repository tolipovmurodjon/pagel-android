package org.pagel.ai.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.pagel.ai.data.compass.KeyGone
import org.pagel.ai.data.compass.NeedsUnlock
import org.pagel.ai.data.compass.VaultState
import org.pagel.ai.data.journal.JournalRepository
import org.pagel.ai.data.journal.OpenJournal
import javax.crypto.Cipher
import javax.inject.Inject

/** Notebook → Journal (Phase 6b): Boss's locked journal. */
@Serializable data object JournalRoute : NavKey

/** Everything the journal draws. Words live here only while its key is open. */
data class JournalUi(
    val state: VaultState = VaultState.READING,
    val opened: OpenJournal = OpenJournal(),
    val today: String = "",
    /** One calm line under the lock or a button when something did not work (never his words). */
    val message: String? = null,
)

@HiltViewModel
class JournalViewModel @Inject constructor(private val repo: JournalRepository) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    val ui: StateFlow<JournalUi> = combine(repo.state, repo.opened, message) { s, o, m -> JournalUi(s, o, repo.today().toString(), m) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalUi(today = repo.today().toString()))

    init { repo.nudge() }

    /** The one-use cipher for the journal's own unlock prompt (a CryptoObject: the plain lock screen never opens it). */
    fun beginUnlock(prompt: (Cipher) -> Unit) {
        viewModelScope.launch {
            message.value = null
            try {
                prompt(repo.cipherForUnlock())
            } catch (_: KeyGone) {
                message.value = "This phone's key is gone."
            } catch (_: java.security.GeneralSecurityException) {
                message.value = "The phone's lock did not answer. Try again."
            } catch (_: IllegalStateException) {
                message.value = "Set a screen lock first."
            }
        }
    }

    fun unlocked(cipher: Cipher, startOver: Boolean = false) {
        viewModelScope.launch {
            message.value = null
            try {
                when {
                    startOver -> repo.startOver(cipher)
                    ui.value.state == VaultState.NEW -> repo.create(cipher)
                    else -> repo.unlock(cipher)
                }
            } catch (_: NeedsUnlock) {
                message.value = "The unlock did not go through. Try again."
            } catch (_: KeyGone) {
                message.value = "This phone's key is gone."
            }
        }
    }

    fun said(text: String?) { message.value = text }
    fun lock() = repo.lock()
    fun leave() = repo.leave()
    fun stay() = repo.stay()

    fun save(id: String, day: String, text: String, mood: String?) = guarded { repo.save(id, day, text, mood) }
    fun delete(id: String) = guarded { repo.delete(id) }

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
