package org.pagel.ai.voice

import android.content.Context
import io.elevenlabs.ConversationClient
import io.elevenlabs.ConversationConfig
import io.elevenlabs.ConversationSession
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns a single live ElevenLabs voice session and projects the SDK's callbacks/flows onto a small
 * [VoiceState] + transcript the UI can drive the JARVIS core from. The brain (Custom-LLM) and the
 * ElevenLabs API key live server-side; the phone only ever holds a 10-minute conversation token.
 */
@Singleton
class VoiceSessionManager @Inject constructor(
    private val tokenClient: VoiceTokenClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _transcript = MutableStateFlow<List<Transcript>>(emptyList())
    val transcript: StateFlow<List<Transcript>> = _transcript.asStateFlow()

    /** Live output level of PAGEL's voice (0..1) — the JARVIS core pulses to this. */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private var session: ConversationSession? = null

    /** Mint a token, open the WebRTC session, and wire the callbacks. No-op if already live. */
    fun start(context: Context) {
        if (_state.value != VoiceState.IDLE && _state.value != VoiceState.ERROR) return
        _error.value = null
        _transcript.value = emptyList()
        _state.value = VoiceState.CONNECTING

        scope.launch {
            val token = tokenClient.fetch().getOrElse {
                _error.value = it.message ?: "Couldn't reach the brain"
                _state.value = VoiceState.ERROR
                return@launch
            }
            try {
                val config = ConversationConfig(
                    conversationToken = token,
                    userId = "boss",
                    onConnect = { _state.value = VoiceState.LISTENING },
                    onModeChange = { mode ->
                        _state.value =
                            if (mode == ConversationMode.SPEAKING) VoiceState.SPEAKING else VoiceState.LISTENING
                    },
                    onUserTranscript = { t ->
                        appendUser(t)
                        _state.value = VoiceState.THINKING
                    },
                    onAgentResponse = { r -> appendAgent(r) },
                    onStatusChange = { status ->
                        when (status) {
                            ConversationStatus.DISCONNECTED -> _state.value = VoiceState.IDLE
                            ConversationStatus.ERROR -> {
                                _error.value = "Voice connection failed — try again"
                                _state.value = VoiceState.ERROR
                            }
                            else -> Unit
                        }
                    },
                )
                val s = ConversationClient.startSession(config, context)
                session = s
                // Mirror the SDK's live flows so the UI can pulse the core to PAGEL's voice + show mute.
                launch { s.audioLevel.collect { _amplitude.value = it } }
                launch { s.isMuted.collect { _muted.value = it } }
            } catch (t: Throwable) {
                _error.value = t.localizedMessage ?: t.message ?: "Failed to start voice"
                _state.value = VoiceState.ERROR
            }
        }
    }

    /** End the live session and return to idle. */
    fun stop() {
        val s = session
        session = null
        if (s == null) {
            _state.value = VoiceState.IDLE
            return
        }
        scope.launch {
            runCatching { s.endSession() }
            _amplitude.value = 0f
            _muted.value = false
            _state.value = VoiceState.IDLE
        }
    }

    fun toggleMute() {
        val s = session ?: return
        scope.launch { runCatching { s.toggleMute() } }
    }

    private fun appendUser(text: String) {
        if (text.isBlank()) return
        _transcript.update { it + Transcript(fromUser = true, text = text) }
    }

    /**
     * Agent text arrives as streaming deltas, tentative partials, and a final consolidated message
     * (all via onAgentResponse). Merge into the trailing agent bubble rather than appending blindly.
     */
    private fun appendAgent(text: String) {
        if (text.isEmpty()) return
        _transcript.update { current ->
            val last = current.lastOrNull()
            if (last == null || last.fromUser) {
                return@update current + Transcript(fromUser = false, text = text)
            }
            val merged = when {
                last.text == text -> last
                text.startsWith(last.text) -> last.copy(text = text)
                else -> last.copy(text = last.text + text)
            }
            if (merged === last) current
            else current.toMutableList().also { it[it.lastIndex] = merged }
        }
    }
}
