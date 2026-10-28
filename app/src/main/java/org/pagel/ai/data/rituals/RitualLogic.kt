package org.pagel.ai.data.rituals

import org.pagel.ai.data.api.StuckDto
import org.pagel.ai.data.api.SweepRowDto
import org.pagel.ai.data.db.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** What happens to one of today's open stars at the end of the day (the web's shutdown: Tomorrow · Later · Drop). */
enum class Move(val label: String) { KEEP("Keep"), LATER("Later"), DROP("Drop") }

/** The web's sweep kinds and their words (sweep-client.tsx KIND_LABEL). */
enum class Kind(val wire: String, val label: String) {
    ACTION("action", "Do"), SOMEDAY("someday", "Someday"), DROP("drop", "Drop");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: ACTION
    }
}

/** The rituals' rules, pure, so they are tested on the JVM (dashboard review.ts, sweep.ts, shutdown/page.tsx). */
object Rituals {
    val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")

    /** GTD's weekly review: due when the last one is this many days old (review.ts REVIEW_DAYS). */
    const val REVIEW_DAYS = 7

    /** The web shows 12 candidates for tomorrow (shutdown-client.tsx `next.slice(0, 12)`). */
    const val CANDIDATES = 12

    /** pagel.uz takes one short task line: 20 words at most (task-api.ts apiAdd). */
    const val MAX_WORDS = 20

    /** The evening reminder's time when Boss has not set one. */
    const val EVENING = "21:30"

    /** The rituals belong to the evening they close: until 05:00 Tashkent it is still last night (review.ts ritualDay). */
    fun ritualDay(ms: Long): String = Instant.ofEpochMilli(ms - 5 * 3_600_000L).atZone(TASHKENT).toLocalDate().toString()

    fun reviewDue(lastReview: String?, today: String): Boolean {
        val last = lastReview?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return true
        return ChronoUnit.DAYS.between(last, LocalDate.parse(today)) >= REVIEW_DAYS
    }

    /** The later of two ritual days (pagel.uz's and the phone's own), so neither side steps the other back. */
    fun later(a: String?, b: String?): String? = listOfNotNull(a, b).maxOrNull()

    /** Today's stars still open, most important first (plans.ts starred()). */
    fun stars(open: List<TaskEntity>): List<TaskEntity> = open.filter { it.star && it.status == "open" }.sortedBy { it.priority }

    /**
     * Tomorrow's candidates (shutdown/page.tsx `next`): open tasks of the NOW phases, not starred, not someday (P3), not a
     * habit (Habits owns those); each project's next action first (GTD: every project shows one), then by priority.
     */
    fun candidates(open: List<TaskEntity>): List<TaskEntity> {
        val pool = open.filter { it.status == "open" && it.phaseNow && !it.star && it.priority < 3 && it.every.isNullOrBlank() }
        val first = open.filter { it.status == "open" && it.phaseNow }
            .groupBy { it.project }
            .mapNotNull { (_, ts) -> ts.sortedWith(compareBy({ it.priority }, { it.phaseSort }, { it.sort })).firstOrNull()?.id }
            .toSet()
        return pool.sortedWith(compareBy({ it.id !in first }, { it.priority }, { it.phaseSort }, { it.sort })).take(CANDIDATES)
    }

    /**
     * A pick toggled. No cap: Today deals the stars 3 at a time, so a 4th pick costs no attention (the web's shutdown,
     * Boss 2026-10-27: "Pick not working… keep my current ones"; checker P5 #3).
     */
    fun togglePick(picks: List<String>, id: String): List<String> = if (id in picks) picks - id else picks + id

    /** Close the day belongs to the evening: Today shows its pill from 18:00 Tashkent until 05:00 (the ritual night). */
    fun evening(ms: Long): Boolean = Instant.ofEpochMilli(ms).atZone(TASHKENT).hour.let { it >= 18 || it < 5 }

    /** The day as the rituals see it: until 05:00 it is still last night's date. */
    fun ritualDate(ms: Long): LocalDate = LocalDate.parse(ritualDay(ms))

    /** Stars tomorrow will hold: the kept ones plus the picks (the web's "N stars for tomorrow"). */
    fun starsTomorrow(stars: List<TaskEntity>, moves: Map<String, Move>, picks: List<String>): Int =
        stars.count { (moves[it.id] ?: Move.KEEP) == Move.KEEP } + picks.size

    /** The web's someday list in the review (review/page.tsx): open P3 tasks of the NOW phases. */
    fun someday(open: List<TaskEntity>): List<TaskEntity> =
        open.filter { it.status == "open" && it.phaseNow && it.priority == 3 }.sortedWith(compareBy({ it.project }, { it.sort }))

    /** pagel.uz's stuck projects, less any that got an open NOW task on the phone since (it has a next step now). */
    fun stuck(server: List<StuckDto>, open: List<TaskEntity>): List<StuckDto> {
        val live = open.filter { it.status == "open" && it.phaseNow }.map { it.project }.toSet()
        val added = open.filter { it.status == "open" && it.phase.isEmpty() }.map { it.project }.toSet()
        return server.filter { it.project !in live && it.project !in added }
    }

    /** The rows "Add N" keeps: not dropped, with words. */
    fun kept(rows: List<SweepRowDto>): List<SweepRowDto> = rows.filter { Kind.of(it.kind) != Kind.DROP && it.text.isNotBlank() }

    fun words(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** A row pagel.uz would refuse (over 20 words): the phone says so before Add, so nothing is lost on the way. */
    fun tooLong(row: SweepRowDto): Boolean = Kind.of(row.kind) != Kind.DROP && words(row.text) > MAX_WORDS

    /** The dump's lines: one skeleton row each while the AI reads (at most 8). */
    fun lines(dump: String): Int = dump.lines().count { it.isNotBlank() }.coerceIn(1, 8)

    /** The evening reminder's next ring after [now] (Tashkent): today at [at] if still ahead, else tomorrow. */
    fun nextEvening(at: String, now: ZonedDateTime): ZonedDateTime {
        val time = runCatching { LocalTime.parse(at) }.getOrDefault(LocalTime.parse(EVENING))
        val local = now.withZoneSameInstant(TASHKENT)
        val today = ZonedDateTime.of(local.toLocalDate(), time, TASHKENT)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }
}
