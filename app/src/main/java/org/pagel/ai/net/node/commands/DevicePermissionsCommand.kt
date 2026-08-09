package org.pagel.ai.net.node.commands

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.pagel.ai.net.node.NodeCommand
import javax.inject.Inject

/**
 * `device.permissions` — real runtime grants, not what the app merely requested. Protocol reference
 * §4.1 pins no schema for this command, and the live `A05s` node was unreachable while implementing
 * this (paired but disconnected — see this phase's report), so this shape is a best-effort design,
 * not a captured one. One requirement is non-negotiable regardless: `location.foreground` and
 * `location.background` are reported as two independent booleans, never collapsed into one — that
 * split is exactly what tells Boss why a watcher is blind (foreground-only vs. truly backgrounded),
 * and Task 5's `location.get` depends on this command being honest about it.
 */
class DevicePermissionsCommand @Inject constructor(
    private val source: DeviceInfoSource,
) : NodeCommand {
    override val name: String = "device.permissions"

    override suspend fun invoke(params: JsonObject): JsonElement {
        val permissions = source.permissions()
        return buildJsonObject {
            putJsonObject("location") {
                put("foreground", permissions.location.foreground)
                put("background", permissions.location.background)
            }
            put("camera", permissions.camera)
            put("microphone", permissions.microphone)
            put("notificationListener", permissions.notificationListener)
            put("postNotifications", permissions.postNotifications)
            put("activityRecognition", permissions.activityRecognition)
            put("usageStats", permissions.usageStats)
        }
    }
}
