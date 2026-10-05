package org.pagel.ai.net.node.commands

/** What a step-counter delta says about movement. [stepsPerMinute] is the AVERAGE over [windowMs], so a
 *  10 minute window with 2 minutes of walking reads as "light", not "walking" — it describes the window. */
data class MotionReading(val state: String, val stepsPerMinute: Double?, val windowMs: Long?)

/**
 * Pure math behind `motion.activity`. The step counter only ever reports a running total since boot, so
 * movement is the difference between two reads. No Google Play services in this app (it was stripped), so
 * there is no ActivityRecognition API; cadence is an honest, if coarse, substitute.
 */
object MotionMath {
    const val MIN_WINDOW_MS = 20_000L
    const val MAX_WINDOW_MS = 30 * 60_000L

    fun classify(prevSteps: Long?, prevAtMs: Long?, steps: Long, atMs: Long): MotionReading {
        if (prevSteps == null || prevAtMs == null) return MotionReading("unknown", null, null)
        val window = atMs - prevAtMs
        // A smaller total means the phone rebooted between reads: the counter restarted from zero.
        if (steps < prevSteps || window < MIN_WINDOW_MS || window > MAX_WINDOW_MS) {
            return MotionReading("unknown", null, if (window > 0) window else null)
        }
        val spm = (steps - prevSteps) * 60_000.0 / window
        val state = when {
            spm < 5.0 -> "still"
            spm < 60.0 -> "light"
            spm < 140.0 -> "walking"
            else -> "running"
        }
        return MotionReading(state, spm, window)
    }
}
