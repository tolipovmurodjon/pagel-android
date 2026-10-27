package org.pagel.ai.net.ws

/**
 * Gateway handshake client identity.
 *
 * `client.id` and `client.mode` are **closed enums** server-side, not free strings:
 * `normalizeGatewayClientId` / `normalizeGatewayClientMode` lowercase the value, test it against a
 * fixed `Set`, and return `undefined` for anything unrecognised. Both values are also bound into
 * the signed v3 auth payload (see [org.pagel.ai.net.identity.buildDeviceAuthPayloadV3]), so a wrong
 * value fails the handshake with an opaque error rather than a useful one.
 *
 * Values read from the shipped bundle on the gateway host, 2026-08-09:
 * `~/.npm-global/lib/node_modules/openclaw/dist/client-info-CcqJJIan.js`
 * (`packages/gateway-protocol/src/client-info.ts`).
 *
 * ```
 * GATEWAY_CLIENT_IDS   = webchat-ui · openclaw-control-ui · openclaw-tui · webchat · cli ·
 *                        gateway-client · openclaw-macos · openclaw-ios · openclaw-android ·
 *                        node-host · test · fingerprint · openclaw-probe
 * GATEWAY_CLIENT_MODES = webchat · cli · ui · backend · node · probe · test
 * ```
 */
object GatewayClientInfo {

    /**
     * The only Android slot in the closed id set. There is no third-party/custom id — an
     * unrecognised value is rejected by `normalizeGatewayClientId`, so this is the sole
     * functional choice for an Android client.
     */
    const val ID: String = "openclaw-android"

    /**
     * Mode for the operator connection (chat, dashboard, settings).
     *
     * **Not `"operator"`.** `docs/gateway/protocol.md:61` shows `"mode": "operator"` in its connect
     * example, but that string is absent from `GATEWAY_CLIENT_MODES` and appears **zero times** in
     * the shipped JavaScript — the doc example is stale. `ui` is the enum member that describes a
     * user-facing client, and it is in live use.
     *
     * Note this is distinct from the top-level `role` field, which *is* `"operator"`. Mode and role
     * are different axes: role gates RPC permissions, mode coarsely groups clients for policy.
     */
    const val MODE_OPERATOR: String = "ui"

    /** Mode for the node connection (sensors, canvas, talk). */
    const val MODE_NODE: String = "node"

    /** Top-level `role` values — separate from [MODE_OPERATOR] / [MODE_NODE] above. */
    const val ROLE_OPERATOR: String = "operator"
    const val ROLE_NODE: String = "node"
}
