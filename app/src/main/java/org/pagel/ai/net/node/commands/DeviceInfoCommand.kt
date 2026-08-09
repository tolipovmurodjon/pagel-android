package org.pagel.ai.net.node.commands

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.pagel.ai.net.node.NodeCommand
import javax.inject.Inject

/**
 * `device.info` — static device/app identity: manufacturer, model, OS version, this app's own
 * version. Protocol reference §4.1 pins no schema for this command (undocumented even in the
 * official app's own docs, `docs/nodes/index.md:628`: "not documented; `{}` by analogy"), and the
 * live `A05s` node was unreachable while implementing this (paired but disconnected — see this
 * phase's report), so this shape is a best-effort design, not a captured one: field names follow
 * this command's own obvious contract (identity, not live status — that's `device.status`) rather
 * than any confirmed official-app wire format.
 */
class DeviceInfoCommand @Inject constructor(
    private val source: DeviceInfoSource,
) : NodeCommand {
    override val name: String = "device.info"

    override suspend fun invoke(params: JsonObject): JsonElement {
        val identity = source.deviceIdentity()
        return buildJsonObject {
            put("manufacturer", identity.manufacturer)
            put("model", identity.model)
            put("androidVersion", identity.androidVersion)
            put("sdkInt", identity.sdkInt)
            put("appVersion", identity.appVersionName)
            put("appVersionCode", identity.appVersionCode)
            put("timezone", identity.timezone)
        }
    }
}
