package org.pagel.ai.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Restarts [AnchorService] if something killed it.
 *
 * ### Why this is legal, and the condition it depends on
 *
 * A `Worker` is **not** on Android's exemption list for starting a foreground service from the
 * background — `restrictions-bg-start` enumerates fourteen exemptions and WorkManager is not one of
 * them. On its own this class would therefore throw
 * [ForegroundServiceStartNotAllowedException] every time and be pure theatre.
 *
 * It works because of exemption 13: **"the user disables battery optimizations for the app"**.
 * That is precisely the grant the setup checklist asks Boss for, which makes the battery exemption
 * load-bearing rather than a nicety — without it this watchdog cannot fire, and PAGEL stays dead
 * until the app is next opened. (Exemption 14, `SYSTEM_ALERT_WINDOW` with a visible overlay window,
 * would also cover it once the Wave 3 HUD exists, but relying on a *visible* overlay is worse.)
 *
 * The failure is therefore expected-and-survivable, not exceptional: it is caught, and the next
 * period simply tries again — by which point Boss may have granted the exemption.
 *
 * Deliberately not a `@HiltWorker`: it injects nothing, so bringing back `androidx.hilt:hilt-work`
 * and a `Configuration.Provider` (both removed in `ffec6df`) would be dependency weight for nothing.
 * The default WorkManager initializer is in play — the manifest `<provider>` block that used to
 * disable it went with them.
 */
class AnchorWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Starting an already-running service is a no-op beyond a second onStartCommand, and
        // AnchorService is START_STICKY, so there is nothing to check first — attempting the start
        // unconditionally is both cheaper and more reliable than asking whether it is running
        // (getRunningServices has been restricted to one's own app and is deprecated besides).
        return try {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, AnchorService::class.java),
            )
            Result.success()
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                // Battery optimisation is still enabled for us. Retrying sooner would not help —
                // only Boss visiting Settings does. Succeed so the periodic schedule survives.
                Result.success()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        private const val UNIQUE_NAME = "pagel-anchor-watchdog"

        /** 15 minutes is WorkManager's floor for periodic work; asking for less is silently raised. */
        private const val PERIOD_MINUTES = 15L

        /**
         * Idempotent — [ExistingPeriodicWorkPolicy.KEEP] means calling this on every
         * [AnchorService] start re-arms nothing and resets no timer. WorkManager's schedule already
         * survives reboot on its own; this exists so the watchdog is armed the first time the app is
         * ever opened, and stays armed.
         */
        fun ensureScheduled(ctx: Context) {
            runCatching {
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<AnchorWatchdogWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build(),
                )
            }
        }
    }
}
