package org.pagel.ai.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pagel.ai.data.inbox.InboxRepository
import org.pagel.ai.data.inbox.splitCapture
import org.pagel.ai.ui.glass.ToastCenter
import javax.inject.Inject

/**
 * Capture to the Inbox (Inbox v3, Boss 2026-10-31: "adding to the Inbox is the most important thing"). One tap from
 * anywhere: the words are in Room and the deck at once, the outbox sends them in the order typed, offline or not
 * (rules/respond-first-save-after). A pasted list is many saves with one Undo.
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(private val repo: InboxRepository, private val toasts: ToastCenter) : ViewModel() {
    private val saved = MutableStateFlow(0)
    /** How many saves this sheet has made ("In Inbox 3"). */
    val count: StateFlow<Int> = saved.asStateFlow()

    /** Saves [raw] now; false when there is nothing to save. The field may clear on true. */
    fun save(raw: String): Boolean {
        val parts = splitCapture(raw)
        if (parts.isEmpty()) return false
        val many = parts.size > 1
        saved.update { it + parts.size }
        viewModelScope.launch {
            val rows = repo.capture(parts, source = null, hold = many)
            if (many) toasts.show("${parts.size} added to Inbox", action = "Undo", millis = 5_000) {
                viewModelScope.launch {
                    val back = repo.undoCapture(rows)
                    if (back > 0) saved.update { (it - back).coerceAtLeast(0) } else toasts.show("Already on pagel.uz")
                }
            }
        }
        return true
    }

    /** The phone has no speech service (an emulator, a phone without Google). */
    fun noSpeech() = toasts.show("No speech service on this phone", good = false)
}
