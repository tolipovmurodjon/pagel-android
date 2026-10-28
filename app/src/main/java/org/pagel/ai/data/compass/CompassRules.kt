package org.pagel.ai.data.compass

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

/**
 * The Compass's levels (Boss 2026-10-28): Why I live → 10-year → Year (birthday to birthday) → four 12-week seasons
 * made by the app → weeks. Each goal links UP to one parent; every chain ends at "Why I live". Someday has no limit and
 * no season. The wire names are what pagel.uz keeps as `kind` (compass-api.ts KINDS).
 */
enum class Level(val wire: String, val label: String) {
    PURPOSE("purpose", "Why I live"),
    TEN_YEAR("ten_year", "10 years"),
    YEAR("year", "This year"),
    SEASON("season", "This season"),
    SOMEDAY("someday", "Someday");

    /** The level a goal here serves: purpose serves nothing; Someday may serve any level or none. */
    val parentLevel: Level? get() = when (this) { PURPOSE, SOMEDAY -> null; TEN_YEAR -> PURPOSE; YEAR -> TEN_YEAR; SEASON -> YEAR }

    companion object {
        fun of(wire: String): Level? = entries.firstOrNull { it.wire == wire }
    }
}

/** The sealed notes beside the goals: the weekly two questions, a season review, the age report, a mid-season change's why. */
object NoteKind {
    const val WEEK = "week"
    const val SEASON_REVIEW = "season_review"
    const val AGE_REPORT = "age_report"
    const val CHANGE_WHY = "change_why"
    val all = setOf(WEEK, SEASON_REVIEW, AGE_REPORT, CHANGE_WHY)
}

/** One key result Boss ticks himself. */
@Serializable
data class KeyResult(val text: String, val done: Boolean = false)

/**
 * Everything Boss writes about one goal: this whole object is sealed (CompassCrypto), never stored or sent in the clear.
 * [myPart] is in his hands and scored; [outcome] is not in his hands and never scored: it closes as "a different door"
 * ([state] DOOR), never as failed. [excused] holds the weeks a "Reset, not restart" set aside.
 */
@Serializable
data class GoalBody(
    val title: String = "",
    val why: String = "",
    val myPart: String = "",
    val outcome: String = "",
    val keyResults: List<KeyResult> = emptyList(),
    val leadAction: String = "",
    val habitId: String? = null,
    val state: String = OPEN,
    val excused: List<String> = emptyList(),
    val createdAt: Long = 0,
) {
    companion object {
        const val OPEN = "open"
        const val DONE = "done"
        const val DOOR = "door"
    }
}

/** A note's sealed words: the answers in order, and the goal it is about (if any). */
@Serializable
data class NoteBody(val answers: List<String> = emptyList(), val goalId: String? = null)

/** A goal as the unlocked screen holds it (memory only; locking drops it). */
data class Goal(val id: String, val level: Level, val parent: String?, val starts: String?, val ends: String?, val body: GoalBody)

/** A note as the unlocked screen holds it. */
data class Note(val id: String, val kind: String, val day: String?, val body: NoteBody)

/** One of the year's four seasons: 12 working weeks and a 13th for the season review; the 4th runs to 31 Oct. */
data class Season(val n: Int, val starts: LocalDate, val ends: LocalDate) {
    val workEnds: LocalDate get() = starts.plusWeeks(12).minusDays(1)
}

/** What one season goal's "my part" scored: only what is in Boss's hands counts. */
data class Score(val value: Float?, val weeksHit: Int, val weeksCounted: Int, val krDone: Int, val krTotal: Int, val missedLastWeek: Boolean, val missedTwice: Boolean) {
    /** 70 % is a win (OKR scoring), shown as a full mint state, never as "70 %". */
    val win: Boolean get() = value != null && value >= CompassRules.WIN
}

object CompassRules {
    /** Boss's birthday (brain me/who-i-am): his year runs 1 Nov → 31 Oct and is titled by his age. */
    val BORN: LocalDate = LocalDate.of(2007, 11, 1)
    const val SEASON_CAP = 3
    const val WIN = 0.7f
    const val WEEKS = 12

    /** The goal year that holds [day]: from the birthday on or before it, to the day before the next. */
    fun yearStart(day: LocalDate, born: LocalDate = BORN): LocalDate {
        val b = born.withYear(day.year)
        return if (day.isBefore(b)) born.withYear(day.year - 1) else b
    }

    fun yearEnd(start: LocalDate): LocalDate = start.plusYears(1).minusDays(1)

    fun ageOn(day: LocalDate, born: LocalDate = BORN): Int = Period.between(born, day).years

    /** "Year 19" for the year that starts on his 19th birthday. */
    fun yearTitle(start: LocalDate, born: LocalDate = BORN): String = "Year ${ageOn(start, born)}"

    /** The age report's title for the year that ends at [end]: "Year 18 → 19". */
    fun reportTitle(end: LocalDate, born: LocalDate = BORN): String {
        val turning = ageOn(end.plusDays(1), born)
        return "Year ${turning - 1} → $turning"
    }

