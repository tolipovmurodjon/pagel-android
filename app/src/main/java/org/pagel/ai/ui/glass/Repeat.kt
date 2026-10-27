package org.pagel.ai.ui.glass

/**
 * Repeats, as the web writes them (dashboard lib/repeat.ts): "day", "weekday", "week", "month", a day name
 * ("monday"), a set of days ("tue,thu,sun") or days of the month ("month 1,15", "month 15,last"; server d8be98c).
 * Day numbers are the web's getDay(): Sunday 0 … Saturday 6. In a month, [LAST] stands for "last".
 */
object Repeat {
    private val SHORT = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
    private val NICE = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    /** The week as the pickers show it: Monday first. */
    val WEEK = listOf(1, 2, 3, 4, 5, 6, 0)
    val NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    /** The web's presets: "" = Never. */
    val PRESETS = listOf("" to "Never", "day" to "Every day", "weekday" to "Every weekday", "week" to "Every week", "month" to "Every month")

    private val DAY_SET = Regex("^(sun|mon|tue|wed|thu|fri|sat)(,(sun|mon|tue|wed|thu|fri|sat))*$")

    fun daysOf(every: String): List<Int> = every.split(",").map { SHORT.indexOf(it.trim().take(3)) }.filter { it >= 0 }

    fun daySet(days: Collection<Int>): String = WEEK.filter { it in days }.joinToString(",") { SHORT[it] }

    /** "last" in a month's days. */
    const val LAST = 32
    private val MONTH_DAYS = Regex("^month (([1-9]|[12][0-9]|3[01]|last)(,([1-9]|[12][0-9]|3[01]|last))*)$")

    fun isMonthly(every: String?): Boolean = every == "month" || (every != null && MONTH_DAYS.matches(every))

    /** The days of the month a repeat falls on; plain "month" = none chosen (it keeps its date's day). */
    fun monthDays(every: String?): List<Int> = MONTH_DAYS.find(every.orEmpty())?.groupValues?.get(1)?.split(",")?.map { if (it == "last") LAST else it.toInt() }.orEmpty()

    /** A day of the month toggled: none left = plain monthly. Days in order, "last" at the end. */
    fun toggleMonthDay(every: String?, day: Int): String {
        val on = monthDays(every)
        val next = (if (day in on) on - day else on + day).sorted()
        return if (next.isEmpty()) "month" else "month " + next.joinToString(",") { if (it == LAST) "last" else it.toString() }
    }

    fun label(every: String): String = when {
        every == "day" -> "Daily"
        every == "weekday" -> "Weekdays"
        every == "week" -> "Weekly"
        every == "month" -> "Monthly"
        MONTH_DAYS.matches(every) -> "Monthly · " + monthDays(every).joinToString(", ") { if (it == LAST) "Last" else it.toString() }
        DAY_SET.matches(every) -> "Every " + WEEK.filter { it in daysOf(every) }.joinToString(", ") { NICE[it] }
        else -> "Every $every"
    }

    /** The days lit in the picker: weekdays and every day light theirs too, so the toggles always show what repeats. */
    fun litDays(every: String?): List<Int> = when (every) {
        null, "" -> emptyList()
        "weekday" -> listOf(1, 2, 3, 4, 5)
        "day" -> listOf(0, 1, 2, 3, 4, 5, 6)
        else -> daysOf(every)
    }

    /** A day toggled in the picker: none = no repeat, all 7 = every day, Monday-Friday = weekdays (the web's toggle). */
    fun toggle(every: String?, day: Int): String? {
        val on = litDays(every)
        val next = if (day in on) on - day else on + day
        return when {
            next.isEmpty() -> null
            next.size == 7 -> "day"
            daySet(next) == "mon,tue,wed,thu,fri" -> "weekday"
            else -> daySet(next)
        }
    }
}
