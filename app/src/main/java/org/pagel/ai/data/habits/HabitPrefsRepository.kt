package org.pagel.ai.data.habits

import kotlinx.coroutines.flow.Flow
import org.pagel.ai.data.db.HabitFreezeEntity
import org.pagel.ai.data.db.HabitPrefEntity
import org.pagel.ai.data.db.HabitPrefsDao
import java.time.YearMonth

/**
 * Habits 2.0's own touches (round D): the emoji, the habit it follows, the identity line, a timed habit's minutes, and
 * the streak freezes. Room only, on this phone (pagel.uz has no field for them yet: START HERE lists the server
 * follow-up). A freeze is refused once the month's two are used.
 */
class HabitPrefsRepository(private val dao: HabitPrefsDao) {
    val prefs: Flow<List<HabitPrefEntity>> = dao.prefs()
    val freezes: Flow<List<HabitFreezeEntity>> = dao.freezes()

    private suspend fun edit(id: String, change: (HabitPrefEntity) -> HabitPrefEntity) =
        dao.setPref(change(dao.prefOnce(id) ?: HabitPrefEntity(id)))

    suspend fun emoji(id: String, key: String?) = edit(id) { it.copy(emoji = key) }
    suspend fun after(id: String, other: String?) = edit(id) { it.copy(after = other?.takeIf { o -> o != id }) }
    suspend fun identity(id: String, line: String?) = edit(id) { it.copy(identity = line?.trim()?.takeIf(String::isNotEmpty)?.take(120)) }
    suspend fun minutes(id: String, n: Int?) = edit(id) { it.copy(minutes = n?.takeIf { m -> m in 1..600 }) }

    /** Freezes [day] for the habit: false when the month's freezes are used (a second freeze of the same day is free). */
    suspend fun freeze(id: String, day: String): Boolean {
        val all = dao.freezesOnce()
        if (all.any { it.taskId == id && it.day == day }) return true
        if (freezesLeft(all, YearMonth.parse(day.take(7))) <= 0) return false
        return dao.freeze(HabitFreezeEntity(day, id)) != -1L
    }

    suspend fun unfreeze(id: String, day: String) = dao.unfreeze(day, id) > 0
}
