package org.pagel.ai.data.inbox

import org.pagel.ai.data.db.InboxItemEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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

/** "Waiting for Aziz: the grant form" or, with nobody named, "Waiting for reply: the grant form". */
fun waitingLine(who: String, thing: String): String {
    val w = who.trim().replace(Regex("\\s+"), " ")
    return "$WAITING_PREFIX ${w.ifEmpty { "reply" }}: ${thing.trim()}"
}

/** True for a task that is someone else's ball. */
fun isWaiting(text: String) = text.startsWith("$WAITING_PREFIX ")

/** The day to check, [days] after [today] ("YYYY-MM-DD"). */
fun checkDate(today: String, days: Long = WAIT_DAYS): String = LocalDate.parse(today).plusDays(days).toString()
