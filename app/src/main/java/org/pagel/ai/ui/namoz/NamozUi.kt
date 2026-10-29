package org.pagel.ai.ui.namoz

import org.pagel.ai.data.db.HabitTickEntity
import org.pagel.ai.data.db.NamozLearnEntity
import org.pagel.ai.data.db.NamozMarkEntity
import org.pagel.ai.data.db.NamozSettingsEntity
import org.pagel.ai.data.db.TaskEntity
import org.pagel.ai.data.habits.Namoz
import org.pagel.ai.data.habits.NamozStats
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Everything the Namoz page draws, computed once a minute from Boss's own ticks, marks and settings. */
data class NamozUi(
    val now: LocalDateTime,
    val today: LocalDate,
    /** false before 1 November: the rows wait, nothing can be ticked. */
    val started: Boolean,
    val hijri: String,
    val prayers: Map<Namoz.Prayer, TaskEntity>,
    val windows: List<Namoz.Window>,
    val current: Namoz.Prayer?,
    val next: Namoz.Window,
    val countdown: String,
    val doneToday: Set<Namoz.Prayer>,
    val marksToday: Map<Namoz.Prayer, String>,
    val week: Pair<Int, Int>,
    val perPrayer: List<Triple<Namoz.Prayer, Int, Int>>,
    val month: List<Pair<LocalDate, Set<Namoz.Prayer>>>,
    val mosque: Int,
    val learning: List<NamozLearnEntity>,
    val settings: NamozSettingsEntity,
    val reminders: Set<String>,
) {
    /** The prayers exist as habits (Boss tapped "Start tracking" once). */
    val tracking get() = prayers.size == Namoz.Prayer.entries.size

    companion object {
        fun of(
            now: LocalDateTime, prayers: List<TaskEntity>, ticks: List<HabitTickEntity>, marks: List<NamozMarkEntity>,
            learning: List<NamozLearnEntity>, settings: NamozSettingsEntity, reminders: Set<String>,
        ): NamozUi {
            val setup = Namoz.setupOf(settings)
            val today = now.toLocalDate()
            val byPrayer = prayers.mapNotNull { t -> Namoz.prayerOf(t.title ?: t.text)?.let { it to t } }.toMap()
            val done = NamozStats.done(prayers, ticks)
            val (next, at) = Namoz.next(now, setup)
            val day = today.toString()
            return NamozUi(
                now = now, today = today, started = Namoz.counts(day), hijri = Namoz.hijri(today),
                prayers = byPrayer, windows = Namoz.windows(today, setup), current = Namoz.current(now, setup),
                next = next, countdown = Namoz.countdown(now, at),
                doneToday = done[day].orEmpty(),
                marksToday = marks.filter { it.day == day }.mapNotNull { m -> Namoz.Prayer.entries.firstOrNull { it.name == m.prayer }?.let { it to m.how } }.toMap(),
                week = NamozStats.week(done, today), perPrayer = NamozStats.perPrayer(done, today),
                month = NamozStats.month(done, YearMonth.from(today)), mosque = NamozStats.mosque(marks, YearMonth.from(today)),
                learning = learning, settings = settings, reminders = reminders,
            )
        }

        /** Before the first emission: Tashkent's day, nothing ticked, so the first frame already has its rows. */
        fun empty(): NamozUi = of(LocalDateTime.now(NamozViewModel.ZONE).withSecond(0).withNano(0), emptyList(), emptyList(), emptyList(), emptyList(), NamozSettingsEntity(), emptySet())
    }
}
