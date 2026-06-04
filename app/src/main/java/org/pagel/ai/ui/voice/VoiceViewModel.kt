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
import org.pagel.ai.voice.VoicePrefs
import org.pagel.ai.voice.VoiceSessionManager
import org.pagel.ai.voice.WakeSettings
import javax.inject.Inject

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val manager: VoiceSessionManager,
    private val wakeSettings: WakeSettings,
    private val setVoiceClient: SetVoiceClient,
    private val voicePrefs: VoicePrefs,
) : ViewModel() {

    init {
        viewModelScope.launch {
            voicePrefs.selectedId()?.let { id -> VoiceCatalog.byId(id)?.let { _selectedVoice.value = it } }
        }
    }

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
        _selectedVoice.value = v // optimistic — reflect the pick instantly
        _voiceStatus.value = "Switching to ${v.name}…"
        val r = setVoiceClient.set(v.id)
        if (r.isSuccess) {
            voicePrefs.setSelectedId(v.id)
            _voiceStatus.value = "${v.name} — you'll hear it on your next call"
        } else {
            _voiceStatus.value = "Couldn't switch voice — check Settings"
        }
    }

    override fun onCleared() {
        manager.stop()
    }
}
