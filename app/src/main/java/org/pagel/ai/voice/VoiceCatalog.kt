package org.pagel.ai.voice

/** A curated pick of voices for PAGEL — human + playful first, then the deep classics. */
data class VoiceOption(val id: String, val name: String, val vibe: String, val female: Boolean = false)

object VoiceCatalog {
    val voices: List<VoiceOption> = listOf(
        // Human & playful — the "doesn't sound like an AI" picks (featured first)
        VoiceOption("YyqkX0AHv8W5D1vxG9lR", "Hazel", "warm · witty · so human", female = true),
        VoiceOption("iePvrB4HtMcAormXZou7", "Sophie", "playful · Scottish · fun", female = true),
        VoiceOption("cgSgspJ2msm6clMCkdW9", "Jessica", "playful · bright", female = true),
        VoiceOption("wCizWKvnaS3LOIUwHo98", "Ava", "British · conversational", female = true),
        VoiceOption("civNMpwNAw6qVmpSrAgz", "Edith", "calm · confident · the EDITH", female = true),
        VoiceOption("EXAVITQu4vr4xnSDxMaL", "Sarah", "warm · reassuring", female = true),
        VoiceOption("Xb7hH8MSUJpSbSDYk0k2", "Alice", "clear · British", female = true),
        // Deep / classic male
        VoiceOption("nPczCjzI2devNBz1zQrb", "Brian", "deep · resonant · JARVIS"),
        VoiceOption("JBFqnCBsd6RMkjVDRZzb", "George", "warm · British"),
        VoiceOption("iP95p4xoKVk53GoZ742B", "Chris", "casual · down-to-earth"),
        VoiceOption("bIHbv24MWmeRgasZH58o", "Will", "relaxed · chill"),
        VoiceOption("cjVigY5qzO86Huf0OWal", "Eric", "smooth · trustworthy"),
    )

    val default = voices.first { it.id == "nPczCjzI2devNBz1zQrb" } // Brian = current server default
    fun byId(id: String) = voices.firstOrNull { it.id == id }
}
