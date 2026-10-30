package org.pagel.ai.data.inbox

import org.pagel.ai.data.db.InboxItemEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The GTD clarify rules for the Inbox (Boss 2026-10-30), apart from any screen so they are tested alone: how old a
 * save is, which are old enough to tidy in bulk, the line a one-tap Someday sends, and the Waiting-for wording.
 */

/** A save older than this many days is offered for the bulk tidy-up. */
const val OLD_DAYS = 60

/** The default check date for a Waiting-for: the day to look again. */
const val WAIT_DAYS = 3L

/** The prefix that marks a task as someone else's ball; pagel.uz has no waiting field, so the words carry it. */
const val WAITING_PREFIX = "Waiting for"

/** The post's own day, else the day it was saved; null when neither is a date. */
fun savedDay(i: InboxItemEntity): LocalDate? =
    listOf(i.posted, i.saved).firstNotNullOfOrNull { s -> runCatching { LocalDate.parse(s.trim().take(10)) }.getOrNull() }

/** Whole days between the save and [today] ("YYYY-MM-DD"); null when the save has no date. */
fun daysOld(i: InboxItemEntity, today: String): Int? =
    savedDay(i)?.let { ChronoUnit.DAYS.between(it, LocalDate.parse(today)).toInt() }

/** The saves at least [days] old, oldest first. A save with no date is never called old. */
fun olderThan(items: List<InboxItemEntity>, days: Int, today: String): List<InboxItemEntity> =
    items.mapNotNull { i -> daysOld(i, today)?.takeIf { it >= days }?.let { i to it } }.sortedByDescending { it.second }.map { it.first }

/** The one line a Someday sends as its task: the AI's next step, else the claim, else the post's first line (2+ words). */
fun somedayLine(i: InboxItemEntity): String {
    val first = i.next.ifBlank { i.claim }.ifBlank { i.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }.trim().replace(Regex("\\s+"), " ").take(160)
    return if (first.split(' ').count { it.isNotEmpty() } >= 2) first else "Look at: ${first.ifEmpty { "this save" }}"
}

/** "Waiting for Aziz: the grant form" or, with nobody named, "Waiting for: the grant form" (no filler word). */
fun waitingLine(who: String, thing: String): String {
    val w = who.trim().replace(Regex("\\s+"), " ")
    return if (w.isEmpty()) "$WAITING_PREFIX: ${thing.trim()}" else "$WAITING_PREFIX $w: ${thing.trim()}"
}

/** True for a task that is someone else's ball. */
fun isWaiting(text: String) = text.startsWith("$WAITING_PREFIX ") || text.startsWith("$WAITING_PREFIX:")

/** The day to check, [days] after [today] ("YYYY-MM-DD"). */
fun checkDate(today: String, days: Long = WAIT_DAYS): String = LocalDate.parse(today).plusDays(days).toString()

/** The hour a Calendar choice starts at until Boss turns the wheel. */
const val CALENDAR_TIME = "09:00"

private val MONTHS = Month.entries.associateBy { it.name.lowercase().take(3) }
private val WEEKDAYS = DayOfWeek.entries.associateBy { it.name.lowercase().take(3) }
private const val MON = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"
private val DAY_MONTH = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+$MON\b""")
private val MONTH_DAY = Regex("""\b$MON\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b""")
// Whole names only: "sat" and "sun" are words too ("it sat here").
private val WEEKDAY = Regex("""\b(mon|tue|wed|thu|fri|sat|sun)(day|sday|nesday|rsday|urday)\b""")

/**
 * The day a save talks about, for the Calendar choice's first guess (checker 2026-10-30 #6: "closes on Friday" ->
 * Friday): "15 November" / "Nov 15" (this year, or next when it has passed), "tomorrow", "today", a weekday (the next
 * one, today included). Null when the words name no day.
 */
fun dayIn(text: String, today: LocalDate): LocalDate? {
    val t = text.lowercase()
    DAY_MONTH.find(t)?.let { m -> return dateOf(m.groupValues[1].toInt(), m.groupValues[2], today) }
    MONTH_DAY.find(t)?.let { m -> return dateOf(m.groupValues[2].toInt(), m.groupValues[1], today) }
    if (Regex("""\btomorrow\b""").containsMatchIn(t)) return today.plusDays(1)
    if (Regex("""\b(today|tonight)\b""").containsMatchIn(t)) return today
    WEEKDAY.find(t)?.let { m -> return today.with(TemporalAdjusters.nextOrSame(WEEKDAYS.getValue(m.groupValues[1]))) }
    return null
}

private fun dateOf(day: Int, month: String, today: LocalDate): LocalDate? {
    val d = runCatching { LocalDate.of(today.year, MONTHS.getValue(month), day) }.getOrNull() ?: return null
    return if (d.isBefore(today)) d.plusYears(1) else d
}

private val PLAIN_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/** A due in plain words for a toast: "Mon 2 Nov", "Fri 31 Oct 09:00" (never "2026-11-02"). */
fun plainDay(due: String?): String {
    if (due.isNullOrBlank()) return ""
    val day = runCatching { LocalDate.parse(due.take(10)) }.getOrNull() ?: return due
    val time = due.drop(11).trim()
    return day.format(PLAIN_DAY) + if (time.isEmpty()) "" else " $time"
}
