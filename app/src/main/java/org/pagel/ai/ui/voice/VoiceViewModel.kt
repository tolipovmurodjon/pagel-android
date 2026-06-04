package org.pagel.ai.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.pagel.ai.voice.VoiceSessionManager
import javax.inject.Inject

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val manager: VoiceSessionManager,
) : ViewModel() {

    val state = manager.state
    val error = manager.error
    val transcript = manager.transcript
    val amplitude = manager.amplitude
    val muted = manager.muted

    fun start(context: Context) = manager.start(context)
    fun stop() = manager.stop()
    fun toggleMute() = manager.toggleMute()

    override fun onCleared() {
        manager.stop()
    }
}
