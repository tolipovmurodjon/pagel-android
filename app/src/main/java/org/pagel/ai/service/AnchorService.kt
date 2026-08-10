package org.pagel.ai.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * The one anchor foreground service.
 *
 * It holds the process alive so the gateway's operator and node connections (both `@Singleton`s,
 * scoped to the process) survive backgrounding, and it is what makes a backgrounded `location.get`
 * legal — see [foregroundServiceType]. The official OpenClaw app keeps an equivalent persistent
 * notification for exactly this reason.
 *
 * It used to also record screen on/off events into a Room table for the on-device usage dashboard;
 * that whole feature was removed when the app became a pure OpenClaw client, so the service no
 * longer injects or stores anything. Type=specialUse (deterministic, no LLM in here).
 */
class AnchorService : Service() {

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "PAGEL", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("PAGEL")
            .setContentText("Connected to your gateway.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, foregroundServiceType())
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /**
     * `specialUse` always applies — it is what keeps this service alive as the catch-all anchor
     * service and must never be silently dropped. `location` is OR'd in only when foreground location
     * permission is *currently* granted: Android 14+ throws a `SecurityException` from
     * `startForeground()` if a declared type's required permission isn't held at call time, and this
     * service starts the moment the app opens, before Boss has necessarily granted anything. The
     * manifest's `specialUse|location` is the ceiling; passing a subset here (just `specialUse`,
     * pre-grant) is legal and does not need the service to restart once location is later granted —
     * `location.get` itself gates on the real permission (`LocationGetCommand`), not on this
     * service's declared type.
     */
    private fun foregroundServiceType(): Int {
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (hasLocation) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
    }

    companion object {
        private const val CHANNEL = "pagel.anchor"
        private const val NOTIF_ID = 4201
    }
}
