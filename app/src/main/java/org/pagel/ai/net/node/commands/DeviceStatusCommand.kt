package org.pagel.ai.net.node.commands

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.pagel.ai.net.node.NodeCommand
import javax.inject.Inject

/**
 * `device.status` — battery/thermal/storage/network snapshot. Protocol reference §4.1: the gateway
 * defines no schema for this payload (`Type.Any()`), so this shape is pinned to a live capture of
 * the official OpenClaw Android app's `A05s` node (Phase D task doc) rather than invented — Boss's
 * existing server-side skills/watchers already parse that exact shape, and a different one would
 * silently break them.
 */
class DeviceStatusCommand @Inject constructor(
    private val source: DeviceInfoSource,
) : NodeCommand {
    override val name: String = "device.status"

    override suspend fun invoke(params: JsonObject): JsonElement {
        val battery = source.battery()
        val storage = source.storage()
        val network = source.network()
        return buildJsonObject {
            putJsonObject("battery") {
                put("level", battery.levelFraction) // 0..1 fraction, never a 0..100 percentage
                put("state", battery.state.wireValue)
                put("lowPowerModeEnabled", battery.lowPowerModeEnabled)
            }
            putJsonObject("thermal") {
                put("state", source.thermalState().wireValue)
            }
            putJsonObject("storage") {
                put("totalBytes", storage.totalBytes)
                put("freeBytes", storage.freeBytes)
                put("usedBytes", storage.usedBytes)
            }
            putJsonObject("network") {
                put("connected", network.connected)
                put("type", network.type.wireValue)
                put("metered", network.metered)
            }
        }
    }
}
