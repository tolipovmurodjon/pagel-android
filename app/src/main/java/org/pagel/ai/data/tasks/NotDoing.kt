package org.pagel.ai.data.tasks

/**
 * "Won't do" with the real reason (Boss 2026-10-26: "only authentic, not fake, no 'bahona'"): the web's
 * lib/not-doing-check.ts, rule for rule, so the phone refuses an excuse before it saves, exactly as the web does.
 */
object NotDoing {
    val KINDS = listOf("waiting", "someday", "drop")

    // Words of an excuse rather than a fact (English, Uzbek, Russian). A real reason names the block.
    private val EXCUSE = Regex(
        """\b(tired|lazy|no mood|don'?t (want|feel like)|not in the mood|too busy|no time|forgot|bored|charchadim|charchagan|erinch\w*|vaqtim yo'?q|xohlamayman|kayfiyat\w*)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val EXCUSE_RU = Regex("(лень|устал|нет времени|не хочу|нет настроения)", RegexOption.IGNORE_CASE)

    /** Null when the reason is a real one; otherwise the web's own words for what is wrong. */
    fun check(reason: String): String? {
        val r = reason.replace(Regex("""\s+"""), " ").trim()
        if (r.split(" ").count { it.isNotEmpty() } < 4) return "Write the real reason in a few words: what blocks it, or why it no longer matters."
        if (EXCUSE.containsMatchIn(r) || EXCUSE_RU.containsMatchIn(r)) return "That sounds like an excuse (bahona), not a block. Name the fact that stops it, or keep the task."
        return null
    }
}
