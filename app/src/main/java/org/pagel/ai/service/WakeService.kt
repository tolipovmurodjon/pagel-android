package org.pagel.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.AndroidEntryPoint
import org.pagel.ai.MainActivity
import org.pagel.ai.voice.VoiceSessionManager
import org.pagel.ai.voice.VoiceState
import org.pagel.ai.voice.WakeSettings
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * On-device "Hey PAGEL" wake-word listener. A microphone foreground service: it streams the mic
 * through sherpa-onnx KWS (all local, no network, no LLM — account-safe), and on a hit it brings up
 * the voice surface and starts a session. Listening pauses while a session is live (mic hand-off) and,
 * if the user chose "only when charging", while off charger. Armed from the app (foreground) so Android's
 * background-mic-FGS rules are never hit; START_STICKY keeps it alive across screen-off.
 */
@AndroidEntryPoint
class WakeService : Service() {

    @Inject lateinit var sessionManager: VoiceSessionManager
    @Inject lateinit var wakeSettings: WakeSettings

    @Volatile private var running = false
    @Volatile private var charging = true
    private var pausedUntil = 0L
    private var worker: Thread? = null
    private var spotter: KeywordSpotter? = null

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            charging = when (i?.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> charging
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotice()
        charging = isCharging()
        registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            worker = thread(name = "pagel-kws", isDaemon = true) { listenLoop() }
        }
        return START_STICKY
    }

    /** Pause the mic loop for [ms] (lets a starting session grab the mic without contention). */
    fun cooldown(ms: Long) {
        pausedUntil = SystemClock.uptimeMillis() + ms
    }

    /** A call is genuinely in progress — pause KWS to free the mic. ERROR/IDLE both mean "free to listen". */
    private fun sessionActive(): Boolean = when (sessionManager.state.value) {
        VoiceState.CONNECTING, VoiceState.LISTENING, VoiceState.THINKING, VoiceState.SPEAKING -> true
        else -> false
    }

    private fun shouldListen(): Boolean {
        if (SystemClock.uptimeMillis() < pausedUntil) return false
        if (sessionActive()) return false
        // only-when-charging policy (read once per check; cheap)
        return charging || !chargerOnlyCached
    }

    @Volatile private var chargerOnlyCached = true

    private fun listenLoop() {
        // snapshot the charger-only policy on a background thread
        runCatching { chargerOnlyCached = kotlinx.coroutines.runBlocking { wakeSettings.chargerOnlyNow() } }

        val spotter = buildSpotter() ?: run {
            Log.e(TAG, "KWS init failed")
            updateNotice("Wake error — couldn't load the model. Toggle hands-free off then on.")
            return
        }
        this.spotter = spotter
        updateNotice("Listening for “Hey PAGEL”")

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate)
        val buf = ShortArray(sampleRate / 10) // 100 ms chunks
        var record: AudioRecord? = null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null

        fun teardown() {
            record?.let { runCatching { it.stop() }; runCatching { it.release() } }
            record = null
            stream?.let { runCatching { it.release() } }
            stream = null
        }

        try {
            while (running) {
                // Pause (and free the mic) while a real call is live, off-charger, or in cooldown.
                if (!shouldListen()) {
                    teardown()
                    Thread.sleep(300)
                    continue
                }
                // (Re)acquire the mic + a fresh recognizer stream when resuming.
                if (record == null) {
                    val r = runCatching {
                        AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
                        )
                    }.getOrNull()
                    if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
                        runCatching { r?.release() }
                        Thread.sleep(500) // mic busy/unavailable — back off and retry, never die
                        continue
                    }
                    runCatching { r.startRecording() }
                    record = r
                    stream = spotter.createStream()
                }
                val s = stream ?: continue
                val n = record?.read(buf, 0, buf.size) ?: -1
                if (n > 0) {
                    val samples = FloatArray(n) { buf[it] / 32768.0f }
                    s.acceptWaveform(samples, sampleRate)
                    while (spotter.isReady(s)) spotter.decode(s)
                    val keyword = spotter.getResult(s).keyword
                    if (keyword.isNotBlank()) {
                        onWake(keyword)
                        teardown()        // release the mic NOW so the session can grab it
                        cooldown(2500)
                    }
                } else {
                    Thread.sleep(15)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "listen loop crashed", t)
        } finally {
            teardown()
            runCatching { spotter.release() }
        }
    }

    private fun onWake(keyword: String) {
        Log.i(TAG, "WAKE: $keyword")
        updateNotice("Heard “Hey PAGEL” ✓")
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_START_VOICE, true)
        }
        runCatching { startActivity(intent) }
    }

    private fun buildSpotter(): KeywordSpotter? = runCatching {
        val dir = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$dir/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    joiner = "$dir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                ),
                tokens = "$dir/tokens.txt",
                numThreads = 1,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            keywordsFile = "$dir/keywords.txt",
            keywordsScore = 2.0f,
            keywordsThreshold = 0.20f,
        )
        KeywordSpotter(assets, config)
    }.getOrNull()

    private fun isCharging(): Boolean {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        return bm?.isCharging ?: true
    }

    private fun startForegroundNotice() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "PAGEL voice", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("PAGEL")
            .setContentText("Listening for “Hey PAGEL”")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
    }

    /** Reflect the live wake state in the ongoing notification — so failures are never silent again. */
    private fun updateNotice(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("PAGEL")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        runCatching { mgr.notify(NOTIF_ID, notif) }
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(powerReceiver) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_VOICE = "start_voice"
        private const val CHANNEL = "pagel_voice_wake"
        // Distinct from AnchorService.NOTIF_ID (4201) — both are foreground services that run
        // simultaneously when hands-free is armed; sharing an id lets one clobber the other's notice.
        private const val NOTIF_ID = 4202
        private const val TAG = "WakeService"

        fun start(context: Context) =
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, WakeService::class.java))
    }
}
