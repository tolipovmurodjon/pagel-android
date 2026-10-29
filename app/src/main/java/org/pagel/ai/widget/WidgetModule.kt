package org.pagel.ai.widget

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.habits.HabitsRepository
import org.pagel.ai.di.IoDispatcher
import javax.inject.Singleton

/** Phase 8: what the widgets read through (WidgetParts), kept in its own module so round D's merge touches one file. */
@Module
@InstallIn(SingletonComponent::class)
object WidgetModule {
    @Provides
    @Singleton
    fun provideWidgetHabits(repo: HabitsRepository): WidgetHabits = RoomWidgetHabits(repo)

    @Provides
    @Singleton
    fun provideWidgetCalories(
        @ApplicationContext ctx: Context,
        api: PagelApi,
        accounts: AccountStore,
        @IoDispatcher io: CoroutineDispatcher,
    ): WidgetCalories = ServerCalories(ctx, api, accounts, io)
}
