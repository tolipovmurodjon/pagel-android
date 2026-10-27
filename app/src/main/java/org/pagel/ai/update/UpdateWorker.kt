package org.pagel.ai.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Every 30 minutes (WorkManager's floor is 15), on any network: ask the update host; if newer, download and verify
 *  it, then post "PAGEL x ready · Tap to install". It never installs by itself: an install closes the app, so it
 *  waits for Boss's tap (2026-10-27, crash cause #1). Silent when there is nothing to do. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val updater = Updater(applicationContext)
        return when (val s = updater.check()) {
            is UpdateState.Available -> {
                val err = updater.download(s.manifest)
                if (err != null) {
                    Timber.w("update download skipped: %s", err)
                } else {
                    updater.rememberReady(s.manifest)
                    updater.notifyReady(s.manifest)
                }
                Result.success()
            }
            is UpdateState.Failed -> { Timber.d("update check failed: %s", s.reason); Result.success() }
            UpdateState.UpToDate -> Result.success()
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("pagel-self-update", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** One check now, at each app start: a new build reaches Boss in minutes, not up to 30 (Boss 2026-10-27). */
        fun checkNow(ctx: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("pagel-self-update-now", androidx.work.ExistingWorkPolicy.KEEP, req)
        }
    }
}
