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
fun dealToday(tasks: List<TaskEntity>, finishing: Set<String>, cap: Int = SHOWN_AT_ONCE, today: String? = null): Dealt {
    val queue = tasks.filter { (it.status == "open" || it.id in finishing) && belongsToday(it, today) }
    val visible = queue.take(cap)
    return Dealt(visible, queue.size - visible.size, allDone = tasks.isNotEmpty() && queue.isEmpty())
}

/**
 * A task due after [today] belongs on Today only when Boss starred it; a late, today's or undated one always does.
 * [today] null = no date known (a test or the widget): everything belongs.
 */
fun belongsToday(t: TaskEntity, today: String?): Boolean {
    val due = t.due?.takeIf { it.length >= 10 }?.take(10) ?: return true
    return today == null || t.star || due <= today
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
    val time = org.pagel.ai.ui.glass.DatePick.timeText(due.drop(11).trim())
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

private val SINCE = DateTimeFormatter.ofPattern("EEE", Locale.UK)

/**
 * What Today's row says about its date: nothing for a future day (Today is today), "since Tue" for a missed one, the
 * time for one today. Quiet words, never red.
 */
fun todayMeta(due: String?, today: LocalDate): DueLabel? {
    val d = dueLabel(due, today) ?: return null
    return when (d.tone) {
        DueTone.LATE -> DueLabel(sinceWord(due, today), DueTone.LATE)
        DueTone.TODAY -> org.pagel.ai.ui.glass.DatePick.timeText(due?.drop(11)?.trim().orEmpty()).takeIf(String::isNotEmpty)?.let { DueLabel(it, DueTone.TODAY) }
        else -> null
    }
}

/** "since Tue" within a week, "since 12 Oct" beyond. */
fun sinceWord(due: String?, today: LocalDate): String {
    val day = LocalDate.parse(due!!.take(10))
    return "since " + if (day.isAfter(today.minusDays(7))) day.format(SINCE) else day.format(DAY)
}