    /** Four seasons of 13 weeks (12 + a review week) from the year's start; the last runs to the year's end. */
    fun seasons(yearStart: LocalDate): List<Season> = (0 until 4).map { i ->
        val s = yearStart.plusWeeks(13L * i)
        Season(i + 1, s, if (i == 3) yearEnd(yearStart) else s.plusWeeks(13).minusDays(1))
    }

    fun seasonOf(day: LocalDate, born: LocalDate = BORN): Season =
        seasons(yearStart(day, born)).last { !day.isBefore(it.starts) }

    /** 1-based week of [season] that holds [day]; 13 is the review week (the 4th season's tail stays 13). */
    fun weekOf(season: Season, day: LocalDate): Int = (ChronoUnit.DAYS.between(season.starts, day) / 7 + 1).toInt().coerceIn(1, 13)

    fun weekStart(season: Season, week: Int): LocalDate = season.starts.plusWeeks(week - 1L)

    /** A season holds at most 3 goals (his own failure pattern: too many goals). */
    fun canAdd(seasonGoals: Int): Boolean = seasonGoals < SEASON_CAP

    /** The season's 3 change freely in its first week; after that a change asks one line why. */
    fun changeNeedsWhy(season: Season, day: LocalDate): Boolean = day.isAfter(season.starts.plusDays(6))

    /**
     * The levels a goal at [level] may link up to, and whether [parent] is one. Purpose has no parent; Someday may
     * serve any level above season or none.
     */
    fun parentOk(level: Level, parent: Goal?): Boolean = when (level) {
        Level.PURPOSE -> parent == null
        Level.SOMEDAY -> parent == null || parent.level in setOf(Level.PURPOSE, Level.TEN_YEAR, Level.YEAR)
        else -> parent != null && parent.level == level.parentLevel
    }

    /** The chain from [id] up to "Why I live", or null when it breaks (a missing parent, a loop, a wrong level). */
    fun chain(goals: Map<String, Goal>, id: String): List<Goal>? {
        val out = mutableListOf<Goal>()
        var at: Goal = goals[id] ?: return null
        while (true) {
            val cur = at
            if (out.any { it.id == cur.id } || out.size > 6) return null
            out += cur
            val p = cur.parent ?: break
            val up = goals[p] ?: return null
            if (!parentOk(cur.level, up)) return null
            at = up
        }
        return if (out.last().level == Level.PURPOSE || out.first().level == Level.SOMEDAY) out else null
    }

    /**
     * A season goal's score: weeks its lead action was done (at least once) over the weeks that count (finished weeks,
     * plus this one once it is hit; weeks a reset set aside never count), averaged with its key results ticked.
     * [hitDays] are the days the lead-action habit was ticked. Null while nothing can be measured yet.
     */
    fun score(season: Season, today: LocalDate, body: GoalBody, hitDays: Set<String>): Score {
        val thisWeek = weekOf(season, today).coerceAtMost(WEEKS)
        val excused = body.excused.toSet()
        fun hit(w: Int): Boolean {
            val s = weekStart(season, w)
            return (0 until 7).any { s.plusDays(it.toLong()).toString() in hitDays }
        }
        val counted = (1..thisWeek).filter { w -> weekStart(season, w).toString() !in excused && (w < thisWeek || hit(w)) }
        val weeksHit = if (body.leadAction.isBlank()) 0 else counted.count(::hit)
        val weeks = if (body.leadAction.isBlank()) 0 else counted.size
        val krTotal = body.keyResults.size
        val krDone = body.keyResults.count { it.done }
        val parts = buildList {
            if (weeks > 0) add(weeksHit.toFloat() / weeks)
            if (krTotal > 0) add(krDone.toFloat() / krTotal)
        }
        val missed = { w: Int -> w >= 1 && body.leadAction.isNotBlank() && weekStart(season, w).toString() !in excused && !hit(w) }
        return Score(
            value = parts.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            weeksHit = weeksHit, weeksCounted = weeks, krDone = krDone, krTotal = krTotal,
            missedLastWeek = thisWeek > 1 && missed(thisWeek - 1),
            missedTwice = thisWeek > 2 && missed(thisWeek - 1) && missed(thisWeek - 2),
        )
    }

    /** "Reset, not restart": every missed week so far is set aside; the plan goes on from this week. No red, no guilt. */
    fun reset(season: Season, today: LocalDate, body: GoalBody, hitDays: Set<String>): GoalBody {
        val thisWeek = weekOf(season, today).coerceAtMost(WEEKS)
        val missed = (1 until thisWeek).map { weekStart(season, it).toString() }.filter { s ->
            val start = LocalDate.parse(s)
            (0 until 7).none { start.plusDays(it.toLong()).toString() in hitDays }
        }
        return body.copy(excused = (body.excused + missed).distinct().sorted())
    }

    /** The first words of a goal for Today's quiet line (at most 6 words, 48 letters). */
    fun firstWords(title: String): String {
        val words = title.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val cut = words.take(6).joinToString(" ")
        return (if (cut.length > 48) cut.take(47).trimEnd() + "…" else if (words.size > 6) "$cut…" else cut)
    }
}
