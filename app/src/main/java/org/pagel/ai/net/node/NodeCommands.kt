package org.pagel.ai.net.node

/**
 * The single source of truth for what this device advertises in the node connection's
 * `connect.commands` list — and, by construction, the ceiling of what the dispatcher may ever serve
 * (it rejects anything not in here).
 *
 * ### The gateway silently drops anything outside its own allowlist
 *
 * `resolveNodeCommandAllowlistInternal` (`dist/node-command-policy-CO8bCAnE.js:237`) builds the
 * permitted set as `PLATFORM_DEFAULTS[platform] + talk (if declared) + plugins + approved + extra`,
 * and `normalizeDeclaredNodeCommands` (:283) then **filters our declared list down to that set**.
 * A name outside it does not error — it simply never reaches the pairing record, which is why
 * inventing plausible-sounding names is worse than useless.
 *
 * `PLATFORM_DEFAULTS.android` (:85-97) is exactly these 17:
 * `camera.list` · `location.get` · `notifications.list` · `notifications.actions` · `system.notify` ·
 * `device.info` · `device.status` · `device.permissions` · `device.health` · `device.apps` ·
 * `contacts.search` · `calendar.events` · `callLog.search` · `reminders.list` · `photos.latest` ·
 * `motion.activity` · `motion.pedometer`
 *
 * ### The five extras below are NOT platform defaults
 *
 * `camera.snap`, `calendar.add` and `contacts.add` are in `DEFAULT_DANGEROUS_NODE_COMMANDS` (:64);
 * `screen.context` and `screen.act` are not OpenClaw commands at all — they are ours, for the
 * accessibility surface. All five reach us only because Boss added them to
 * `gateway.nodes.allowCommands` on 2026-08-10, which `extra` merges in unconditionally (:250, :260).
 * **If that config key is ever cleared, these five vanish from the pairing record silently.**
 *
 * ### Changing this list costs a re-pair
 *
 * Any change to the declared set invalidates the pairing and Boss must approve the device *and* the
 * node again, separately. That is why commands with no implementation yet are declared here anyway:
 * one re-pair now beats four later. An invoke of an unimplemented one gets an honest
 * `INVALID_REQUEST: unknown command "..."` from [NodeCommandDispatcher] rather than a silent
 * timeout — see [org.pagel.ai.net.GatewayManager]'s note on the same trade-off.
 *
 * ### Deliberately NOT declared
 * - `callLog.search` — Boss parked call log and SMS ("I will allow after thinking", 2026-08-10).
 * - `sms.send` / `sms.search` — same, and dangerous-class besides.
 * - `screen.record`, `camera.clip`, `reminders.add` — dangerous-class, and nothing implements them.
 * - `talk.ptt.*` — declaring the `talk` cap unconditionally widens the server allowlist to all four
 *   PTT commands (`hasTalkSurface`, :234) and voice was deleted from this app in `ffec6df`.
 */
object NodeCommands {
    val DECLARED: List<String> = listOf(
        // --- Implemented (Phase D Tasks 4-5) ---
        "device.status",
        "device.info",
        "device.health",
        "device.permissions",
        "location.get",

        // --- Platform defaults, implementations land in Wave 2 ---
        "notifications.list",
        "notifications.actions",
        "motion.activity",
        "motion.pedometer",
        "calendar.events",
        "contacts.search",
        "photos.latest",
        "reminders.list",
        "device.apps",
        "camera.list",
        "system.notify",

        // --- Allowed only by gateway.nodes.allowCommands; see the class doc ---
        "camera.snap",
        "calendar.add",
        "contacts.add",
        // Ours, not OpenClaw's — the accessibility surface (Wave 3).
        "screen.context",
        "screen.act",
    )
}
