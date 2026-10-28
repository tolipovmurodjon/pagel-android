package org.pagel.ai.ui.glass

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * Liquid glass comes on in steps after a cold start's first frame, never all in it.
 *
 * Why (CI 40656925148, ANR at launch): the first frame that uses a glass effect pays for building its shaders once per
 * process. On CI's software GPU the cold Today frame spent 7,055 ms issuing draw commands (the pair screen 4,000 ms); the
 * main thread then waited for it in `syncAndDrawFrame` and missed a FocusEvent for 5 s = "PAGEL isn't responding". Split
 * over a few frames, each wait stays short; on a phone the steps pass in ~3 frames (~50 ms).
 *
 * [level]: 0 = flat (the same colours, no backdrop effects, no alpha mask), 1 = panes + the scroll-edge mask, 2 = the
 * bar, 3 = everything (the +, the toast, the title capsule, sheets). Tests and previews never call [coldStart], so they
 * always draw full glass.
 */
object GlassStage {
    const val FLAT = 0
    const val PANES = 1
    const val BAR = 2
    const val FULL = 3

    var level by mutableIntStateOf(FULL)
        private set

    /** Debug builds only: CI's launch probe sets "old" (all glass in the first frame) or "flat" (never) to measure both. */
    var probe: String = ""

    private var started = false

    /** Called once per process before the first frame. A recreated activity (rotation) keeps the glass it has. */
    fun coldStart() {
        if (started) return
        started = true
        if (probe != "old") level = FLAT
    }

    fun allows(step: Int): Boolean = level >= step

    /** Moves one step per two drawn frames once [ready] (a screen with glass is composed), logging when each came on. */
    @Composable
    fun Advance(ready: Boolean) {
        LaunchedEffect(ready) {
            if (!ready || level >= FULL || probe == "flat") return@LaunchedEffect
            val t0 = SystemClock.uptimeMillis()
            while (level < FULL) {
                // The second frame is only produced once the render thread has finished the first, so each step's
                // shader work has its own frame.
                withFrameNanos { }
                withFrameNanos { }
                level += 1
                android.util.Log.i("PagelGlass", "glass step $level at +${SystemClock.uptimeMillis() - t0} ms")
            }
        }
    }
}
