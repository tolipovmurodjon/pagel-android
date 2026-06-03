package org.pagel.ai.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.pagel.ai.data.usage.UsageStatsHarvester

/** Periodic background harvest of the usage-event stream into Room. */
@HiltWorker
class HarvestWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val harvester: UsageStatsHarvester,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        harvester.harvest()
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
