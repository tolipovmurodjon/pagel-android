package org.pagel.ai.net.node.commands

/**
 * Fake for [LocationGetCommandTest] — [LocationGetCommand] depends only on [LocationSource], never
 * on `android.location.LocationManager`/`Context` directly (see that interface's doc), so this is
 * the one fixture its tests need. Every field defaults to "a healthy, fully-granted, foregrounded
 * device with no cached fix" so each test overrides only what it's asserting on, per the existing
 * project convention ([FakeDeviceInfoSource]'s own doc).
 */
internal class FakeLocationSource(
    private val hasForegroundPermission: Boolean = true,
    private val hasBackgroundPermission: Boolean = true,
    private val locationEnabled: Boolean = true,
    private val appInForeground: Boolean = true,
    private val cachedFix: LocationFix? = null,
    private val freshFix: LocationFix? = DEFAULT_FIX,
    private val freshFixFailure: LocationFixFailure? = null,
) : LocationSource {

    var lastMaxAgeMsRequested: Long? = null
        private set
    var requestFixCallCount = 0
        private set
    var lastDesiredAccuracyRequested: DesiredAccuracy? = null
        private set
    var lastTimeoutMsRequested: Long? = null
        private set

    override fun hasForegroundPermission(): Boolean = hasForegroundPermission
    override fun hasBackgroundPermission(): Boolean = hasBackgroundPermission
    override fun isLocationEnabled(): Boolean = locationEnabled
    override fun isAppInForeground(): Boolean = appInForeground

    override fun lastKnownFix(maxAgeMs: Long): LocationFix? {
        lastMaxAgeMsRequested = maxAgeMs
        return cachedFix
    }

    override suspend fun requestFix(desiredAccuracy: DesiredAccuracy, timeoutMs: Long): LocationFix {
        requestFixCallCount++
        lastDesiredAccuracyRequested = desiredAccuracy
        lastTimeoutMsRequested = timeoutMs
        freshFixFailure?.let { throw it }
        return freshFix ?: error("test misconfiguration: no freshFix and no freshFixFailure provided")
    }

    companion object {
        val DEFAULT_FIX = LocationFix(
            lat = 48.20849,
            lon = 16.37208,
            accuracyMeters = 12.5f,
            altitudeMeters = 182.0,
            speedMps = 0f,
            headingDeg = 270f,
            timestampMs = 1_700_000_000_000L,
            isPrecise = true,
            fixSource = LocationFixSource.GPS,
        )
    }
}
