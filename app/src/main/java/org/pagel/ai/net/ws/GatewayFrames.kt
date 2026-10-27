package org.pagel.ai.net.ws

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire JSON config for every gateway frame.
 *
 * - `encodeDefaults = true` — otherwise kotlinx drops any property still at its default value,
 *   which would silently strip the `type` discriminator (it defaults to `"req"`/`"res"`/`"event"`).
 * - `explicitNulls = false` — the gateway schemas are `additionalProperties:false` and treat an
 *   *absent* optional key differently from a key present with a JSON `null`; a null Kotlin field
 *   (e.g. unset `params`, `auth`, `device.deviceFamily`) must vanish from the JSON, not serialize
 *   as `"field":null`.
 * - `ignoreUnknownKeys = true` — `hello-ok`/events carry server fields we don't model; don't crash
 *   decoding on `snapshot`, `controlUiTabs`, etc.
 */
val GatewayJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * The three gateway envelope kinds, verbatim from the shipped `schema-BuOFpc7K.js:3182-3210`
 * (`RequestFrameSchema` / `ResponseFrameSchema` / `EventFrameSchema`), discriminated by `type`.
 * Confirmed against the live gateway source (`message-handler-CzwI6JjW.js`,
 * `server-ws-runtime-*.js`) on 2026-08-09: `connect` is sent as a `Req` with `method:"connect"`,
 * and `hello-ok` comes back as the `payload` of the matching `Res` (`{type:"hello-ok", ...}`
 * itself, nested one level in) — not as a bespoke top-level frame kind.
 */
sealed interface GatewayFrame {

    @Serializable
    data class Req(
        val id: String,
        val method: String,
        val params: JsonElement? = null,
        val type: String = "req",
    ) : GatewayFrame

    @Serializable
    data class Res(
        val id: String,
        val ok: Boolean,
        val payload: JsonElement? = null,
        val error: GatewayError? = null,
        val type: String = "res",
    ) : GatewayFrame

    @Serializable
    data class Event(
        val event: String,
        val payload: JsonElement? = null,
        val seq: Long? = null,
        val stateVersion: JsonElement? = null,
        val type: String = "event",
    ) : GatewayFrame
}

/** `ErrorShapeSchema` (`schema-BuOFpc7K.js:3174-3181`), carried in `Res.error`. */
@Serializable
data class GatewayError(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
    val retryable: Boolean? = null,
    val retryAfterMs: Long? = null,
)

/**
 * Parses one inbound WS text frame. Returns `null` — never throws — for malformed JSON, a missing
 * `type`, or a `type` outside the closed `req`/`res`/`event` union, so a gateway sending a frame
 * kind this client doesn't know about degrades to "ignored" rather than crashing the socket loop.
 */
fun parseGatewayFrame(text: String): GatewayFrame? {
    val obj = runCatching { GatewayJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return runCatching {
        when (type) {
            "req" -> GatewayJson.decodeFromJsonElement(GatewayFrame.Req.serializer(), obj)
            "res" -> GatewayJson.decodeFromJsonElement(GatewayFrame.Res.serializer(), obj)
            "event" -> GatewayJson.decodeFromJsonElement(GatewayFrame.Event.serializer(), obj)
            else -> null
        }
    }.getOrNull()
}
