package org.pagel.ai.data.inbox

import android.content.SharedPreferences
import kotlinx.coroutines.flow.first
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.data.tasks.TaskRepository

/** The daily goal of the Inbox (Boss 2026-10-31): five sorted, and the habit that follows it. */
const val INBOX_GOAL = 5

/** Boss's habit, as he first wrote it and as it is renamed ("Sort 5 saves in your Inbox"). */
const val INBOX_HABIT_TEXT = "Sort 5 saves in your Inbox"

/**
 * His habit "Go through five saves in your Inbox" follows the goal: ticked once when five are sorted (the same write as the
 * Habits screen), taken back when a sort is undone below five. Only a tick made here is ever taken back, never one Boss
 * made himself in Habits. The habit's text is renamed to [INBOX_HABIT_TEXT] once. [prefs] null = memory only (tests).
 */
class InboxGoalHabit(
    private val habits: HabitsRepository,
    private val tasks: TaskRepository,
    private val prefs: SharedPreferences?,
    private val today: () -> String,
) {
    private var tickedDay: String? = runCatching { prefs?.getString(TICKED, null) }.getOrNull()
    private var renamed = runCatching { prefs?.getBoolean(RENAMED, false) }.getOrNull() ?: false

    /** [n] = how many sorts count today. True when it ticked or unticked the habit. */
    suspend fun sync(n: Int): Boolean {
        val habit = habits.habits.first().firstOrNull { it.text.contains("saves in your Inbox", ignoreCase = true) } ?: return false
        val day = today()
        if (habit.text != INBOX_HABIT_TEXT && !renamed) {
            tasks.text(habit.id, INBOX_HABIT_TEXT)
            renamed = true
            prefs?.edit()?.putBoolean(RENAMED, true)?.apply()
        }
        val mine = tickedDay == day
        if (n >= INBOX_GOAL) {
            if (!mine && habits.tick(habit.id) != null) {
                tickedDay = day
                prefs?.edit()?.putString(TICKED, day)?.apply()
                return true
            }
        } else if (mine && habits.setDay(habit.id, day, false)) {
            tickedDay = null
            prefs?.edit()?.remove(TICKED)?.apply()
            return true
        }
        return false
    }

    private companion object {
        const val TICKED = "inbox_habit_ticked_day"
        const val RENAMED = "inbox_habit_renamed"
    }
}
