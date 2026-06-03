package org.pagel.ai.domain

/**
 * Pure focus scoring. Productive time counts full, neutral half, distracting zero:
 *   score = 100 * (productive + 0.5*neutral) / total.
 * Honest and explainable — every point maps to real time.
 */
class FocusScore {
    fun compute(catTimes: List<Pair<AppCategory, Long>>): FocusResult {
        var prod = 0L
        var neu = 0L
        var dist = 0L
        for ((cat, ms) in catTimes) {
            when (cat) {
                AppCategory.PRODUCTIVE -> prod += ms
                AppCategory.NEUTRAL -> neu += ms
                AppCategory.DISTRACTING -> dist += ms
            }
        }
        val total = prod + neu + dist
        val score = if (total == 0L) 0 else (((prod + neu * 0.5) / total) * 100.0).toInt().coerceIn(0, 100)
        return FocusResult(score, prod, neu, dist)
    }
}
