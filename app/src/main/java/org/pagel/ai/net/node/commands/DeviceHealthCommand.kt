package org.pagel.ai.net.node.commands

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.pagel.ai.net.node.NodeCommand
import javax.inject.Inject

/**
 * `device.health` — runtime resource health, deliberately disjoint from `device.status`'s hardware
 * snapshot (battery/thermal/storage/network) rather than duplicating any of it: RAM pressure and how
 * long the OS has been up. Protocol reference §4.1 pins no schema for this command ("not
 * documented"), and the live `A05s` node was unreachable while implementing this (paired but
 * disconnected — see this phase's report), so this shape is a best-effort design, not a captured
 * one.
 */
class DeviceHealthCommand @Inject constructor(
    private val source: DeviceInfoSource,
) : NodeCommand {
    override val name: String = "device.health"

    override suspend fun invoke(params: JsonObject): JsonElement {
        val memory = source.memory()
        return buildJsonObject {
            putJsonObject("memory") {
                put("totalBytes", memory.totalBytes)
                put("availableBytes", memory.availableBytes)
                put("lowMemory", memory.lowMemory)
                put("thresholdBytes", memory.thresholdBytes)
            }
            put("uptimeMs", source.uptimeMs())
        }
    }
}
