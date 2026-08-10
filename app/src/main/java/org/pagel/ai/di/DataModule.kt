package org.pagel.ai.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.pagel.ai.net.identity.DefaultDeviceIdentityStore
import org.pagel.ai.net.identity.DeviceIdentityFileIo
import org.pagel.ai.net.identity.DeviceIdentityStore
import org.pagel.ai.net.identity.EncryptedFileDeviceIdentityFileIo
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDeviceIdentityFileIo(io: EncryptedFileDeviceIdentityFileIo): DeviceIdentityFileIo = io

    @Provides
    @Singleton
    fun provideDeviceIdentityStore(store: DefaultDeviceIdentityStore): DeviceIdentityStore = store
}
