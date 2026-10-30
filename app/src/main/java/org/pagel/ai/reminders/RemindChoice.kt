package org.pagel.ai.reminders

import java.time.LocalTime

/**
 * "🔔 Remind me" on a timed task (core round 4, Boss: "what is 'open task / 10 before'?"): one plain choice instead of an
 * unexplained switch. Minutes before the task's time; 0 = at the time; [NotifyPrefs.OFF] = no ring. Pure, tested.
 */
object RemindChoice {
    val ALL = listOf(0, 10, 30, 60, NotifyPrefs.OFF)

    /**
     * The rings one task gets, as minutes before its time (0 = on time): its choice, or none made yet. A task the old
     * "Also 10 min before" switch set ([oldEarly], no choice since) keeps both of its rings, on time and 10 early.
     */
    fun rings(chosen: Int?, oldEarly: Boolean): List<Int> = when {
        chosen == NotifyPrefs.OFF -> emptyList()
        chosen != null -> listOf(chosen)
        oldEarly -> listOf(0, 10)
        else -> listOf(0)
    }

    /** "10 min", "1 h" (a notification's "in 10 min · …"). */
    fun words(minutes: Int): String = if (minutes >= 60 && minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"

    /** The picker's words: At the time · 10 min early · 30 min early · 1 h early · Off. */
    fun label(minutes: Int): String = when (minutes) {
        NotifyPrefs.OFF -> "Off"
        0 -> "At the time"
        else -> "${words(minutes)} early"
    }

    /** The row's value with the ring's own time: "10 min early, 17:50"; "Off" without one. */
    fun value(minutes: Int, due: String?): String {
        if (minutes == NotifyPrefs.OFF) return "Off"
        val at = due?.takeIf { it.length >= 16 }?.let { runCatching { LocalTime.parse(it.substring(11, 16)) }.getOrNull() } ?: return label(minutes)
        val ring = at.minusMinutes(minutes.toLong())
        return "${label(minutes)}, %02d:%02d".format(java.util.Locale.ROOT, ring.hour, ring.minute)
    }
}
