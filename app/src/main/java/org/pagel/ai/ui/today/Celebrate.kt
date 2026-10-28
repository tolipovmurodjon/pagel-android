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
 * It goes through the MEDIA stream (Duolingo, Things): Boss's A05s is usually on silent, and the old sonification
 * stream is muted then, so he never heard a tick (2026-10-28). "Follow silent mode" in More keeps it quiet on silent.
 */
object Ding {
    private const val RATE = 44_100
    private var track: AudioTrack? = null
    private var chordTrack: AudioTrack? = null

    /** [notes] = (Hz, start s): each a sine with a soft overtone, 8 ms rise, fading to silence [fade] s later. */
    private fun samples(notes: List<Pair<Double, Double>>, fade: Double = 0.55, vol0: Double = 0.16): ShortArray {
        val out = FloatArray((RATE * (notes.maxOf { it.second } + fade + 0.15)).toInt())
        for ((freq, at) in notes) {
            for ((mult, vol) in listOf(1.0 to vol0, 2.0 to vol0 * 0.22)) {
                val start = (at * RATE).toInt()
                val end = minOf(out.size, ((at + fade + 0.05) * RATE).toInt())
                for (i in start until end) {
                    val t = (i - start).toDouble() / RATE
                    // Linear rise over 8 ms, then exponential fall from vol to 0.0001 at [fade] (Web Audio's ramps).
                    val env = if (t < 0.008) vol * t / 0.008 else vol * exp(ln(0.0001 / vol) * ((t - 0.008) / (fade - 0.008)).coerceAtMost(1.0))
                    out[i] += (env * sin(2 * PI * freq * mult * t)).toFloat()
                }
            }
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun build(pcm: ShortArray): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size); it.setVolume(SOFT) }

    private fun start(t: AudioTrack) {
        if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
        t.reloadStaticData()
        t.play()
    }

    /** The tick's ding. Call off Main. A missing audio device never breaks the tick: the ring still fills. */
    @Synchronized
    fun play(ctx: Context) {
        if (!FinishSound.allowed(ctx)) return
        runCatching { start(track ?: build(samples(listOf(1046.5 to 0.0, 1568.0 to 0.085))).also { track = it }) }
    }

    /** The day cleared: C5 E5 G5 C6 rising, then held (the big moment). Call off Main. */
    @Synchronized
    fun chord(ctx: Context) {
        if (!FinishSound.allowed(ctx)) return
        runCatching { start(chordTrack ?: build(samples(listOf(523.25 to 0.0, 659.25 to 0.09, 783.99 to 0.18, 1046.5 to 0.27), fade = 1.1, vol0 = 0.11)).also { chordTrack = it }) }
    }

    /** The media stream is loud at the volume Boss sets for videos: the ding plays at 70 % of it. */
    private const val SOFT = 0.7f
}

/** The Sounds row in More: "Always" (default) or "Follow silent mode" (no sound while the phone is on silent or vibrate). */
object FinishSound {
    const val ALWAYS = "always"
    const val FOLLOW = "follow"
    private const val PREFS = "pagel-finish"
    private const val KEY = "sounds"

    /** Read off Main (SharedPreferences opens a file the first time). */
    fun mode(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ALWAYS) ?: ALWAYS

    fun setMode(ctx: Context, mode: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()

    fun allowed(ctx: Context): Boolean = mode(ctx) == ALWAYS ||
        ctx.getSystemService(android.media.AudioManager::class.java)?.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL
}

/**
 * Android's "remove animations" switch (animator duration scale 0, Samsung's "Remove animations"): the folds and slides
 * are skipped. A scale of 0.5 is not "off". The confetti is not skipped: it is the reward, 1.1 s, and moves no layout
 * (Boss 2026-10-28: "no confetti").
 */
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
        // The walk reads this line as proof a tick threw confetti (a 1.1 s burst is gone before a UI dump).
        android.util.Log.i("PagelFinish", "confetti ${burst.id}")
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
