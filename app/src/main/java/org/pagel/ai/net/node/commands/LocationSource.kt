package org.pagel.ai.net.node.commands

/**
 * `location.get`'s `desiredAccuracy` param (protocol reference §4.2) — a closed three-value tier,
 * not an arbitrary accuracy number. [COARSE] trades precision for battery by skipping GPS entirely
 * ([AndroidLocationSource.selectProviders]); [BALANCED] and [PRECISE] both allow GPS but race it
 * against network-ish providers rather than waiting on GPS alone.
 */
enum class DesiredAccuracy { COARSE, BALANCED, PRECISE }

/**
 * The wire's closed `source` set (protocol reference §4.2). `WIFI`/`CELL` are best-effort: the
 * platform `LocationManager` (no Play Services — see [AndroidLocationSource]'s doc) only exposes
 * `gps`/`network`/`fused` providers, never wifi vs. cell directly, so anything not attributable to
 * GPS collapses to [UNKNOWN] unless a device-specific `Location` extra says otherwise.
 */
enum class LocationFixSource(val wireValue: String) {
    GPS("gps"), WIFI("wifi"), CELL("cell"), UNKNOWN("unknown"),
}

/**
 * One location fix, mapped 1:1 to `location.get`'s success payload (protocol reference §4.2).
 * [accuracyMeters]/[altitudeMeters]/[speedMps]/[headingDeg] are nullable because a real `Location`
 * frequently lacks one or more of them (`Location.hasAltitude()` etc. — see
 * [AndroidLocationSource]'s mapping); the wire reports each as JSON `null` there rather than
 * inventing a `0.0`, which would be indistinguishable from a real reading.
 *
 * [toString] is overridden to never print [lat]/[lon]. This is Boss's live coordinates — the
 * project-wide rule (`CLAUDE.md` §8, this phase's task doc) is that a coordinate never appears in a
 * log line or an exception message, including an accidental default `data class` rendering picked up
 * by a logging framework, a debugger, or a future `.toString()` call nobody meant to be a log line.
 */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val speedMps: Float?,
    val headingDeg: Float?,
    val timestampMs: Long,
    val isPrecise: Boolean,
    val fixSource: LocationFixSource,
) {
    override fun toString(): String = "LocationFix(redacted)"
}

/**
 * Thrown by [LocationSource.requestFix] for the two outcomes [LocationGetCommand] maps to their own
 * stable wire codes (`LOCATION_TIMEOUT`, `LOCATION_UNAVAILABLE` — protocol reference §4.2) instead of
 * the generic `COMMAND_ERROR` every other thrown [Throwable] falls back to. See
 * [org.pagel.ai.net.node.NodeCommandError].
 */
sealed class LocationFixFailure(message: String) : Exception(message) {
    class Timeout(message: String = "no fix within the requested timeout") : LocationFixFailure(message)
    class Unavailable(message: String = "no location providers available") : LocationFixFailure(message)
}

/**
 * Every Android location read/action `location.get` needs, behind one small interface — the same
 * reasoning as [DeviceInfoSource]'s doc: no `androidTest` source set and no Robolectric in this repo,
 * so [LocationGetCommand] depends on this interface, never on [android.location.LocationManager] or
 * [android.content.Context] directly, to stay unit-testable behind a fake.
 * [AndroidLocationSource] is the only production implementation, wired in `GatewayModule`.
 */
interface LocationSource {
    /** `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION` currently granted. */
    fun hasForegroundPermission(): Boolean

    /** `ACCESS_BACKGROUND_LOCATION` currently granted. Boss must pick "Allow all the time" in system
     *  Settings for this to ever read `true` — Android 11+ has no runtime dialog that grants it. */
    fun hasBackgroundPermission(): Boolean

    /** The OS-level Location toggle (Settings > Location), independent of any per-app grant. */
    fun isLocationEnabled(): Boolean

    /** `true` only when this process currently has a visible activity. A foreground service alone —
     *  e.g. [org.pagel.ai.service.AnchorService] keeping the gateway connection alive while the app
     *  is "closed" and the phone is locked, which is the exact scenario this command exists for —
     *  reads `false` here. Deliberately narrower than "process is alive." */
    fun isAppInForeground(): Boolean

    /** The freshest fix any provider currently has cached, if its age is within [maxAgeMs] — the
     *  cheap path [LocationGetCommand] must try before ever starting a fresh request. `null` if no
     *  cached fix qualifies. */
    fun lastKnownFix(maxAgeMs: Long): LocationFix?

    /** Starts a fresh fix and suspends until one arrives, [timeoutMs] elapses, or the calling
     *  coroutine is cancelled. Implementations must unregister every listener/callback they
     *  registered in all three cases — a dangling GPS request keeps the radio warm and drains
     *  battery on a phone this awareness service is meant to run on all day. */
    suspend fun requestFix(desiredAccuracy: DesiredAccuracy, timeoutMs: Long): LocationFix
}
