package org.pagel.ai.data.compass

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
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.today.OutboxScheduler
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.di.AppScope
import org.pagel.ai.di.IoDispatcher
import org.pagel.ai.di.PagelHttp
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Sends the Compass's sealed changes when there is a network; its own queue, apart from the task outbox. */
class CompassWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Parts {
        fun compass(): CompassRepository
        fun accounts(): AccountStore
    }

    override suspend fun doWork(): Result {
        val parts = EntryPointAccessors.fromApplication(applicationContext, Parts::class.java)
        val account = (parts.accounts().load() as? AccountState.Paired)?.account ?: return Result.success()
        return when (parts.compass().sendAll(account)) {
            SendResult.ALL_SENT, SendResult.SIGNED_OUT -> Result.success()
            SendResult.TRY_LATER -> Result.retry()
        }
    }

    companion object {
        private const val NAME = "pagel-compass"

        fun scheduler(ctx: Context) = OutboxScheduler {
            val req = OneTimeWorkRequestBuilder<CompassWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/** The Compass's parts (Phase 6a): its own sealed database, its calls, the phone's Keystore locks, the open key. */
@Module
@InstallIn(SingletonComponent::class)
object CompassModule {
    @Provides @Singleton
    fun provideCompassDatabase(@ApplicationContext ctx: Context): CompassDatabase =
        Room.databaseBuilder(ctx, CompassDatabase::class.java, CompassDatabase.NAME).build()

    @Provides
    fun provideCompassDao(db: CompassDatabase): CompassDao = db.dao()

    @Provides @Singleton
    fun provideCompassApi(@PagelHttp http: OkHttpClient, @IoDispatcher io: CoroutineDispatcher): CompassApi = CompassApi(http, io)

    @Provides @Singleton
    fun provideDeviceKeys(@ApplicationContext ctx: Context): DeviceKeys = KeystoreDeviceKeys(ctx)

    @Provides @Singleton
    fun provideCompassKey(@AppScope scope: CoroutineScope): CompassKey = CompassKey(scope)

    @Provides @Singleton
    fun provideCompassRepository(
        @ApplicationContext ctx: Context, dao: CompassDao, api: CompassApi, keys: DeviceKeys, key: CompassKey, tasks: PagelDao, today: TodayRepository,
        @IoDispatcher io: CoroutineDispatcher,
    ): CompassRepository = CompassRepository(dao, api, keys, key, tasks, today, CompassWorker.scheduler(ctx), io)
}
