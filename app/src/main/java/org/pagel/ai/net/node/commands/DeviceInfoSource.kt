package org.pagel.ai.net.node.commands

/**
 * Battery charge state as the official OpenClaw Android app's `device.status` reports it on the
 * wire — three lowercase values, pinned by a live capture of the `A05s` node (Phase D task doc,
 * `protocol-reference-node-commands.md` §4.1: the gateway defines no schema for this payload, so
 * matching the official app's shape is the only way Boss's existing server-side skills/watchers
 * keep parsing it). Android's own `BATTERY_STATUS_DISCHARGING`/`NOT_CHARGING`/`UNKNOWN` all collapse
 * to [UNPLUGGED] — the wire shape has no room for the extra states, and "not actively charging" is
 * what a watcher branching on this field actually cares about.
 */
enum class BatteryChargeState(val wireValue: String) {
    UNPLUGGED("unplugged"),
    CHARGING("charging"),
    FULL("full"),
}

/** [android.os.PowerManager.getCurrentThermalStatus]'s seven levels, lowercased for the wire.
 *  `THERMAL_STATUS_NONE` -> [NOMINAL] is the one name that doesn't match Android's own constant. */
enum class ThermalState(val wireValue: String) {
    NOMINAL("nominal"),
    LIGHT("light"),
    MODERATE("moderate"),
    SEVERE("severe"),
    CRITICAL("critical"),
    EMERGENCY("emergency"),
    SHUTDOWN("shutdown"),
}

/** [android.net.NetworkCapabilities] transport, collapsed to the one active connection. */
enum class NetworkType(val wireValue: String) {
    WIFI("wifi"),
    CELLULAR("cellular"),
    ETHERNET("ethernet"),
    NONE("none"),
    UNKNOWN("unknown"),
}

data class BatterySnapshot(
    /** 0..1 — a 78% battery is `0.78`, never `78`. [AndroidDeviceInfoSource.battery] is the only
     *  place that divides `BatteryManager.BATTERY_PROPERTY_CAPACITY`'s 0..100 int by 100. */
    val levelFraction: Double,
    val state: BatteryChargeState,
    val lowPowerModeEnabled: Boolean,
)

data class StorageSnapshot(val totalBytes: Long, val freeBytes: Long, val usedBytes: Long)

data class NetworkSnapshot(val connected: Boolean, val type: NetworkType, val metered: Boolean)

data class DeviceIdentitySnapshot(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val appVersionName: String,
    val appVersionCode: Long,
    val timezone: String,
)

data class MemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val lowMemory: Boolean,
    val thresholdBytes: Long,
)

/**
 * Foreground and background location kept as two independent booleans, never collapsed into one —
 * this is the field the whole `device.permissions` command exists for (Phase D task doc): Boss's
 * watchers are blind specifically when [background] is `false` while [foreground] reads `true`
 * ("While Using" granted, "Allow all the time" not), and a single collapsed boolean would hide
 * exactly that distinction.
 */
data class LocationPermissionSnapshot(val foreground: Boolean, val background: Boolean)

data class DevicePermissionsSnapshot(
    val location: LocationPermissionSnapshot,
    val camera: Boolean,
    val microphone: Boolean,
    val notificationListener: Boolean,
    val postNotifications: Boolean,
    val activityRecognition: Boolean,
    val usageStats: Boolean,
    // Added 2026-10-05 so the whole PERMISSIONS list can be audited from the server. Defaults keep old callers valid.
    val calendar: Boolean = false,
    val callLog: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val installPackages: Boolean = false,
)

/**
 * Every Android API read Task 4's four `device.*` commands need, behind one small interface — the
 * same shape [org.pagel.ai.net.identity.DeviceIdentityStore]/[org.pagel.ai.net.identity.DeviceIdentityFileIo]
 * use to keep Android-touching code out of the classes that need to be unit-tested. The repo has no
 * `androidTest` source set and no Robolectric (established project convention, not an oversight —
 * see those classes' own doc), so a command that reads [android.content.Context] directly is a
 * command that can never be unit-tested; every command in this package depends on this interface
 * and a fake instead.
 *
 * [AndroidDeviceInfoSource] is the only production implementation, wired in `GatewayModule`.
 */
interface DeviceInfoSource {
    fun battery(): BatterySnapshot
    fun thermalState(): ThermalState
    fun storage(): StorageSnapshot
    fun network(): NetworkSnapshot
    fun deviceIdentity(): DeviceIdentitySnapshot
    fun memory(): MemorySnapshot
    fun uptimeMs(): Long
    fun permissions(): DevicePermissionsSnapshot
}
