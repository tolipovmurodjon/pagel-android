package org.pagel.ai.widget

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.api.HabitMeasuresResponse
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.habits.HabitsRepository
import java.time.Instant
import java.time.ZoneId

/**
 * The habit widgets' one door into the app's habits (Phase 8). The widgets know only these three calls, so when round D
 * (feat/roundD-habits: HabitBoard, habit prefs, `habitGroup`) is merged, only [RoomWidgetHabits] changes: build the
 * rings from `habitBoard(...)` (Boss's emoji picks) and find prayers with `isNamoz` (see START HERE "Phase 8").
 */
interface WidgetHabits {
    /** Today's habits as rings ("3/5"), prayers apart. */
    fun habits(): Flow<HabitsGlance>
    /** Today's five prayers with their times. */
    fun namoz(): Flow<NamozGlance>
    /** One tap on a ring or a prayer: the same tick as the Habits tab (Room first, then the outbox). */
    suspend fun tap(id: String): Boolean
}

class RoomWidgetHabits(
    private val repo: HabitsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : WidgetHabits {
    override fun habits(): Flow<HabitsGlance> = combine(repo.habits, repo.ticks()) { h, t -> habitsGlance(h, t, repo.today()) }

    override fun namoz(): Flow<NamozGlance> = combine(repo.habits, repo.ticks()) { h, t ->
        val now = Instant.ofEpochMilli(clock()).atZone(TASHKENT)
        namozGlance(h, t, now.toLocalDate(), now.toLocalTime())
    }

    override suspend fun tap(id: String): Boolean = repo.tick(id) != null
}

/** The Calories widget's numbers: the saved copy at once (offline too), refreshed from pagel.uz in the background. */
interface WidgetCalories {
    fun today(): Flow<CaloriesGlance?>
    suspend fun refresh()
}

/**
 * Calories from pagel.uz (round D item 1 keeps the entries there; the phone has no Room table for them yet): one small
 * `GET /api/v1/habits?days=1`, the habit whose unit is kcal, today's total. The last answer is kept in a small private
 * SharedPreferences file (a few numbers, not a list), so the widget draws at once and offline. After round D item 1
 * lands its Room table, swap this for a Room read; the widget does not change.
 */
class ServerCalories(
    private val ctx: Context,
    private val api: PagelApi,
    private val accounts: AccountStore,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : WidgetCalories {
    private val saved = MutableStateFlow<CaloriesSnapshot?>(null)
    @Volatile private var loaded = false

    override fun today(): Flow<CaloriesGlance?> = snapshots().map { it?.glanceOn(today()) }

    private fun snapshots(): StateFlow<CaloriesSnapshot?> {
        if (!loaded) { loaded = true; saved.value = CaloriesSnapshot.read(prefs()) }
        return saved.asStateFlow()
    }

    override suspend fun refresh() {
        withContext(io) { fetch() }
    }

    private suspend fun fetch() {
        val account = (accounts.state.value ?: accounts.load()) as? AccountState.Paired ?: return
        when (val r = api.habitMeasures(account.account.serverUrl, account.account.token)) {
            is Outcome.Ok -> {
                val snap = CaloriesSnapshot.of(r.value, today())
                CaloriesSnapshot.write(prefs(), snap)
                loaded = true
                saved.value = snap
            }
            // Offline or refused: the saved copy stays (no spinner, no error on a widget).
            is Outcome.Failed -> Unit
        }
    }

    private fun today(): String = Instant.ofEpochMilli(clock()).atZone(TASHKENT).toLocalDate().toString()

    private fun prefs() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val PREFS = "pagel-widget-calories"
    }
}

/** The kcal habit's numbers on one day, as last read from pagel.uz. */
data class CaloriesSnapshot(val day: String, val habitId: String?, val eaten: Int, val goal: Int?, val atMost: Boolean) {
    /** On a new day before the next answer: the same goal, nothing eaten yet. */
    fun glanceOn(today: String): CaloriesGlance? =
        if (habitId == null) null else CaloriesGlance(habitId, if (day == today) eaten else 0, goal, atMost)

    companion object {
        fun of(r: HabitMeasuresResponse, today: String): CaloriesSnapshot {
            val h = r.habits.firstOrNull { it.unit.equals("kcal", ignoreCase = true) }
            val day = r.day.ifEmpty { today }
            return CaloriesSnapshot(
                day = day,
                habitId = h?.id,
                eaten = h?.let { (r.totals[it.id] ?: 0.0).toInt() } ?: 0,
                goal = h?.goal?.toInt()?.takeIf { it > 0 },
                atMost = h?.goalKind != "at_least",
            )
        }

        fun read(p: android.content.SharedPreferences): CaloriesSnapshot? {
            val day = p.getString("day", null) ?: return null
            return CaloriesSnapshot(day, p.getString("habit", null), p.getInt("eaten", 0), p.getInt("goal", 0).takeIf { it > 0 }, p.getBoolean("atMost", true))
        }

        fun write(p: android.content.SharedPreferences, s: CaloriesSnapshot) {
            p.edit().putString("day", s.day).putString("habit", s.habitId).putInt("eaten", s.eaten).putInt("goal", s.goal ?: 0)
                .putBoolean("atMost", s.atMost).apply()
        }
    }
}

internal val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")
