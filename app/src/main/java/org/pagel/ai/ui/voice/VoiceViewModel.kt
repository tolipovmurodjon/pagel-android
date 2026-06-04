package org.pagel.ai.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.pagel.ai.voice.VoiceSessionManager
import org.pagel.ai.voice.WakeSettings
import javax.inject.Inject

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val manager: VoiceSessionManager,
    private val wakeSettings: WakeSettings,
) : ViewModel() {

    val state = manager.state
    val error = manager.error
    val transcript = manager.transcript
    val amplitude = manager.amplitude
    val muted = manager.muted

    val armed = wakeSettings.armed
    val chargerOnly = wakeSettings.chargerOnly

    fun start(context: Context) = manager.start(context)
    fun stop() = manager.stop()
    fun toggleMute() = manager.toggleMute()

    fun setArmed(v: Boolean) = viewModelScope.launch { wakeSettings.setArmed(v) }
    fun setChargerOnly(v: Boolean) = viewModelScope.launch { wakeSettings.setChargerOnly(v) }

    override fun onCleared() {
        manager.stop()
    }
}
