package org.pagel.ai.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.pagel.ai.data.room.AwarenessDao
import org.pagel.ai.data.room.AwarenessDb
import org.pagel.ai.net.identity.DeviceIdentityStore
import org.pagel.ai.net.identity.EncryptedFileDeviceIdentityStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDb(@ApplicationContext ctx: Context): AwarenessDb =
        Room.databaseBuilder(ctx, AwarenessDb::class.java, "pagel-awareness.db")
            .fallbackToDestructiveMigration() // pre-1.0: schema churn ok, nuke + rebuild (no user data lost)
            .build()

    @Provides
    @Singleton
    fun provideDao(db: AwarenessDb): AwarenessDao = db.dao()

    @Provides
    @Singleton
    fun provideDeviceIdentityStore(store: EncryptedFileDeviceIdentityStore): DeviceIdentityStore = store
}
