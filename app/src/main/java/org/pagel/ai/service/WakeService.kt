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

    private fun shouldListen(): Boolean {
        if (SystemClock.uptimeMillis() < pausedUntil) return false
        if (sessionManager.state.value != VoiceState.IDLE) return false
        // only-when-charging policy (read once per check; cheap)
        return charging || !chargerOnlyCached
    }

    @Volatile private var chargerOnlyCached = true

    private fun listenLoop() {
        // snapshot the charger-only policy on a background thread
        runCatching { chargerOnlyCached = kotlinx.coroutines.runBlocking { wakeSettings.chargerOnlyNow() } }

        val spotter = buildSpotter() ?: run {
            Log.e(TAG, "KWS init failed — stopping"); stopSelf(); return
        }
        this.spotter = spotter

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate)
        var record: AudioRecord? = null
        var stream = spotter.createStream()
        val buf = ShortArray(sampleRate / 10) // 100 ms chunks

        try {
            while (running) {
                if (!shouldListen()) {
                    record?.let { runCatching { it.stop() }; runCatching { it.release() } }
                    record = null
                    Thread.sleep(250)
                    continue
                }
                if (record == null) {
                    record = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
                    ).also { runCatching { it.startRecording() } }
                    spotter.reset(stream)
                }
                val n = record?.read(buf, 0, buf.size) ?: 0
                if (n > 0) {
                    val samples = FloatArray(n) { buf[it] / 32768.0f }
                    stream.acceptWaveform(samples, sampleRate)
                    while (spotter.isReady(stream)) spotter.decode(stream)
                    val keyword = spotter.getResult(stream).keyword
                    if (keyword.isNotBlank()) {
                        onWake(keyword)
                        spotter.reset(stream)
                        cooldown(2000) // let the session take the mic
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "listen loop crashed", t)
        } finally {
            record?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { stream.release() }
            runCatching { spotter.release() }
        }
    }

    private fun onWake(keyword: String) {
        Log.i(TAG, "WAKE: $keyword")
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
            keywordsScore = 1.8f,
            keywordsThreshold = 0.22f,
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

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(powerReceiver) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_VOICE = "start_voice"
        private const val CHANNEL = "pagel_voice_wake"
        private const val NOTIF_ID = 4201
        private const val TAG = "WakeService"

        fun start(context: Context) =
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, WakeService::class.java))
    }
}
