package org.pagel.ai.ui.today

import org.pagel.ai.data.db.TaskEntity
import java.time.LocalDate
import java.time.LocalTime

/**
 * Today's starting machine (core round 4): the hardest moment of a task is its first 30 seconds, and everything here
 * shortens it. Plain logic, tested by itself (StartingMachineTest): Today's own order, What now's one pick, the timers.
 * Borrowed: BJ Fogg's Tiny Habits (start ridiculously small), Gollwitzer's implementation intentions ("when X, I do Y").
 */

/**
 * Each task's key in Today's order: a task Boss dragged keeps its own sort; every other one keeps pagel.uz's place, read
 * as its position among the OPEN tasks (0, 1, 2…, as /api/v1/today lists them: finished ones are not in it), so the two
 * mix and one drag never renumbers the rest. A finished row sits just before the next open one, so it folds in place.
 */
fun todayKeys(tasks: List<TaskEntity>, sorts: Map<String, Double>): Map<String, Double> {
    var open = 0
    return tasks.associate { t ->
        val place = if (t.status == "open") (open++).toDouble() else open - 0.5
        t.id to (sorts[t.id] ?: place)
    }
}

/**
 * The open rows that still keep pagel.uz's place, with the key they show at now: what `TodayRepository.pinSorts` stores
 * once Boss's own order is in play (the full list [expanded], or any row with its own sort). Empty before that.
 */
fun unpinnedKeys(tasks: List<TaskEntity>, sorts: Map<String, Double>, expanded: Boolean): Map<String, Double> {
    if (!expanded && sorts.isEmpty()) return emptyMap()
    val open = tasks.filter { it.status == "open" }.mapTo(HashSet()) { it.id }
    return todayKeys(tasks, sorts).filterKeys { it in open && it !in sorts }
}

/** Today in Boss's order for the day ([todayKeys]); a tie keeps pagel.uz's order. */
fun orderToday(tasks: List<TaskEntity>, sorts: Map<String, Double>): List<TaskEntity> {
    val keys = todayKeys(tasks, sorts)
    return tasks.withIndex().sortedWith(compareBy({ keys[it.value.id] ?: 0.0 }, { it.index })).map { it.value }
}

/**
 * The sort a row moved from [from] to [to] takes: half way between its new neighbours (first: one below the top; last:
 * one above the end). The other rows keep theirs, so a drag is one write of one row.
 */
fun todaySortAfterMove(keys: List<Double>, from: Int, to: Int): Double {
    if (from == to) return keys[from]
    val rest = keys.toMutableList().apply { removeAt(from) }
    val before = rest.getOrNull(to - 1)
    val after = rest.getOrNull(to)
    return when {
        before == null && after == null -> keys[from]
        before == null -> after!! - 1.0
        after == null -> before + 1.0
        else -> (before + after) / 2.0
    }
}

/** The 🔋 tap, asked once a day. */
enum class Energy(val word: String, val label: String) {
    LOW("low", "Low"), OK("ok", "OK"), HIGH("high", "High");

    companion object {
        fun of(word: String?): Energy? = entries.firstOrNull { it.word == word }
    }
}

/** Where Boss is right now (What now's first question). [fits] says which task contexts can be done there. */
enum class Where(val word: String, val label: String) {
    PHONE("phone", "Phone only"), LAPTOP("laptop", "Laptop"), HOME("home", "Home"), OUT("out", "Out"), RESTING("resting", "Resting");

    /** A task needing [context] can be done here: the phone and "anywhere" tasks go everywhere; the rest need their place. */
    fun fits(context: String): Boolean = when (this) {
        PHONE, RESTING -> context == "phone" || context == "any"
        LAPTOP -> context == "laptop" || context == "phone" || context == "any"
        HOME -> context == "home" || context == "laptop" || context == "phone" || context == "any"
        OUT -> context == "out" || context == "phone" || context == "any"
    }
}

/** The minutes Boss has: 5, 15, 30 or 60+ (60 means no limit). */
val TIME_CHOICES = listOf(5, 15, 30, 60)

private val PHONE_VERBS = setOf("call", "text", "reply", "read", "watch", "listen", "message", "send", "check", "pay", "answer", "scroll")
private val LAPTOP_VERBS = setOf("code", "write", "design", "build", "edit", "debug", "draft", "record", "measure", "train", "implement", "refactor", "deploy", "plan")
private val OUT_WORDS = setOf("buy", "visit", "bank", "post", "pharmacy", "shop", "pick", "drop", "meet")
private val HOME_VERBS = setOf("clean", "cook", "wash", "tidy", "fix", "organize", "organise", "move")
private val LAPTOP_PROJECTS = setOf("pagel", "humopedia", "ababil", "labs", "muhub", "ci-rocket", "games", "autonomy")
private val QUICK_VERBS = setOf("reply", "text", "call", "pay", "send", "check", "buy", "answer")
private val LOW_EFFORT_VERBS = setOf("read", "reply", "text", "listen", "watch", "check", "pay", "send", "answer", "tidy")

private fun firstWord(title: String) = title.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.trim(',', '.', ':').orEmpty()
private fun words(title: String) = title.lowercase().split(Regex("[^a-z']+")).filter(String::isNotEmpty).toSet()

/**
 * Where a task can be done, from its words and its project (GTD context): the first word decides (call / text / reply /
 * read / watch = phone; code / write / design / build / edit = laptop; clean / cook = home), then any errand word (buy,
 * visit, bank, post office = out), then the project's default (the software projects = laptop); a short task with no
 * tool says "any". Boss corrects it in one tap in the task panel, which stores it.
 */
