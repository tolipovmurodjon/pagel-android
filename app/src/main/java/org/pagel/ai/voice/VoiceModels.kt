package org.pagel.ai.voice

/** Where a conversation is in its loop — drives the JARVIS core's energy + the caption. */
enum class VoiceState {
    IDLE,        // not connected — tap TALK to begin
    CONNECTING,  // minting token + opening the WebRTC session
    LISTENING,   // mic open, PAGEL waiting on you
    THINKING,    // your turn ended, brain is composing
    SPEAKING,    // PAGEL is talking back
    ERROR,       // something failed — caption carries the reason
}

/** One line of the live transcript. */
data class Transcript(val fromUser: Boolean, val text: String)
