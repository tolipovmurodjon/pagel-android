package org.pagel.ai.ui.inbox

import org.pagel.ai.data.db.InboxItemEntity
import org.pagel.ai.data.inbox.savedDay

/** A topic or source inside the Old pile that Boss can drop in one move ("Claude / AI tool tips · 63"). */
data class OldGroup(val name: String, val items: List<InboxItemEntity>)

private val AI_WORDS = Regex("""\b(claude|chatgpt|gpt-?\d?|gemini|cursor|copilot|openai|llm|prompts?|midjourney|n8n|ai tools?|ai agents?)\b""", RegexOption.IGNORE_CASE)

/**
 * The Old pile grouped by plain rules, no AI call (Boss 2026-10-31: "old Claude things, no longer valid"): saves that talk
 * about AI tools first, then the rest by where they came from and the year, so a stale pile goes in a few taps instead of
 * hundreds. A group needs [minSize] saves; each save is in one group only; the biggest first, at most [max].
 */
fun oldGroups(items: List<InboxItemEntity>, minSize: Int = 3, max: Int = 4): List<OldGroup> {
    val ai = items.filter { AI_WORDS.containsMatchIn(it.claim + " " + it.text.take(400)) }
    val taken = ai.map(InboxItemEntity::file).toSet()
    val rest = items.filter { it.file !in taken }.groupBy { i ->
        val (_, name) = siteOf(i.source)
        val year = savedDay(i)?.year
        if (year == null) name else "$name $year"
    }
    val bySource = rest.filter { it.value.size >= minSize }.map { (name, list) -> OldGroup(name, list) }.sortedByDescending { it.items.size }
    val first = if (ai.size >= minSize) listOf(OldGroup("Claude / AI tool tips", ai)) else emptyList()
    return (first + bySource).take(max)
}