fun inferContext(title: String, project: String): String {
    val first = firstWord(title)
    val all = words(title)
    return when {
        first in OUT_WORDS || (first !in PHONE_VERBS && first !in LAPTOP_VERBS && all.any { it in OUT_WORDS }) -> "out"
        first in PHONE_VERBS -> if (all.any { it in OUT_WORDS } && first == "pay") "out" else "phone"
        first in LAPTOP_VERBS -> "laptop"
        first in HOME_VERBS -> "home"
        project.lowercase() in LAPTOP_PROJECTS -> "laptop"
        else -> "any"
    }
}

/** A task's context: the one Boss set, else the inferred one. */
fun contextOf(row: TodayRow): String = row.context?.takeIf(String::isNotBlank) ?: inferContext(row.title, row.project)

/** Minutes a task probably needs (a guess from its words): a quick verb 5, a big-looking one 60, the rest 30. */
fun estimateMinutes(title: String): Int = when {
    firstWord(title) in QUICK_VERBS -> 5
    looksBig(title) -> 60
    else -> 30
}

/** The energy a task asks for: reading, replying and the like are low; a big-looking task is high; the rest OK. */
fun effortOf(title: String): Energy = when {
    firstWord(title) in LOW_EFFORT_VERBS -> Energy.LOW
    looksBig(title) -> Energy.HIGH
    else -> Energy.OK
}

/**
 * What now by the four GTD criteria, in GTD's order: CONTEXT (what can be done where Boss is; Resting = only low-effort
 * phone things like reading), then TIME (the task's guess fits the minutes), then ENERGY (its effort is not above his),
 * then PRIORITY and Today's order ([whatNow]'s score). Null = nothing fits.
 */
fun whatNowGtd(
    order: List<TodayRow>, where: Where, minutes: Int, energy: Energy, day: LocalDate, now: LocalTime, free: Int?, skipped: Set<String>,
): TodayRow? {
    val fitting = order.filter { r ->
        !r.done && r.id !in skipped && r.every.isNullOrBlank() &&
            where.fits(contextOf(r)) &&
            (where != Where.RESTING || effortOf(r.title) == Energy.LOW) &&
            estimateMinutes(r.title) <= (if (minutes >= 60) Int.MAX_VALUE else minutes) &&
            effortOf(r.title).ordinal <= energy.ordinal
    }
    return whatNow(fitting, energy, day, now, free, emptySet())
}

/** At most this many picks before What now says "nothing else fits" (never a list of 20). */
const val MAX_PICKS = 3

/** The minutes a What now start runs, and a "Don't want to" start. */
const val START_MINUTES = 10
const val TWO_MINUTE_START = 2

/** Verbs that hide a project inside one line ("make", "build", "learn"…), as the Split-it hint reads them. */
private val VAGUE = setOf("make", "build", "do", "finish", "learn", "write", "create", "plan", "fix", "organize", "organise", "research")

/** A task that is probably bigger than it looks: a vague verb first, or more than 10 words. */
fun looksBig(title: String): Boolean {
    val words = title.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return words.size > 10 || words.firstOrNull()?.lowercase()?.trim(',', '.', ':') in VAGUE
}

/** A timed task's start on [day] ("2026-10-30 17:00" or "… 10:00-12:00"); null without a time or on another day. */
fun startOn(due: String?, day: LocalDate): LocalTime? {
    if (due == null || due.length < 16 || due.take(10) != day.toString()) return null
    return runCatching { LocalTime.parse(due.substring(11, 16)) }.getOrNull()
}

/** Minutes free before the next timed task today (Today's and the calendar's); null = nothing timed is ahead. */
fun freeMinutes(dueTimes: List<String?>, day: LocalDate, now: LocalTime): Int? =
    dueTimes.mapNotNull { startOn(it, day) }.filter { it.isAfter(now) }.minOrNull()
        ?.let { ((it.toSecondOfDay() - now.toSecondOfDay()) / 60) }

/**
 * What now: ONE task for right now. Starts from Today's order (the first is what Boss put first) and moves a task by
 * the moment: a timed task due within 15 minutes goes first; one later today waits; low energy, little free time or
 * the late evening push a big task down; high energy before 15:00 lifts it (eat the frog). Habits live on Habits.
 */
fun whatNow(order: List<TodayRow>, energy: Energy, day: LocalDate, now: LocalTime, free: Int?, skipped: Set<String>): TodayRow? {
    var best: TodayRow? = null
    var bestScore = Double.MAX_VALUE
    for ((i, r) in order.withIndex()) {
        if (r.done || r.id in skipped || !r.every.isNullOrBlank()) continue
        var score = i.toDouble()
        startOn(r.due, day)?.let { at ->
            val minutes = (at.toSecondOfDay() - now.toSecondOfDay()) / 60
            score += if (minutes <= 15) -100.0 else 50.0
        }
        if (looksBig(r.title)) {
            if (energy == Energy.LOW) score += 10.0
            if (energy == Energy.HIGH && now.hour < 15) score -= 3.0
            if (free != null && free < 30) score += 20.0
            if (now.hour >= 20) score += 5.0
        }
        if (score < bestScore) { bestScore = score; best = r }
    }
    return best
}

/** "09:58" for a countdown's seconds left (never negative). */
fun clockText(secondsLeft: Long): String {
    val s = secondsLeft.coerceAtLeast(0)
    return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/** What What now says when a task has no micro-steps and no steps: a physical 30-second start, the same for any task. */
const val FIRST_FALLBACK = "Put it in front of you · 30 s"

/** The first thing to do: the first micro-step, else the first open step, else [FIRST_FALLBACK]. */
fun firstStepOf(micro: List<String>, openSteps: List<String>): String =
    micro.firstOrNull() ?: openSteps.firstOrNull() ?: FIRST_FALLBACK
