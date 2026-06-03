package org.pagel.ai.domain

/** How an app counts toward focus. Encoded in the UI by brightness, never a second hue. */
enum class AppCategory { PRODUCTIVE, NEUTRAL, DISTRACTING }

/** Result of scoring a day's usage. */
data class FocusResult(
    val score: Int,          // 0..100
    val productiveMs: Long,
    val neutralMs: Long,
    val distractingMs: Long,
)
