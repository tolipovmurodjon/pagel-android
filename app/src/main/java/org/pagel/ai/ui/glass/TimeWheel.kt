package org.pagel.ai.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * The wheel's arithmetic, apart from the drawing so it is tested: hours 00-23, minutes in steps of 5, each column a
 * long loop (so 23 → 00 just keeps turning) that starts in its middle on the current value.
 */
object TimeWheelMath {
    const val HOURS = 24
    const val MINUTES = 12 // 00, 05 … 55
    /** How many times a column repeats its values: long enough that no one reaches the end by turning. */
    const val LOOPS = 400

    /** "15:32" → hour 15, minute row 6 (the nearest 5, 30; 58 stays in the hour as 55). Blank or bad → 09:00. */
    fun split(time: String?): Pair<Int, Int> {
        val m = Regex("""^(\d{2}):(\d{2})$""").find(time.orEmpty()) ?: return 9 to 0
        val h = m.groupValues[1].toInt().coerceIn(0, 23)
        val row = ((m.groupValues[2].toInt() + 2) / 5).coerceIn(0, MINUTES - 1)
        return h to row
    }

    fun value(hour: Int, minuteRow: Int): String = "%02d:%02d".format(hour, minuteRow * 5)

    /** The list index a column starts on: [row] in the middle loop. */
    fun start(row: Int, size: Int): Int = (LOOPS / 2) * size + row

    /** The value at any list index of a column of [size]. */
    fun rowOf(index: Int, size: Int): Int = ((index % size) + size) % size
}

/** iOS UIDatePicker proportions (checker 2026-10-28 #4): 7 rows of 34 dp, drums ~104 dp wide, no ":" between them. */
private val RowHeight = 34.dp
private const val VISIBLE = 7
private val DrumWidth = 104.dp
/** The drum's turn per row: rows sit on a cylinder of radius rowHeight / step, so they bunch up as they turn away. */
private const val STEP_DEG = 20f

/**
 * Apple's time wheel (UIDatePicker .wheels): two drums, hours and minutes, that fling and snap; the chosen row sits
 * in a fixed light-ink band (no border: Apple's band is a fill, the outlined one read as a text field); rows away from
 * it turn back on a cylinder, fade and bunch up. A light tick on every notch; [onChange] gets "HH:mm" once the drum
 * comes to rest after Boss turned it (never on opening).
 */
@Composable
fun TimeWheel(time: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val (h0, m0) = remember(time) { TimeWheelMath.split(time) }
    val hours = rememberLazyListState(TimeWheelMath.start(h0, TimeWheelMath.HOURS) - VISIBLE / 2)
    val minutes = rememberLazyListState(TimeWheelMath.start(m0, TimeWheelMath.MINUTES) - VISIBLE / 2)
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    val hour by remember(rowPx) { derivedStateOf { TimeWheelMath.rowOf(centre(hours, rowPx), TimeWheelMath.HOURS) } }
    val minute by remember(rowPx) { derivedStateOf { TimeWheelMath.rowOf(centre(minutes, rowPx), TimeWheelMath.MINUTES) } }
    val now = TimeWheelMath.value(hour, minute)
    // Settled = neither drum moving: then the time is chosen (never mid-fling).
    LaunchedEffect(Unit) {
        snapshotFlow { if (hours.isScrollInProgress || minutes.isScrollInProgress) null else TimeWheelMath.value(hour, minute) }
            .filterNotNull().distinctUntilChanged().drop(1)
            .collect { onChange(it) }
    }
    Box(
        modifier.fillMaxWidth().height(RowHeight * VISIBLE).semantics { contentDescription = "Time wheel $now" },
        contentAlignment = Alignment.Center,
    ) {
        // The selection band: fixed, the numbers turn through it. Ink at 6 %, radius 10, as iOS.
        Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(RowHeight).background(Glass.Ink.copy(alpha = 0.06f), RoundedCornerShape(10.dp)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Drum(hours, TimeWheelMath.HOURS) { "%02d".format(it) }
            Spacer(Modifier.width(8.dp))
            Drum(minutes, TimeWheelMath.MINUTES) { "%02d".format(it * 5) }
        }
    }
}

/** The wheel's numbers: grow with the phone's font up to 1.2x, then stop (a drum row is a fixed 34 dp). */
private val WheelText: TextStyle @Composable get() {
    val scale = LocalDensity.current.fontScale
    val size = 22f * minOf(scale, 1.2f) / scale
    return TextStyle(fontFamily = Glass.Body, fontWeight = FontWeight.Medium, fontSize = size.sp, lineHeight = (size + 4f).sp, fontFeatureSettings = "tnum")
}

/**
 * Where the drum stands, in rows: the first row's index plus how far it has scrolled off. Read from the scroll
 * position itself (every row is [RowHeight] tall), not from layoutInfo, which can lag a frame behind.
 */
private fun position(state: LazyListState, rowPx: Float): Float =
    state.firstVisibleItemIndex + if (rowPx > 0f) state.firstVisibleItemScrollOffset / rowPx else 0f

/** The index whose row is nearest the middle of the column. */
private fun centre(state: LazyListState, rowPx: Float): Int = (position(state, rowPx) + 0.5f).toInt() + VISIBLE / 2

@Composable
private fun Drum(state: LazyListState, size: Int, label: (Int) -> String) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    LaunchedEffect(state, rowPx) {
        snapshotFlow { centre(state, rowPx) }.distinctUntilChanged().drop(1).collect { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }
    val step = Math.toRadians(STEP_DEG.toDouble()).toFloat()
    val radius = rowPx / step
    LazyColumn(
        Modifier.width(DrumWidth).height(RowHeight * VISIBLE),
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
    ) {
        items(size * TimeWheelMath.LOOPS, key = { it }, contentType = { "row" }) { i ->
            val row = TimeWheelMath.rowOf(i, size)
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier.fillMaxWidth().height(RowHeight)
                    .graphicsLayer {
                        // Distance from the band in rows, read while drawing: the row's angle on the drum.
                        val d = i - position(state, rowPx) - VISIBLE / 2
                        val angle = (d * step).coerceIn(-1.45f, 1.45f)
                        translationY = radius * sin(angle) - d * rowPx
                        rotationX = -Math.toDegrees(angle.toDouble()).toFloat()
                        cameraDistance = 8f * density
                        alpha = (cos(angle) * cos(angle)).coerceIn(0.12f, 1f)
                    }
                    .clickable(press, indication = null) { scope.launch { state.animateScrollToItem((i - VISIBLE / 2).coerceAtLeast(0)) } },
                contentAlignment = Alignment.Center,
            ) { Text(label(row), style = WheelText, color = Glass.Ink, textAlign = TextAlign.Center, maxLines = 1, softWrap = false) }
        }
    }
}
