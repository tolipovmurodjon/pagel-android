package org.pagel.ai.net.node.commands

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.pagel.ai.data.usage.UsagePermission
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only production [DeviceInfoSource] — every method is a direct, side-effect-free read of an
 * Android system service. No caching: each `device.*` invoke (protocol reference §1.4, budgeted
 * well under 15s) is infrequent enough that a fresh read is cheap and, unlike a cached one, can
 * never go stale mid-diagnosis.
 */
@Singleton
class AndroidDeviceInfoSource @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : DeviceInfoSource {

    override fun battery(): BatterySnapshot {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        // BATTERY_PROPERTY_CAPACITY is 0..100 (or Integer.MIN_VALUE if unsupported, which
        // coerceIn(0, 100) collapses to 0 rather than a stray negative fraction on the wire).
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return BatterySnapshot(
            levelFraction = level / 100.0,
            state = mapBatteryState(status),
            lowPowerModeEnabled = pm.isPowerSaveMode,
        )
    }

    override fun thermalState(): ThermalState {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return mapThermalState(pm.currentThermalStatus)
    }

    override fun storage(): StorageSnapshot {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val free = stat.freeBytes
        return StorageSnapshot(totalBytes = total, freeBytes = free, usedBytes = (total - free).coerceAtLeast(0))
    }

    override fun network(): NetworkSnapshot {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val type = when {
            caps == null -> NetworkType.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }
        return NetworkSnapshot(
            connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false,
            type = type,
            metered = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: false,
        )
    }

    override fun deviceIdentity(): DeviceIdentitySnapshot {
        val pkg = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        return DeviceIdentitySnapshot(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            sdkInt = Build.VERSION.SDK_INT,
            appVersionName = pkg?.versionName ?: "unknown",
            appVersionCode = pkg?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
            timezone = TimeZone.getDefault().id,
        )
    }

    override fun memory(): MemorySnapshot {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return MemorySnapshot(
            totalBytes = info.totalMem,
            availableBytes = info.availMem,
            lowMemory = info.lowMemory,
            thresholdBytes = info.threshold,
        )
    }

    override fun uptimeMs(): Long = SystemClock.elapsedRealtime()

    override fun permissions(): DevicePermissionsSnapshot {
        val fineGranted = hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseGranted = hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        // ACCESS_BACKGROUND_LOCATION only exists as a distinct grant from API 29 on, and only means
        // anything once ACCESS_BACKGROUND_LOCATION is in the manifest (Task 5) — checking it now,
        // before that manifest entry exists, honestly reports `false` rather than pretending
        // foreground implies background the way it did pre-Q.
        val backgroundGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            fineGranted || coarseGranted
        }
        return DevicePermissionsSnapshot(
            location = LocationPermissionSnapshot(foreground = fineGranted || coarseGranted, background = backgroundGranted),
            camera = hasPermission(android.Manifest.permission.CAMERA),
            microphone = hasPermission(android.Manifest.permission.RECORD_AUDIO),
            notificationListener = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
            postNotifications = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            activityRecognition = hasPermission(android.Manifest.permission.ACTIVITY_RECOGNITION),
            usageStats = UsagePermission.hasAccess(ctx),
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** [BatteryManager.BATTERY_PROPERTY_STATUS] -> the three-value wire shape ([BatteryChargeState]).
         *  Pure int mapping, factored out of [battery] so it's unit-testable without an Android
         *  runtime: `BATTERY_STATUS_*` are compile-time-inlined int constants, safe to reference
         *  from a plain JVM test even though the rest of this class needs a real [Context]. */
        fun mapBatteryState(batteryStatus: Int): BatteryChargeState = when (batteryStatus) {
            BatteryManager.BATTERY_STATUS_CHARGING -> BatteryChargeState.CHARGING
            BatteryManager.BATTERY_STATUS_FULL -> BatteryChargeState.FULL
            // DISCHARGING, NOT_CHARGING, UNKNOWN, and any unrecognized value all collapse to
            // UNPLUGGED — the official app's wire shape has no fourth state (see BatteryChargeState's doc).
            else -> BatteryChargeState.UNPLUGGED
        }

        /** [PowerManager.getCurrentThermalStatus] -> lowercase wire name ([ThermalState]). Same
         *  testability reasoning as [mapBatteryState]. */
        fun mapThermalState(thermalStatus: Int): ThermalState = when (thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.SHUTDOWN
            else -> ThermalState.NOMINAL
        }
    }
}
