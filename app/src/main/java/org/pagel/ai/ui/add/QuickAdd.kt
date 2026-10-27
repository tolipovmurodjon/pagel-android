package org.pagel.ai.ui.add

import java.time.LocalDate
import java.time.LocalDateTime

/** One typed line, read like Todoist: the words that set a field are cut from [text], so the task reads clean. */
data class Quick(val text: String, val project: String?, val priority: Int?, val due: String?, val every: String?)

private val DAYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
private val FULL = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
private val I = setOf(RegexOption.IGNORE_CASE)

/**
 * The web's lib/quickadd.ts, rule for rule and in the same order: "call the bank tomorrow 3pm #me p1" → task, project,
 * priority, due, repeat. Todoist's p1 is the most urgent; PAGEL counts from P0, so p1 → 0 … p4 → 3.
 */
fun parseQuick(input: String, projects: List<String>, now: LocalDateTime): Quick {
    var text = " ${input.trim()} "
    var project: String? = null
    var priority: Int? = null
    var day: LocalDate? = null
    var time = ""
    var every: String? = null
    val today = now.toLocalDate()
    val dow = now.dayOfWeek.value % 7 // the web's getDay(): Sunday 0 … Saturday 6
    fun ahead(want: Int) = ((want - dow + 7) % 7).let { if (it == 0) 7 else it }

    fun take(re: Regex, fn: (MatchResult) -> Boolean) {
        val m = re.find(text) ?: return
        if (fn(m)) text = text.replaceFirst(m.value, " ")
    }

    take(Regex("""\s#([\w-]+)(?=\s)""", I)) { m ->
        projects.firstOrNull { it.equals(m.groupValues[1], ignoreCase = true) }?.also { project = it } != null
    }
    take(Regex("""\sp([1-4])(?=\s)""", I)) { m -> priority = m.groupValues[1].toInt() - 1; true }
    take(Regex("""\s(\d{1,2})(?::(\d{2}))?\s?(am|pm)(?=\s)""", I)) { m ->
        var h = m.groupValues[1].toInt() % 12
        if (m.groupValues[3].equals("pm", ignoreCase = true)) h += 12
        time = "%02d:%s".format(h, m.groupValues[2].ifEmpty { "00" }); true
    }
    take(Regex("""\s(\d{1,2}):(\d{2})(?=\s)""")) { m -> time = "${m.groupValues[1].padStart(2, '0')}:${m.groupValues[2]}"; true }
    // "every …" first, so "every monday" is a repeat, not a one-off Monday.
    take(Regex("""\s(?:every\s+day|daily)(?=\s)""", I)) { every = "day"; true }
    take(Regex("""\s(?:every\s+weekday|weekdays)(?=\s)""", I)) { every = "weekday"; true }
    take(Regex("""\severy((?:[\s,]+(?:and\s+)?(?:sun|mon|tue|wed|thu|fri|sat)[a-z]*)+)(?=\s)""", I)) { m ->
        val days = Regex("(sun|mon|tue|wed|thu|fri|sat)").findAll(m.groupValues[1].lowercase()).map { DAYS.indexOf(it.groupValues[1]) }.toList()
        // The first date is the nearest of those days, today included.
        day = today.plusDays(days.minOf { (it - dow + 7) % 7 }.toLong())
        every = if (days.size == 1) FULL[days[0]] else listOf(1, 2, 3, 4, 5, 6, 0).filter { it in days }.joinToString(",") { DAYS[it] }
        true
    }
    take(Regex("""\s(?:every\s+week|weekly)(?=\s)""", I)) { every = "week"; true }
    take(Regex("""\s(?:every\s+month|monthly)(?=\s)""", I)) { every = "month"; true }
    take(Regex("""\s(today|tod)(?=\s)""", I)) { day = today; true }
    take(Regex("""\s(tomorrow|tom)(?=\s)""", I)) { day = today.plusDays(1); true }
    take(Regex("""\snext week(?=\s)""", I)) { day = today.plusDays(((8 - dow) % 7).let { if (it == 0) 7 else it }.toLong()); true }
    take(Regex("""\s(?:on\s)?(sun|mon|tue|wed|thu|fri|sat)[a-z]*(?=\s)""", I)) { m ->
        day = today.plusDays(ahead(DAYS.indexOf(m.groupValues[1].lowercase())).toLong()); true
    }
    take(Regex("""\s(\d{4}-\d{2}-\d{2})(?=\s)""")) { m -> runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()?.also { day = it } != null }
    if ((time.isNotEmpty() || every != null) && day == null) day = today
    val due = day?.let { if (time.isEmpty()) it.toString() else "$it $time" }
    return Quick(text.replace(Regex("""\s+"""), " ").trim(), project, priority, due, every)
}
