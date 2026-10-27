package org.pagel.ai.ui.inbox

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.pagel.ai.data.inbox.Choice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Each choice's own short sound, the web's blip (dashboard src/app/today/celebrate.ts): triangle notes 60 ms apart,
 * each a 6 ms rise to 0.09 and a fall to silence by 0.22 s. Made once per choice as PCM; played off Main.
 */
object ChoiceSound {
    private const val RATE = 44_100
    private val tracks = HashMap<Choice, AudioTrack>()

    /** The notes of learn-client.tsx CHOICES, in Hz. */
    private val NOTES = mapOf(
        Choice.ACT to intArrayOf(659, 880, 1175),
        Choice.NOTE to intArrayOf(784, 1047),
        Choice.KEEP to intArrayOf(523, 659, 784, 1047),
        Choice.NOW to intArrayOf(1319, 1568),
        Choice.LATER to intArrayOf(440),
        Choice.DROP to intArrayOf(330, 247),
    )

    private fun pcm(notes: IntArray): ShortArray {
        val out = FloatArray((RATE * (0.06 * (notes.size - 1) + 0.26)).toInt())
        notes.forEachIndexed { n, f ->
            val start = (n * 0.06 * RATE).toInt()
            val end = minOf(out.size, start + (0.25 * RATE).toInt())
            for (i in start until end) {
                val t = (i - start).toDouble() / RATE
                val env = if (t < 0.006) 0.09 * t / 0.006 else 0.09 * exp(ln(0.0001 / 0.09) * ((t - 0.006) / (0.22 - 0.006)).coerceAtMost(1.0))
                val tri = (2 / PI) * asin(sin(2 * PI * f * t))
                out[i] += (env * tri).toFloat()
            }
        }
        val peak = out.maxOf { abs(it) }.coerceAtLeast(1f)
        return ShortArray(out.size) { (out[it] / peak * Short.MAX_VALUE).toInt().toShort() }
    }

    /** A missing audio device never blocks a choice: the motion and the haptic still play. */
    @Synchronized
    fun play(choice: Choice) {
        runCatching {
            val t = tracks[choice] ?: run {
                val data = pcm(NOTES.getValue(choice))
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(data.size * 2)
                    .build()
                    .also { it.write(data, 0, data.size); it.setVolume(0.35f); tracks[choice] = it }
            }
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        }
    }
}
