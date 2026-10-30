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

    /** The id a habit answers to now: pagel.uz's, once it renamed one the phone made (checker 3 #5). */
    private suspend fun currentId(id: String): String = dao.adoptedId(id) ?: id

    private suspend fun edit(phoneId: String, change: (HabitPrefEntity) -> HabitPrefEntity) {
        val id = currentId(phoneId)
        dao.setPref(change(dao.prefOnce(id) ?: HabitPrefEntity(id)))
    }

    suspend fun emoji(id: String, key: String?) = edit(id) { it.copy(emoji = key) }
    suspend fun after(id: String, other: String?) {
        val follows = other?.let { o -> if (o.startsWith(HabitLook.MOMENT)) o else currentId(o) }
        edit(id) { it.copy(after = follows?.takeIf { o -> o != it.taskId }) }
    }
    suspend fun identity(id: String, line: String?) = edit(id) { it.copy(identity = line?.trim()?.takeIf(String::isNotEmpty)?.take(120)) }
    suspend fun minutes(id: String, n: Int?) = edit(id) { it.copy(minutes = n?.takeIf { m -> m in 1..600 }) }

    /** Freezes [day] for the habit: false when the month's freezes are used (a second freeze of the same day is free). */
    suspend fun freeze(phoneId: String, day: String): Boolean {
        val id = currentId(phoneId)
        val all = dao.freezesOnce()
        if (all.any { it.taskId == id && it.day == day }) return true
        if (freezesLeft(all, YearMonth.parse(day.take(7))) <= 0) return false
        return dao.freeze(HabitFreezeEntity(day, id)) != -1L
    }

    suspend fun unfreeze(id: String, day: String) = dao.unfreeze(day, currentId(id)) > 0
}
