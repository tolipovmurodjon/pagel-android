package org.pagel.ai.ui.habits

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The timer's six ambient sounds: real field recordings, all CC0 (public domain dedication), cut into seamless loops
 * (crossfaded join, about -23 LUFS, mono Opus in Ogg) and kept in assets/sounds. Who recorded each one, and where from,
 * is in assets/sounds/LICENSES.md. A habit remembers its own pick and volume.
 */
object AmbientSounds {
    class Sound(val id: String, val label: String, val icon: String)

    val all = listOf(
        Sound("rain", "Rain", "droplets"), Sound("forest", "Forest", "leaf"), Sound("ocean", "Ocean", "waves"),
        Sound("stream", "Stream", "mountain"), Sound("wind", "Wind", "wind"), Sound("night", "Night", "moon-star"),
    )
}

/**
 * One looping ambient sound: gapless repeat (the files are cut to loop), 400 ms fades in and out, a volume, audio focus
 * (a call or another player pauses it), and a full stop on [release]. A phone with no audio device just stays quiet.
 */
internal class AmbientPlayer(private val ctx: Context) {
    private var player: MediaPlayer? = null
    private var index = -1
    private var gain = 0f
    private var volume = 0.6f
    private var job: Job? = null
    private var focus: AudioFocusRequest? = null
    private val audio = ctx.getSystemService(AudioManager::class.java)

    private val onFocus = AudioManager.OnAudioFocusChangeListener { change ->
        // Someone else needs the speaker (a call, an alarm): this sound steps aside and does not come back by itself.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) runCatching { player?.pause() }
    }

    /** Starts (or resumes) sound [i] at [vol], fading in over 400 ms. Off Main. */
    fun play(scope: CoroutineScope, i: Int, vol: Float) {
        volume = vol
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            runCatching {
                if (i != index || player == null) {
                    closePlayer()
                    val sound = AmbientSounds.all.getOrNull(i) ?: return@runCatching
                    val afd = ctx.assets.openFd("sounds/${sound.id}.ogg")
                    val p = MediaPlayer()
                    p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    p.isLooping = true
                    p.setVolume(0f, 0f)
                    p.prepare()
                    player = p
                    index = i
                    gain = 0f
                }
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(onFocus).build()
                focus = request
                if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    player?.start()
                    fadeTo(1f)
                }
            }
        }
    }

    /** Fades out over 400 ms, then pauses (Pause, Finish, the sheet closed). */
    fun pause(scope: CoroutineScope) {
        if (player == null) return
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            runCatching {
                fadeTo(0f)
                player?.pause()
                focus?.let { audio.abandonAudioFocusRequest(it) }
            }
        }
    }

    fun setVolume(v: Float) {
        volume = v
        apply()
    }

    /** Stops at once and lets go of the player and the focus (the screen is leaving). */
    fun release() {
        job?.cancel()
        closePlayer()
    }

    private fun closePlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        index = -1
        gain = 0f
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
    }

    private fun apply() {
        val v = (gain * volume).coerceIn(0f, 1f)
        runCatching { player?.setVolume(v, v) }
    }

    private suspend fun fadeTo(target: Float) = withContext(Dispatchers.IO) {
        val from = gain
        val steps = 20
        for (s in 1..steps) {
            gain = from + (target - from) * s / steps
            apply()
            delay(20)
        }
    }
}
