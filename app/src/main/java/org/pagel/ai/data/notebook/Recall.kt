package org.pagel.ai.data.notebook

/** FSRS's four answers (pagel.uz fsrs.ts GRADES), in the order the buttons sit. */
enum class Grade(val wire: String, val label: String) {
    AGAIN("again", "Again"),
    HARD("hard", "Hard"),
    GOOD("good", "Good"),
    EASY("easy", "Easy"),
}

/** One recall card as pagel.uz deals it (`GET /api/v1/recall/due`): a question, its answer, when it is due. */
data class RecallCard(val id: String, val question: String, val answer: String, val due: String, val sourceKind: String = "", val sourceId: String = "")

/**
 * A "Review 5" session (Phase 6b, plan: "a daily 5-card review, FSRS scheduling"): one card at a time, its answer
 * hidden until Boss reveals it, then one of four grades. Pure: the screen draws it, the repository sends each grade
 * (offline, it waits in the outbox; pagel.uz's FSRS decides the next due).
 */
data class RecallSession(
    val cards: List<RecallCard> = emptyList(),
    val index: Int = 0,
    val revealed: Boolean = false,
    val graded: List<Pair<String, Grade>> = emptyList(),
) {
    val current: RecallCard? get() = cards.getOrNull(index)
    val finished: Boolean get() = cards.isNotEmpty() && index >= cards.size
    /** "2/5": the card on screen and how many this session has. */
    val position: String get() = "${(index + 1).coerceAtMost(cards.size)}/${cards.size}"

    fun reveal(): RecallSession = if (current == null) this else copy(revealed = true)

    /** A grade only counts once the answer was seen; the next card comes face down. */
    fun grade(g: Grade): RecallSession {
        val c = current ?: return this
        if (!revealed) return this
        return copy(index = index + 1, revealed = false, graded = graded + (c.id to g))
    }

    companion object {
        const val SIZE = 5

        /**
         * The session's cards: the ones due by [nowIso] (ISO instants compare as text), oldest due first, at most
         * [size], none whose grade is still waiting to be sent ([waiting]).
         */
        fun start(due: List<RecallCard>, nowIso: String, waiting: Set<String> = emptySet(), size: Int = SIZE): RecallSession =
            RecallSession(due.filter { it.due <= nowIso && it.id !in waiting }.sortedWith(compareBy({ it.due }, { it.id })).take(size))
    }
}
