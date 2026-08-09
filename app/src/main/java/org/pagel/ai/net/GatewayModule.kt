package org.pagel.ai.net

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.pagel.ai.BuildConfig
import org.pagel.ai.net.ws.GatewayDeviceTokenStore
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Backs [DataStoreGatewayDeviceTokenStore] — per-`(deviceId, role)` hello-ok tokens. Its own
 *  DataStore file, distinct from [GatewaySettings]'s `"pagel_gateway_settings"` and
 *  `org.pagel.ai.data.sync.SyncSettings`'s `"pagel_sync"`. */
private val Context.gatewayTokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "pagel_gateway")

/** Wires Task 1-6's gateway classes (identity, connections, [GatewayManager]) into the app: the
 *  OkHttp transport, the concrete [GatewayDeviceTokenStore], the app-lifetime [CoroutineScope]
 *  [GatewayManager] needs, and the [GatewayConnectionConfig] it's constructed with. */
@Module
@InstallIn(SingletonComponent::class)
object GatewayModule {

    @Provides
    @Singleton
    fun provideGatewayTokenDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        ctx.gatewayTokenDataStore

    @Provides
    @Singleton
    fun provideGatewayDeviceTokenStore(store: DataStoreGatewayDeviceTokenStore): GatewayDeviceTokenStore = store

    /**
     * `pingInterval = 0`: the gateway has its own tick/watchdog protocol
     * ([org.pagel.ai.net.ws.GatewayConnection]'s watchdog, built in Task 5) — OkHttp's ping would
     * double up on it. `readTimeout = 0`: a WebSocket is long-lived; OkHttp's default 10s read
     * timeout would kill an idle-but-healthy connection waiting on the next server frame.
     */
    @Provides
    @Singleton
    fun provideGatewayOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Provides
    @Singleton
    fun provideWebSocketFactory(client: OkHttpClient): WebSocket.Factory = client

    /** [GatewayManager] must keep retrying across navigation, not just while the debug screen is
     *  on screen — an app-lifetime scope, not a screen-lifetime one. [SupervisorJob] so one
     *  connection failing can't cancel the sibling connection's coroutines. */
    @Provides
    @Singleton
    fun provideGatewayScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Read once, synchronously, when Hilt first builds the [GatewayManager] singleton (normally
     * when `GatewayViewModel` is first created) — see [GatewaySettings]'s doc for why a later
     * [GatewaySettings.saveUrl] doesn't retarget it live. `runBlocking` here is a single
     * main-thread DataStore read at cold start, not a hot path.
     */
    @Provides
    @Singleton
    fun provideGatewayConnectionConfig(settings: GatewaySettings): GatewayConnectionConfig =
        GatewayConnectionConfig(
            serverUrl = runBlocking { settings.currentUrl() },
            clientVersion = BuildConfig.VERSION_NAME,
            platform = "Android",
            deviceFamily = Build.MODEL,
        )
}
