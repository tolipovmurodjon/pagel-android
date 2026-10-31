package org.pagel.ai.ui.motion.blackhole

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.metrics.performance.JankStats
import kotlinx.coroutines.delay
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Space
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** A fixed moment for the frame-series tests: [BlackHoleFrame] shows the run at [t] s instead of playing it. */
data class LabStill(val t: Float, val timing: BlackHoleTiming = BlackHoleTiming.ClearAll, val shaderRing: Boolean = true)

private val SAMPLE_ROWS = listOf(
    "Renew the domain", "Write the IT Park report", "Export the hover log",
    "Call the bank about the report", "Record the fractions lesson", "Weigh in",
)

/** The lab's stage in px: six sample rows from [top] down (the hole is not here: it opens out of the camera). */
internal class LabGeometry(val width: Int, density: Float, top: Float = Space.lg.value * density) {
    val cell = BlackHole.CELL_DP * density
    private val rowH = 48f * density
    private val gap = Space.sm.value * density
    val rows = SAMPLE_ROWS.indices.map { i -> PieceRect(0f, top + i * (rowH + gap), width.toFloat(), top + i * (rowH + gap) + rowH) }
    val height = (rows.last().bottom + Space.lg.value * density).roundToInt()
}

/** The six sample rows drawn once with Canvas (the same pixels on the phone and in the frame-series tests). */
internal fun sampleRows(geo: LabGeometry, density: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(geo.width, geo.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bitmap)
    val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(235, 255, 255, 255) }
    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density; color = Glass.Ink.copy(alpha = 0.08f).toArgb() }
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.Aurora1.toArgb() }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.Ink.toArgb(); textSize = 15f * density }
    val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.Muted.toArgb(); textSize = 12f * density; textAlign = Paint.Align.RIGHT }
    val inset = Space.lg.value * density
    for ((i, r) in geo.rows.withIndex()) {
        val box = RectF(r.left + inset, r.top, r.right - inset, r.bottom)
        val corner = 16f * density
        c.drawRoundRect(box, corner, corner, card)
        c.drawRoundRect(box, corner, corner, rim)
        val mid = (box.top + box.bottom) / 2f
        c.drawCircle(box.left + 22f * density, mid, 9f * density, dot)
        c.drawText(SAMPLE_ROWS[i], box.left + 44f * density, mid + 5f * density, text)
        c.drawText("09:${10 + i * 7}", box.right - 14f * density, mid + 4f * density, meta)
    }
    return bitmap
}

/** More → Lab → Black hole: the effect on six sample rows, both lengths, and the janky % JankStats counted here. */
@Composable
fun BlackHoleLabScreen(padding: PaddingValues) {
    val meter = rememberJankMeter()
    var report by remember { mutableStateOf<String?>(null) }
    BlackHoleLab(padding, report, onRunStart = meter::start, onRunEnd = { report = meter.stop() })
}

