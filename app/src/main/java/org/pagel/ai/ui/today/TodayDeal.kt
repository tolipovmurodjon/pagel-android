package org.pagel.ai.ui.today

import org.pagel.ai.data.db.TaskEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The web's Today shows 3 at a time; the next one comes when one is finished (dashboard today/page.tsx CAP). */
const val SHOWN_AT_ONCE = 3

/** What Today shows now: [visible] rows, [waiting] behind them, [allDone] when every task of the day is ticked. */
data class Dealt(val visible: List<TaskEntity>, val waiting: Int, val allDone: Boolean)

/**
 * Deals today's tasks like today-list.tsx: open tasks in pagel.uz's order, [SHOWN_AT_ONCE] at a time. A task being
 * ticked ([finishing]) keeps its slot until its fold ends, then the next one slides into the free slot.
 */
fun dealToday(tasks: List<TaskEntity>, finishing: Set<String>, cap: Int = SHOWN_AT_ONCE): Dealt {
    val queue = tasks.filter { it.status == "open" || it.id in finishing }
    val visible = queue.take(cap)
    return Dealt(visible, queue.size - visible.size, allDone = tasks.isNotEmpty() && queue.isEmpty())
}

/** The short line a task shows, as the web's lib/headline.ts: links read as their label, cut at the first sentence. */
fun headline(text: String): String {
    val plain = text
        .replace(Regex("""\[\[[^\]|]+\|([^\]]+)]]"""), "$1")
        .replace(Regex("""\[\[(?:[^\]]*/)?([^\]/]+)]]"""), "$1")
        .replace(Regex("[*`]"), "")
        .trim()
    val first = plain.split(Regex("""(?<=[.!?:;])\s|\s[→(]"""))[0].trim().replace(Regex("[:;,]$"), "")
    return if (first.length > 90) first.take(88).trimEnd() + "…" else first
}

enum class DueTone { LATE, TODAY, SOON, NONE }

data class DueLabel(val text: String, val tone: DueTone)

private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.UK)
private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

/** "Today 15:00", "Tomorrow", "Friday 30 Oct", "20 Nov", with the web's tone (late red, today mint, soon). */
fun dueLabel(due: String?, today: LocalDate): DueLabel? {
    if (due.isNullOrBlank() || due.length < 10) return null
    val day = runCatching { LocalDate.parse(due.take(10)) }.getOrNull() ?: return null
    val time = due.drop(11).trim()
    val word = when {
        day == today -> "Today"
        day == today.plusDays(1) -> "Tomorrow"
        day < today.plusDays(7) -> day.format(WEEKDAY)
        else -> day.format(DAY)
    }
    val tone = when {
        day < today -> DueTone.LATE
        day == today -> DueTone.TODAY
        day <= today.plusDays(6) -> DueTone.SOON
        else -> DueTone.NONE
    }
    return DueLabel(if (time.isEmpty()) word else "$word $time", tone)
}
