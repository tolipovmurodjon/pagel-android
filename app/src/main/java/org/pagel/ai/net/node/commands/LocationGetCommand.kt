package org.pagel.ai.net.node.commands

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.pagel.ai.net.node.NodeCommand
import org.pagel.ai.net.node.NodeCommandError
import java.time.Instant
import javax.inject.Inject

/**
 * `location.get` — the reason this project exists (Phase D task doc): a location fix obtainable
 * while the app is closed and the phone is locked, which the Play-Store build of the official
 * OpenClaw app cannot do because it never declares `ACCESS_BACKGROUND_LOCATION`. Protocol reference
 * §4.2 is authoritative for the success shape and the five stable error codes below — Boss's
 * server-side watchers (`commute-brain.js`, `move-nudge.js`) already branch on the exact `error.code`
 * strings, so every failure path here throws [NodeCommandError] with one of those exact strings
 * rather than falling through to [org.pagel.ai.net.node.NodeCommandDispatcher]'s generic
 * `COMMAND_ERROR`.
 *
 * Gate order mirrors the task doc's own "map them honestly" list: permission, then background
 * availability, then OS-level disablement, then the timing/system outcomes of an actual fix attempt.
 * Never logs, and never lets a coordinate reach an exception message — see [LocationFix]'s doc.
 */
class LocationGetCommand @Inject constructor(
    private val source: LocationSource,
) : NodeCommand {
    override val name: String = "location.get"

    override suspend fun invoke(params: JsonObject): JsonElement {
        if (!source.hasForegroundPermission()) {
            throw NodeCommandError("LOCATION_PERMISSION_REQUIRED", "foreground location permission not granted")
        }
        if (!source.isAppInForeground() && !source.hasBackgroundPermission()) {
            // The exact bug this project exists to fix: foreground-only ("While Using") granted, and
            // we're backgrounded right now. Must be honest about it, not silently attempt a fix that
            // the OS would refuse anyway.
            throw NodeCommandError("LOCATION_BACKGROUND_UNAVAILABLE", "app is backgrounded and background location is not granted")
        }
        if (!source.isLocationEnabled()) {
            throw NodeCommandError("LOCATION_DISABLED", "location services are off at the OS level")
        }

        val maxAgeMs = params["maxAgeMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_MAX_AGE_MS
        val timeoutMs = params["timeoutMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS
        val desiredAccuracy = parseDesiredAccuracy(params["desiredAccuracy"]?.jsonPrimitive?.contentOrNull)

        // The cheap path: a cached fix newer than maxAgeMs is acceptable and skips ever touching the
        // radio. maxAgeMs defaults to 0 (never satisfied) rather than some guessed positive window —
        // silently serving a stale fix nobody asked to tolerate would be the wrong default here.
        val fix = source.lastKnownFix(maxAgeMs) ?: fetchFreshFix(desiredAccuracy, timeoutMs)
        return fix.toJson()
    }

    private suspend fun fetchFreshFix(desiredAccuracy: DesiredAccuracy, timeoutMs: Long): LocationFix =
        try {
            source.requestFix(desiredAccuracy, timeoutMs)
        } catch (e: LocationFixFailure.Timeout) {
            throw NodeCommandError("LOCATION_TIMEOUT", "no fix within ${timeoutMs}ms")
        } catch (e: LocationFixFailure.Unavailable) {
            throw NodeCommandError("LOCATION_UNAVAILABLE", e.message ?: "location unavailable")
        }

    private fun parseDesiredAccuracy(raw: String?): DesiredAccuracy = when (raw) {
        "coarse" -> DesiredAccuracy.COARSE
        "precise" -> DesiredAccuracy.PRECISE
        else -> DesiredAccuracy.BALANCED // "balanced", absent, or unrecognized all fall back honestly
    }

    private fun LocationFix.toJson(): JsonElement = buildJsonObject {
        put("lat", lat)
        put("lon", lon)
        put("accuracyMeters", accuracyMeters?.let { JsonPrimitive(it) } ?: JsonNull)
        put("altitudeMeters", altitudeMeters?.let { JsonPrimitive(it) } ?: JsonNull)
        put("speedMps", speedMps?.let { JsonPrimitive(it) } ?: JsonNull)
        put("headingDeg", headingDeg?.let { JsonPrimitive(it) } ?: JsonNull)
        put("timestamp", Instant.ofEpochMilli(timestampMs).toString()) // ISO-8601, not epoch ms
        put("isPrecise", isPrecise)
        put("source", fixSource.wireValue)
    }

    private companion object {
        /** No maxAgeMs in params -> never satisfied by a cached fix -> always fetch fresh. See the
         *  "cheap path" comment in [invoke] for why this default is 0, not a guessed positive window. */
        const val DEFAULT_MAX_AGE_MS = 0L

        /** Mirrors the CLI's own `--location-timeout` default (protocol reference §4.2) — used only
         *  when the request params carry no `timeoutMs`. */
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
