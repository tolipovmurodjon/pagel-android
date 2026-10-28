package org.pagel.ai.ui.glass

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The date picker's rules, from the web's pickers.tsx, kept apart from the screen so tests read them directly. */
object DatePick {
    private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE", Locale.UK)
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)
    private val SPOKEN = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)

    data class Quick(val label: String, val day: LocalDate, val kind: String)

    /** Today, Tomorrow, This weekend (next Saturday), Next week (next Monday): the web's quick list. */
    fun quickDays(today: LocalDate): List<Quick> {
        val dow = today.dayOfWeek.value % 7 // Sunday 0 … Saturday 6, as the web's getDay()
        val sat = today.plusDays(((6 - dow + 7) % 7).let { if (it == 0) 7 else it }.toLong())
        val mon = today.plusDays(((8 - dow) % 7).let { if (it == 0) 7 else it }.toLong())
        return listOf(Quick("Today", today, "today"), Quick("Tomorrow", today.plusDays(1), "tomorrow"), Quick("This weekend", sat, "weekend"), Quick("Next week", mon, "week"))
    }

    fun weekday(d: LocalDate): String = d.format(SHORT_DAY)
    fun monthTitle(m: YearMonth): String = m.atDay(1).format(MONTH)
    /** What TalkBack reads for a day, and how the walkthrough finds it: "29 October 2026". */
    fun spoken(d: LocalDate): String = d.format(SPOKEN)

    /** The month as 6 weeks of 7, Monday first, with the days of the months around it (the web's 42 cells). */
    fun cells(m: YearMonth): List<LocalDate> {
        val first = m.atDay(1)
        val start = first.minusDays(((first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7).toLong())
        return (0 until 42).map { start.plusDays(it.toLong()) }
    }

    /** Every half hour of the day: 00:00 … 23:30. */
    val SLOTS: List<String> = (0 until 48).map { "%02d:%s".format(it / 2, if (it % 2 == 1) "30" else "00") }

    /** Any typed time, as the web takes it: "9:05", "905", "14:45", "2:45pm", "9.30", "12am". Null if it is not one. */
    fun parseTime(s: String): String? {
        val m = Regex("""^(\d{1,2})(?:[:.]?(\d{2}))?\s*(am|pm)?$""").find(s.trim().lowercase()) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (m.groupValues[3] == "pm" && h < 12) h += 12
        if (m.groupValues[3] == "am" && h == 12) h = 0
        return if (h < 24 && min < 60) "%02d:%02d".format(h, min) else null
    }

    /** The chip's words: "Today", "Tomorrow 15:00", "30 Oct"; no date = "Date" (the web's dueText). */
    fun dueText(due: String?, today: LocalDate): String {
        if (due.isNullOrBlank()) return "Date"
        val day = runCatching { LocalDate.parse(due.take(10)) }.getOrNull() ?: return due
        val time = timeText(due.drop(11))
        val label = when (day) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> day.format(DAY_MONTH) }
        return if (time.isEmpty()) label else "$label $time"
    }

    /** A day and an optional time (or range) as pagel.uz stores them: "2026-10-30", "2026-10-30 15:00", "2026-10-30 10:00-12:00". */
    fun due(day: LocalDate, time: String?): String = if (time.isNullOrEmpty()) day.toString() else "$day $time"

    /** The start of a time or a range ("10:00-12:00" → "10:00"); "" when there is none. */
    fun startOf(time: String): String = time.take(5).takeIf { Regex("""^\d{2}:\d{2}$""").matches(it) }.orEmpty()

    /** The end of a range ("10:00-12:00" → "12:00"); null for a single time. */
    fun endOf(time: String): String? = time.substringAfter('-', "").takeIf { Regex("""^\d{2}:\d{2}$""").matches(it) }

    /** A start and an end as one value: the end only when it is after the start (24 hours, no range over midnight). */
    fun range(start: String?, end: String?): String? = when {
        start.isNullOrEmpty() -> null
        end != null && end > start -> "$start-$end"
        else -> start
    }

    /** An hour after [time], never past 23:55 (a range stays inside its day). */
    fun plusHour(time: String): String {
        val t = runCatching { java.time.LocalTime.parse(time) }.getOrNull() ?: return "10:00"
        val later = t.plusHours(1)
        return if (later.isBefore(t)) "23:55" else "%02d:%02d".format(later.hour, later.minute)
    }

    /**
     * The end after the start moved from [from] to [to]: the same length, never past 23:55 (round B checker #6: a start
     * turned past the end dropped the end). When even 23:55 is not after [to], the end goes (a single time is left).
     */
    fun shiftEnd(from: String, end: String, to: String): String? {
        val f = runCatching { java.time.LocalTime.parse(from) }.getOrNull() ?: return end
        val e = runCatching { java.time.LocalTime.parse(end) }.getOrNull() ?: return end
        val t = runCatching { java.time.LocalTime.parse(to) }.getOrNull() ?: return end
        val minutes = java.time.Duration.between(f, e).toMinutes()
        val moved = (t.hour * 60 + t.minute + minutes).coerceAtMost(23 * 60 + 55L)
        val out = "%02d:%02d".format(moved / 60, moved % 60)
        return out.takeIf { it > to }
    }

    /** How a time or a range reads: "10:00–12:00" (an en dash), as the web shows it. */
    fun timeText(time: String): String = time.replace('-', '–')
}
