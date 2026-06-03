package org.pagel.ai.data.usage

import org.pagel.ai.domain.AppCategory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a package to a focus category via a curated substring list (seeded with Boss's apps).
 * A single-user app knows its own ~80 apps better than any ML model; this stays editable later.
 */
@Singleton
class AppCategorizer @Inject constructor() {

    fun categorize(pkg: String): AppCategory {
        val p = pkg.lowercase()
        return when {
            PRODUCTIVE.any { p.contains(it) } -> AppCategory.PRODUCTIVE
            DISTRACTING.any { p.contains(it) } -> AppCategory.DISTRACTING
            else -> AppCategory.NEUTRAL
        }
    }

    companion object {
        private val PRODUCTIVE = listOf(
            "brilliant", "blinkist", "mutolaa", "quran", "tafsir", "zikr", "duolingo", "khan",
            "anki", "notion", "obsidian", "docs", "sheets", "calendar", "github", "gitlab",
            "androidstudio", "android.studio", "code", "kotlin", "drive", "keep", "wikipedia",
        )
        private val DISTRACTING = listOf(
            "instagram", "tiktok", "musically", "youtube", "facebook", "katana", "twitter",
            "snapchat", "reddit", "netflix", "tinder", "9gag", "game", "pubg", "mobilelegends",
        )
    }
}
