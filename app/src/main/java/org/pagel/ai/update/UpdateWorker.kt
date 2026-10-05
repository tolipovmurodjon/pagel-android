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

/** Every 6 hours, on any network: ask the update host, install if newer. Silent when there is nothing to do. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val updater = Updater(applicationContext)
        return when (val s = updater.check()) {
            is UpdateState.Available -> {
                val err = updater.downloadAndInstall(s.manifest)
                if (err != null) Timber.w("self-update skipped: %s", err)
                Result.success()
            }
            is UpdateState.Failed -> { Timber.d("update check failed: %s", s.reason); Result.success() }
            UpdateState.UpToDate -> Result.success()
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("pagel-self-update", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
