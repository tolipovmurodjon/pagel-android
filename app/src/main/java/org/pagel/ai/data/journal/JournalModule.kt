package org.pagel.ai.data.journal

import android.content.Context
import androidx.room.Room
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.compass.CompassKey
import org.pagel.ai.data.compass.KeystoreDeviceKeys
import org.pagel.ai.data.today.OutboxScheduler
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.di.AppScope
import org.pagel.ai.di.IoDispatcher
import org.pagel.ai.di.PagelHttp
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Sends the journal's sealed entries when there is a network; its own queue. */
class JournalWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Parts {
        fun journal(): JournalRepository
        fun accounts(): AccountStore
    }

    override suspend fun doWork(): Result {
        val parts = EntryPointAccessors.fromApplication(applicationContext, Parts::class.java)
        val account = (parts.accounts().load() as? AccountState.Paired)?.account ?: return Result.success()
        return when (parts.journal().sendAll(account)) {
            SendResult.ALL_SENT, SendResult.SIGNED_OUT -> Result.success()
            SendResult.TRY_LATER -> Result.retry()
        }
    }

    companion object {
        private const val NAME = "pagel-journal"

        fun scheduler(ctx: Context) = OutboxScheduler {
            val req = OneTimeWorkRequestBuilder<JournalWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/** The locked journal's parts (Phase 6b): its own sealed database, its own Keystore guard key, its own open key. */
@Module
@InstallIn(SingletonComponent::class)
object JournalModule {
    @Provides @Singleton
    fun provideJournalDatabase(@ApplicationContext ctx: Context): JournalDatabase =
        Room.databaseBuilder(ctx, JournalDatabase::class.java, JournalDatabase.NAME).build()

    @Provides
    fun provideJournalDao(db: JournalDatabase): JournalDao = db.dao()

    @Provides @Singleton
    fun provideJournalRepository(
        @ApplicationContext ctx: Context, dao: JournalDao, @PagelHttp http: OkHttpClient, @AppScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): JournalRepository = JournalRepository(
        dao, JournalApi(http, io), KeystoreDeviceKeys(ctx, JournalRepository.GUARD), CompassKey(scope), JournalWorker.scheduler(ctx), io,
    )
}
