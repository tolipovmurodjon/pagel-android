package org.pagel.ai.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.pagel.ai.voice.SetVoiceClient
import org.pagel.ai.voice.VoiceCatalog
import org.pagel.ai.voice.VoiceOption
import org.pagel.ai.voice.VoiceSessionManager
import org.pagel.ai.voice.WakeSettings
import javax.inject.Inject

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val manager: VoiceSessionManager,
    private val wakeSettings: WakeSettings,
    private val setVoiceClient: SetVoiceClient,
) : ViewModel() {

    val state = manager.state
    val error = manager.error
    val transcript = manager.transcript
    val amplitude = manager.amplitude
    val muted = manager.muted

    val armed = wakeSettings.armed
    val chargerOnly = wakeSettings.chargerOnly

    private val _selectedVoice = MutableStateFlow(VoiceCatalog.default)
    val selectedVoice = _selectedVoice.asStateFlow()
    private val _voiceStatus = MutableStateFlow<String?>(null)
    val voiceStatus = _voiceStatus.asStateFlow()

    fun start(context: Context) = manager.start(context)
    fun stop() = manager.stop()
    fun toggleMute() = manager.toggleMute()

    fun setArmed(v: Boolean) = viewModelScope.launch { wakeSettings.setArmed(v) }
    fun setChargerOnly(v: Boolean) = viewModelScope.launch { wakeSettings.setChargerOnly(v) }

    fun setVoice(v: VoiceOption) = viewModelScope.launch {
        _voiceStatus.value = "Switching to ${v.name}…"
        val r = setVoiceClient.set(v.id)
        if (r.isSuccess) {
            _selectedVoice.value = v
            _voiceStatus.value = "Voice: ${v.name}"
        } else {
            _voiceStatus.value = "Couldn't switch voice"
        }
    }

    override fun onCleared() {
        manager.stop()
    }
}
