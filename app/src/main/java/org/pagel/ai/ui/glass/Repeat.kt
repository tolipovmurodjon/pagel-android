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

    private val EVERY_N = Regex("^(\\d+) (days|weeks|months)$")
    private val FULL = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    /** The web's getDay(): Sunday 0 … Saturday 6. */
    private fun jsDay(d: java.time.LocalDate) = d.dayOfWeek.value % 7

    /**
     * The next date after [due] (its time kept), the web's nextDue (lib/repeat.ts) step for step: a habit ticked moves
     * here. If that is still before [today] it keeps stepping, so a habit missed for days comes back once, not N times.
     */
    fun nextDue(due: String, every: String, today: String): String {
        val time = due.drop(10)
        val end = java.time.LocalDate.parse(today)
        val n = EVERY_N.find(every)?.groupValues
        fun step(x: java.time.LocalDate): java.time.LocalDate = when {
            every == "day" -> x.plusDays(1)
            every == "week" -> x.plusDays(7)
            every == "2 weeks" -> x.plusDays(14)
            every == "weekday" -> { var y = x.plusDays(1); while (jsDay(y) == 0 || jsDay(y) == 6) y = y.plusDays(1); y }
            MONTH_DAYS.matches(every) -> {
                val set = monthDays(every)
                fun hit(y: java.time.LocalDate): Boolean { val last = y.lengthOfMonth(); return set.any { m -> if (m == LAST) y.dayOfMonth == last else m == y.dayOfMonth || (m > last && y.dayOfMonth == last) } }
                var y = x.plusDays(1)
                var i = 0
                while (i < 62 && !hit(y)) { y = y.plusDays(1); i++ }
                y
            }
            every == "month" || every == "year" || n?.get(2) == "months" -> x.plusMonths(if (every == "year") 12L else n?.get(1)?.toLong() ?: 1L)
            n != null -> x.plusDays(n[1].toLong() * if (n[2] == "weeks") 7 else 1)
            every in FULL -> { val w = FULL.indexOf(every); x.plusDays(((w - jsDay(x) + 7) % 7).let { if (it == 0) 7 else it }.toLong()) }
            DAY_SET.matches(every) -> {
                val set = daysOf(every)
                var y = x.plusDays(1)
                var i = 0
                while (i < 7 && jsDay(y) !in set) { y = y.plusDays(1); i++ }
                y
            }
            else -> x.plusDays(1)
        }
        var d = java.time.LocalDate.parse(due.take(10))
        do d = step(d) while (d < end)
        return d.toString() + time
    }
}
