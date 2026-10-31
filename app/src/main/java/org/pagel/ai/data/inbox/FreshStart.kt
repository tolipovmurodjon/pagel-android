package org.pagel.ai.data.inbox

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.pagel.ai.data.db.InboxItemEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Current Inbox against the Old pile (Boss 2026-10-31: "put the old ones as later, so it is clean"). Assumption stated to
 * Boss: a save from before 2026-11-01 00:00 in Tashkent is old. pagel.uz may move the line with `fresh_from` in its answer.
 */
const val FRESH_START = "2026-11-01"

/** A capture waits in Room under this name until pagel.uz gives it its own file. */
const val CAPTURE_PREFIX = "capture/"

/** pagel.uz refuses a longer save (saveToInbox). */
const val CAPTURE_MAX = 20_000

private fun lineDay(s: String?): LocalDate =
    runCatching { LocalDate.parse(s!!.trim().take(10)) }.getOrDefault(LocalDate.parse(FRESH_START))

/**
 * True for a save that belongs in the Inbox: its day is on or after the fresh start, or it was captured on this phone
 * (a capture made today must not fall into the Old pile), or it has no date at all (never called old).
 */
fun isCurrent(i: InboxItemEntity, freshFrom: String, captured: Set<String>): Boolean {
    if (i.file.startsWith(CAPTURE_PREFIX) || i.file in captured) return true
    val day = savedDay(i) ?: return true
    return !day.isBefore(lineDay(freshFrom))
}

/**
 * The `older_than` days that select exactly the saves before the fresh start: a save [n] days old on [today] was made
 * before the line when n is at least today minus the line, plus one. Never negative (0 = everything).
 */
fun oldPileDays(freshFrom: String, today: String): Int =
    (ChronoUnit.DAYS.between(lineDay(freshFrom), LocalDate.parse(today)) + 1).toInt().coerceAtLeast(0)

private val BULLET = Regex("^([-*•]|\d{1,3}[.)])\s+")

/**
 * What one capture becomes: every non-empty line is its own save (a pasted list is many items, Boss 2026-10-31), with
 * a list mark such as "- " or "2. " taken off the front.
 */
fun splitCapture(raw: String): List<String> =
    raw.replace("\r", "").lines().map { it.trim().replace(BULLET, "").trim() }.filter { it.isNotEmpty() }.map { it.take(CAPTURE_MAX) }

/**
 * What the Inbox repository and its outbox sender share: the files of saves captured on this phone (they stay in the
 * Inbox after pagel.uz names them, though their day may be before the fresh start), and how many old saves are left
 * (the sender takes one off as each reaches pagel.uz, so the count never jumps back). [prefs] null = memory only (tests).
 */
class CapturedFiles(private val prefs: SharedPreferences? = null) {
    private val state = MutableStateFlow(runCatching { prefs?.getStringSet(KEY, null)?.toSet() }.getOrNull().orEmpty())
    val files: StateFlow<Set<String>> = state

    /** Old saves pagel.uz still holds, as last known; null until the first answer. */
    val oldLeft = MutableStateFlow<Int?>(null)

    fun add(file: String) {
        state.update { (it + file).toList().takeLast(MAX).toSet() }
        runCatching { prefs?.edit()?.putStringSet(KEY, state.value)?.apply() }
    }

    private companion object {
        const val KEY = "captured_files"
        const val MAX = 400
    }
}
