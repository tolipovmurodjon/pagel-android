package org.pagel.ai.net.identity

/**
 * The v3 device-auth signing payload, transcribed character-for-character from the shipped
 * upstream bundle (`packages/gateway-client/src/device-auth.ts`, via
 * `~/.npm-global/lib/node_modules/openclaw/dist/src-DZzKBMa7.js`). This is undocumented and
 * unverifiable except against the gateway itself — do not "clean up" the field order, the
 * separator, or the empty-token convention; any deviation breaks the handshake signature.
 *
 * ```js
 * function normalizeDeviceMetadataForAuth(value) {
 *   if (typeof value !== "string") return "";
 *   const trimmed = value.trim();
 *   if (!trimmed) return "";
 *   return trimmed.replace(/[A-Z]/g, (char) => String.fromCharCode(char.charCodeAt(0) + 32));
 * }
 * function buildDeviceAuthPayloadV3(params) {
 *   const scopes = params.scopes.join(",");
 *   const token = params.token ?? "";
 *   const platform = normalizeDeviceMetadataForAuth(params.platform);
 *   const deviceFamily = normalizeDeviceMetadataForAuth(params.deviceFamily);
 *   return [ "v3", params.deviceId, params.clientId, params.clientMode, params.role,
 *            scopes, String(params.signedAtMs), token, params.nonce,
 *            platform, deviceFamily ].join("|");
 * }
 * ```
 */

/**
 * ASCII-only lowercasing: `[A-Z]` -> char code +32. NOT `String.lowercase()` — that transforms
 * non-ASCII characters (e.g. `Ä` -> `ä`) and is locale-sensitive (Turkish `I` -> `ı`), which the
 * upstream regex-based implementation never does.
 */
fun normalizeDeviceMetadataForAuth(value: String?): String {
    if (value == null) return ""
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
}

/** Builds the exact `|`-joined string the gateway expects to be Ed25519-signed for `device.signature`. */
fun buildDeviceAuthPayloadV3(
    deviceId: String,
    clientId: String,
    clientMode: String,
    role: String,
    scopes: List<String>,
    signedAtMs: Long,
    token: String?,
    nonce: String,
    platform: String?,
    deviceFamily: String?,
): String {
    val scopesJoined = scopes.joinToString(",")
    val tokenOrEmpty = token ?: ""
    val normalizedPlatform = normalizeDeviceMetadataForAuth(platform)
    val normalizedDeviceFamily = normalizeDeviceMetadataForAuth(deviceFamily)
    return listOf(
        "v3", deviceId, clientId, clientMode, role,
        scopesJoined, signedAtMs.toString(), tokenOrEmpty, nonce,
        normalizedPlatform, normalizedDeviceFamily,
    ).joinToString("|")
}

/** The signed device-auth block sent as `device` in the `connect` frame (built in Task 4). */
data class DeviceAuthBlock(
    val id: String,
    val publicKey: String,
    val signature: String,
    val signedAt: Long,
    val nonce: String,
)
