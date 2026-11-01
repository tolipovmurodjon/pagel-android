package org.pagel.ai.ui.habits

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * The timer's three soft sounds, synthesised here (no download, no licence): sine notes with a quiet second overtone, a
 * 10 ms rise and an exponential fall to silence. Played at a low volume; a phone with no audio device just stays quiet.
 */
object HabitChime {
    val names = listOf("Soft bell", "Drop", "Rise")
    private const val RATE = 22_050

    /** (Hz, start s, length s) of each note. */
    private fun notes(variant: Int): List<Triple<Double, Double, Double>> = when (variant) {
        0 -> listOf(Triple(880.0, 0.0, 1.3), Triple(1318.5, 0.0, 0.9))
        1 -> listOf(Triple(659.25, 0.0, 0.5), Triple(440.0, 0.16, 1.0))
        else -> listOf(Triple(523.25, 0.0, 0.6), Triple(659.25, 0.14, 0.6), Triple(783.99, 0.28, 1.0))
    }

    internal fun pcm(variant: Int): ShortArray {
        val list = notes(variant)
        val out = FloatArray((RATE * (list.maxOf { it.second + it.third } + 0.1)).toInt())
        for ((hz, at, len) in list) {
            for ((mult, vol) in listOf(1.0 to 0.18, 2.0 to 0.04)) {
                val start = (at * RATE).toInt()
                val end = minOf(out.size, ((at + len) * RATE).toInt())
                for (i in start until end) {
                    val t = (i - start).toDouble() / RATE
                    val env = if (t < 0.01) vol * t / 0.01 else vol * exp(ln(0.0005 / vol) * ((t - 0.01) / (len - 0.01)).coerceAtMost(1.0))
                    out[i] += (env * sin(2 * PI * hz * mult * t)).toFloat()
                }
            }
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Plays sound [variant] once, off Main. */
    suspend fun play(variant: Int) = withContext(Dispatchers.Default) {
        runCatching {
            val data = pcm(variant.coerceIn(0, names.lastIndex))
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size)
            track.setVolume(0.6f)
            track.play()
            delay(data.size * 1000L / RATE + 200)
            track.release()
        }
    }
}
