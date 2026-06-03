package org.pagel.ai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.pagel.ai.data.room.AwarenessDao
import org.pagel.ai.data.room.AwarenessEventEntity
import org.pagel.ai.domain.EventType
import javax.inject.Inject

/**
 * The one anchor foreground service. It keeps PAGEL alive to (a) capture real-time screen on/off /
 * unlock events the OS doesn't retain for UsageStats, and (b) ensure the WorkManager harvest is
 * scheduled. Type=specialUse (account-safe, deterministic — no LLM in here).
 */
@AndroidEntryPoint
class AnchorService : Service() {

    @Inject lateinit var dao: AwarenessDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val type = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> EventType.SCREEN_ON
                Intent.ACTION_SCREEN_OFF -> EventType.SCREEN_OFF
                Intent.ACTION_USER_PRESENT -> EventType.UNLOCK
                else -> return
            }
            val ts = System.currentTimeMillis()
            scope.launch { dao.insertEvents(listOf(AwarenessEventEntity(ts = ts, type = type.name))) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        goForeground()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        WorkScheduler.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "PAGEL awareness", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("PAGEL")
            .setContentText("Aware — sensing your phone, on-device.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "pagel.awareness"
        private const val NOTIF_ID = 4201
    }
}
