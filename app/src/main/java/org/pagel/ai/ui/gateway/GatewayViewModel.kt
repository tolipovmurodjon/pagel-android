package org.pagel.ai.ui.gateway

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.pagel.ai.net.GatewayManager
import org.pagel.ai.net.GatewaySettings
import org.pagel.ai.net.ws.ConnState
import javax.inject.Inject

data class GatewayUi(
    val url: String = "",
    val operatorState: ConnState = ConnState.Disconnected,
    val operatorError: String? = null,
    val nodeState: ConnState = ConnState.Disconnected,
    val nodeError: String? = null,
)

/**
 * Backs the Task 8 debug screen. Note what this deliberately does *not* do: [GatewayManager] is a
 * Hilt singleton built once, with [org.pagel.ai.net.GatewayConnectionConfig.serverUrl] baked in
 * at construction (see [GatewaySettings]'s doc) — so editing [GatewayUi.url] here and pressing
 * Connect saves the new value for *next app launch*, it does not retarget the connections already
 * running in this process. [connect] still calls [GatewaySettings.saveUrl] every time (not just
 * on a hypothetical first run) so whatever Boss last typed is what the next cold start picks up.
 */
@HiltViewModel
class GatewayViewModel @Inject constructor(
    private val manager: GatewayManager,
    private val settings: GatewaySettings,
) : ViewModel() {

    private val _ui = MutableStateFlow(GatewayUi())
    val ui: StateFlow<GatewayUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { _ui.value = _ui.value.copy(url = settings.currentUrl()) }
        viewModelScope.launch {
            manager.operatorState.collect { s -> _ui.value = _ui.value.copy(operatorState = s) }
        }
        viewModelScope.launch {
            manager.nodeState.collect { s -> _ui.value = _ui.value.copy(nodeState = s) }
        }
        viewModelScope.launch {
            manager.operatorLastError.collect { e -> _ui.value = _ui.value.copy(operatorError = e?.let { "${it.code}: ${it.message}" }) }
        }
        viewModelScope.launch {
            manager.nodeLastError.collect { e -> _ui.value = _ui.value.copy(nodeError = e?.let { "${it.code}: ${it.message}" }) }
        }
    }

    fun setUrl(v: String) {
        _ui.value = _ui.value.copy(url = v)
    }

    fun connect() {
        viewModelScope.launch { settings.saveUrl(_ui.value.url) }
        manager.start()
    }

    fun disconnect() {
        manager.stop()
    }
}
