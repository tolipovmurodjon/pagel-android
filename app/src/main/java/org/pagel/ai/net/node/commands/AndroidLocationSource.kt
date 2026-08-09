package org.pagel.ai.net.node.commands

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The only production [LocationSource] — platform `android.location.LocationManager`, deliberately
 * not `play-services-location`/`FusedLocationProviderClient`. Boss's phone is a budget A05s and this
 * dependency would only buy smoother provider fusion the platform API's `FUSED_PROVIDER` (API 31+,
 * available on Boss's Android 15 device) already gives us; adding Play Services for that would be a
 * large dependency (Google Play Services Location + its transitive tree) for marginal gain on a
 * command whose actual hard requirement — `ACCESS_BACKGROUND_LOCATION` even existing as an option —
 * is a manifest declaration, not a library choice.
 *
 * No caching beyond [lastKnownFix]'s own provider-cache read: every call re-derives permission/
 * enablement state fresh, matching [AndroidDeviceInfoSource]'s "no caching" convention.
 */
@Singleton
class AndroidLocationSource @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : LocationSource {

    private val locationManager: LocationManager
        get() = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // A LocationListener callback executed inline on whatever binder thread Android invokes it on —
    // resuming a suspendCancellableCoroutine's continuation is thread-safe, so there is no need for a
    // dedicated Looper/main-thread executor here (and none is available reliably from a background
    // service context anyway).
    private val directExecutor = Executor { it.run() }

    override fun hasForegroundPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    override fun hasBackgroundPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    override fun isLocationEnabled(): Boolean = locationManager.isLocationEnabled

    override fun isAppInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        // IMPORTANCE_FOREGROUND (100) means a visible activity; a foreground service alone (no
        // visible UI — AnchorService's normal state while the app is "closed") reports
        // IMPORTANCE_FOREGROUND_SERVICE (125), which must read as "backgrounded" here.
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    override fun lastKnownFix(maxAgeMs: Long): LocationFix? {
        val lm = locationManager
        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
        return lm.allProviders
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .filter { location ->
                // elapsedRealtimeNanos, not location.time (wall clock) — a wall-clock jump (NTP sync,
                // manual clock change) must never make a stale fix look fresh or a fresh one look stale.
                val ageMs = (nowElapsedNanos - location.elapsedRealtimeNanos) / 1_000_000
                ageMs in 0..maxAgeMs
            }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.toLocationFix(hasFine)
    }

    override suspend fun requestFix(desiredAccuracy: DesiredAccuracy, timeoutMs: Long): LocationFix {
        val lm = locationManager
        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val enabled = lm.allProviders.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val providers = selectProviders(enabled, desiredAccuracy, hasFine)
        if (providers.isEmpty()) throw LocationFixFailure.Unavailable("no enabled provider matches the requested accuracy")

        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    lateinit var listener: LocationListener
                    listener = LocationListener { location ->
                        if (cont.isActive) {
                            lm.removeUpdates(listener)
                            cont.resume(location.toLocationFix(hasFine))
                        }
                    }
                    providers.forEach { provider ->
                        lm.requestLocationUpdates(provider, 0L, 0f, directExecutor, listener)
                    }
                    // Covers all three ways this suspension ends without a listener callback: the
                    // withTimeout above expiring, and the owning coroutine being cancelled from
                    // outside (e.g. the gateway connection dropping mid-request) — both cancel this
                    // coroutine, and invokeOnCancellation is the one place that fires for either. A
                    // dangling requestLocationUpdates keeps the GPS radio warm and drains battery.
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw LocationFixFailure.Timeout("no fix within ${timeoutMs}ms")
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toLocationFix(hasFinePermission: Boolean): LocationFix = LocationFix(
        lat = latitude,
        lon = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        altitudeMeters = if (hasAltitude()) altitude else null,
        speedMps = if (hasSpeed()) speed else null,
        headingDeg = if (hasBearing()) bearing else null,
        timestampMs = time,
        // "Precise" tracks the app's currently-granted permission tier (Android 12+'s
        // precise/approximate toggle), not this one fix's numeric accuracy value — a GPS fix
        // obtained under coarse-only permission is still deliberately fuzzed by the OS.
        isPrecise = hasFinePermission,
        fixSource = mapProviderToSource(provider, extras?.getString(EXTRA_NETWORK_LOCATION_TYPE)),
    )

    companion object {
        private const val EXTRA_NETWORK_LOCATION_TYPE = "networkLocationType"

        /**
         * Which of [enabledProviders] to race a fresh fix against for [desiredAccuracy] and whether
         * fine location is currently granted. Every candidate provider is requested simultaneously
         * and whichever answers first wins ([requestFix]) — this can only ever finish as fast as or
         * faster than picking a single provider up front, never slower. GPS is omitted entirely for
         * [DesiredAccuracy.COARSE]: it is the one provider accuracy tiers trade away for battery, and
         * GPS is the power-hungry one. Pure function of plain values — no [LocationManager] needed to
         * exercise this in a JVM unit test.
         */
        fun selectProviders(
            enabledProviders: List<String>,
            desiredAccuracy: DesiredAccuracy,
            hasFinePermission: Boolean,
        ): List<String> {
            val gps = LocationManager.GPS_PROVIDER.takeIf {
                hasFinePermission && it in enabledProviders && desiredAccuracy != DesiredAccuracy.COARSE
            }
            val networkish = enabledProviders.filter {
                it == LocationManager.NETWORK_PROVIDER || (Build.VERSION.SDK_INT >= 31 && it == LocationManager.FUSED_PROVIDER)
            }
            return listOfNotNull(gps) + networkish
        }

        /**
         * [Location.getProvider] (plus a device-specific `networkLocationType` extra, where present)
         * -> the wire's closed `source` set. See [LocationFixSource]'s doc for why `wifi`/`cell` are
         * best-effort. Pure string mapping — same testability reasoning as
         * [AndroidDeviceInfoSource.mapBatteryState].
         */
        fun mapProviderToSource(provider: String?, networkLocationTypeExtra: String?): LocationFixSource = when (provider) {
            LocationManager.GPS_PROVIDER -> LocationFixSource.GPS
            LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER -> when (networkLocationTypeExtra?.lowercase()) {
                "wifi" -> LocationFixSource.WIFI
                "cell", "cellular" -> LocationFixSource.CELL
                else -> LocationFixSource.UNKNOWN
            }
            else -> LocationFixSource.UNKNOWN
        }
    }
}
