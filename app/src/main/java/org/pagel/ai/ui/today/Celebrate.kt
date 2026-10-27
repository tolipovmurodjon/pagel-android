package org.pagel.ai.ui.today

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/**
 * The finish sound of the web (dashboard src/app/today/celebrate.ts): C6 then G6 a fifth apart, each a sine with a soft
 * overtone, an 8 ms rise and a fade to silence by 0.55 s. Made once as PCM, no sound file; played on a worker thread.
 */
object Ding {
    private const val RATE = 44_100
    private var track: AudioTrack? = null

    private fun samples(): ShortArray {
        val out = FloatArray((RATE * 0.7).toInt())
        for ((freq, at) in listOf(1046.5 to 0.0, 1568.0 to 0.085)) {
            for ((mult, vol) in listOf(1.0 to 0.16, 2.0 to 0.035)) {
                val start = (at * RATE).toInt()
                val end = minOf(out.size, ((at + 0.6) * RATE).toInt())
                for (i in start until end) {
                    val t = (i - start).toDouble() / RATE
                    // Linear rise over 8 ms, then exponential fall from vol to 0.0001 at 0.55 s (Web Audio's ramps).
                    val env = if (t < 0.008) vol * t / 0.008 else vol * exp(ln(0.0001 / vol) * ((t - 0.008) / (0.55 - 0.008)).coerceAtMost(1.0))
                    out[i] += (env * sin(2 * PI * freq * mult * t)).toFloat()
                }
            }
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Call off Main. A missing audio device never breaks the tick: the ring still fills. */
    @Synchronized
    fun play() {
        runCatching {
            val t = track ?: run {
                val pcm = samples()
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                    .also { it.write(pcm, 0, pcm.size); track = it }
            }
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        }
    }
}

/** Android's "remove animations" switch: confetti and folds are skipped, the tick still shows (as the web's reduced motion). */
fun reducedMotion(ctx: Context): Boolean =
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** One burst from a ring's centre, in the overlay's own coordinates. */
data class Burst(val id: Long, val origin: Offset)

// The web's colours; the confetti is the one place the whole Todoist palette shows.
private val CONFETTI = listOf(0xFFD1453B, 0xFFEB8909, 0xFFF6C445, 0xFF058527, 0xFF246FE0, 0xFF692FC2, 0xFFE05194).map { Color(it) }

private class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, val w: Float, val h: Float, var rot: Float, val spin: Float, val color: Color, val round: Boolean)

/**
 * The web's confetti, step for step: 34 bits thrown upward in a 200° fan, gravity 0.22 px per 60 Hz frame, 1.5 % drag,
 * fading out over 1.1 s. Positions advance in whole 60 Hz steps, so a slow phone shows the same arc, only coarser.
 * Drawn in the draw phase from one frame clock; [onEnd] removes it.
 */
@Composable
fun ConfettiBurst(burst: Burst, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val bits = remember(burst.id) {
        List(34) {
            val a = -PI / 2 + (Random.nextDouble() - 0.5) * PI * 1.1
            val v = 4 + Random.nextDouble() * 5
            Bit(0f, 0f, (cos(a) * v).toFloat(), (sin(a) * v).toFloat(), 4 + Random.nextFloat() * 4, 6 + Random.nextFloat() * 5,
                Random.nextFloat() * 6, (Random.nextFloat() - 0.5f) * 0.4f, CONFETTI[Random.nextInt(CONFETTI.size)], Random.nextFloat() < 0.3f)
        }
    }
    var elapsedMs by remember(burst.id) { mutableLongStateOf(0L) }
    LaunchedEffect(burst.id) {
        val start = withFrameNanos { it }
        var steps = 0
        while (elapsedMs < LIFE_MS) {
            withFrameNanos { now ->
                elapsedMs = (now - start) / 1_000_000
                val due = (elapsedMs / (1000f / 60f)).toInt()
                while (steps < due) {
                    for (b in bits) { b.vy += 0.22f; b.vx *= 0.985f; b.vy *= 0.985f; b.x += b.vx; b.y += b.vy; b.rot += b.spin }
                    steps++
                }
            }
        }
        onEnd()
    }
    Canvas(modifier) {
        val k = elapsedMs / LIFE_MS.toFloat()
        if (k >= 1f) return@Canvas
        val px = density // the web's numbers are CSS px; one CSS px = one dp
        for (b in bits) {
            translate(burst.origin.x + b.x * px, burst.origin.y + b.y * px) {
                rotateRad(b.rot, pivot = Offset.Zero) {
                    val c = b.color.copy(alpha = 1f - k * k)
                    if (b.round) drawCircle(c, radius = b.w * px / 2, center = Offset.Zero)
                    else {
                        val h = b.h * px * abs(cos(b.rot))
                        drawRoundRect(c, Offset(-b.w * px / 2, -h / 2), Size(b.w * px, h), CornerRadius(0.5f * px))
                    }
                }
            }
        }
    }
}

private const val LIFE_MS = 1_100L
