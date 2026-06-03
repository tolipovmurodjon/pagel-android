package org.pagel.ai.data.spend

import org.pagel.ai.domain.SpendEvent

/**
 * Pure parser for Uzbek bank notifications. v1 is intentionally permissive: it captures any bank
 * notification carrying a money amount as a *candidate* spend — Boss confirms in chat before it ever
 * logs, so false positives cost nothing. Refine the regex once we have a real Uzum sample.
 */
object SpendParser {

    private val BANK_KEYS = listOf(
        "uzum", "tbc", "kapital", "hamkor", "ipak", "anor", "payme", "click",
        "agro", "aloqa", "infin", "davr", "ravnaq", "octo", "milliy", "asia", "ziraat",
    )

    fun isBank(pkg: String): Boolean {
        val p = pkg.lowercase()
        return BANK_KEYS.any { p.contains(it) }
    }

    // "50 000 so'm" / "50,000 UZS" / "50000 сум" / "1 200 000 сўм"
    private val amount = Regex(
        "([0-9][0-9 ., ]{2,})\\s*(so'?m|som|sum|uzs|сум|сўм)",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): SpendEvent? {
        val m = amount.find(text) ?: return null
        val digits = m.groupValues[1].filter { it.isDigit() }
        val value = digits.toLongOrNull() ?: return null
        if (value < 100) return null // ignore tiny / noise
        return SpendEvent(ts = System.currentTimeMillis(), amount = value, raw = text.take(140).trim())
    }
}
