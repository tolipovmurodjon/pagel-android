package org.pagel.ai.data.notebook

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
import okhttp3.OkHttpClient
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.today.OutboxScheduler
import org.pagel.ai.data.today.SendResult
import org.pagel.ai.di.IoDispatcher
import org.pagel.ai.di.PagelHttp
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Sends the second brain's waiting changes (folder switches, notes, recall grades) when there is a network. */
class NotebookWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Parts {
        fun notebook(): NotebookRepository
        fun accounts(): AccountStore
    }

    override suspend fun doWork(): Result {
        val parts = EntryPointAccessors.fromApplication(applicationContext, Parts::class.java)
        val account = (parts.accounts().load() as? AccountState.Paired)?.account ?: return Result.success()
        return when (parts.notebook().sendAll(account)) {
            SendResult.ALL_SENT, SendResult.SIGNED_OUT -> Result.success()
            SendResult.TRY_LATER -> Result.retry()
        }
    }

    companion object {
        private const val NAME = "pagel-notebook"

        fun scheduler(ctx: Context) = OutboxScheduler {
            val req = OneTimeWorkRequestBuilder<NotebookWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/** The second brain's parts (Phase 6b): its own database file, its calls, the repository. */
@Module
@InstallIn(SingletonComponent::class)
object NotebookModule {
    @Provides @Singleton
    fun provideNotebookDatabase(@ApplicationContext ctx: Context): NotebookDatabase =
        Room.databaseBuilder(ctx, NotebookDatabase::class.java, NotebookDatabase.NAME).build()

    @Provides
    fun provideNotebookDao(db: NotebookDatabase): NotebookDao = db.dao()

    @Provides @Singleton
    fun provideNotebookApi(@PagelHttp http: OkHttpClient, @IoDispatcher io: CoroutineDispatcher): NotebookApi = NotebookApi(http, io)

    @Provides @Singleton
    fun provideNotebookRepository(
        @ApplicationContext ctx: Context, dao: NotebookDao, api: NotebookApi, tasks: PagelDao, @IoDispatcher io: CoroutineDispatcher,
    ): NotebookRepository = NotebookRepository(dao, api, tasks, NotebookWorker.scheduler(ctx), io)
}