@Composable
fun BlackHoleLab(padding: PaddingValues, jank: String?, onRunStart: () -> Unit = {}, onRunEnd: () -> Unit = {}) {
    val density = LocalDensity.current.density
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(bottom = Space.xl)) {
        Column(Modifier.padding(start = Space.textEdge, end = Space.textEdge, top = Space.headerTop)) {
            Text("Lab", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
            Text("Black hole", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
            Text("Delete one or clear all. The janky frames are counted on this phone.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val geo = remember(constraints.maxWidth, density) { LabGeometry(constraints.maxWidth, density) }
            val rows = remember(geo) { sampleRows(geo, density) }
            val image = remember(rows) { rows.asImageBitmap() }
            var run by remember { mutableStateOf<BlackHoleRun?>(null) }
            var gone by remember { mutableStateOf<Set<Int>>(emptySet()) }
            var next by remember { mutableLongStateOf(1L) }
            // The rows come back a moment after a run, ready for the next one.
            LaunchedEffect(run, gone) {
                if (run == null && gone.isNotEmpty()) { delay(700); gone = emptySet() }
            }
            Column {
                val stageDp = with(LocalDensity.current) { geo.height.toDp() }
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(stageDp)) {
                    Spacer(
                        Modifier.fillMaxSize().drawBehind {
                            for ((i, r) in geo.rows.withIndex()) if (i !in gone) {
                                val o = IntOffset(0, r.top.roundToInt())
                                val s = IntSize(geo.width, r.height.roundToInt())
                                drawImage(image, srcOffset = o, srcSize = s, dstOffset = o, dstSize = s)
                            }
                        },
                    )
                    BlackHoleOverlay(run, onEnd = { run = null; onRunEnd() }, Modifier.fillMaxSize())
                    BlackHolePrewarm()
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    for ((label, timing) in listOf("Delete one" to BlackHoleTiming.Single, "Clear all" to BlackHoleTiming.ClearAll)) {
                        Button(
                            onClick = {
                                if (run == null) {
                                    val pieces = if (timing == BlackHoleTiming.Single) listOf(geo.rows.first()) else geo.rows
                                    gone = if (timing == BlackHoleTiming.Single) setOf(0) else geo.rows.indices.toSet()
                                    onRunStart()
                                    run = BlackHoleRun(next++, rows, pieces, timing)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Glass.Primary),
                            // Material's 24 dp sides clipped "Delete one · 1.3 s" at 360 dp (frame series, CI 41070909739).
                            contentPadding = PaddingValues(horizontal = Space.md),
                            modifier = Modifier.weight(1f),
                        ) { Text("$label · ${"%.1f".format(Locale.US, timing.total)} s", maxLines = 1) }
                    }
                }
            }
        }
        Text(
            jank ?: "Play it, then read the janky frames here. Target on the A05s: under 5 %.",
            style = MaterialTheme.typography.bodyMedium, color = if (jank == null) Glass.Muted else Glass.Ink,
            modifier = Modifier.padding(start = Space.textEdge, end = Space.textEdge, top = Space.md),
        )
    }
}

/**
 * One still frame of the effect on a whole-screen stage (the frame-series tests): the sample rows under a [camera]
 * capsule at the top, the hole at [still].t. The camera is in px of the stage.
 */
@Composable
fun BlackHoleFrame(still: LabStill, camera: Capsule) {
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val geo = remember(width, density) { LabGeometry(width, density, top = camera.bottom + 4f * density) }
        val rows = remember(geo) { sampleRows(geo, density) }
        val scene = remember(geo, camera, still.timing) {
            BlackHoleScene.prepare(rows, geo.rows, holeGeometry(camera, width.toFloat()), geo.cell, still.timing)
        }
        BlackHoleCanvas(scene, { still.t }, shaderRing = still.shaderRing)
    }
}

/** Counts frames and janky frames with JankStats, only while a run plays. */
internal class JankMeter {
    private val frames = AtomicInteger()
    private val janky = AtomicInteger()
    @Volatile private var counting = false

    fun onFrame(isJank: Boolean) {
        if (!counting) return
        frames.incrementAndGet()
        if (isJank) janky.incrementAndGet()
    }

    fun start() {
        frames.set(0)
        janky.set(0)
        counting = true
    }

    fun stop(): String {
        counting = false
        val n = frames.get()
        val j = janky.get()
        val line = if (n == 0) "No frames were counted on this phone." else "Janky frames: ${"%.1f".format(Locale.US, 100f * j / n)} % ($j of $n)"
        Timber.i("BlackHole jank: %s", line)
        return line
    }
}

@Composable
private fun rememberJankMeter(): JankMeter {
    val view = LocalView.current
    val meter = remember { JankMeter() }
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val stats = window?.let { w -> runCatching { JankStats.createAndTrack(w, JankStats.OnFrameListener { meter.onFrame(it.isJank) }) }.getOrNull() }
        onDispose { stats?.isTrackingEnabled = false }
    }
    return meter
}
