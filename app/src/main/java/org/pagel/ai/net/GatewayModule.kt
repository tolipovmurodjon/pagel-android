package org.pagel.ai.net

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.pagel.ai.BuildConfig
import org.pagel.ai.net.node.NodeCommand
import org.pagel.ai.net.node.commands.AndroidDeviceInfoSource
import org.pagel.ai.net.node.commands.DeviceHealthCommand
import org.pagel.ai.net.node.commands.DeviceInfoCommand
import org.pagel.ai.net.node.commands.DeviceInfoSource
import org.pagel.ai.net.node.commands.AndroidLocationSource
import org.pagel.ai.net.node.commands.DevicePermissionsCommand
import org.pagel.ai.net.node.commands.DeviceAppsCommand
import org.pagel.ai.net.node.commands.DeviceStatusCommand
import org.pagel.ai.net.node.commands.MotionActivityCommand
import org.pagel.ai.net.node.commands.MotionPedometerCommand
import org.pagel.ai.net.node.commands.NotificationsListCommand
import org.pagel.ai.net.node.commands.LocationGetCommand
import org.pagel.ai.net.node.commands.LocationSource
import org.pagel.ai.net.ws.GatewayDeviceTokenStore
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Backs [DataStoreGatewayDeviceTokenStore] — per-`(deviceId, role)` hello-ok tokens. Its own
 *  DataStore file, distinct from [GatewaySettings]'s `"pagel_gateway_settings"`. */
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

    /** The gateway's shared `auth.token` secret — a different gate from [GatewayDeviceTokenStore]
     *  above. See [GatewayAuthTokenStore]'s doc. */
    @Provides
    @Singleton
    fun provideGatewayAuthTokenFileIo(io: EncryptedFileGatewayAuthTokenFileIo): GatewayAuthTokenFileIo = io

    @Provides
    @Singleton
    fun provideGatewayAuthTokenStore(store: DefaultGatewayAuthTokenStore): GatewayAuthTokenStore = store

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

    /** [AndroidDeviceInfoSource] is the only production [DeviceInfoSource] — see that interface's
     *  doc for why the four `device.*` commands (Task 4) depend on the interface, not the concrete
     *  Android-backed class, directly. */
    @Provides
    @Singleton
    fun provideDeviceInfoSource(source: AndroidDeviceInfoSource): DeviceInfoSource = source

    /** [AndroidLocationSource] is the only production [LocationSource] — see that interface's doc for
     *  why `location.get` (Task 5) depends on the interface, not the concrete Android-backed class,
     *  directly. */
    @Provides
    @Singleton
    fun provideLocationSource(source: AndroidLocationSource): LocationSource = source

    /**
     * [GatewayManager]'s `nodeCommands` binding — Task 4's four `device.*` commands plus Task 5's
     * `location.get`; Task 6 adds its commands here too, not by touching [GatewayManager]'s
     * constructor again. Returns a plain `List<NodeCommand>`, not a `Set` multibinding: a small,
     * fixed, individually-injected set of commands needs no open-ended collection mechanism, and a
     * plain list matches [GatewayManager]'s own `List<NodeCommand>` parameter type without adding
     * Dagger multibinding machinery this phase doesn't otherwise use.
     */
    @Provides
    @Singleton
    fun provideNodeCommands(
        deviceStatus: DeviceStatusCommand,
        deviceInfo: DeviceInfoCommand,
        deviceHealth: DeviceHealthCommand,
        devicePermissions: DevicePermissionsCommand,
        locationGet: LocationGetCommand,
        motionPedometer: MotionPedometerCommand,
        motionActivity: MotionActivityCommand,
        notificationsList: NotificationsListCommand,
        deviceApps: DeviceAppsCommand,
    ): List<NodeCommand> = listOf(
        deviceStatus, deviceInfo, deviceHealth, devicePermissions, locationGet,
        motionPedometer, motionActivity, notificationsList, deviceApps,
    )

    /** [GatewayManager] must keep retrying across navigation, not just while the debug screen is
     *  on screen — an app-lifetime scope, not a screen-lifetime one. [SupervisorJob] so one
     *  connection failing can't cancel the sibling connection's coroutines.
     *
     *  The [CoroutineExceptionHandler] is a backstop, not the fix: a [SupervisorJob] scope routes
     *  anything a root coroutine throws to the handler, and *without* one that means Android's
     *  default handler and process death. Every known throwing path
     *  (`GatewayConnection.runHandshake`, the `tokenStore.put` launch) already catches its own
     *  exceptions — this only exists so a future one can't kill the app before anyone notices. */
    @Provides
    @Singleton
    fun provideGatewayScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Log.e("GatewayScope", "unhandled exception in a gateway coroutine", t)
        },
    )

    /**
     * Read once, synchronously, when Hilt first builds the [GatewayManager] singleton (normally
     * when `GatewayViewModel` is first created) — only relevant if a connection attempt somehow
     * happens before the debug screen ever runs its own [GatewayManager.start] overload, which reads
     * the current URL/token live on every CONNECT tap (see [GatewaySettings]'s doc). `runBlocking`
     * here is a single main-thread DataStore read at cold start, not a hot path.
     */
    /**
     * `deviceFamily = "Android"`, NOT `Build.MODEL`. The gateway's `platformMatchesDeviceFamily`
     * (`node-command-policy-CO8bCAnE.js:157-165`) only resolves `platformId = "android"` when
     * `deviceFamily` normalizes to `""` or `"android"` — a model name like `"SM-A057F"` there
     * demoted every node to the 3-command `unknown` allowlist and blocked every `nodes invoke`.
     * The model goes in `modelIdentifier` instead, which the schema accepts and which plays no part
     * in platform resolution or the signed device-auth payload.
     */
    @Provides
    @Singleton
    fun provideGatewayConnectionConfig(settings: GatewaySettings): GatewayConnectionConfig =
        GatewayConnectionConfig(
            serverUrl = runBlocking { settings.currentUrl() },
            clientVersion = BuildConfig.VERSION_NAME,
            platform = "Android",
            deviceFamily = "Android",
            modelIdentifier = Build.MODEL,
        )
}
