package org.pagel.ai.data.today

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore

/** Sends the outbox when there is a network; WorkManager retries with exponential backoff (10 s, 20 s, 40 s …). */
class OutboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Parts {
        fun sender(): OutboxSender
        fun accounts(): AccountStore
    }

    override suspend fun doWork(): Result {
        val parts = EntryPointAccessors.fromApplication(applicationContext, Parts::class.java)
        val account = (parts.accounts().load() as? AccountState.Paired)?.account ?: return Result.success()
        return when (parts.sender().sendAll(account)) {
            SendResult.ALL_SENT, SendResult.SIGNED_OUT -> Result.success()
            SendResult.TRY_LATER -> Result.retry()
        }
    }

    companion object {
        private const val NAME = "pagel-outbox"

        /** One sender at a time; a new tick while it runs appends a pass, so nothing waits for the next backoff. */
        fun scheduler(ctx: Context) = OutboxScheduler {
            val req = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
