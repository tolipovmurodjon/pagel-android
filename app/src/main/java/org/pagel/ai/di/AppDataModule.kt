package org.pagel.ai.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.account.EncryptedAccountStore
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.sync.SyncEngine
import org.pagel.ai.data.today.OutboxSender
import org.pagel.ai.data.today.OutboxWorker
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.data.tasks.TaskRepository
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** A scope that lives as long as the app: the sync loop runs here, not in a screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/** pagel.uz's HTTP client (timeouts), apart from the gateway's WebSocket client, which never times out reads. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PagelHttp

/** The native app's data layer (NATIVE-PLAN Phase 1): pagel.uz API, Room, sync engine, the paired account. */
@Module
@InstallIn(SingletonComponent::class)
object AppDataModule {
    @Provides @IoDispatcher
    fun provideIo(): CoroutineDispatcher = Dispatchers.IO

    @Provides @Singleton @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton @PagelHttp
    fun provideHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun provideApi(@PagelHttp http: OkHttpClient, @IoDispatcher io: CoroutineDispatcher): PagelApi = PagelApi(http, io)

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): PagelDatabase =
        // v1 → v2 → v3 are AutoMigrations (PagelDatabase); each step has a test (MigrationTest).
        Room.databaseBuilder(ctx, PagelDatabase::class.java, PagelDatabase.NAME).build()

    @Provides
    fun provideDao(db: PagelDatabase): PagelDao = db.dao()

    @Provides @Singleton
    fun provideSyncEngine(api: PagelApi, dao: PagelDao): SyncEngine = SyncEngine(api, dao)

    @Provides @Singleton
    fun provideOutboxSender(api: PagelApi, dao: PagelDao): OutboxSender = OutboxSender(api, dao)

    @Provides @Singleton
    fun provideTodayRepository(@ApplicationContext ctx: Context, dao: PagelDao): TodayRepository =
        TodayRepository(dao, OutboxWorker.scheduler(ctx))

    @Provides @Singleton
    fun provideTaskRepository(@ApplicationContext ctx: Context, dao: PagelDao): TaskRepository =
        TaskRepository(dao, OutboxWorker.scheduler(ctx))

    @Provides @Singleton
    fun provideAccountStore(store: EncryptedAccountStore): AccountStore = store
}
