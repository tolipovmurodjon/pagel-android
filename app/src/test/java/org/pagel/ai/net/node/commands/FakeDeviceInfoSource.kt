package org.pagel.ai.net.node.commands

/**
 * Shared fake for the four `device.*` command tests in this package — every command depends only on
 * [DeviceInfoSource], never on [android.content.Context] directly (see that interface's doc), so
 * this is the one fixture all of them need. Every field has a harmless default; each test overrides
 * only what it's asserting on, per the existing project convention (e.g.
 * [org.pagel.ai.net.node.NodeCommandDispatcherTest]'s `FakeNodeCommand`).
 */
internal class FakeDeviceInfoSource(
    private val battery: BatterySnapshot = BatterySnapshot(0.5, BatteryChargeState.UNPLUGGED, lowPowerModeEnabled = false),
    private val thermal: ThermalState = ThermalState.NOMINAL,
    private val storage: StorageSnapshot = StorageSnapshot(totalBytes = 1000, freeBytes = 400, usedBytes = 600),
    private val network: NetworkSnapshot = NetworkSnapshot(connected = true, type = NetworkType.WIFI, metered = false),
    private val identity: DeviceIdentitySnapshot = DeviceIdentitySnapshot(
        manufacturer = "samsung",
        model = "SM-A057F",
        androidVersion = "15",
        sdkInt = 35,
        appVersionName = "0.1-A1",
        appVersionCode = 1,
        timezone = "Asia/Tashkent",
    ),
    private val memory: MemorySnapshot = MemorySnapshot(
        totalBytes = 6_000_000_000,
        availableBytes = 2_000_000_000,
        lowMemory = false,
        thresholdBytes = 500_000_000,
    ),
    private val uptime: Long = 123_456L,
    private val permissions: DevicePermissionsSnapshot = DevicePermissionsSnapshot(
        location = LocationPermissionSnapshot(foreground = false, background = false),
        camera = false,
        microphone = false,
        notificationListener = false,
        postNotifications = false,
        activityRecognition = false,
        usageStats = false,
    ),
) : DeviceInfoSource {
    override fun battery(): BatterySnapshot = battery
    override fun thermalState(): ThermalState = thermal
    override fun storage(): StorageSnapshot = storage
    override fun network(): NetworkSnapshot = network
    override fun deviceIdentity(): DeviceIdentitySnapshot = identity
    override fun memory(): MemorySnapshot = memory
    override fun uptimeMs(): Long = uptime
    override fun permissions(): DevicePermissionsSnapshot = permissions
}
