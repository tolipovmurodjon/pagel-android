package org.pagel.ai.ui.gateway

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pagel.ai.net.GatewayAuthTokenStore
import org.pagel.ai.net.GatewayManager
import org.pagel.ai.net.GatewaySettings
import org.pagel.ai.net.ws.ConnState
import javax.inject.Inject

data class GatewayUi(
    val url: String = "",
    val token: String = "",
    val operatorState: ConnState = ConnState.Disconnected,
    val operatorError: String? = null,
    val nodeState: ConnState = ConnState.Disconnected,
    val nodeError: String? = null,
) {
    // Same rule, same reason as ConnectAuth.toString() / DeviceIdentity.toString(): a live gateway
    // auth token must never be able to reach a stray Timber.d("$ui") or crash-report toString().
    override fun toString(): String =
        "GatewayUi(url=$url, token=${if (token.isEmpty()) "<empty>" else "<redacted>"}, " +
            "operatorState=$operatorState, operatorError=$operatorError, nodeState=$nodeState, nodeError=$nodeError)"
}

/**
 * Backs the Task 8 debug screen. [connect] calls [GatewayManager.start]'s two-arg overload with the
 * URL and token currently in [GatewayUi] on every tap, so a correction Boss just typed takes effect
 * on *this* CONNECT press, not the next app launch (see that overload's doc). [GatewaySettings.saveUrl]
 * and [GatewayAuthTokenStore.put] still persist those values every time (not just on a hypothetical
 * first run) so whatever Boss last typed is also what a future cold start picks up.
 */
@HiltViewModel
class GatewayViewModel @Inject constructor(
    private val manager: GatewayManager,
    private val settings: GatewaySettings,
    private val authTokenStore: GatewayAuthTokenStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(GatewayUi())
    val ui: StateFlow<GatewayUi> = _ui.asStateFlow()

    // Every mutation goes through [update], never `_ui.value = _ui.value.copy(...)`.
    //
    // `MutableStateFlow.value = x` is atomic per assignment, but `_ui.value.copy(...)` is a
    // read-modify-write: six coroutines start here concurrently, and two that interleave lose one
    // another's field. That is not hypothetical — it shipped, and Boss hit it on the first real run:
    // the URL loader and the token loader both read the initial blank state, the URL loader wrote
    // its default, and the token loader then wrote a copy built from the stale blank snapshot,
    // leaving the Gateway URL field empty on screen. The collectors below could do the same to a
    // value Boss was mid-way through typing, whenever a connection state arrived at the wrong
    // moment. [update] does the read-modify-write atomically, retrying on conflict.
    init {
        viewModelScope.launch { val u = settings.currentUrl(); _ui.update { it.copy(url = u) } }
        viewModelScope.launch { val t = authTokenStore.get().orEmpty(); _ui.update { s -> s.copy(token = t) } }
        viewModelScope.launch {
            manager.operatorState.collect { s -> _ui.update { it.copy(operatorState = s) } }
        }
        viewModelScope.launch {
            manager.nodeState.collect { s -> _ui.update { it.copy(nodeState = s) } }
        }
        viewModelScope.launch {
            manager.operatorLastError.collect { e -> _ui.update { it.copy(operatorError = e?.let { x -> "${x.code}: ${x.message}" }) } }
        }
        viewModelScope.launch {
            manager.nodeLastError.collect { e -> _ui.update { it.copy(nodeError = e?.let { x -> "${x.code}: ${x.message}" }) } }
        }
    }

    fun setUrl(v: String) {
        _ui.update { it.copy(url = v) }
    }

    fun setToken(v: String) {
        _ui.update { it.copy(token = v) }
    }

    /** Saves first, *then* starts — sequentially, in one coroutine. The old version launched the
     *  suspending [GatewaySettings.saveUrl] and called [GatewayManager.start] in the same tick, so a
     *  correction Boss typed after a bad URL raced the connect attempt that the bad URL had caused,
     *  and could lose. */
    fun connect() {
        viewModelScope.launch {
            val url = _ui.value.url
            val token = _ui.value.token
            settings.saveUrl(url)
            authTokenStore.put(token)
            manager.start(url, token.trim().ifEmpty { null })
        }
    }

    fun disconnect() {
        manager.stop()
    }
}
