package org.pagel.ai.voice

/** A curated pick of voices for PAGEL — human + playful first, then the deep classics. */
data class VoiceOption(
    val id: String,
    val name: String,
    val vibe: String,
    val female: Boolean = false,
    val previewUrl: String = "",
)

object VoiceCatalog {
    val voices: List<VoiceOption> = listOf(
        // Human & playful — the "doesn't sound like an AI" picks (featured first)
        VoiceOption("YyqkX0AHv8W5D1vxG9lR", "Hazel", "warm · witty · so human", female = true, previewUrl = "https://api.us.elevenlabs.io/v1/voices/YyqkX0AHv8W5D1vxG9lR/previews/audio?payload=eyJ2b2ljZV9zb3VyY2UiOiJjdXN0b20iLCJ3b3Jrc3BhY2VfaWQiOiI2YjY3N2QzYWQyYjM0MjAwOWEzYmMzZDQyOGNlYmZjMyIsImZpbGVuYW1lIjoiTThOajRONWkyN3UzQTMyTmlZanYubXAzIiwidGltZXN0YW1wIjoxNzgwNTkyNDAwMDAwMDAwfQ%3D%3D"),
        VoiceOption("iePvrB4HtMcAormXZou7", "Sophie", "playful · Scottish · fun", female = true, previewUrl = "https://storage.googleapis.com/eleven-public-prod/database/workspace/d7f1113a0fbf4d048ac84d8b5356329a/voices/iePvrB4HtMcAormXZou7/5a78fedb-da8f-4454-a6eb-8fff43e93a76.mp3"),
        VoiceOption("cgSgspJ2msm6clMCkdW9", "Jessica", "playful · bright", female = true, previewUrl = "https://storage.googleapis.com/eleven-public-prod/premade/voices/cgSgspJ2msm6clMCkdW9/56a97bf8-b69b-448f-846c-c3a11683d45a.mp3"),
        VoiceOption("wCizWKvnaS3LOIUwHo98", "Ava", "British · conversational", female = true, previewUrl = "https://api.us.elevenlabs.io/v1/voices/wCizWKvnaS3LOIUwHo98/previews/audio?payload=eyJ2b2ljZV9zb3VyY2UiOiJjdXN0b20iLCJ3b3Jrc3BhY2VfaWQiOiIyYmQyZGVmMjc1MTc0NzcyYmM5NTgwYmRmNDgwODlhNyIsImZpbGVuYW1lIjoiODk1OGIxNzMtYzRiZC00ODk4LTkwZWUtMjlmOWZmNzgxNzk3Lm1wMyIsInRpbWVzdGFtcCI6MTc4MDU5MjQwMDAwMDAwMH0%3D"),
        VoiceOption("civNMpwNAw6qVmpSrAgz", "Edith", "calm · confident · the EDITH", female = true, previewUrl = "https://storage.googleapis.com/eleven-public-prod/database/workspace/866a1480be7748e59ac5b99202912bdb/voices/civNMpwNAw6qVmpSrAgz/53833ff5-6f54-460b-9df9-ed9d799b25ae.mp3"),
        VoiceOption("EXAVITQu4vr4xnSDxMaL", "Sarah", "warm · reassuring", female = true, previewUrl = "https://storage.googleapis.com/eleven-public-prod/premade/voices/EXAVITQu4vr4xnSDxMaL/01a3e33c-6e99-4ee7-8543-ff2216a32186.mp3"),
        VoiceOption("Xb7hH8MSUJpSbSDYk0k2", "Alice", "clear · British", female = true, previewUrl = "https://storage.googleapis.com/eleven-public-prod/premade/voices/Xb7hH8MSUJpSbSDYk0k2/d10f7534-11f6-41fe-a012-2de1e482d336.mp3"),
        // Deep / classic male
        VoiceOption("nPczCjzI2devNBz1zQrb", "Brian", "deep · resonant · JARVIS", previewUrl = "https://api.us.elevenlabs.io/v1/voices/nPczCjzI2devNBz1zQrb/previews/audio?payload=eyJ2b2ljZV9zb3VyY2UiOiJwcmVtYWRlIiwiZmlsZW5hbWUiOiIyZGQzZTcyYy00ZmQzLTQyZjEtOTNlYS1hYmM1ZDRlNWFhMWQubXAzIiwidGltZXN0YW1wIjoxNzgwNTkyNDAwMDAwMDAwfQ%3D%3D"),
        VoiceOption("JBFqnCBsd6RMkjVDRZzb", "George", "warm · British", previewUrl = "https://api.us.elevenlabs.io/v1/voices/JBFqnCBsd6RMkjVDRZzb/previews/audio?payload=eyJ2b2ljZV9zb3VyY2UiOiJwcmVtYWRlIiwiZmlsZW5hbWUiOiJlNjIwNmQxYS0wNzIxLTQ3ODctYWFmYi0wNmE2ZTcwNWNhYzUubXAzIiwidGltZXN0YW1wIjoxNzgwNTkyNDAwMDAwMDAwfQ%3D%3D"),
        VoiceOption("iP95p4xoKVk53GoZ742B", "Chris", "casual · down-to-earth", previewUrl = "https://storage.googleapis.com/eleven-public-prod/premade/voices/iP95p4xoKVk53GoZ742B/3f4bde72-cc48-40dd-829f-57fbf906f4d7.mp3"),
        VoiceOption("bIHbv24MWmeRgasZH58o", "Will", "relaxed · chill", previewUrl = "https://storage.googleapis.com/eleven-public-prod/premade/voices/bIHbv24MWmeRgasZH58o/8caf8f3d-ad29-4980-af41-53f20c72d7a4.mp3"),
        VoiceOption("cjVigY5qzO86Huf0OWal", "Eric", "smooth · trustworthy", previewUrl = "https://storage.googleapis.com/eleven-public-prod/premade/voices/cjVigY5qzO86Huf0OWal/d098fda0-6456-4030-b3d8-63aa048c9070.mp3"),
    )

    val default = voices.first { it.id == "nPczCjzI2devNBz1zQrb" } // Brian = current server default
    fun byId(id: String) = voices.firstOrNull { it.id == id }
}
