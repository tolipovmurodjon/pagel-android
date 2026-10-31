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
import org.pagel.ai.data.db.InboxDao
import org.pagel.ai.data.inbox.InboxPictures
import org.pagel.ai.data.inbox.InboxRepository
import org.pagel.ai.data.inbox.InboxSender
import org.pagel.ai.data.db.PagelDatabase
import org.pagel.ai.data.db.RitualsDao
import org.pagel.ai.data.rituals.RitualSender
import org.pagel.ai.data.rituals.RitualsRepository
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.data.habits.ReminderPlanner
import org.pagel.ai.reminders.HabitAlarms
import org.pagel.ai.data.sync.SyncEngine
import org.pagel.ai.data.today.OutboxSender
import org.pagel.ai.data.today.OutboxWorker
import org.pagel.ai.data.today.TodayRepository
import org.pagel.ai.data.tasks.TaskRepository
import org.pagel.ai.data.upcoming.UpcomingRepository
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
    fun provideHttp(): OkHttpClient = org.pagel.ai.data.api.pagelHttp()

    @Provides @Singleton
    fun provideApi(@PagelHttp http: OkHttpClient, @IoDispatcher io: CoroutineDispatcher): PagelApi = PagelApi(http, io)

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): PagelDatabase =
        // v1 → v2 → v3 → v4 → v5 are AutoMigrations (PagelDatabase); each step has a test (MigrationTest).
        Room.databaseBuilder(ctx, PagelDatabase::class.java, PagelDatabase.NAME).build()

    @Provides
    fun provideDao(db: PagelDatabase): PagelDao = db.dao()

    @Provides @Singleton
    fun provideSyncEngine(api: PagelApi, dao: PagelDao): SyncEngine = SyncEngine(api, dao)

    @Provides @Singleton
    fun provideOutboxSender(api: PagelApi, dao: PagelDao, inbox: InboxDao, rituals: RitualsDao, captured: org.pagel.ai.data.inbox.CapturedFiles): OutboxSender =
        OutboxSender(api, dao, inbox = InboxSender(api, inbox, captured = captured), rituals = RitualSender(api, rituals))

    /** The saves captured on this phone, kept so they stay in the Inbox after pagel.uz names them (Inbox v3). */
    @Provides @Singleton
    fun provideCapturedFiles(@ApplicationContext ctx: Context): org.pagel.ai.data.inbox.CapturedFiles =
        org.pagel.ai.data.inbox.CapturedFiles(ctx.getSharedPreferences("pagel-inbox", Context.MODE_PRIVATE))

    @Provides
    fun provideRitualsDao(db: PagelDatabase): RitualsDao = db.rituals()

    @Provides
    @Singleton
    fun provideHabitPrefsDao(db: PagelDatabase): org.pagel.ai.data.db.HabitPrefsDao = db.habitPrefs()

    @Provides
    @Singleton
    fun provideNamozDao(db: PagelDatabase): org.pagel.ai.data.db.NamozDao = db.namoz()

    @Provides
    @Singleton
    fun provideWhereItWent(db: PagelDatabase, inbox: InboxDao, api: PagelApi): org.pagel.ai.data.inbox.WhereItWent =
        org.pagel.ai.data.inbox.WhereItWent(db.inboxLog(), inbox, api)

    @Provides
    @Singleton
    fun provideHabitPrefsRepository(dao: org.pagel.ai.data.db.HabitPrefsDao): org.pagel.ai.data.habits.HabitPrefsRepository =
        org.pagel.ai.data.habits.HabitPrefsRepository(dao)

    /** The rituals (Phase 5): Empty your head, Close the day, the weekly review, the evening reminder. */
    @Provides @Singleton
    fun provideRitualsRepository(
        @ApplicationContext ctx: Context, dao: PagelDao, rituals: RitualsDao, api: PagelApi, today: TodayRepository, tasks: TaskRepository, reminders: ReminderPlanner,
    ): RitualsRepository = RitualsRepository(dao, rituals, api, today, tasks, OutboxWorker.scheduler(ctx), reminders)

    @Provides
    fun provideInboxDao(db: PagelDatabase): InboxDao = db.inbox()

    @Provides @Singleton
    fun provideInboxRepository(@ApplicationContext ctx: Context, api: PagelApi, inbox: InboxDao, captured: org.pagel.ai.data.inbox.CapturedFiles): InboxRepository =
        InboxRepository(api, inbox, OutboxWorker.scheduler(ctx), captured = captured)

    @Provides @Singleton
    fun provideInboxPictures(@ApplicationContext ctx: Context, api: PagelApi, @IoDispatcher io: CoroutineDispatcher): InboxPictures =
        InboxPictures(api, java.io.File(ctx.cacheDir, "inbox-pictures"), io)

    @Provides @Singleton
    fun provideTodayRepository(@ApplicationContext ctx: Context, dao: PagelDao): TodayRepository =
        TodayRepository(dao, OutboxWorker.scheduler(ctx))

    @Provides @Singleton
    fun provideTaskRepository(@ApplicationContext ctx: Context, dao: PagelDao): TaskRepository =
        TaskRepository(dao, OutboxWorker.scheduler(ctx))

    /** Projects you shape (core round): create, edit, merge, delete with Undo, finish, goals, KPI counts. */
    @Provides @Singleton
    fun provideProjectRepository(@ApplicationContext ctx: Context, dao: PagelDao, tasks: TaskRepository): org.pagel.ai.data.projects.ProjectRepository =
        org.pagel.ai.data.projects.ProjectRepository(dao, tasks, OutboxWorker.scheduler(ctx))

    /** The habits' reminder alarms (Phase 3). */
    @Provides @Singleton
    fun provideReminderPlanner(@ApplicationContext ctx: Context, dao: PagelDao, db: PagelDatabase, @AppScope scope: CoroutineScope, @IoDispatcher io: CoroutineDispatcher): ReminderPlanner =
        HabitAlarms(ctx, dao, scope, io, rituals = db.rituals(), namoz = db.namoz())

    @Provides @Singleton
    fun provideHabitsRepository(@ApplicationContext ctx: Context, dao: PagelDao, api: PagelApi, reminders: ReminderPlanner): HabitsRepository =
        HabitsRepository(dao, api, OutboxWorker.scheduler(ctx), reminders)

    @Provides @Singleton
    fun provideUpcomingRepository(@ApplicationContext ctx: Context, dao: PagelDao, api: PagelApi, tasks: TaskRepository): UpcomingRepository =
        UpcomingRepository(dao, api, tasks, scheduler = OutboxWorker.scheduler(ctx))

    @Provides @Singleton
    fun provideAccountStore(store: EncryptedAccountStore): AccountStore = store
}
