package org.pagel.ai.net.node

/**
 * The single source of truth for what this device advertises in the node connection's
 * `connect.commands` list — and, by construction, the ceiling of what Task 3's dispatcher may ever
 * serve (it rejects anything not in here). Every entry must satisfy two independent things:
 *
 * 1. It is in the Android platform's server-side default allowlist
 *    (`protocol-reference-node-commands.md` §3.1, `PLATFORM_DEFAULTS.android`) — declaring a command
 *    outside it is rejected by the gateway's own policy regardless of what we send.
 * 2. A [org.pagel.ai.net.node.NodeCommand] implementation for it actually exists (Phase D Tasks 4-6).
 *    Declaring a command we cannot serve routes an invoke straight to a dropped/`TIMEOUT` result on
 *    Boss's end for no reason.
 *
 * **Deliberately excluded**, though present in the Android default-allowed 17 and thus *legal* to
 * declare — none has an implementing task in this phase:
 * - `system.notify`, `camera.list`, `device.apps`, `notifications.actions`, `calendar.events`,
 *   `reminders.list` — no `NodeCommand` for any of them anywhere in Tasks 4-6's file list.
 * - `contacts.search`, `callLog.search`, `photos.latest` — same reason, *and* they need contacts /
 *   call-log / photos access, which Boss has deliberately denied on this phone (`CLAUDE.md` §5) —
 *   declaring them would be a privacy overreach even if a command existed.
 *
 * Also excluded, and *not* legal to declare regardless of implementation status: `camera.snap`,
 * `camera.clip`, `screen.record`, `sms.send`, `sms.search`, `contacts.add`, `calendar.add`,
 * `reminders.add` — §3.2's `DEFAULT_DANGEROUS_NODE_COMMANDS`, which need an explicit
 * `gateway.nodes.allowCommands` opt-in on the server that Boss has not made.
 */
object NodeCommands {
    val DECLARED: List<String> = listOf(
        // Task 4 — DeviceStatusCommand / DeviceInfoCommand / DeviceHealthCommand / DevicePermissionsCommand
        "device.status",
        "device.info",
        "device.health",
        "device.permissions",
        // Task 5 — LocationGetCommand
        "location.get",
        // Task 6 — NotificationsListCommand / MotionActivityCommand / MotionPedometerCommand
        "notifications.list",
        "motion.activity",
        "motion.pedometer",
    )
}
