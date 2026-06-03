package org.pagel.ai.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.pagel.ai.data.sync.BrainSync
import org.pagel.ai.data.usage.UsageRepository
import java.time.LocalDate

/** Periodically pushes today's summary to Boss's Oracle brain (no-op if unconfigured / no access). */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val repo: UsageRepository,
    private val sync: BrainSync,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val snap = runCatching { repo.today() }.getOrNull() ?: return Result.success()
        val result = sync.push(snap, LocalDate.now().toString())
        if (result.isSuccess) return Result.success()
        val msg = result.exceptionOrNull()?.message ?: ""
        // Unconfigured = nothing to do (don't hammer); a real network error = retry with backoff.
        return if (msg.contains("not configured")) Result.success() else Result.retry()
    }
}
