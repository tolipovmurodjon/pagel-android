package org.pagel.ai.ui.glass

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.first

/**
 * A cold start draws its first frame without the glass effects and turns them all on once the window has focus.
 *
 * Why (CI 40656925148 and 40679698108, "PAGEL isn't responding" at launch, nothing touched): the first frame that uses
 * the glass builds its shaders. With an empty shader cache on CI's software GPU that frame took 6,004-7,055 ms on the
 * render thread; Main drew the next frame (rows rising in, data arriving), waited behind it in `syncAndDrawFrame`, and
 * Android counted the 5 s it took to take the window's FocusEvent as an ANR. A flat first frame costs 30-65 ms there,
 * the FocusEvent is taken at once, and the glass frame after it has no event waiting on it.
 *
 * All at once, never in steps: CI 40666139501 measured three steps at 4,209 ms per frame and 225 skipped frames against
 * 51 ms / 89 for all glass in one frame (each mixed step builds shaders the cache never holds). On a phone the flat frame
 * shows for one or two frames (~30 ms).
 *
 * Tests and previews never call [coldStart], so they always draw the glass.
 */
object GlassStage {
    var on by mutableStateOf(true)
        private set

    private var started = false

    /** Called once per process before the first frame. A recreated activity (rotation) keeps the glass it has. */
    fun coldStart() {
        if (started) return
        started = true
        on = false
    }

    /** Turns the glass on one frame after the window has focus, once [ready] (a screen is composed). */
    @Composable
    fun Advance(ready: Boolean) {
        val window = LocalWindowInfo.current
        LaunchedEffect(ready) {
            if (!ready || on) return@LaunchedEffect
            val t0 = SystemClock.uptimeMillis()
            snapshotFlow { window.isWindowFocused }.first { it }
            withFrameNanos { }
            on = true
            // The smoke waits on this line before it touches the screen.
            android.util.Log.i("PagelGlass", "glass on at +${SystemClock.uptimeMillis() - t0} ms")
        }
    }
}
