package org.pagel.ai.voice

/** A pick of ElevenLabs voices for PAGEL, spanning vibes. id = ElevenLabs voice_id. */
data class VoiceOption(val id: String, val name: String, val vibe: String)

object VoiceCatalog {
    val voices: List<VoiceOption> = listOf(
        VoiceOption("nPczCjzI2devNBz1zQrb", "Brian", "deep · resonant · JARVIS"),
        VoiceOption("JBFqnCBsd6RMkjVDRZzb", "George", "warm · British · captivating"),
        VoiceOption("onwK4e9ZLuTAKqWW03F9", "Daniel", "British · mission-control"),
        VoiceOption("cjVigY5qzO86Huf0OWal", "Eric", "smooth · trustworthy"),
        VoiceOption("iP95p4xoKVk53GoZ742B", "Chris", "casual · down-to-earth"),
        VoiceOption("bIHbv24MWmeRgasZH58o", "Will", "relaxed · chill"),
        VoiceOption("IKne3meq5aSn9XLyUdCD", "Charlie", "deep · hyped · Aussie"),
        VoiceOption("TX3LPaxmHKxFdv7VOQHJ", "Liam", "energetic · social"),
    )
    val default = voices.first()
}
