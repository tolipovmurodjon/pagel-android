package org.pagel.ai.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Enqueues the background harvest (≤30 min — OS events expire fast) + the rollup fold. */
object WorkScheduler {

    private const val HARVEST = "pagel-harvest"
    private const val ROLLUP = "pagel-rollup"
    private const val HARVEST_NOW = "pagel-harvest-now"

    fun ensure(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val harvest = PeriodicWorkRequestBuilder<HarvestWorker>(20, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(HARVEST, ExistingPeriodicWorkPolicy.KEEP, harvest)

        val rollup = PeriodicWorkRequestBuilder<RollupWorker>(2, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(ROLLUP, ExistingPeriodicWorkPolicy.KEEP, rollup)
    }

    /** Fire an immediate harvest (e.g. user tapped refresh). */
    fun harvestNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            HARVEST_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HarvestWorker>().build(),
        )
    }
}
