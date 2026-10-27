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
        val time = due.drop(11)
        val label = when (day) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> day.format(DAY_MONTH) }
        return if (time.isEmpty()) label else "$label $time"
    }

    /** A day and an optional time as pagel.uz stores them: "2026-10-30" or "2026-10-30 15:00". */
    fun due(day: LocalDate, time: String?): String = if (time.isNullOrEmpty()) day.toString() else "$day $time"
}
