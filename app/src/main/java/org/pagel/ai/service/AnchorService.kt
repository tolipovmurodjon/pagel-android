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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.pagel.ai.net.GatewayAuthTokenStore
import org.pagel.ai.net.GatewayManager
import org.pagel.ai.net.GatewaySettings
import javax.inject.Inject

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
@AndroidEntryPoint
class AnchorService : Service() {

    @Inject lateinit var gateway: GatewayManager
    @Inject lateinit var settings: GatewaySettings
    @Inject lateinit var authTokenStore: GatewayAuthTokenStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        goForeground()
        AnchorWatchdogWorker.ensureScheduled(this)
        // Found 2026-10-05: nothing started the gateway except the CONNECT button, so after any restart (an
        // update, a reboot, a kill) PAGEL sat disconnected until Boss opened the app. Start it here, with the
        // saved URL and token, both read here on IO (never on Main).
        scope.launch {
            val url = settings.currentUrl()
            val token = authTokenStore.get()?.trim()?.ifEmpty { null }
            gateway.start(url, token)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
            .setSmallIcon(org.pagel.ai.R.drawable.ic_stat_pagel)
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
     * service and must never be silently dropped. The manifest's `specialUse|location` is the
     * *ceiling*; passing a subset here is legal, and the service does not need restarting when
     * permissions later change — `location.get` gates on the real permission
     * (`LocationGetCommand`), never on this service's declared type.
     *
     * ### Why the `location` bit is gated on BACKGROUND, not foreground
     *
     * The obvious version of this check tests foreground location, and it was written that way
     * while [MainActivity][org.pagel.ai.MainActivity] was the only caller — a foreground start, for
     * which foreground permission is sufficient.
     *
     * [BootReceiver] changed that. A boot start is by definition a **background** start, and
     * Android 14+ refuses a background-started foreground service that claims the `location` type
     * unless `ACCESS_BACKGROUND_LOCATION` is held — it throws from `startForeground()`, killing the
     * service, with a logcat line about location/camera/microphone access rather than anything that
     * names the real cause. Boss granting only "While using the app" would therefore have made
     * every reboot silently lose PAGEL — the precise feature the boot receiver exists to deliver.
     *
     * Gating on background permission is correct for both callers: it is strictly stronger than
     * foreground (Android will not grant background without foreground), so the foreground path
     * loses nothing real.
     */
    private fun foregroundServiceType(): Int {
        val hasBackgroundLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return if (hasBackgroundLocation) {
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
